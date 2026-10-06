package com.HanFeng.adblocker.shizuku

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SuSession {

    companion object {
        private const val TAG = "SuSession"
        private const val FIRST_CALL_TIMEOUT_SEC = 60L
        private const val NORMAL_TIMEOUT_SEC = 15L
        private const val MAX_COMMAND_LOG = 200

        @Volatile
        private var instance: SuSession? = null

        fun getInstance(): SuSession {
            return instance ?: synchronized(this) {
                instance ?: SuSession().also { instance = it }
            }
        }
    }

    data class ShellResult(val exitCode: Int, val output: String)

    data class CommandLogEntry(
        val timestamp: Long,
        val command: String,
        val exitCode: Int,
        val truncated: Boolean,
        val durationMs: Long
    )

    enum class RootSolution {
        MAGISK, KERNELSU, APATCH, UNKNOWN_ROOT, NOT_ROOTED
    }

    private val permissionGranted = AtomicBoolean(false)
    private val permissionDenied = AtomicBoolean(false)
    private val commandLog = CopyOnWriteArrayList<CommandLogEntry>()
    private val totalCommands = AtomicLong(0)
    private val totalFailures = AtomicLong(0)

    // ==================== 持久化 root shell ====================
    // 旧实现每条命令 spawn 一个 `su -c`，Root 管理器"仅一次性"授权时每条命令都重新弹授权框，
    // 复合脚本还会重复经历 su 启动延迟，设备慢时默认 15s 超时被 destroyForcibly 半途截断。
    // 重构为一个常驻 `su` 交互 shell（stdin 喂命令 + 标记协议回读），整个会话只弹一次授权。
    private var shellProcess: Process? = null
    private var shellStdin: BufferedWriter? = null
    private val shellOutputQueue = LinkedBlockingQueue<String>()
    private val shellReaderAlive = AtomicBoolean(false)
    private val shellLock = ReentrantLock(true)
    private val shellOpCounter = AtomicLong(0)

    @Volatile
    private var shellBroken = false

    @Volatile
    private var lastShellStartError: String = ""

    @Volatile
    var rootSolution: RootSolution = RootSolution.NOT_ROOTED
        private set

    @Volatile
    var rootVersion: String = ""
        private set

    @Volatile
    private var lastOpenDiagnostic: String = ""

    fun getLastOpenDiagnostic(): String = lastOpenDiagnostic

    /** 检测 su 可执行文件是否存在于常见挂载路径(不要求已授权) */
    fun findSuBinary(): String? {
        val candidates = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/vendor/bin/su", "/system/kernel/su"
        )
        for (p in candidates) {
            if (File(p).exists()) return p
        }
        return null
    }

    private fun buildOpenDiagnostic(raw: String): String {
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            lower.contains("no such file") || lower.contains("not found") ->
                "未找到 su 命令（设备未获得 Root 或 Root 方案未生效）"
            lower.contains("permission denied") ->
                "检测到 su 但执行被拒绝（请在 Root 管理器同意授权后重试）"
            raw.contains("su_permission_denied") || raw.contains("timed out") ->
                "Root 授权被拒绝或无响应"
            raw.isBlank() -> "Root 会话无输出（请确认已授权 Root）"
            else -> "Root 不可用：${raw.take(120)}"
        }
    }

    fun open(timeoutSeconds: Long = FIRST_CALL_TIMEOUT_SEC): Boolean {
        if (permissionGranted.get() && isShellAlive()) return true
        // 不再因 permissionDenied 永久拒绝后续重试: 让用户每次操作都有机会重新授权
        permissionDenied.set(false)
        lastOpenDiagnostic = ""
        closeShell()

        Log.d(TAG, "Requesting root permission (timeout=${timeoutSeconds}s)...")
        val result = runThroughShellWithProbe("echo SU_READY && id", timeoutSeconds)

        return if (result.output.contains("SU_READY") &&
            (result.output.contains("uid=0") || result.output.contains("uid=0(root)"))
        ) {
            permissionGranted.set(true)
            permissionDenied.set(false)
            lastOpenDiagnostic = ""
            Log.d(TAG, "Root permission granted")
            detectRootSolution()
            // 新会话可能来自不同的 root 方案, 使 resetprop 缓存失效
            com.HanFeng.adblocker.shizuku.DeviceIdModifier.invalidateResetpropCache()
            true
        } else {
            closeShell()
            permissionDenied.set(true)
            lastOpenDiagnostic = buildOpenDiagnostic(result.output)
            if (lastOpenDiagnostic.startsWith("未找到 su")) {
                rootSolution = RootSolution.NOT_ROOTED
                rootVersion = ""
            }
            Log.e(TAG, "Root permission denied/timed out. Output: [${result.output.take(200)}]")
            false
        }
    }

    private fun isShellAlive(): Boolean {
        val p = shellProcess ?: return false
        return shellReaderAlive.get() && !shellBroken && p.isAlive
    }

    /**
     * 在常驻 root shell 中执行命令。shell 未就绪时先拉起（此路径仅用于 open 探测）。
     * 协议: echo START标记 → 命令本体 → echo "END标记 $?"，按标记回读输出与退出码。
     */
    private fun runThroughShellWithProbe(command: String, timeoutSeconds: Long): ShellResult {
        return try {
            shellLock.withLock {
                if (!ensureShellProcessLocked()) {
                    return ShellResult(-1, lastShellStartError.ifBlank { "su_unavailable" })
                }
                runCommandViaShellLocked(command, timeoutSeconds)
            }
        } catch (e: Exception) {
            Log.e(TAG, "persistent shell execute failed: ${e.message}")
            closeShell()
            ShellResult(-1, e.message ?: "exception")
        }
    }

    /** 持有 shellLock 的前提下确保 su 进程已拉起（含 reader 线程） */
    private fun ensureShellProcessLocked(): Boolean {
        if (isShellAlive()) return true
        closeShell()
        return try {
            val p = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()
            shellProcess = p
            shellStdin = p.outputStream.bufferedWriter()
            shellOutputQueue.clear()
            shellBroken = false
            shellReaderAlive.set(true)
            Thread {
                try {
                    p.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            line?.let { shellOutputQueue.put(it) }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    shellReaderAlive.set(false)
                }
            }.apply { isDaemon = true }.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "open su shell failed: ${e.message}")
            lastShellStartError = e.message ?: "exception"
            closeShell()
            false
        }
    }

    /** 持有 shellLock 的前提下执行单条命令并等待 END 标记 */
    private fun runCommandViaShellLocked(command: String, timeoutSeconds: Long): ShellResult {
        val stdinWriter = shellStdin ?: return ShellResult(-1, "su_unavailable")
        val op = shellOpCounter.incrementAndGet()
        val nonce = "${op}_${System.nanoTime()}"
        val startMarker = "__HF_CS_${nonce}__"
        val endMarker = "__HF_CE_${nonce}__"

        try {
            // 丢弃上一条命令残留的输出行
            shellOutputQueue.clear()
            stdinWriter.write("echo $startMarker")
            stdinWriter.newLine()
            stdinWriter.write(command)
            stdinWriter.newLine()
            stdinWriter.write("echo \"$endMarker \$?\"")
            stdinWriter.newLine()
            stdinWriter.flush()
        } catch (e: Exception) {
            shellBroken = true
            return ShellResult(-1, "su_broken: ${e.message}")
        }

        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        val collected = StringBuilder()
        var sawStart = false
        var exitCode = -1

        while (System.currentTimeMillis() < deadline) {
            if (!isShellAlive() && shellOutputQueue.isEmpty()) break
            val line = try {
                shellOutputQueue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                break
            } ?: continue

            if (!sawStart) {
                if (line.contains(startMarker)) sawStart = true
                continue
            }
            if (line.startsWith(endMarker)) {
                exitCode = line.removePrefix(endMarker).trim().toIntOrNull() ?: -1
                return ShellResult(exitCode, collected.toString())
            }
            if (collected.length < 4 * 1024 * 1024) {
                if (collected.isNotEmpty()) collected.append("\n")
                collected.append(line)
            }
        }

        // 超时或 shell EOF：当前 shell 可能被坏命令卡死，整体作废重建（下次 execute 自动重开）
        Log.w(TAG, "persistent shell command ${if (isShellAlive()) "timed out after ${timeoutSeconds}s" else "lost EOF"}")
        shellBroken = true
        return ShellResult(-1, collected.toString().ifBlank { "su_timed_out" })
    }

    private fun closeShell() {
        shellLock.withLock {
            shellBroken = true
            try { shellStdin?.write("exit") ; shellStdin?.newLine() ; shellStdin?.flush() } catch (_: Exception) {}
            try { shellStdin?.close() } catch (_: Exception) {}
            try { shellProcess?.destroy() } catch (_: Exception) {}
            shellStdin = null
            shellProcess = null
            shellOutputQueue.clear()
            shellReaderAlive.set(false)
        }
    }

    private fun detectRootSolution() {
        try {
            val magiskResult = runRawInternal("magisk -c", 5)
            if (magiskResult.isNotBlank() && !magiskResult.contains("not found")) {
                if (magiskResult.contains("ed") && magiskResult.trim().length <= 40) {
                    rootSolution = RootSolution.MAGISK
                    rootVersion = magiskResult.trim()
                    Log.d(TAG, "Detected Magisk: $rootVersion")
                    return
                }
            }
        } catch (_: Exception) {}

        try {
            val ksuResult = runRawInternal("ksud -v", 5)
            if (ksuResult.isNotBlank() && !ksuResult.contains("not found")) {
                rootSolution = RootSolution.KERNELSU
                rootVersion = ksuResult.trim()
                Log.d(TAG, "Detected KernelSU: $rootVersion")
                return
            }
        } catch (_: Exception) {}

        try {
            if (runRawInternal("test -f /data/adb/ap/bin/apd && echo APD_FOUND", 3).contains("APD_FOUND")) {
                rootSolution = RootSolution.APATCH
                rootVersion = "APatch"
                Log.d(TAG, "Detected APatch")
                return
            }
        } catch (_: Exception) {}

        // 兜底: 通过安装目录确认方案(即使前台命令被沙箱掩盖)
        try {
            if (runRawInternal("test -d /data/adb/magisk && echo MAGISK_DIR", 3).contains("MAGISK_DIR")) {
                rootSolution = RootSolution.MAGISK
                rootVersion = "Magisk(目录检测)"
                return
            }
        } catch (_: Exception) {}
        try {
            if (runRawInternal("test -d /data/adb/ksu && echo KSU_DIR", 3).contains("KSU_DIR")) {
                rootSolution = RootSolution.KERNELSU
                rootVersion = "KernelSU(目录检测)"
                return
            }
        } catch (_: Exception) {}

        rootSolution = RootSolution.UNKNOWN_ROOT
        rootVersion = "Unknown"
    }

    fun execute(command: String, timeoutSeconds: Long = -1): ShellResult {
        val timeout = if (timeoutSeconds > 0) timeoutSeconds
        else if (!permissionGranted.get()) FIRST_CALL_TIMEOUT_SEC
        else NORMAL_TIMEOUT_SEC

        if (!permissionGranted.get() && !open(timeout)) {
            val result = ShellResult(-1, "su_permission_denied")
            logCommand(command, result, timeout)
            return result
        }

        val startTime = System.currentTimeMillis()
        // 会话已授权：优先走常驻 shell（免重复 spawn/授权）；shell 失效自动重建，重建失败再退回单次 spawn
        val result = if (permissionGranted.get()) {
            var r = runThroughShellWithProbe(command, timeout)
            if (r.exitCode == -1 && (r.output.startsWith("su_broken") || r.output == "su_unavailable")) {
                // shell 进程被系统回收等场景：重建一次再试
                closeShell()
                r = runThroughShellWithProbe(command, timeout)
            }
            if (r.output == "su_unavailable" || r.output.startsWith("su_unavailable")) {
                // 设备可能已失去 root：回退单次 spawn（与旧实现一致）
                runRawWithExit(command, timeout)
            } else {
                r
            }
        } else {
            runRawWithExit(command, timeout)
        }
        val duration = System.currentTimeMillis() - startTime
        logCommand(command, result, duration)
        return result
    }

    fun executeBypassDenied(command: String, timeoutSeconds: Long = -1): ShellResult {
        if (isPermissionDenied()) {
            close()
        }
        return execute(command, timeoutSeconds)
    }

    fun copyFile(from: String, to: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("cp -f '$from' '$to' && chmod 644 '$to'", timeoutSeconds)
        return result.exitCode == 0
    }

    fun copyFileWithMode(from: String, to: String, mode: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("cp -f '$from' '$to' && chmod $mode '$to' && chown root:root '$to'", timeoutSeconds)
        return result.exitCode == 0
    }

    fun deleteFile(path: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("rm -f '$path'", timeoutSeconds)
        return result.exitCode == 0
    }

    fun deleteDir(path: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("rm -rf '$path'", timeoutSeconds)
        return result.exitCode == 0
    }

    fun fileExists(path: String, timeoutSeconds: Long = 5): Boolean {
        return execute("test -e '$path' && echo EXISTS || echo NOT_FOUND", timeoutSeconds)
            .output.contains("EXISTS")
    }

    fun mountOverlay(source: String, target: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("mount --bind '$source' '$target'", timeoutSeconds)
        return result.exitCode == 0
    }

    fun unmountOverlay(target: String, timeoutSeconds: Long = 10): Boolean {
        val result = execute("umount '$target' 2>/dev/null || umount -l '$target' 2>/dev/null", timeoutSeconds)
        return result.exitCode == 0
    }

    fun getProp(prop: String): String {
        return execute("getprop $prop", 5).output.trim()
    }

    fun checkPermission(): Boolean {
        if (permissionGranted.get()) return true
        if (permissionDenied.get()) return false
        return open(15)
    }

    fun isSessionOpen(): Boolean = permissionGranted.get()
    fun isPermissionDenied(): Boolean = permissionDenied.get()

    fun waitForSession(timeoutSeconds: Long = 30): Boolean {
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
        while (System.currentTimeMillis() < deadline) {
            if (permissionGranted.get() || permissionDenied.get()) break
            Thread.sleep(300)
        }
        return permissionGranted.get()
    }

    fun close() {
        permissionGranted.set(false)
        permissionDenied.set(false)
        closeShell()
    }

    fun getCommandLog(): List<CommandLogEntry> = commandLog.toList()
    fun getTotalCommands(): Long = totalCommands.get()
    fun getTotalFailures(): Long = totalFailures.get()
    fun clearCommandLog() = commandLog.clear()

    fun resetStats() {
        totalCommands.set(0)
        totalFailures.set(0)
    }

    fun escapeShell(str: String): String {
        return str.replace("'", "'\\''")
    }

    fun listDirectory(dir: String, timeoutSeconds: Long = 10): List<String> {
        val result = execute("ls -1 '$dir' 2>/dev/null", timeoutSeconds)
        return result.output.lines().filter { it.isNotBlank() }
    }

    private fun logCommand(command: String, result: ShellResult, durationMs: Long) {
        totalCommands.incrementAndGet()
        if (result.exitCode != 0) {
            totalFailures.incrementAndGet()
        }
        val entry = CommandLogEntry(
            timestamp = System.currentTimeMillis(),
            command = command.take(200),
            exitCode = result.exitCode,
            truncated = command.length > 200,
            durationMs = durationMs
        )
        commandLog.add(entry)
        while (commandLog.size > MAX_COMMAND_LOG) {
            commandLog.removeAt(0)
        }
    }

    fun executeWithStdin(command: String, stdinLines: List<String>, timeoutSeconds: Long): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val stdin = process.outputStream.bufferedWriter()
            for (line in stdinLines) {
                stdin.write(line)
                stdin.newLine()
            }
            stdin.flush()
            stdin.close()

            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            var completed = false
            val readerThread = Thread {
                try {
                    reader.use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            if (output.length < 4 * 1024 * 1024) {
                                if (output.isNotEmpty()) output.append("\n")
                                output.append(line)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            readerThread.start()

            try {
                completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } finally {
                if (!completed) {
                    process.destroyForcibly()
                }
                readerThread.join(3000)
            }

            if (!completed) ShellResult(-1, output.toString())
            else ShellResult(process.exitValue(), output.toString())
        } catch (e: Exception) {
            Log.e(TAG, "executeWithStdin failed: ${e.message}")
            ShellResult(-1, e.message ?: "exception")
        }
    }

    fun startInteractiveSession(): InteractiveSession {
        return InteractiveSession()
    }

    inner class InteractiveSession {
        private var process: Process? = null
        private var stdin: BufferedWriter? = null
        private val stdoutQueue = LinkedBlockingQueue<String>()
        private val alive = AtomicBoolean(false)
        private val PS1_MARKER = "__SUSH_READY__"
        private val allOutput = StringBuilder()

        fun open(): Boolean {
            return try {
                process = ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start()
                stdin = process!!.outputStream.bufferedWriter()
                alive.set(true)

                Thread {
                    try {
                        process!!.inputStream.bufferedReader().use { reader ->
                            var line: String? = null
                            while (alive.get() && reader.readLine().also { line = it } != null) {
                                val l = line!!
                                synchronized(allOutput) {
                                    if (allOutput.length < 4 * 1024 * 1024) {
                                        if (allOutput.isNotEmpty()) allOutput.append("\n")
                                        allOutput.append(l)
                                    }
                                }
                                stdoutQueue.put(l)
                            }
                        }
                    } catch (_: Exception) {}
                }.start()

                sendLine("export PS1='$PS1_MARKER'")
                sendLine("echo $PS1_MARKER")
                waitForMarker(5000)
                true
            } catch (e: Exception) {
                Log.e(TAG, "InteractiveSession open failed: ${e.message}")
                false
            }
        }

        fun sendLine(line: String) {
            try {
                stdin?.let {
                    it.write(line)
                    it.newLine()
                    it.flush()
                }
            } catch (_: Exception) {}
        }

        fun sendInput(input: String) {
            try {
                stdin?.let {
                    it.write(input)
                    it.flush()
                }
            } catch (_: Exception) {}
        }

        fun readNextLine(timeoutMs: Long): String? {
            return try {
                stdoutQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                null
            }
        }

        fun waitFor(matchText: String, timeoutMs: Long): String? {
            val deadline = System.currentTimeMillis() + timeoutMs
            val captured = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                val line = try {
                    stdoutQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { null }

                if (line == null) {
                    if (!alive.get()) return null
                    continue
                }
                if (captured.isNotEmpty()) captured.append("\n")
                captured.append(line)
                if (line.contains(matchText)) return captured.toString()
            }
            return null
        }

        fun drainUntil(matchText: String, timeoutMs: Long): String? {
            val deadline = System.currentTimeMillis() + timeoutMs
            val captured = StringBuilder()
            while (System.currentTimeMillis() < deadline) {
                val line = try {
                    stdoutQueue.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) { null }

                if (line == null) {
                    if (!alive.get()) return null
                    continue
                }
                if (captured.isNotEmpty()) captured.append("\n")
                captured.append(line)
                if (line == matchText || line.contains(matchText)) return captured.toString()
            }
            return if (captured.isNotEmpty()) captured.toString() else null
        }

        fun waitForMarker(timeoutMs: Long): Boolean {
            return waitFor(PS1_MARKER, timeoutMs) != null
        }

        fun expectAndRespond(expectedText: String, response: String, timeoutMs: Long): Boolean {
            val found = waitFor(expectedText, timeoutMs)
            if (found != null) {
                sendLine(response)
                return true
            }
            return false
        }

        fun getAllOutput(): String {
            synchronized(allOutput) {
                return allOutput.toString()
            }
        }

        fun isAlive(): Boolean = alive.get()

        fun close() {
            alive.set(false)
            try { sendLine("exit") } catch (_: Exception) {}
            try { stdin?.close() } catch (_: Exception) {}
            try { process?.destroy() } catch (_: Exception) {}
            stdoutQueue.clear()
        }
    }

    private fun runRawInternal(command: String, timeoutSeconds: Long): String {
        return runThroughShellWithProbe(command, timeoutSeconds).output
    }

    private fun runRawWithExit(command: String, timeoutSeconds: Long): ShellResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            val output = StringBuilder()
            val reader = process.inputStream.bufferedReader()
            var completed = false
            val readerThread = Thread {
                try {
                    reader.use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            if (output.length < 4 * 1024 * 1024) {
                                if (output.isNotEmpty()) output.append("\n")
                                output.append(line)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            readerThread.start()

            try {
                completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } finally {
                if (!completed) {
                    process.destroyForcibly()
                }
                readerThread.join(3000)
            }

            val result = output.toString()
            if (!completed) {
                Log.w(TAG, "su command timed out after ${timeoutSeconds}s")
                return ShellResult(-1, result)
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Log.w(TAG, "su exit code=$exitCode cmd=${command.take(80)}")
            }
            ShellResult(exitCode, result)
        } catch (e: Exception) {
            Log.e(TAG, "su execute failed: ${e.message}")
            ShellResult(-1, e.message ?: "exception")
        }
    }
}
