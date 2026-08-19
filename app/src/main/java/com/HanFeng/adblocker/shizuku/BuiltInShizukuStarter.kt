package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.File

/**
 * 内置 Shizuku 激活管理器.
 *
 * 实现策略与官方 RikkaApps/Shizuku 的 StarterActivity.kt + Starter.kt 完全一致, 仅做必要 applicationId 改名:
 *   - Root 激活: su -c '<nativeLibraryDir>/libshizuku.so --apk=<applicationInfo.sourceDir>'
 *   - 无线调试激活: 用官方 AdbClient + AdbKey 走一次 shellCommand(Starter.internalCommand)
 *
 * Shizuku 的 server 由本 app 内置的 starter nativitve binary (fork starter.cpp) 拉起,
 * 它通过 binder 推回本 app 的 ShizukuProvider. 不再依赖外部 Shizuku APK.
 *
 * 不再做任何 pgrep / binder-wait / zip-extract 等附加兜底,
 * 这些曾是过去激活失败的根因 — 与官方一致保持简单链路.
 */
object BuiltInShizukuStarter {

    private const val TAG = "BuiltInShizukuStarter"

    private const val STARTER_BINARY_NAME = "libshizuku.so"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    data class ActivationResult(
        val success: Boolean,
        val mode: String,
        val message: String
    )

    // ---------------------------------------------------------------
    // Root 激活 - 直接对齐官方 Starter.kt.internalCommand
    // ---------------------------------------------------------------

    fun activateViaRoot(): ActivationResult {
        val context = appContext
            ?: return ActivationResult(false, "root", "BuiltInShizukuStarter 未初始化")

        val su = SuSession.getInstance()
        if (!su.open(10)) {
            return ActivationResult(false, "root", "Root 权限获取失败")
        }

        val apkPath = context.applicationInfo.sourceDir
        if (apkPath.isBlank() || !File(apkPath).isFile) {
            return ActivationResult(false, "root", "无法获取本 app APK 路径: $apkPath")
        }

        val libDir = context.applicationInfo.nativeLibraryDir
        if (libDir.isBlank()) {
            return ActivationResult(false, "root", "无法获取本 app nativeLibraryDir")
        }

        val starterBinary = pickStarterBinaryPath(libDir)
            ?: return ActivationResult(
                false, "root",
                "无法定位内置 starter ($STARTER_BINARY_NAME). 检查 APK 是否完整安装或重新安装本 app"
            )

        // 与官方 Starter.kt 完全一致:
        //   internalCommand = "$starterBinary --apk=$apkPath"
        // 用 root shell 执行一次即可. starter.cpp fork 后 child setsid + execvp app_process,
        // parent 立即 printf "info: shizuku_starter exit with 0" 然后退出.
        // server 进程是 child 跑起后异步 binder 推回, 本函数返回后由 ShizukuProvider 接收 binder.
        val cmd = buildString {
            append("chmod 755 '").append(starterBinary).append("' 2>/dev/null; ")
            append("'").append(starterBinary).append("' --apk='").append(apkPath).append("'")
        }

        Log.d(TAG, "root activation cmd: $cmd")
        val result = try {
            su.execute(cmd, 15)
        } catch (t: Throwable) {
            Log.e(TAG, "root activation threw", t)
            return ActivationResult(false, "root", "执行失败: ${t.message}")
        }

        val out = result.output.trim()
        Log.d(TAG, "root activation exitCode=${result.exitCode} out=$out")

        // 官方判据: starter 输出 "info: shizuku_starter exit with 0" 表示 starter 已 fork 子进程去拉 server 成功.
        // 不依赖 binder ping - binder 异步推回由 ShizukuProvider 接收, UI 层会订阅 binder received listener.
        val starterOk = out.contains("info: shizuku_starter exit with 0", ignoreCase = true)
                || out.contains("info: shizuku_server pid is", ignoreCase = true)

        return if (starterOk) {
            ActivationResult(true, "root", buildString {
                append("已通过 Root 内置 starter 启动 Shizuku 服务\n\n")
                append("starter 输出:\n")
                append(out.take(400))
            })
        } else {
            ActivationResult(false, "root", buildString {
                append("starter 输出:\n")
                append(out.take(800))
            })
        }
    }

    // ---------------------------------------------------------------
    // 无线调试配对 + 激活
    // ---------------------------------------------------------------

    fun pairAndActivateViaWirelessDebug(
        pairingCode: String,
        host: String,
        port: Int
    ): ActivationResult {
        val context = appContext
            ?: return ActivationResult(false, "wireless", "BuiltInShizukuStarter 未初始化")

        try {
            return WirelessDebugPairer(context).pairAndActivate(pairingCode, host, port)
        } catch (t: Throwable) {
            Log.e(TAG, "wireless activation failed", t)
            return ActivationResult(false, "wireless", "无线调试激活异常: ${t.message}")
        }
    }

    // ---------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------

    /**
     * 定位 starter binary 实际所在路径.
     * nativeLibraryDir 一般就是 <apk_dir>/lib/<ABI> 并 direkt 包含 libshizuku.so (useLegacyPackaging=true).
     */
    private fun pickStarterBinaryPath(nativeLibraryDir: String): String? {
        if (nativeLibraryDir.isBlank()) return null
        val direct = "$nativeLibraryDir/$STARTER_BINARY_NAME"
        if (File(direct).isFile) return direct
        // 兜底: 列目录找 (个别 ROM 不稳定, nativeLibraryDir 是符号链接)
        val dir = File(nativeLibraryDir)
        if (dir.isDirectory) {
            dir.listFiles()?.forEach { f ->
                if (f.name == STARTER_BINARY_NAME && f.isFile && f.canRead()) {
                    return f.absolutePath
                }
            }
        }
        return null
    }

    fun isShizukuBuiltin(): Boolean = true

    fun isShizukuAppInstalled(context: Context): Boolean = false
}
