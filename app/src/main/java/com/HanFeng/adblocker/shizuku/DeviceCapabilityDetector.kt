package com.HanFeng.adblocker.shizuku

import android.util.Log

/**
 * Root 功能兼容性检测器。
 *
 * 探测当前设备支持哪些 Root 隐藏 / 应用隐藏 / 性能调优手段，
 * 并识别设备类型（手机 / 平板 / 折叠屏），供 UI 展示和脚本分支使用。
 */
class DeviceCapabilityDetector {

    companion object {
        private const val TAG = "DeviceCapability"
    }

    data class CapabilityReport(
        val rootAvailable: Boolean,
        val rootSolution: String,
        val deviceType: String,
        val pmHideSupported: Boolean,
        val appopsSupported: Boolean,
        val magiskDenylistSupported: Boolean,
        val ksuMagiskhideSupported: Boolean,
        val systemMountSupported: Boolean,
        val zygiskAvailable: Boolean,
        val isTablet: Boolean,
        val isFoldable: Boolean,
        val androidVersion: Int,
        val notes: List<String>
    ) {
        val summary: String
            get() = buildString {
                append(rootSolution).append(" / ").append(deviceType)
                if (pmHideSupported) append(" / pm hide")
                if (appopsSupported) append(" / appops")
                if (magiskDenylistSupported) append(" / denylist")
                if (ksuMagiskhideSupported) append(" / magiskhide")
                if (systemMountSupported) append(" / mount")
            }
    }

    private val suSession get() = SuSession.getInstance()

    fun detect(): CapabilityReport {
        val notes = mutableListOf<String>()
        val rootAvailable = suSession.isSessionOpen() || suSession.open(20)
        if (!rootAvailable) {
            return CapabilityReport(
                rootAvailable = false, rootSolution = "未获取 Root", deviceType = "未知",
                pmHideSupported = false, appopsSupported = false,
                magiskDenylistSupported = false, ksuMagiskhideSupported = false,
                systemMountSupported = false, zygiskAvailable = false,
                isTablet = false, isFoldable = false,
                androidVersion = android.os.Build.VERSION.SDK_INT,
                notes = listOf("未获取到 Root 权限，部分功能不可用")
            )
        }

        val rootSolution = detectRootSolution()
        val deviceType = detectDeviceType()
        val isTablet = deviceType.contains("平板")
        val isFoldable = detectFoldable()

        val pmHide = runRootShell("pm help 2>/dev/null | grep -qE 'hide +\\[--user' && echo YES || echo NO")
            .output.trim() == "YES"
        val appops = runRootShell("command -v appops >/dev/null 2>&1 && echo YES || echo NO")
            .output.trim() == "YES"
        val denylist = runRootShell("magisk --denylist ls 2>/dev/null | grep -q . && echo YES || echo NO")
            .output.trim() == "YES"
        val magiskhide = runRootShell(
            "command -v ksud >/dev/null 2>&1 || [ -x /data/adb/ksud ] || [ -x /data/adb/ksu/bin/ksud ] && echo YES || echo NO"
        ).output.trim() == "YES"
        val mount = runRootShell("mount | grep ' / ' | grep -qv 'ro,' && echo YES || echo NO")
            .output.trim() == "YES"
        val zygisk = runRootShell(
            "test -d /data/adb/zygisk || test -d /data/adb/modules/zygisksu || test -d /data/adb/modules/zygisk-next && echo YES || echo NO"
        ).output.trim() == "YES"

        if (!pmHide) notes += "pm hide 不可用，应用隐藏将降级"
        if (!appops) notes += "appops 不可用，权限回收降级"
        if (!denylist && !magiskhide) notes += "DenyList/MagiskHide 均不可用，Root 隐藏能力受限"
        if (!mount) notes += "系统分区只读，无法使用挂载隐藏"
        if (isTablet) notes += "平板设备，已启用平板优化配置"
        if (isFoldable) notes += "折叠屏设备，已适配多形态"

        return CapabilityReport(
            rootAvailable = true,
            rootSolution = rootSolution,
            deviceType = deviceType,
            pmHideSupported = pmHide,
            appopsSupported = appops,
            magiskDenylistSupported = denylist,
            ksuMagiskhideSupported = magiskhide,
            systemMountSupported = mount,
            zygiskAvailable = zygisk,
            isTablet = isTablet,
            isFoldable = isFoldable,
            androidVersion = android.os.Build.VERSION.SDK_INT,
            notes = notes
        )
    }

    private fun detectRootSolution(): String {
        val r = runRootShell(
            "if [ -d /data/adb/magisk ]; then echo Magisk; " +
                "elif [ -d /data/adb/ksu ]; then echo KernelSU; " +
                "elif [ -d /data/adb/ap ]; then echo APatch; " +
                "elif [ -f /system/bin/su ] || [ -f /system/xbin/su ]; then echo UnknownRoot; " +
                "else echo None; fi"
        )
        return r.output.trim().ifBlank { "Unknown" }
    }

    private fun detectDeviceType(): String {
        val r = runRootShell(
            "size=$(wm size 2>/dev/null | grep -oE '[0-9]+x[0-9]+' | head -1); " +
                "diag=$(wm density 2>/dev/null | grep -oE '[0-9]+' | head -1); " +
                "echo \"${'$'}size|${'$'}diag\""
        )
        val parts = r.output.trim().split("|")
        val size = parts.getOrNull(0) ?: ""
        val density = parts.getOrNull(1)?.toIntOrNull() ?: 0

        if (size.isBlank()) return "未知"

        val dims = size.split("x")
        val w = dims.getOrNull(0)?.toIntOrNull() ?: 0
        val h = dims.getOrNull(1)?.toIntOrNull() ?: 0
        val minDim = minOf(w, h)
        val maxDim = maxOf(w, h)
        val aspect = if (minDim > 0) maxDim.toFloat() / minDim else 0f
        // 用最小边 dp (sw) 判型 — 2K 屏手机物理像素宽可超 1200, 按物理像素判断会把手机误判成平板。
        // Android 标准阈值: sw600dp 为平板 (values-sw600dp 资源分界), 480-600 为大屏手机。
        val swDp = if (density > 0) minDim * 160 / density else 0

        return when {
            swDp >= 600 -> "平板"
            swDp >= 480 && aspect < 1.6f -> "平板"
            swDp >= 480 -> "大屏手机"
            minDim >= 2000 && density >= 400 -> "大屏手机"
            else -> "手机"
        }
    }

    private fun detectFoldable(): Boolean {
        val r = runRootShell(
            "getprop ro.hardware.folding 2>/dev/null | grep -qi 'fold\\|flex' && echo YES || " +
                "getprop ro.surface_folding 2>/dev/null | grep -q '[0-9]' && echo YES || echo NO"
        )
        return r.output.trim() == "YES"
    }

    private fun runRootShell(command: String, timeoutSeconds: Long = 15): ShellResult {
        if (!suSession.isSessionOpen()) {
            suSession.open(timeoutSeconds = timeoutSeconds)
        }
        val result = suSession.execute(command, timeoutSeconds)
        return ShellResult(result.exitCode, result.output)
    }

    private data class ShellResult(val exitCode: Int, val output: String)
}
