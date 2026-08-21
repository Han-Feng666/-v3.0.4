package com.HanFeng.service

import android.content.Context
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.model.WeakNetworkParams
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 弱网整形纯逻辑（无 Android 依赖，可单测）。
 * - shouldDrop：按 lossPercent 概率整包丢弃（UDP 直接丢；TCP 依托重传还原真实弱网）。
 * - delayMillis：latencyMs + 随机抖动(0..jitterMs)，均匀分布模拟 RTT 波动。
 */
internal class WeakTrafficShaper(
    private val params: WeakNetworkParams,
    private val random: Random = Random()
) {
    fun shouldDrop(): Boolean =
        params.lossPercent > 0 && random.nextInt(100) < params.lossPercent

    fun delayMillis(): Int {
        if (params.latencyMs <= 0 && params.jitterMs <= 0) return 0
        return params.latencyMs + if (params.jitterMs > 0) random.nextInt(params.jitterMs + 1) else 0
    }

    fun upRateKbps(): Int = params.upKbps
    fun downRateKbps(): Int = params.downKbps
}

/** 每秒令牌桶：保证啮合限速的背压 sleep 不超过单次阈值，避免极端限速时线程长时间阻塞到失控。 */
internal class WeakRateBucket(private val kbps: Int, private val maxSingleSleepMs: Long = 2000L) {
    private var tokens: Long = 0
    private var lastMs: Long = System.currentTimeMillis()

    /** 每字节率为 kbps*125（bytes/s）。返回实际需要的 sleep 时长；sleep 由调用方分批执行。 */
    @Synchronized
    fun consume(bytes: Int): Long {
        if (kbps <= 0 || bytes <= 0) return 0
        val rateBytesPerSec = kbps * 125L
        val now = System.currentTimeMillis()
        val elapsed = now - lastMs
        if (elapsed > 0) {
            tokens = minOf(tokens + elapsed * rateBytesPerSec / 1000L, rateBytesPerSec)
            lastMs = now
        }
        if (tokens >= bytes) {
            tokens -= bytes
            return 0
        }
        val deficit = bytes - tokens
        tokens = 0
        val waitMs = deficit * 1000L / rateBytesPerSec
        return waitMs.coerceAtMost(maxSingleSleepMs)
    }

    @Synchronized
    fun reset() {
        tokens = 0
        lastMs = System.currentTimeMillis()
    }
}

/**
 * 弱网整形引擎（运行态，线程安全）。插入 TUN 转发管线：
 * - 上行（客户端→服务器）：handlePacket 入口调用 [applyIngress]
 * - 下行（服务器→客户端）：writeTunPacket 写回前调用 [applyEgress]
 * 返回 true 表示该包应被丢弃；false 表示可继续转发（期间可能已发生延迟/限速 sleep）。
 */
object WeakNetworkEngine {

    private val active = AtomicBoolean(false)
    private val paramsRef = AtomicReference(WeakNetworkParams())
    private val shaper = AtomicReference(WeakTrafficShaper(WeakNetworkParams()))
    private val randomRef = AtomicReference(Random())
    private val upBucket = AtomicReference(WeakRateBucket(0))
    private val downBucket = AtomicReference(WeakRateBucket(0))

    fun isActive(): Boolean = active.get()

    fun parameters(): WeakNetworkParams = paramsRef.get()

    /** 从存储读取参数并激活引擎。 */
    fun refresh(context: Context) {
        val params = FeatureSettingsRepository.getWeakNetworkParams(context)
        paramsRef.set(params)
        shaper.set(WeakTrafficShaper(params, randomRef.get()))
        upBucket.set(WeakRateBucket(params.upKbps))
        downBucket.set(WeakRateBucket(params.downKbps))
        active.set(true)
    }

    /** 关闭弱网，引擎立即短路过期配置。 */
    fun deactivate() {
        active.set(false)
        upBucket.get().reset()
        downBucket.get().reset()
    }

    /** 上行（客户端→服务器），在 handlePacket 入口调用；命中丢包或限速上行带宽。 */
    fun applyIngress(byteCount: Int): Boolean {
        if (!active.get()) return false
        val s = shaper.get()
        if (s.shouldDrop()) return true
        applyDelayWithSleep(s.delayMillis())
        throttleSleep(upBucket.get(), byteCount)
        return false
    }

    /** 下行（服务器→客户端），在 writeTunPacket 写回前调用；命中丢包或限速下行带宽。 */
    fun applyEgress(byteCount: Int): Boolean {
        if (!active.get()) return false
        val s = shaper.get()
        if (s.shouldDrop()) return true
        applyDelayWithSleep(s.delayMillis())
        throttleSleep(downBucket.get(), byteCount)
        return false
    }

    private fun applyDelayWithSleep(delayMs: Int) {
        if (delayMs <= 0) return
        runCatching { Thread.sleep(delayMs.toLong()) }
    }

    /** 限速背压：按令牌不足时长分批 sleep，避免单次长睡冻结转发线程数不清。 */
    private fun throttleSleep(bucket: WeakRateBucket, byteCount: Int) {
        var remaining = bucket.consume(byteCount)
        while (remaining > 0) {
            runCatching { Thread.sleep(minOf(remaining, 200L)) }
            remaining -= 200L
        }
    }
}