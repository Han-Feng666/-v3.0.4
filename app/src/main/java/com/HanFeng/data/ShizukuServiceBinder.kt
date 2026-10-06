package com.HanFeng.data

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import rikka.shizuku.Shizuku
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

/**
 * Shizuku UserService 绑定基类：封装绑定重试、binder 存活检查、等待与失效逻辑。
 *
 * 原先 ShizukuAdControlRepository 与 ShizukuConnectionOwnerRepository 各自复制了一份
 * 近乎相同的绑定状态机（约 120 行），改一处要同步两处。子类（object）只需提供
 * ServiceArgs 构造、Stub 转换、就绪判定与日志标签，绑定行为完全复用。
 */
abstract class ShizukuServiceBinder<T : IInterface> {
    protected companion object {
        const val BIND_RETRY_INTERVAL_MILLIS = 1500L
        const val BIND_WAIT_TIMEOUT_MILLIS = 1500L
        const val BIND_WAIT_STEP_MILLIS = 40L
        // 主线程 getService 快速失败超时：远小于 1.5s，避免 ANR
        const val BIND_FAST_FAIL_TIMEOUT_MILLIS = 200L
        const val BIND_STALE_TIMEOUT_MILLIS = 3000L
    }

    @Volatile private var service: T? = null
    @Volatile private var binding = false
    @Volatile private var lastBindAttemptAt = 0L
    @Volatile private var lastBindLogAt = 0L
    @Volatile private var lastContext: Context? = null

    /** 子类提供 UserService 构造（服务类名 + 进程后缀） */
    protected abstract fun createUserServiceArgs(context: Context): Shizuku.UserServiceArgs

    /** 子类提供 IBinder → Stub 转换 */
    protected abstract fun asService(binder: IBinder?): T?

    /** 子类提供日志标签（如 "Shizuku ad control"） */
    protected abstract val serviceLabel: String

    /** 子类提供就绪判定（Shizuku 可用 + 版本要求等） */
    abstract fun isReady(context: Context): Boolean

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = asService(binder)
            binding = false
            logBindEvent(name, binder, "connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            logBindEvent(name, null, "disconnected")
        }
    }

    fun ensureBound(context: Context): Boolean {
        lastContext = context.applicationContext
        if (!isReady(context)) return false
        if (hasLiveService()) return true
        if (!runCatching { Shizuku.pingBinder() || Shizuku.getBinder()?.isBinderAlive == true }.getOrDefault(false)) {
            return false
        }
        if (binding && System.currentTimeMillis() - lastBindAttemptAt > BIND_STALE_TIMEOUT_MILLIS) {
            maybeLog(context, "$serviceLabel binding stale, reset after ${System.currentTimeMillis() - lastBindAttemptAt}ms")
            binding = false
        }
        if (binding) return false
        val now = System.currentTimeMillis()
        if (now - lastBindAttemptAt < BIND_RETRY_INTERVAL_MILLIS) return false
        lastBindAttemptAt = now
        binding = true
        return runCatching {
            Shizuku.bindUserService(createUserServiceArgs(context), serviceConnection)
            true
        }.onFailure {
            binding = false
            LogRepository.append(context, "Bind $serviceLabel service failed: ${it.message ?: it.javaClass.simpleName}")
        }.getOrDefault(false)
    }

    /**
     * 阻塞版绑定等待 — 仅供 IO 协程调用，UI 线程禁止调用。
     * 子类可追加健康检查兜底（如 AdControl 的 ping 复核）
     */
    open fun ensureBoundAndWait(context: Context): Boolean {
        if (hasLiveService()) return true
        ensureBound(context)
        return runBlocking {
            try {
                withTimeout(BIND_WAIT_TIMEOUT_MILLIS) {
                    while (binding) {
                        if (hasLiveService()) return@withTimeout true
                        delay(BIND_WAIT_STEP_MILLIS)
                    }
                    false
                }
            } catch (_: TimeoutCancellationException) {
                false
            }
        }
    }

    protected fun getService(context: Context): T? {
        liveService()?.let { return it }
        ensureBound(context)
        // 主线程调用方等待只允许 200ms，避免 ANR；
        // caller 应当在外层调 ensureBoundAndWait(context)（IO 协程）以充分等待 binder 绑定。
        val isMainThread = android.os.Looper.getMainLooper().thread === Thread.currentThread()
        val timeoutMs = if (isMainThread) BIND_FAST_FAIL_TIMEOUT_MILLIS else BIND_WAIT_TIMEOUT_MILLIS
        val result = runBlocking {
            try {
                withTimeout(timeoutMs) {
                    while (binding) {
                        liveService()?.let { return@withTimeout it }
                        delay(BIND_WAIT_STEP_MILLIS)
                    }
                    null
                }
            } catch (_: TimeoutCancellationException) {
                null
            }
        }
        if (result == null && binding) {
            maybeLog(context, "Wait $serviceLabel service timeout after ${timeoutMs}ms on ${if (isMainThread) "main" else "worker"}")
        }
        return result ?: liveService()
    }

    protected fun hasLiveService(): Boolean = liveService() != null

    protected fun liveService(): T? {
        val current = service ?: return null
        return if (current.asBinder()?.isBinderAlive == true) {
            current
        } else {
            invalidateService()
            null
        }
    }

    fun invalidateService() {
        service = null
        binding = false
    }

    fun isServiceAlive(): Boolean = liveService() != null

    private fun logBindEvent(name: ComponentName?, binder: IBinder?, state: String) {
        val context = lastContext ?: return
        maybeLog(
            context,
            "$serviceLabel service $state component=${name?.flattenToShortString() ?: "unknown"} binderAlive=${binder?.isBinderAlive == true}"
        )
    }

    private fun maybeLog(context: Context, message: String) {
        val now = System.currentTimeMillis()
        if (now - lastBindLogAt < 1500L) return
        lastBindLogAt = now
        LogRepository.append(context, message)
    }
}
