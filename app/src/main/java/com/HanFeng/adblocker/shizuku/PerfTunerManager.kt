package com.HanFeng.adblocker.shizuku

import android.content.Context
import android.util.Log
import com.HanFeng.data.LogRepository
import com.HanFeng.data.PerformanceTunerRepository
import java.io.File

/**
 * 性能调优模块的 Root 部署与守护管理。
 * 职责：
 *  - 将 assets/perf 解包到 /data/adb/HanFengPerf
 *  - 部署 AppOpt 二进制、规则文件、cpu_control 脚本
 *  - 部署 SCENE 独立/兼容模式配置并起守护
 */
object PerfTunerManager {
    private const val TAG = "PerfTuner"

    private val suSession get() = SuSession.getInstance()

    data class Status(
        val sceneRunning: Boolean,
        val sceneMode: String,
        val sceneProfile: String,
        val appoptRunning: Boolean,
        val appoptRulesLines: Int
    )

    private fun hasRoot(): Boolean = suSession.isSessionOpen() || suSession.open(20)

    // ============ 部署 ============

    /** 从 app assets 复制模块文件到 /data/adb/HanFengPerf */
    fun deployAssets(context: Context): Boolean {
        if (!hasRoot()) return false
        LogRepository.append(context, "[$TAG] deploying assets to /data/adb/HanFengPerf")

        // 中转目录：写入 app 私有文件，再由 root 拷贝
        val staging = File(context.filesDir, "perf_staging")
        val appoptOk = copyAssetTree(context, "perf/appopt", File(staging, "appopt"))
        val sceneOk = copyAssetTree(context, "perf/scene", File(staging, "scene"))
        if (!appoptOk || !sceneOk) {
            LogRepository.append(context, "[$TAG] deploy failed: asset copy error")
            return false
        }

        val src = staging.absolutePath
        val cmd = buildString {
            append("SRC='$src'\n")
            append("DST='${PerformanceTunerRepository.DEPLOY_DIR}'\n")
            append("mkdir -p \"\$DST\" \"\$DST/appopt\" \"\$DST/scene\"\n")
            append("cp -af \"\$SRC/appopt/\" \"\$DST/appopt/\" 2>&1\n")
            append("cp -af \"\$SRC/scene/\" \"\$DST/scene/\" 2>&1\n")
            append("chmod 755 \"\$DST/appopt/AppOpt\" 2>/dev/null\n")
            append("chmod +x \"\$DST/appopt\"/*.sh \"\$DST/scene\"/*.sh 2>/dev/null\n")
            append("chown -R root:shell \"\$DST\" 2>/dev/null\n")
            append("echo DEPLOY_COPIED")
        }
        val res = suSession.execute(cmd, 15)
        LogRepository.append(context, "[$TAG] deploy copy exit=${res.exitCode}")
        return res.output.contains("DEPLOY_COPIED")
    }

    fun isDeployed(): Boolean {
        if (!hasRoot()) return false
        return suSession.execute(
            "test -f '${PerformanceTunerRepository.APPOPT_DIR}/AppOpt' && " +
                "test -f '${PerformanceTunerRepository.SCENE_DIR}/scene_daemon.sh' && echo DEPLOYED || echo NOT_DEPLOYED", 5
        ).output.contains("DEPLOYED")
    }

    private fun copyAssetTree(context: Context, assetPath: String, destDir: File): Boolean {
        return try {
            context.assets.list(assetPath)?.let { children ->
                if (children.isNotEmpty()) {
                    destDir.mkdirs()
                    children.forEach { child ->
                        val childAsset = "$assetPath/$child"
                        if (context.assets.list(childAsset)?.isNotEmpty() == true) {
                            copyAssetTree(context, childAsset, File(destDir, child))
                        } else {
                            context.assets.open(childAsset).use { ins ->
                                File(destDir, child).outputStream().use { ins.copyTo(it) }
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "copyAssetTree failed: $assetPath", e)
            false
        }
    }

    // ============ AppOpt ============

    private fun writeAppOptRules(ruleText: String): Boolean {
        val command = "cat > '${PerformanceTunerRepository.APPOPT_DIR}/applist.prop' << 'EOF_HF_RULE'\n" +
            ruleText + "\nEOF_HF_RULE\nchmod 644 '${PerformanceTunerRepository.APPOPT_DIR}/applist.prop' && echo RULE_OK"
        val res = suSession.execute(command, 5)
        return res.output.contains("RULE_OK")
    }

    /** 部署 AppOpt 二进制、规则与守护脚本 */
    fun deployAppOpt(context: Context): Boolean {
        if (!hasRoot()) return false
        if (!isDeployed()) {
            if (!deployAssets(context)) return false
        }
        val ruleText = PerformanceTunerRepository.getAppOptRuleText(context)
        if (!writeAppOptRules(ruleText)) return false

        val cpuControl = PerformanceTunerRepository.isAppOptCpuControlEnabled(context)
        val daemon = buildAppOptDaemon(cpuControlScript = if (cpuControl) 1 else 0)
        val res = suSession.execute(
            "cat > '${PerformanceTunerRepository.APPOPT_DAEMON}' << 'EOF_HF_AP'\n" +
                daemon + "\nEOF_HF_AP\nchmod 755 '${PerformanceTunerRepository.APPOPT_DAEMON}' && echo DAEMON_WRITTEN", 5
        )
        return res.output.contains("DAEMON_WRITTEN")
    }

    private fun buildAppOptDaemon(cpuControlScript: Int): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("# HanFeng AppOpt daemon — 线程亲和守护")
        appendLine("DIR='${PerformanceTunerRepository.APPOPT_DIR}'")
        appendLine("LOG='${PerformanceTunerRepository.APPOPT_LOG}'")
        appendLine("CPU_CTL=$cpuControlScript")
        appendLine("")
        appendLine("log_msg() { echo \"\$(date '+%Y-%m-%d %H:%M:%S') [\$1] \$2\" >> \"\$LOG\" 2>/dev/null; }")
        appendLine("")
        appendLine("while [ \"\$(getprop sys.boot_completed)\" != \"1\" ]; do sleep 2; done")
        appendLine("i=0; until [ -d /data/user/0/android ] || [ \$i -ge 60 ]; do sleep 2; i=\$((i+1)); done")
        appendLine("")
        appendLine("if [ -f '${PerformanceTunerRepository.APPOPT_PID}' ]; then")
        appendLine("  kill \$(cat '${PerformanceTunerRepository.APPOPT_PID}') 2>/dev/null")
        appendLine("  sleep 0.3; kill -9 \$(cat '${PerformanceTunerRepository.APPOPT_PID}') 2>/dev/null")
        appendLine("fi")
        appendLine("killall -15 AppOpt 2>/dev/null")
        appendLine("sleep 0.5")
        appendLine("")
        appendLine("log_msg \"I\" \"AppOpt starting (cpu_ctl=\$CPU_CTL)\"")
        appendLine("if [ \"\$CPU_CTL\" = \"1\" ] && [ -f \"\$DIR/cpu_control.sh\" ]; then")
        appendLine("  MODPATH=\"\$DIR\" sh \"\$DIR/cpu_control.sh\" >> \"\$LOG\" 2>&1")
        appendLine("  log_msg \"I\" \"cpu_control.sh executed\"")
        appendLine("fi")
        appendLine("")
        appendLine("nohup \"\$DIR/AppOpt\" -c \"\$DIR/applist.prop\" -s 2 >> \"\$LOG\" 2>&1 &")
        appendLine("echo \$! > '${PerformanceTunerRepository.APPOPT_PID}'")
        appendLine("log_msg \"I\" \"AppOpt pid=\$(cat '${PerformanceTunerRepository.APPOPT_PID}')\"")
        appendLine("exit 0")
    }

    fun startAppOpt(context: Context): Boolean {
        if (!hasRoot()) return false
        if (!deployAppOpt(context)) {
            LogRepository.append(context, "[$TAG] AppOpt deploy failed")
            return false
        }
        if (appOptRunning()) stopAppOpt(context)
        val res = suSession.execute(
            "nohup sh '${PerformanceTunerRepository.APPOPT_DAEMON}' > '${PerformanceTunerRepository.APPOPT_LOG}' 2>&1 &\n" +
                "sleep 1.5 && pgrep -f 'AppOpt -c' >/dev/null 2>&1 && echo AP_STARTED || echo AP_FAIL", 12
        )
        val started = res.output.contains("AP_STARTED")
        if (started) {
            PerformanceTunerRepository.setAppOptEnabled(context, true)
            LogRepository.append(context, "[$TAG] AppOpt started")
        } else {
            LogRepository.append(context, "[$TAG] AppOpt failed to start: ${res.output.take(200)}")
        }
        return started
    }

    fun stopAppOpt(context: Context): Boolean {
        if (!hasRoot()) return false
        if (appOptRunning()) {
            suSession.execute(
                "if [ -f '${PerformanceTunerRepository.APPOPT_PID}' ]; then " +
                    "kill \$(cat '${PerformanceTunerRepository.APPOPT_PID}') 2>/dev/null; sleep 0.3; " +
                    "kill -9 \$(cat '${PerformanceTunerRepository.APPOPT_PID}') 2>/dev/null; fi; " +
                    "killall -15 AppOpt 2>/dev/null; sleep 0.3; killall -9 AppOpt 2>/dev/null; echo STOPPED", 6
            )
        }
        suSession.execute("rm -f '${PerformanceTunerRepository.APPOPT_PID}' 2>/dev/null", 3)
        PerformanceTunerRepository.setAppOptEnabled(context, false)
        LogRepository.append(context, "[$TAG] AppOpt stopped")
        return true
    }

    fun restartAppOpt(context: Context): Boolean {
        stopAppOpt(context)
        return startAppOpt(context)
    }

    fun applyAppOptRules(context: Context): Boolean {
        val ok = writeAppOptRules(PerformanceTunerRepository.getAppOptRuleText(context))
        if (ok) LogRepository.append(context, "[$TAG] appopt rules updated")
        return ok
    }

    fun appOptRunning(): Boolean {
        if (!hasRoot()) return false
        return suSession.execute("pgrep -f 'AppOpt -c' >/dev/null 2>&1 && echo AP_RUN || echo AP_DEAD", 3)
            .output.contains("AP_RUN")
    }

    // ============ SCENE ============

    /** 部署 SCENE 独立/兼容模式配置（写入守护脚本，不启动） */
    fun deployScene(context: Context): Boolean {
        if (!hasRoot()) return false
        if (!isDeployed()) {
            if (!deployAssets(context)) return false
        }
        val mode = PerformanceTunerRepository.getSceneMode(context)
        return when (mode) {
            PerformanceTunerRepository.SCENE_MODE_SCENE_DEP -> writeSceneCompatMarker(context)
            else -> writeSceneDaemon(context)
        }
    }

    private fun writeSceneDaemon(context: Context): Boolean {
        val profile = PerformanceTunerRepository.getSceneProfile(context)
        val params = PerformanceTunerRepository.getSceneCustomParams(context)
        val pairs = params.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
        // 独立 UI 设置的频率/温度墙优先于自定义参数文本
        val fmaxCap = PerformanceTunerRepository.getSceneFmaxCap(context).takeIf { it != PerformanceTunerRepository.DEFAULT_SCENE_FMAX_CAP }
            ?: pairs["fmax_cap"]
            ?: PerformanceTunerRepository.DEFAULT_SCENE_FMAX_CAP
        val thermal = PerformanceTunerRepository.getSceneThermal(context).takeIf { it != PerformanceTunerRepository.DEFAULT_SCENE_THERMAL }
            ?: pairs["thermal_guard"]
            ?: PerformanceTunerRepository.DEFAULT_SCENE_THERMAL
        val header = """
            #!/system/bin/sh
            # HanFeng SCENE runner (generated)
            export SCENE_LOG='${PerformanceTunerRepository.SCENE_LOG}'
            export SCENE_PROFILE='$profile'
            export SCENE_FMAX_CAP='$fmaxCap'
            export SCENE_ADAPTIVE='${pairs["adaptive"] ?: "0"}'
            export SCENE_THERMAL='$thermal'
            export SCENE_DISABLE_MIGT='${pairs["disable_migt"] ?: "1"}'
            export SCENE_HISPD_LOAD='${pairs["hispd_load"] ?: "90"}'
            sh '${PerformanceTunerRepository.SCENE_DIR}/scene_daemon.sh'
        """.trimIndent() + "\n"
        val res = suSession.execute(
            "cat > '${PerformanceTunerRepository.SCENE_DAEMON}' << 'EOF_HF_SC'\n" +
                header + "EOF_HF_SC\nchmod 755 '${PerformanceTunerRepository.SCENE_DAEMON}' && echo SC_WRITTEN", 5
        )
        return res.output.contains("SC_WRITTEN")
    }

    private fun runSceneStandaloneOnce(context: Context): Boolean {
        killOrphanScene()
        val res = suSession.execute(
            "nohup sh '${PerformanceTunerRepository.SCENE_DAEMON}' > '${PerformanceTunerRepository.SCENE_LOG}' 2>&1 &\n" +
                "sleep 2 && pgrep -f 'scene_daemon.sh' >/dev/null 2>&1 && echo SC_STARTED || echo SC_FAIL", 12
        )
        return res.output.contains("SC_STARTED")
    }

    private fun writeSceneCompatMarker(context: Context): Boolean {
        val res = suSession.execute("echo COMPAT > '${PerformanceTunerRepository.SCENE_DIR}/mode.compat' && echo OK", 3)
        return res.output.contains("OK")
    }

    /** 兼容模式：注入配置到 Scene 工具箱应用目录 */
    private fun injectSceneCompat(context: Context): Boolean {
        val vtFiles = "/data/data/com.omarea.vtools/files"
        val sceneInstalled = suSession.execute("pm path com.omarea.vtools>/dev/null 2>&1 && echo VT_OK || echo VT_NO", 5)
            .output.contains("VT_OK")
        if (!sceneInstalled) {
            LogRepository.append(context, "[$TAG] Scene 工具箱未安装，兼容模式不可用")
            return false
        }
        val srcDir = "'${PerformanceTunerRepository.SCENE_DIR}/config8gen3/'"
        val res = suSession.execute(
            "mkdir -p '$vtFiles' && chmod 755 '$vtFiles' 2>/dev/null\n" +
                "cp -f $srcDir* '$vtFiles/' 2>/dev/null\n" +
                "chmod 555 '$vtFiles'/*.json '$vtFiles'/*.conf '$vtFiles/powercfg.sh' 2>/dev/null\n" +
                "echo VT_DEPLOYED", 10
        )
        if (!res.output.contains("VT_DEPLOYED")) {
            LogRepository.append(context, "[$TAG] Scene 兼容部署失败: ${res.output.take(200)}")
            return false
        }
        LogRepository.append(
            context,
            "[$TAG] Scene 兼容模式已注入 (vt_files=$vtFiles)"
        )
        return true
    }

    fun startScene(context: Context): Boolean {
        if (!hasRoot()) return false
        val mode = PerformanceTunerRepository.getSceneMode(context)
        val ok = when (mode) {
            PerformanceTunerRepository.SCENE_MODE_SCENE_DEP -> startSceneCompat(context)
            else -> runSceneStandaloneOnce(context)
        }
        if (ok) {
            PerformanceTunerRepository.setSceneEnabled(context, true)
            LogRepository.append(context, "[$TAG] scene started (mode=$mode)")
        }
        return ok
    }

    /** 常驻场景守护：循环重应用（供独立模式） */
    private fun startSceneGuard(context: Context): Boolean {
        val guard = """
            #!/system/bin/sh
            while true; do
              pgrep -f 'scene_daemon.sh' >/dev/null 2>&1 || nohup sh '${PerformanceTunerRepository.SCENE_DAEMON}' >> '${PerformanceTunerRepository.SCENE_LOG}' 2>&1 &
              sleep 30
            done
        """.trimIndent()
        suSession.execute(
            "cat > '${PerformanceTunerRepository.SCENE_DIR}/guard.sh' << 'EOF_GUARD'\n" +
                guard + "\nEOF_GUARD\nchmod 755 '${PerformanceTunerRepository.SCENE_DIR}/guard.sh' && echo GUARD_OK", 5
        )
        suSession.execute(
            "nohup sh '${PerformanceTunerRepository.SCENE_DIR}/guard.sh' >/dev/null 2>&1 &\n" +
                "sleep 0.5 && echo GUARD_STARTED", 5
        )
        return true
    }

    private fun startSceneCompat(context: Context): Boolean {
        injectSceneCompat(context)
        // Scene 模式由 Scene 应用自身调度，注入后返回注入结果
        return true
    }

    private fun killOrphanScene(): Boolean {
        if (!suSession.isSessionOpen()) return false
        suSession.execute("pkill -f 'scene_daemon.sh' 2>/dev/null; pkill -f 'scene_guard' 2>/dev/null; echo OK", 5)
        return true
    }

    fun stopScene(context: Context): Boolean {
        if (!hasRoot()) return false
        suSession.execute("pkill -f 'scene_daemon.sh' 2>/dev/null; sleep 0.3; pkill -KILL -f 'scene_daemon.sh' 2>/dev/null; echo STOPPED", 6)
        PerformanceTunerRepository.setSceneEnabled(context, false)
        LogRepository.append(context, "[$TAG] scene stopped")
        return true
    }

    fun sceneRunning(): Boolean {
        if (!hasRoot()) return false
        return suSession.execute("pgrep -f 'scene_daemon.sh' >/dev/null 2>&1 && echo SC_RUN || echo SC_DEAD", 3)
            .output.contains("SC_RUN")
    }

    // ============ 状态 ============

    fun status(context: Context): Status {
        val sceneRun = sceneRunning()
        val appRun = appOptRunning()
        return Status(
            sceneRunning = sceneRun,
            sceneMode = PerformanceTunerRepository.getSceneMode(context),
            sceneProfile = PerformanceTunerRepository.getSceneProfile(context),
            appoptRunning = appRun,
            appoptRulesLines = PerformanceTunerRepository.getAppOptRuleText(context)
                .lineSequence().count { it.isNotBlank() && !it.startsWith("#") }
        )
    }

    fun dumpLog(which: String, tail: Int = 200): String {
        if (!hasRoot()) return "Root 不可用"
        val path = if (which == "scene") PerformanceTunerRepository.SCENE_LOG else PerformanceTunerRepository.APPOPT_LOG
        return suSession.execute("tail -n $tail '$path' 2>/dev/null || echo '(无日志)'", 5).output.trim()
    }
}