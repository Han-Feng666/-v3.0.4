package com.HanFeng.service

import android.content.Context
import android.content.SharedPreferences
import com.HanFeng.model.WeakNetworkParams
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * WeakNetworkEngine / WeakTrafficShaper / WeakRateBucket 单元测试。
 * 覆盖：丢包概率整形、延迟/抖动范围、限速令牌桶阈值与背压时间、引擎开关短路。
 */
class WeakNetworkEngineTest {

    // ---------- WeakTrafficShaper ----------

    @Test
    fun `loss 0 never drops`() {
        val shaper = WeakTrafficShaper(WeakNetworkParams(lossPercent = 0), Random(1))
        repeat(500) { assertTrue(!shaper.shouldDrop()) }
    }

    @Test
    fun `loss 100 always drops`() {
        val shaper = WeakTrafficShaper(WeakNetworkParams(lossPercent = 100), Random(2))
        repeat(500) { assertTrue(shaper.shouldDrop()) }
    }

    @Test
    fun `loss 30 keeps drop rate within tolerance`() {
        val shaper = WeakTrafficShaper(WeakNetworkParams(lossPercent = 30), Random(42))
        val total = 2000
        val dropped = (1..total).count { shaper.shouldDrop() }
        val rate = dropped.toDouble() / total
        assertTrue("drop rate=$rate", rate in 0.20..0.40)
    }

    @Test
    fun `delay with fixed latency returns exact value`() {
        val shaper = WeakTrafficShaper(WeakNetworkParams(latencyMs = 80), Random(3))
        repeat(100) { assertEquals(80, shaper.delayMillis()) }
    }

    @Test
    fun `delay with jitter stays within latency range`() {
        val shaper = WeakTrafficShaper(WeakNetworkParams(latencyMs = 80, jitterMs = 20), Random(4))
        repeat(200) {
            val d = shaper.delayMillis()
            assertTrue("delay=$d", d in 80..100)
        }
    }

    @Test
    fun `zero params produce no delay`() {
        val shaper = WeakTrafficShaper(WeakNetworkParams(), Random(5))
        repeat(50) { assertEquals(0, shaper.delayMillis()) }
    }

    // ---------- WeakRateBucket ----------

    @Test
    fun `bucket with zero rate never paces`() {
        val bucket = WeakRateBucket(0)
        assertEquals(0L, bucket.consume(65536))
    }

    @Test
    fun `bucket paces a full-second burst`() {
        // 1000 kbps → 125000 bytes/s。突发一整秒流量应带来约 1000ms 背压。
        val bucket = WeakRateBucket(1000)
        val wait = bucket.consume(125_000)
        assertTrue("wait=$wait", wait in 950L..1000L)
    }

    @Test
    fun `bucket caps single wait to threshold`() {
        val bucket = WeakRateBucket(1000)
        // 突发远大于一秒额度，单次 wait 不得超 maxSingleSleepMs。
        val wait = bucket.consume(2_500_000)
        assertTrue("wait=$wait", wait <= 2000L)
    }

    @Test
    fun `bucket refills after elapsed time`() {
        val bucket = WeakRateBucket(1000)
        bucket.consume(125_000)
        Thread.sleep(110)
        // 100ms 应回填 ~12500 tokens，小额包不再需要等待。
        val wait = bucket.consume(10_000)
        assertEquals(0L, wait)
    }

    // ---------- WeakNetworkEngine ----------

    @Test
    fun `engine inactive short-circuits both directions`() {
        WeakNetworkEngine.deactivate()
        assertTrue(!WeakNetworkEngine.isActive())
        assertTrue(!WeakNetworkEngine.applyIngress(512))
        assertTrue(!WeakNetworkEngine.applyEgress(512))
    }

    @Test
    fun `engine refresh with default params forwards packets`() {
        val ctx = mockContext()
        com.HanFeng.data.FeatureSettingsRepository.setWeakNetworkParams(ctx, WeakNetworkParams())
        WeakNetworkEngine.refresh(ctx)
        assertTrue(WeakNetworkEngine.isActive())
        // 默认参数全部为 0：不丢包、不延迟、不限速。
        assertTrue(!WeakNetworkEngine.applyIngress(1500))
        assertTrue(!WeakNetworkEngine.applyEgress(1500))
        assertEquals(WeakNetworkParams(), WeakNetworkEngine.parameters())
    }

    @Test
    fun `engine refresh with 100 loss drops every packet`() {
        val ctx = mockContext()
        com.HanFeng.data.FeatureSettingsRepository.setWeakNetworkParams(
            ctx, WeakNetworkParams(lossPercent = 100)
        )
        WeakNetworkEngine.refresh(ctx)
        repeat(30) {
            assertTrue(WeakNetworkEngine.applyIngress(800))
        }
    }

    private fun mockContext(): Context {
        val store = mutableMapOf<String, Any>()
        val prefs = mock<SharedPreferences>()
        val editor = mock<SharedPreferences.Editor>()
        whenever(editor.putInt(any(), any())).thenAnswer { inv ->
            store[inv.getArgument(0) as String] = inv.getArgument(1)
            editor
        }
        whenever(editor.putLong(any(), any())).thenAnswer { inv ->
            store[inv.getArgument(0) as String] = inv.getArgument(1)
            editor
        }
        whenever(editor.putString(any(), any())).thenAnswer { inv ->
            store[inv.getArgument(0) as String] = inv.getArgument(1)
            editor
        }
        whenever(editor.putBoolean(any(), any())).thenAnswer { inv ->
            store[inv.getArgument(0) as String] = inv.getArgument(1)
            editor
        }
        org.mockito.Mockito.doAnswer { }.`when`(editor).apply()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.getInt(any(), any())).thenAnswer { inv ->
            store[inv.getArgument(0) as String] as? Int ?: inv.getArgument(1) as Int
        }
        whenever(prefs.getString(any(), any())).thenAnswer { inv ->
            store[inv.getArgument(0) as String] as? String ?: inv.getArgument(1) as String?
        }
        val ctx = mock<Context>()
        whenever(ctx.getSharedPreferences(any(), any())).thenReturn(prefs)
        return ctx
    }
}