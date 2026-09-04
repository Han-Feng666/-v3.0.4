package com.HanFeng.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.HanFeng.adblocker.shizuku.PerfTunerManager
import com.HanFeng.adblocker.shizuku.SuSession
import com.HanFeng.data.PerformanceTunerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CoreFreqInfo(
    val core: Int,
    val online: Boolean,
    val curFreqKHz: Int = 0,
    val maxFreqKHz: Int = 0,
    val minFreqKHz: Int = 0,
    val availableFreqs: List<Int> = emptyList()
)

data class FreqSnapshot(
    val cores: List<CoreFreqInfo>,
    val gpuFreqKHz: Int = 0,
    val gpuMinFreqKHz: Int = 0,
    val gpuMaxFreqKHz: Int = 0,
    val temperatures: Map<String, Int> = emptyMap()
)

class PerformanceTunerActivity : BaseActivity() {

    private lateinit var binding: com.HanFeng.databinding.ActivityPerformanceTunerBinding
    private val handler = Handler(Looper.getMainLooper())
    private var rootReady = false
    private val statusRefreshRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) refreshStatus()
            handler.postDelayed(this, 3000L)
        }
    }
    // 缓存核心拓扑检测结果，避免每轮刷新重判导致大小核标签跳动
    private var cachedCoreTopology: TopologyResult? = null

    data class TopologyResult(
        val bigCores: Set<Int>,
        val oneClusterOnly: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = com.HanFeng.databinding.ActivityPerformanceTunerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val bgPath = com.HanFeng.data.FeatureSettingsRepository.getCustomBackgroundPath(this)
        if (!bgPath.isNullOrEmpty()) {
            binding.ivBackground.applyCustomFileBackground(bgPath)
        } else {
            binding.ivBackground.applyCustomAssetBackground("custom/background")
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top + 8.dp, view.paddingRight, bars.bottom + 16.dp)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }

        setupSceneUi()

        binding.btnDeploy.setOnClickListener {
            if (!rootReady) {
                showShortToast("Root 不可用")
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    PerfTunerManager.deployAssets(this@PerformanceTunerActivity)
                }
                showShortToast(if (ok) "模块文件已部署到 /data/adb/HanFengPerf" else "部署失败")
                    .also { if (ok) refreshStatus() }
            }
        }
        binding.btnRefreshStatus.setOnClickListener { refreshStatus() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handler.removeCallbacks(statusRefreshRunnable)
        handler.postDelayed(statusRefreshRunnable, 3000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(statusRefreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(statusRefreshRunnable)
    }

    // ================= SCENE =================

    private fun setupSceneUi() {
        binding.switchScene.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchScene.tag != null) return@setOnCheckedChangeListener
            if (!rootReady) {
                binding.switchScene.setOnCheckedChangeListener(null)
                binding.switchScene.isChecked = false
                binding.switchScene.setOnCheckedChangeListener { _, c -> if (binding.switchScene.tag != null) {} }
                showShortToast("Root 不可用")
                return@setOnCheckedChangeListener
            }
            toggleScene(isChecked)
        }

        binding.btnSceneEditParams.setOnClickListener { showSceneParamsEditor() }
        binding.btnSceneLog.setOnClickListener { showSceneLog() }
        binding.btnEditFreqThermal.setOnClickListener { showSceneParamsEditor() }
        binding.btnRestoreDefault.setOnClickListener { restoreDefaultConfig() }
    }

    private fun restoreDefaultConfig() {
        StableDialog.builder(this)
            .setTitle("恢复出厂默认")
            .setMessage(
                "将把 SCENE 自定义参数、频率、温度墙与 AppOpt 线程规则全部恢复为出厂默认。\n" +
                    "当前已启用的守护会自动用默认配置重新启动。"
            )
            .setPositiveButton("确认恢复") { _, _ ->
                PerformanceTunerRepository.restoreDefaultSceneConfig(this)
                PerformanceTunerRepository.restoreDefaultAppOptRules(this)
                showShortToast("已恢复出厂默认配置")
                if (rootReady) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            if (PerformanceTunerRepository.isSceneEnabled(this@PerformanceTunerActivity)) {
                                PerfTunerManager.deployScene(this@PerformanceTunerActivity)
                                PerfTunerManager.startScene(this@PerformanceTunerActivity)
                            }
                            if (PerformanceTunerRepository.isAppOptEnabled(this@PerformanceTunerActivity)) {
                                PerfTunerManager.applyAppOptRules(this@PerformanceTunerActivity)
                                PerfTunerManager.restartAppOpt(this@PerformanceTunerActivity)
                            }
                        }
                    }
                }
                refreshStatus()
            }
            .setNegativeButton("取消", null)
            .showSafely(this, "glass-dialog")
    }

    private fun toggleScene(start: Boolean) {
        lifecycleScope.launch {
            if (start) {
                val deployed = withContext(Dispatchers.IO) {
                    PerfTunerManager.deployScene(this@PerformanceTunerActivity)
                }
                if (!deployed) {
                    showShortToast("SCENE 配置部署失败")
                    syncSceneSwitch()
                    return@launch
                }
                val started = withContext(Dispatchers.IO) {
                    PerfTunerManager.startScene(this@PerformanceTunerActivity)
                }
                if (!started) {
                    showShortToast("SCENE 启动失败")
                    syncSceneSwitch()
                    return@launch
                }
                showShortToast("SCENE 调度已启动")
            } else {
                withContext(Dispatchers.IO) { PerfTunerManager.stopScene(this@PerformanceTunerActivity) }
                showShortToast("SCENE 调度已停止")
            }
            refreshStatus()
        }
    }

    private fun showSceneParamsEditor() {
        launchActivitySafely(
            SceneParamsEditorActivity.createIntent(this),
            failureMessage = "打开参数编辑器失败"
        )
    }

    private fun showSceneLog() {
        lifecycleScope.launch {
            val log = withContext(Dispatchers.IO) { PerfTunerManager.dumpLog("scene", 200) }
            StableDialog.builder(this@PerformanceTunerActivity)
                .setTitle("SCENE 日志（最近 200 行）")
                .setMessage(log.ifBlank { "(暂无日志)" })
                .setPositiveButton("关闭", null)
                .showSafely(this@PerformanceTunerActivity, "glass-dialog")
        }
    }

    // ================= 状态 =================

    private fun refreshStatus() {
        lifecycleScope.launch {
            rootReady = withContext(Dispatchers.IO) {
                val s = SuSession.getInstance()
                s.isSessionOpen() || s.open(12)
            }
            if (!rootReady) {
                binding.tvStatus.text = "Root 不可用。该功能需要 Root（Magisk/KernelSU/APatch）才能部署与运行。"
                binding.tvFreqMonitor.text = "Root 不可用，无法读取频率信息"
                binding.layoutFreqControl.removeAllViews()
                return@launch
            }

            val deployed = withContext(Dispatchers.IO) { PerfTunerManager.isDeployed() }
            val status = withContext(Dispatchers.IO) { PerfTunerManager.status(this@PerformanceTunerActivity) }

            val text = buildString {
                val socName = withContext(Dispatchers.IO) { com.HanFeng.data.SocDatabase.detectSocName(this@PerformanceTunerActivity) }
                append("处理器：").append(socName).appendLine()
                append("模块文件：").append(if (deployed) "已部署" else "未部署").appendLine()
                appendLine("— SCENE 调度 —")
                append("状态：").append(if (status.sceneRunning) "运行中 / " else "未运行 / ")
                append(if (status.sceneMode == PerformanceTunerRepository.SCENE_MODE_SCENE_DEP) "Scene兼容模式" else "独立模式").appendLine()
                append("档位：").append(sceneProfileName(status.sceneProfile)).appendLine()
            }
            binding.tvStatus.text = text

            syncSceneSwitch()

            binding.switchScene.isEnabled = rootReady
            binding.btnSceneLog.isEnabled = rootReady
            binding.btnDeploy.isEnabled = rootReady

            // 当前模式选中态
            binding.rgSceneMode.setOnCheckedChangeListener(null)
            binding.rgSceneMode.check(
                if (status.sceneMode == PerformanceTunerRepository.SCENE_MODE_SCENE_DEP)
                    com.HanFeng.R.id.rbSceneDep
                else
                    com.HanFeng.R.id.rbSceneStandalone
            )
            binding.rgSceneMode.setOnCheckedChangeListener { _, checkedId ->
                val mode = if (checkedId == com.HanFeng.R.id.rbSceneDep) {
                    PerformanceTunerRepository.SCENE_MODE_SCENE_DEP
                } else {
                    PerformanceTunerRepository.SCENE_MODE_STANDALONE
                }
                PerformanceTunerRepository.setSceneMode(this@PerformanceTunerActivity, mode)
                if (rootReady) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { PerfTunerManager.deployScene(this@PerformanceTunerActivity) }
                    }
                }
            }

            binding.rgSceneProfile.setOnCheckedChangeListener(null)
            binding.rgSceneProfile.check(
                when (status.sceneProfile) {
                    PerformanceTunerRepository.PROFILE_BALANCED -> com.HanFeng.R.id.rbSceneBalanced
                    PerformanceTunerRepository.PROFILE_GAME -> com.HanFeng.R.id.rbSceneGame
                    PerformanceTunerRepository.PROFILE_CUSTOM -> com.HanFeng.R.id.rbSceneCustom
                    PerformanceTunerRepository.PROFILE_DEFAULT -> com.HanFeng.R.id.rbSceneDefault
                    else -> com.HanFeng.R.id.rbScenePerformance
                }
            )
            binding.rgSceneProfile.setOnCheckedChangeListener { _, checkedId ->
                val profile = when (checkedId) {
                    com.HanFeng.R.id.rbSceneBalanced -> PerformanceTunerRepository.PROFILE_BALANCED
                    com.HanFeng.R.id.rbSceneGame -> PerformanceTunerRepository.PROFILE_GAME
                    com.HanFeng.R.id.rbSceneCustom -> PerformanceTunerRepository.PROFILE_CUSTOM
                    com.HanFeng.R.id.rbSceneDefault -> {
                        PerformanceTunerRepository.restoreDefaultSceneConfig(this@PerformanceTunerActivity)
                        PerformanceTunerRepository.PROFILE_CUSTOM
                    }
                    else -> PerformanceTunerRepository.PROFILE_PERFORMANCE
                }
                PerformanceTunerRepository.setSceneProfile(this@PerformanceTunerActivity, profile)
                binding.btnSceneEditParams.visibility =
                    if (profile == PerformanceTunerRepository.PROFILE_CUSTOM) View.VISIBLE else View.GONE
                if (rootReady && PerformanceTunerRepository.isSceneEnabled(this@PerformanceTunerActivity)) {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            PerfTunerManager.deployScene(this@PerformanceTunerActivity)
                            PerfTunerManager.startScene(this@PerformanceTunerActivity)
                        }
                    }
                }
            }
            binding.btnSceneEditParams.visibility =
                if (PerformanceTunerRepository.getSceneProfile(this@PerformanceTunerActivity) == PerformanceTunerRepository.PROFILE_CUSTOM) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            refreshFreqMonitor()
        }
    }

    // ================= CPU/GPU 频率监控 =================

    private suspend fun refreshFreqMonitor() {
        if (!rootReady) {
            binding.tvFreqMonitor.text = "Root 不可用，无法读取频率信息"
            binding.layoutFreqControl.removeAllViews()
            return
        }

        val snapshot = withContext(Dispatchers.IO) { readFreqSnapshot() }
        updateFreqDisplay(snapshot)
    }

    /** 强制重建频率卡片（应用频率后调用） */
    private suspend fun rebuildFreqCards() {
        binding.layoutFreqControl.removeAllViews()
        cachedCoreTopology = null
        val snapshot = withContext(Dispatchers.IO) { readFreqSnapshot() }
        buildFreqCards(snapshot)
    }

    private fun readFreqSnapshot(): FreqSnapshot {
        val session = SuSession.getInstance()
        if (!session.isSessionOpen()) return FreqSnapshot(emptyList())

        val D = "${'$'}"
        val cmd = buildString {
            append("echo \"===CPU===\" && ")
            append("for cpu in /sys/devices/system/cpu/cpu[0-9]*; do ")
            append("core=$D(basename \"$D{cpu}\"); ")
            append("online=$D(cat \"$D{cpu}/online\" 2>/dev/null || echo 1); ")
            append("if [ \"$D{online}\" = \"1\" ]; then ")
            append("cur=$D(cat \"$D{cpu}/cpufreq/scaling_cur_freq\" 2>/dev/null || echo 0); ")
            append("max=$D(cat \"$D{cpu}/cpufreq/scaling_max_freq\" 2>/dev/null || echo 0); ")
            append("min=$D(cat \"$D{cpu}/cpufreq/scaling_min_freq\" 2>/dev/null || echo 0); ")
            append("avail=$D(cat \"$D{cpu}/cpufreq/scaling_available_frequencies\" 2>/dev/null || echo \"\"); ")
            append("else cur=0; max=0; min=0; avail=\"\"; fi; ")
            append("echo \"$D{core}|$D{online}|$D{cur}|$D{max}|$D{min}|$D{avail}\"; ")
            append("done && ")
            append("echo \"===GPU===\" && ")
            append("gpu=$D(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null || echo 0) && ")
            append("gpumin=$D(cat /sys/class/kgsl/kgsl-3d0/min_gpuclk 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null || echo 0) && ")
            append("gpumax=$D(cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null || cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null || echo 0) && ")
            append("echo \"$D{gpu}|$D{gpumin}|$D{gpumax}\" && ")
            append("echo \"===TEMP===\" && ")
            append("for z in /sys/class/thermal/thermal_zone*; do ")
            append("type=$D(cat \"$D{z}/type\" 2>/dev/null); ")
            append("temp=$D(cat \"$D{z}/temp\" 2>/dev/null || echo 0); ")
            append("echo \"$D{type}=$D{temp}\"; ")
            append("done")
        }

        val result = session.execute(cmd, 8)
        val output = result.output
        val cores = mutableListOf<CoreFreqInfo>()
        var gpuFreq = 0
        var gpuMinFreq = 0
        var gpuMaxFreq = 0
        val temperatures = mutableMapOf<String, Int>()

        var section = ""
        for (line in output.lines()) {
            val trimmed = line.trim()
            when (trimmed) {
                "===CPU===" -> section = "cpu"
                "===GPU===" -> section = "gpu"
                "===TEMP===" -> section = "temp"
                else -> {
                    when (section) {
                        "cpu" -> {
                            val parts = trimmed.split("|")
                            if (parts.size >= 5) {
                                val coreName = parts[0]
                                val coreNum = coreName.removePrefix("cpu").toIntOrNull()
                                if (coreNum != null) {
                                    val online = parts[1] == "1"
                                    val cur = parts[2].toIntOrNull() ?: 0
                                    val max = parts[3].toIntOrNull() ?: 0
                                    val min = parts[4].toIntOrNull() ?: 0
                                    val avail = if (parts.size >= 6) {
                                        parts[5].trim().split(" ").mapNotNull { it.toIntOrNull() }
                                    } else emptyList()
                                    cores.add(CoreFreqInfo(coreNum, online, cur, max, min, avail))
                                }
                            }
                        }
                        "gpu" -> {
                            val parts = trimmed.split("|")
                            gpuFreq = parts.getOrNull(0)?.toIntOrNull() ?: 0
                            var gpuMin = parts.getOrNull(1)?.toIntOrNull() ?: 0
                            var gpuMax = parts.getOrNull(2)?.toIntOrNull() ?: 0
                            if (gpuMax == 0) gpuMax = gpuFreq
                            gpuMinFreq = if (gpuMin > 0 && gpuMin <= gpuMax) gpuMin else if (gpuMin > 0) gpuMax else 0
                            gpuMaxFreq = gpuMax
                        }
                        "temp" -> {
                            val eqIdx = trimmed.indexOf('=')
                            if (eqIdx > 0 && eqIdx < trimmed.length - 1) {
                                val type = trimmed.substring(0, eqIdx)
                                val temp = trimmed.substring(eqIdx + 1).toIntOrNull() ?: 0
                                temperatures[type] = temp
                            }
                        }
                    }
                }
            }
        }

        return FreqSnapshot(cores.sortedBy { it.core }, gpuFreq, gpuMinFreq, gpuMaxFreq, temperatures)
    }

    private fun updateFreqDisplay(snapshot: FreqSnapshot) {
        // 只显示所有核心的当前频率 + GPU 频率 + 温度，其余隐藏
        val freqText = buildString {
            for (core in snapshot.cores) {
                if (core.online) {
                    val curMHz = core.curFreqKHz / 1000
                    val temp = snapshot.tempFor("cpu${core.core}")
                    append("CPU${core.core}: $curMHz MHz")
                    if (temp != null) {
                        append(" · ${"%.1f".format(temp / 1000.0)}°C")
                    }
                    appendLine()
                } else {
                    appendLine("CPU${core.core}: offline")
                }
            }
            val gpuMHz = snapshot.gpuFreqKHz / 1000
            val gpuTemp = snapshot.tempFor("gpu")
            append("GPU: $gpuMHz MHz")
            if (gpuTemp != null) {
                append(" · ${"%.1f".format(gpuTemp / 1000.0)}°C")
            }
        }
        binding.tvFreqMonitor.text = freqText

        // 可编辑卡片：仅首次构建，避免自动刷新破坏用户输入
        if (binding.layoutFreqControl.childCount > 0) return
        buildFreqCards(snapshot)
    }

    private fun buildFreqCards(snapshot: FreqSnapshot) {
        // 缓存拓扑：首次检测，后续复用避免标签跳动
        if (cachedCoreTopology == null) {
            val clusterGroups = snapshot.cores.filter { it.online && it.availableFreqs.isNotEmpty() }
                .groupBy { it.availableFreqs }
            val clusters: List<List<CoreFreqInfo>> = if (clusterGroups.isNotEmpty()) {
                clusterGroups.values.toList()
            } else {
                snapshot.cores.filter { it.online }
                    .groupBy { it.maxFreqKHz }
                    .values.toList()
            }
            val sortedClusters = clusters
                .filter { it.isNotEmpty() }
                .sortedByDescending { cluster -> cluster.maxOfOrNull { it.maxFreqKHz } ?: 0 }
            val oneClusterOnly = sortedClusters.size <= 1
            val bigCores: Set<Int> = if (oneClusterOnly) {
                emptySet()
            } else {
                (sortedClusters.firstOrNull()?.map { it.core }?.toSet() ?: emptySet())
            }
            cachedCoreTopology = TopologyResult(bigCores, oneClusterOnly)
        }
        val topology = cachedCoreTopology!!

        // 为每个在线核心创建频率控制卡
        for (cpu in snapshot.cores) {
            val card = layoutInflater.inflate(
                com.HanFeng.R.layout.item_perf_freq_card,
                binding.layoutFreqControl,
                false
            ) as LinearLayout
            val nameView = card.findViewById<TextView>(com.HanFeng.R.id.tvCoreName)
            val etMin = card.findViewById<android.widget.EditText>(com.HanFeng.R.id.etMinFreq)
            val etMax = card.findViewById<android.widget.EditText>(com.HanFeng.R.id.etMaxFreq)
            val applyBtn = card.findViewById<Button>(com.HanFeng.R.id.btnApplyFreq)

            if (!cpu.online) {
                nameView.text = "核心 ${cpu.core}"
                etMin.isEnabled = false
                etMax.isEnabled = false
                applyBtn.isEnabled = false
            } else {
                val typeLabel = when {
                    topology.oneClusterOnly -> ""
                    cpu.core in topology.bigCores -> "大核"
                    else -> "小核"
                }
                nameView.text = if (typeLabel.isEmpty()) "核心 ${cpu.core}" else "$typeLabel ${cpu.core}"
                if (cpu.minFreqKHz / 1000 > 0) etMin.setText((cpu.minFreqKHz / 1000).toString())
                if (cpu.maxFreqKHz / 1000 > 0) etMax.setText((cpu.maxFreqKHz / 1000).toString())
            }

            applyBtn.setOnClickListener {
                applyCpuFreq(cpu.core, etMin, etMax)
            }
            binding.layoutFreqControl.addView(card)
        }

        // GPU card
        val gpuCard = layoutInflater.inflate(
            com.HanFeng.R.layout.item_perf_freq_card,
            binding.layoutFreqControl,
            false
        ) as LinearLayout
        gpuCard.findViewById<TextView>(com.HanFeng.R.id.tvCoreName).text = "GPU"
        val gpuEtMin = gpuCard.findViewById<android.widget.EditText>(com.HanFeng.R.id.etMinFreq)
        val gpuEtMax = gpuCard.findViewById<android.widget.EditText>(com.HanFeng.R.id.etMaxFreq)
        val gpuApplyBtn = gpuCard.findViewById<Button>(com.HanFeng.R.id.btnApplyFreq)
        val gpuMinMHz = snapshot.gpuMinFreqKHz / 1000
        val gpuMaxMHz = snapshot.gpuMaxFreqKHz / 1000
        if (gpuMinMHz > 0) gpuEtMin.setText(gpuMinMHz.toString())
        if (gpuMaxMHz > 0) gpuEtMax.setText(gpuMaxMHz.toString())
        gpuApplyBtn.setOnClickListener {
            applyGpuFreq(gpuEtMin, gpuEtMax)
        }
        binding.layoutFreqControl.addView(gpuCard)
    }

    /** 温度匹配：优先完全一致的 zone type，其次子串匹配；返回毫摄氏度 */
    private fun FreqSnapshot.tempFor(match: String): Int? {
        temperatures.entries.firstOrNull { it.key.equals(match, ignoreCase = true) }?.let { return it.value }
        return temperatures.entries
            .firstOrNull { it.key.contains(match, ignoreCase = true) && it.value > 0 }
            ?.value
    }

    /** 返回命中的 zone type，用于后续去重过滤 */
    private fun FreqSnapshot.tempTypeFor(match: String): String {
        temperatures.keys.firstOrNull { it.equals(match, ignoreCase = true) }
            ?.let { return it }
        return temperatures.keys.firstOrNull { it.contains(match, ignoreCase = true) } ?: ""
    }

    /** 解析输入框（MHz），返回 kHz；非法返回 null */
    private fun parseFreqInput(et: android.widget.EditText): Int? {
        val raw = et.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val mhz = raw.toLongOrNull() ?: return null
        if (mhz <= 0) return null
        return (mhz * 1000L).toInt()
    }

    /** 同一值则同时写 min/max = 锁频；否则分别写 min 与 max */
    private fun setFreqPair(minKHz: Int, maxKHz: Int, minWrite: (Int) -> String, maxWrite: (Int) -> String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val session = SuSession.getInstance()
                if (!session.isSessionOpen()) return@withContext false
                val cmds = buildString {
                    if (minKHz > 0) {
                        append(minWrite(minKHz)).append("; ")
                    }
                    if (maxKHz > 0) {
                        append(maxWrite(maxKHz)).append("; ")
                    }
                }
                if (cmds.isEmpty()) return@withContext false
                val result = session.execute("$cmds echo FREQ_SET_OK", 8)
                result.output.contains("FREQ_SET_OK")
            }
            if (ok) {
                showShortToast("频率已应用")
                rebuildFreqCards()
            } else {
                showShortToast("设置频率失败，请检查 Root 权限或输入是否合法")
            }
        }
    }

    private fun applyCpuFreq(coreId: Int, etMin: android.widget.EditText, etMax: android.widget.EditText) {
        if (!rootReady) {
            showShortToast("Root 不可用")
            return
        }
        val corePath = "/sys/devices/system/cpu/cpu$coreId/cpufreq"
        val minKHz = parseFreqInput(etMin)
        val maxKHz = parseFreqInput(etMax)
        if (minKHz == null && maxKHz == null) {
            showShortToast("请至少填写一个频率（MHz）")
            return
        }
        if (minKHz != null && maxKHz != null && minKHz > maxKHz) {
            showShortToast("最小频率不能大于最大频率")
            return
        }
        setFreqPair(
            minKHz ?: (maxKHz ?: 0), maxKHz ?: (minKHz ?: 0),
            { value -> "echo $value > $corePath/scaling_min_freq" },
            { value -> "echo $value > $corePath/scaling_max_freq" }
        )
    }

    private fun applyGpuFreq(etMin: android.widget.EditText, etMax: android.widget.EditText) {
        if (!rootReady) {
            showShortToast("Root 不可用")
            return
        }
        val minPath = "/sys/class/kgsl/kgsl-3d0/min_gpuclk"
        val maxPath = "/sys/class/kgsl/kgsl-3d0/max_gpuclk"
        val minPathAlt = "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
        val maxPathAlt = "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"
        val minKHz = parseFreqInput(etMin)
        val maxKHz = parseFreqInput(etMax)
        if (minKHz == null && maxKHz == null) {
            showShortToast("请至少填写一个频率（MHz）")
            return
        }
        if (minKHz != null && maxKHz != null && minKHz > maxKHz) {
            showShortToast("最小频率不能大于最大频率")
            return
        }
        setFreqPair(
            (minKHz ?: (maxKHz ?: 0)), maxKHz ?: (minKHz ?: 0),
            { value ->
                // min 与 max 相同（锁频）时写 min 为 value；不同时只写 min
                "echo $value > $minPath; echo $value > $minPathAlt"
            },
            { value ->
                "echo $value > $maxPath; echo $value > $maxPathAlt"
            }
        )
    }

    private fun sceneProfileName(profile: String): String = when (profile) {
        PerformanceTunerRepository.PROFILE_BALANCED -> "均衡"
        PerformanceTunerRepository.PROFILE_GAME -> "游戏"
        PerformanceTunerRepository.PROFILE_CUSTOM -> "自定义"
        PerformanceTunerRepository.PROFILE_DEFAULT -> "默认"
        else -> "性能"
    }

    private fun syncSceneSwitch() {
        val enabled = PerformanceTunerRepository.isSceneEnabled(this)
        if (binding.switchScene.isChecked == enabled) return
        binding.switchScene.setOnCheckedChangeListener(null)
        binding.switchScene.tag = true
        binding.switchScene.isChecked = enabled
        binding.switchScene.setOnCheckedChangeListener { _, isChecked ->
            if (binding.switchScene.tag != null) return@setOnCheckedChangeListener
            if (!rootReady) {
                showShortToast("Root 不可用")
                return@setOnCheckedChangeListener
            }
            toggleScene(isChecked)
        }
        binding.switchScene.tag = null
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, PerformanceTunerActivity::class.java)
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}