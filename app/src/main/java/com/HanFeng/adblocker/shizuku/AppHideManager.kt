package com.HanFeng.adblocker.shizuku

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用隐藏管理器：让指定应用不再被其它 App 的包查询发现。
 *
 * 生效层级（从强到弱，逐级回退）：
 * 1. [ApplicationInfo.HIDDEN_YES] —— 系统级隐藏，包对全局不可见（pm list packages / 桌面 / 其它 App 全部查不到）
 * 2. AppOps `QUERY_ALL_PACKAGES` deny —— 阻止目标 App 枚举完整包列表
 * 3. 目标 App 的 `android.permission.QUERY_ALL_PACKAGES` revoke —— 从权限层面断掉主动枚举能力
 *
 * 说明：真正「只对指定检测 App 不可见、目标 App 自身照常使用」需要 Zygisk/LSPosed hook
 * system_server 的 PackageManagerService，本管理器不覆盖该场景。
 */
class AppHideManager {

    companion object {
        private const val TAG = "AppHideManager"

        /** 绝对不允许隐藏的包：系统关键组件与本 App 自身 */
        private val PROTECTED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.shell",
            "com.android.providers.settings",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.gsf"
        )

        /** 桌面/系统级不建议隐藏的包前缀 */
        private val PROTECTED_PREFIXES = listOf(
            "com.android.",
            "android.",
            "com.google.android.gms",
            "com.miui.",
            "com.xiaomi."
        )
    }

    data class HideCapability(
        val rootAvailable: Boolean,
        val pmHideSupported: Boolean,
        val appopsAvailable: Boolean,
        val notes: List<String>
    )

    data class AppHideResult(
        val success: Boolean,
        val packageName: String,
        val actions: List<String>,
        val failure: String?
    ) {
        val detail: String
            get() = if (success) {
                actions.joinToString("；")
            } else {
                failure ?: "未知错误"
            }
    }

    data class AppHideStatus(
        val hiddenPackages: List<String>,
        val totalHidden: Int
    )

    private val suSession get() = SuSession.getInstance()
    private val verifying = AtomicBoolean(false)

    /** 探测当前设备支持哪些隐藏手段 */
    fun detectCapability(): HideCapability {
        if (!suSession.isSessionOpen() && !suSession.open(20)) {
            return HideCapability(false, false, false, listOf("未获取到 Root 权限"))
        }
        val pmOk = runRootShell("pm help 2>/dev/null | grep -qE 'hide +\\[--user' && echo PM_OK || echo PM_NO")
        val appopsOk = runRootShell("command -v appops >/dev/null 2>&1 && echo AO_OK || echo AO_NO")
        val notes = mutableListOf<String>()
        if (!pmOk.output.contains("PM_OK")) notes += "当前 pm 不支持 hide 子命令"
        if (!appopsOk.output.contains("AO_OK")) notes += "appops 不可用，权限回收降级"
        notes += "隐藏后目标 App 会从桌面和所有包查询中消失，可用「恢复」找回"
        return HideCapability(
            rootAvailable = true,
            pmHideSupported = pmOk.output.contains("PM_OK"),
            appopsAvailable = appopsOk.output.contains("AO_OK"),
            notes = notes
        )
    }

    fun isProtectedPackage(packageName: String): Boolean {
        if (PROTECTED_PACKAGES.contains(packageName)) return true
        return PROTECTED_PREFIXES.any { packageName == it || packageName.startsWith(it) }
    }

    /** 隐藏单个应用。已隐藏时返回 success。 */
    fun hideApp(packageName: String, selfPackage: String): AppHideResult {
        if (packageName.isBlank()) {
            return AppHideResult(false, packageName, emptyList(), "包名为空")
        }
        if (packageName == selfPackage) {
            return AppHideResult(false, packageName, emptyList(), "不能隐藏本 App 自身")
        }
        if (isProtectedPackage(packageName)) {
            return AppHideResult(false, packageName, emptyList(), "$packageName 属于系统关键包，禁止隐藏")
        }
        if (!suSession.isSessionOpen() && !suSession.open(20)) {
            return AppHideResult(false, packageName, emptyList(), "未获取到 Root 权限")
        }

        val escaped = escape(packageName)
        val actions = mutableListOf<String>()

        val hide = runRootShell("pm hide '$escaped' >/dev/null 2>&1 && echo HIDE_OK || echo HIDE_NO", 15)
        if (hide.output.contains("HIDE_OK") && isHiddenOnDevice(packageName)) {
            actions += "系统级隐藏 (pm hide)"
        } else {
            Log.w(TAG, "pm hide not applied for $packageName: ${hide.output.take(200)}")
        }

        // appops 阻断目标 App 自身枚举完整包列表
        val appops = runRootShell(
            "appops set '$escaped' QUERY_ALL_PACKAGES deny 2>/dev/null && echo AO_OK || echo AO_NO",
            10
        )
        if (appops.output.contains("AO_OK")) {
            actions += "AppOps 阻断枚举 (QUERY_ALL_PACKAGES deny)"
        }

        if (actions.isEmpty()) {
            return AppHideResult(false, packageName, actions, "隐藏失败：系统未接受任何隐藏方式")
        }
        return AppHideResult(true, packageName, actions, null)
    }

    /** 恢复单个应用的可见性 */
    fun unhideApp(packageName: String): AppHideResult {
        if (packageName.isBlank()) {
            return AppHideResult(false, packageName, emptyList(), "包名为空")
        }
        if (!suSession.isSessionOpen() && !suSession.open(20)) {
            return AppHideResult(false, packageName, emptyList(), "未获取到 Root 权限")
        }
        val escaped = escape(packageName)
        val actions = mutableListOf<String>()

        val unhide = runRootShell("pm unhide '$escaped' >/dev/null 2>&1 && echo UNHIDE_OK || echo UNHIDE_NO", 15)
        if (unhide.output.contains("UNHIDE_OK") && !isHiddenOnDevice(packageName)) {
            actions += "已恢复系统级可见 (pm unhide)"
        }

        val appops = runRootShell("appops set '$escaped' QUERY_ALL_PACKAGES allow 2>/dev/null && echo AO_OK || echo AO_NO", 10)
        if (appops.output.contains("AO_OK")) {
            actions += "已恢复 AppOps 枚举权限"
        }

        if (actions.isEmpty()) {
            return AppHideResult(false, packageName, actions, "恢复失败：目标应用可能已卸载")
        }
        return AppHideResult(true, packageName, actions, null)
    }

    fun hideApps(packageNames: Collection<String>, selfPackage: String): List<AppHideResult> =
        packageNames.map { hideApp(it, selfPackage) }

    fun unhideAll(packageNames: Collection<String>): List<AppHideResult> =
        packageNames.map { unhideApp(it) }

    /**
     * 读取设备上所有处于 hidden 状态的包名集合。
     * hidden 包不会出现在 `pm list packages` 中，只能从 dumpsys 的 PackageUserState 段解析。
     * 单次 dumpsys + awk 扫描，避免逐包 spawn。
     */
    fun listSystemHidden(): Set<String> {
        if (!suSession.isSessionOpen() && !suSession.open(20)) return emptySet()
        val r = runRootShell(
            "dumpsys package packages 2>/dev/null | " +
                "awk 'match(\$0, /Package \\[[^]]+\\]/) { pkg=substr(\$0, RSTART+9, RLENGTH-10) } " +
                "/hidden=true/ { if (pkg != \"\") { print pkg; pkg=\"\" } }'",
            30
        )
        if (r.output.isBlank()) return emptySet()
        return r.output.lines().map { it.trim() }.filter { it.contains('.') }.toSet()
    }

    private fun isHiddenOnDevice(packageName: String): Boolean {
        val r = runRootShell(
            "dumpsys package '$packageName' 2>/dev/null | grep -c 'hidden=true'",
            12
        )
        return r.output.trim().toIntOrNull()?.let { it > 0 } ?: false
    }

    /**
     * 校验持久化隐藏列表与系统实际状态是否一致，必要时补应用。
     * 系统重启或用户手动恢复后 pm hide 状态可能丢失，这里做一次兜底。
     */
    fun verifyAndRepair(targetPackages: Collection<String>, selfPackage: String): List<String> {
        if (verifying.compareAndSet(false, true).not()) return emptyList()
        try {
            if (targetPackages.isEmpty()) return emptyList()
            val actual = listSystemHidden()
            val missing = targetPackages.filter { it !in actual && !isProtectedPackage(it) && it != selfPackage }
            if (missing.isEmpty()) return emptyList()
            val repaired = mutableListOf<String>()
            for (pkg in missing) {
                val r = hideApp(pkg, selfPackage)
                if (r.success) repaired += pkg
            }
            Log.d(TAG, "repaired hidden packages: ${repaired.size}")
            return repaired
        } finally {
            verifying.set(false)
        }
    }

    private fun escape(raw: String): String = raw.replace("'", "'\\''")

    private fun runRootShell(command: String, timeoutSeconds: Long = 30): ShellResult {
        if (!suSession.isSessionOpen()) {
            suSession.open(timeoutSeconds = timeoutSeconds)
        }
        val result = suSession.execute(command, timeoutSeconds)
        return ShellResult(result.exitCode, result.output)
    }

    private data class ShellResult(val exitCode: Int, val output: String)
}
