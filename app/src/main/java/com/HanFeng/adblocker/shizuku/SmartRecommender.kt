package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import com.HanFeng.data.PerformanceTunerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 智能推荐引擎。
 *
 * 监控设备运行状态（CPU 频率、温度、内存、电池、后台应用），
 * 根据设备类型、SoC 型号、当前负载生成优化建议。
 *
 * 用户不需要懂参数，只需看推荐方案并一键应用。
 */
class SmartRecommender {

    companion object {
        private const val TAG = "SmartRecommender"
    }

    data class DeviceSnapshot(
        val cpuFreqKHz: Int = 0,
        val cpuMaxFreqKHz: Int = 0,
        val cpuUsagePercent: Int = 0,
        val gpuFreqKHz: Int = 0,
        /** 每个逻辑核心的当前频率（kHz），下标即核心编号，采集失败为空列表 */
        val cpuPerCoreFreqKHz: List<Int> = emptyList(),
        val temperature: Int = 0,
        val batteryLevel: Int = 0,
        val batteryTemp: Int = 0,
        val memoryUsedPercent: Int = 0,
        val topApps: List<String> = emptyList(),
        val backgroundAppCount: Int = 0,
        val isCharging: Boolean = false,
        val screenOn: Boolean = false
    )

    data class Recommendation(
        val id: String,
        val title: String,
        val description: String,
        val priority: Int,
        val category: String,
        val action: RecommendationAction
    )

    sealed class RecommendationAction {
        data class ApplySceneProfile(val profile: String) : RecommendationAction()
        data class EnableAppOpt(val enabled: Boolean) : RecommendationAction()
        data class LimitBackgroundApps(val packages: List<String>) : RecommendationAction()
        data class AdjustThermal(val value: String) : RecommendationAction()
        data class NoAction(val reason: String) : RecommendationAction()
    }

    data class RecommendationReport(
        val snapshot: DeviceSnapshot,
        val recommendations: List<Recommendation>,
        val deviceType: String,
        val socName: String,
        val summary: String
    )

    private val suSession get() = SuSession.getInstance()

    /** 采集设备当前状态快照。
     *  每类数据用独立短命令采集, 避免单条长命令超时截断导致全字段归零;
     *  温度/电量/内存全部走 sysfs/direct 计数器, 修复 thermal zone 单位混采 (656℃) 与 dumpsys 解析失败。 */
    suspend fun captureSnapshot(context: Context): DeviceSnapshot = withContext(Dispatchers.IO) {
        if (!suSession.isSessionOpen()) return@withContext DeviceSnapshot()

        val D = "${'$'}"

        // 1. CPU/GPU 频率 (sysfs, 快)
        val freqOut = suSession.execute(
            "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq 2>/dev/null; " +
                "cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_max_freq 2>/dev/null; " +
                "cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null || " +
                "cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null || echo 0",
            5
        ).output.lines().map { it.trim() }
        val cpuFreq = freqOut.getOrNull(0)?.toIntOrNull() ?: 0
        val cpuMax = freqOut.getOrNull(1)?.toIntOrNull() ?: 0
        val gpuFreq = freqOut.getOrNull(2)?.toIntOrNull() ?: 0

        // 1b. 每核心频率（独立短命令; nproc 缺失时按 8 核兜底, 缺失核心记 0）
        val perCoreOut = suSession.execute(
            "N=\$(nproc 2>/dev/null); N=\${N:-8}; i=0; " +
                "while [ \$i -lt \$N ]; do " +
                "cat /sys/devices/system/cpu/cpu\$i/cpufreq/scaling_cur_freq 2>/dev/null || echo 0; " +
                "i=\$((i+1)); done",
            6
        ).output.lines()
            .filter { it.isNotBlank() }
            .map { it.trim().toIntOrNull() ?: 0 }
            .take(16)  // 防御异常输出超长
        val cpuPerCore = perCoreOut

        // 2. 电池温度/电量/充电状态 (power_supply sysfs, 标准单位: temp 为 0.1°C)
        val batOut = suSession.execute(
            "cat /sys/class/power_supply/battery/capacity 2>/dev/null || echo 0; " +
                "cat /sys/class/power_supply/battery/temp 2>/dev/null || echo 0; " +
                "cat /sys/class/power_supply/battery/status 2>/dev/null || echo Unknown",
            5
        ).output.lines().map { it.trim() }
        val batteryLevel = batOut.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 100) ?: 0
        val batteryTemp = batOut.getOrNull(1)?.toIntOrNull() ?: 0
        val batStatus = batOut.getOrNull(2) ?: "Unknown"
        val isCharging = batStatus.equals("Charging", true) ||
            batStatus.equals("Full", true) ||
            batStatus == "2" || batStatus == "5"

        // 3. 内存占用 (/proc/meminfo + shell 算术, 不依赖 awk — 多数 ROM 无 awk)
        val memOut = suSession.execute(
            "T=${D}(grep MemTotal /proc/meminfo 2>/dev/null | tr -dc '0-9'); " +
                "A=${D}(grep MemAvailable /proc/meminfo 2>/dev/null | tr -dc '0-9'); " +
                "if [ -n \"${D}T\" ] && [ -n \"${D}A\" ] && [ \"${D}T\" -gt 0 ]; then " +
                "echo ${D}(( (${D}T - ${D}A) * 100 / ${D}T )); else echo 0; fi",
            5
        ).output.trim()
        val memPercent = memOut.toIntOrNull()?.coerceIn(0, 100) ?: 0

        // 4. 设备温度: 只采 cpu/gpu/soc 类型 thermal zone, 统一毫度→°C, 过滤异常值
        //    (原始值 >10000 按毫度 /1000; 1000-10000 按 0.1°C /10; 其余视为异常丢弃)
        val tempOut = suSession.execute(
            "for z in /sys/class/thermal/thermal_zone*; do " +
                "ty=${D}(cat \"${D}z/type\" 2>/dev/null); " +
                "case \"${D}ty\" in *cpu*|*CPU*|*gpu*|*GPU*|*soc*|*SoC*) " +
                "echo ${D}(cat \"${D}z/temp\" 2>/dev/null || echo 0);; esac; done",
            6
        ).output.lines().mapNotNull { it.trim().toIntOrNull() }
        val temp = tempOut.map { raw ->
            when {
                raw > 10000 -> raw / 1000
                raw > 1000 -> raw / 10
                else -> 0
            }
        }.filter { it in 5..110 }
            .maxOrNull() ?: 0
        // cpu/gpu zone 全异常时回退电池温度 (0.1°C → °C), 保证显示有值且物理合理
        val displayTemp = if (temp in 5..110) temp else (batteryTemp / 10).takeIf { it in 5..110 } ?: 0

        // 5. 前台/后台应用 (dumpsys 独立跑, 失败不影响主状态)
        val appsOut = runCatching {
            suSession.execute(
                "dumpsys activity activities 2>/dev/null | grep -oE 'u0 [a-zA-Z0-9_.]+/' | sort -u | head -10",
                12
            ).output
        }.getOrDefault("")
        val topApps = appsOut.lines().mapNotNull {
            val pkg = it.trim().removePrefix("u0 ").removeSuffix("/")
            if (pkg.isNotBlank() && pkg.contains('.')) pkg else null
        }
        val bgCount = runCatching {
            suSession.execute(
                "dumpsys activity processes 2>/dev/null | grep -c 'cch-empty\\|bg' || echo 0",
                12
            ).output.trim().toIntOrNull() ?: 0
        }.getOrDefault(0)

        val cpuUsage = if (cpuMax > 0) (cpuFreq * 100 / cpuMax).coerceIn(0, 100) else 0

        DeviceSnapshot(
            cpuFreqKHz = cpuFreq,
            cpuMaxFreqKHz = cpuMax,
            cpuUsagePercent = cpuUsage,
            gpuFreqKHz = gpuFreq,
            cpuPerCoreFreqKHz = cpuPerCore,
            temperature = displayTemp,
            batteryLevel = batteryLevel,
            batteryTemp = batteryTemp,
            memoryUsedPercent = memPercent,
            topApps = topApps,
            backgroundAppCount = bgCount,
            isCharging = isCharging,
            screenOn = true
        )
    }

    /** 根据快照生成推荐方案（纯逻辑，可单测） */
    fun generateRecommendations(
        snapshot: DeviceSnapshot,
        context: Context,
        deviceType: String,
        socName: String
    ): RecommendationReport {
        val sceneEnabled = PerformanceTunerRepository.isSceneEnabled(context)
        val appOptEnabled = PerformanceTunerRepository.isAppOptEnabled(context)
        return generateRecommendations(snapshot, deviceType, socName, sceneEnabled, appOptEnabled)
    }

    /** 根据快照生成推荐方案（纯逻辑，不依赖 Android Context） */
    fun generateRecommendations(
        snapshot: DeviceSnapshot,
        deviceType: String,
        socName: String,
        sceneEnabled: Boolean = false,
        appOptEnabled: Boolean = false
    ): RecommendationReport {
        val recommendations = mutableListOf<Recommendation>()
        val isTablet = deviceType.contains("平板")

        // 1. 温度过高 → 建议开启性能模式或调整温度墙
        if (snapshot.temperature >= 45) {
            recommendations += Recommendation(
                id = "thermal_high",
                title = "设备温度偏高",
                description = "当前温度 ${snapshot.temperature}°C，建议开启性能模式或降低温度墙以减少降频。",
                priority = 100,
                category = "温度",
                action = RecommendationAction.ApplySceneProfile(
                    if (isTablet) PerformanceTunerRepository.PROFILE_BALANCED
                    else PerformanceTunerRepository.PROFILE_PERFORMANCE
                )
            )
        }

        // 2. 电池温度过高
        if (snapshot.batteryTemp >= 350) {
            recommendations += Recommendation(
                id = "battery_hot",
                title = "电池温度过高",
                description = "电池温度 ${snapshot.batteryTemp / 10}°C，建议暂停高负载应用或开启省电模式。",
                priority = 95,
                category = "电池",
                action = RecommendationAction.LimitBackgroundApps(snapshot.topApps.take(3))
            )
        }

        // 3. 内存占用过高
        if (snapshot.memoryUsedPercent >= 85) {
            recommendations += Recommendation(
                id = "memory_high",
                title = "内存占用过高",
                description = "内存使用率 ${snapshot.memoryUsedPercent}%，建议清理后台应用或开启内存优化。",
                priority = 80,
                category = "内存",
                action = RecommendationAction.EnableAppOpt(true)
            )
        }

        // 4. 后台应用过多
        if (snapshot.backgroundAppCount >= 15) {
            recommendations += Recommendation(
                id = "bg_apps",
                title = "后台应用过多",
                description = "检测到 ${snapshot.backgroundAppCount} 个后台应用，建议限制后台活动以节省电量。",
                priority = 70,
                category = "后台",
                action = RecommendationAction.LimitBackgroundApps(snapshot.topApps.take(5))
            )
        }

        // 5. 低电量 + 高负载
        if (snapshot.batteryLevel <= 20 && !snapshot.isCharging && snapshot.cpuUsagePercent >= 60) {
            recommendations += Recommendation(
                id = "low_battery_load",
                title = "低电量高负载",
                description = "电量 ${snapshot.batteryLevel}% 且 CPU 负载 ${snapshot.cpuUsagePercent}%，建议开启省电模式。",
                priority = 90,
                category = "电量",
                action = RecommendationAction.ApplySceneProfile(PerformanceTunerRepository.PROFILE_BALANCED)
            )
        }

        // 6. 平板 + 未开启性能模式
        if (isTablet && !sceneEnabled) {
            recommendations += Recommendation(
                id = "tablet_perf",
                title = "平板性能优化",
                description = "检测到平板设备，建议开启性能模式以获得更流畅体验。",
                priority = 50,
                category = "设备",
                action = RecommendationAction.ApplySceneProfile(PerformanceTunerRepository.PROFILE_PERFORMANCE)
            )
        }

        // 7. 未部署性能模块
        if (!sceneEnabled && !appOptEnabled) {
            recommendations += Recommendation(
                id = "not_optimized",
                title = "尚未开启性能优化",
                description = "当前未启用任何性能优化，建议开启以获得更流畅体验。",
                priority = 40,
                category = "优化",
                action = RecommendationAction.ApplySceneProfile(PerformanceTunerRepository.PROFILE_BALANCED)
            )
        }

        // 8. 充电中 + 高温度
        if (snapshot.isCharging && snapshot.temperature >= 40) {
            recommendations += Recommendation(
                id = "charging_hot",
                title = "充电时温度偏高",
                description = "充电时温度 ${snapshot.temperature}°C，建议取下保护壳或暂停高负载应用。",
                priority = 85,
                category = "充电",
                action = RecommendationAction.NoAction("建议物理降温")
            )
        }

        val summary = if (recommendations.isEmpty()) {
            "设备状态良好，暂无优化建议"
        } else {
            "检测到 ${recommendations.size} 项可优化点，最高优先级：${recommendations.maxByOrNull { it.priority }?.title}"
        }

        return RecommendationReport(
            snapshot = snapshot,
            recommendations = recommendations.sortedByDescending { it.priority },
            deviceType = deviceType,
            socName = socName,
            summary = summary
        )
    }

    /** 一键应用推荐方案 */
    suspend fun applyRecommendation(
        context: Context,
        recommendation: Recommendation
    ): Boolean = withContext(Dispatchers.IO) {
        when (val action = recommendation.action) {
            is RecommendationAction.ApplySceneProfile -> {
                PerformanceTunerRepository.setSceneProfile(context, action.profile)
                PerformanceTunerRepository.setSceneEnabled(context, true)
                PerfTunerManager.deployScene(context)
                PerfTunerManager.startScene(context)
                true
            }
            is RecommendationAction.EnableAppOpt -> {
                PerformanceTunerRepository.setAppOptEnabled(context, action.enabled)
                if (action.enabled) {
                    PerfTunerManager.deployAppOpt(context)
                    PerfTunerManager.startAppOpt(context)
                } else {
                    PerfTunerManager.stopAppOpt(context)
                }
                true
            }
            is RecommendationAction.LimitBackgroundApps -> {
                // 通过 Shizuku 限制后台应用
                action.packages.forEach { pkg ->
                    runRootShell("cmd appops set $pkg RUN_IN_BACKGROUND deny 2>/dev/null", 5)
                }
                true
            }
            is RecommendationAction.AdjustThermal -> {
                PerformanceTunerRepository.setSceneThermal(context, action.value)
                true
            }
            is RecommendationAction.NoAction -> false
        }
    }

    private fun runRootShell(command: String, timeoutSeconds: Long = 10): ShellResult {
        if (!suSession.isSessionOpen()) {
            suSession.open(timeoutSeconds = timeoutSeconds)
        }
        val result = suSession.execute(command, timeoutSeconds)
        return ShellResult(result.exitCode, result.output)
    }

    private data class ShellResult(val exitCode: Int, val output: String)
}
