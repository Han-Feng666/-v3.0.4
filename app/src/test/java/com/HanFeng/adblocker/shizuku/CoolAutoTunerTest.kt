package com.HanFeng.adblocker.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoolAutoTunerTest {

    // ==================== 档位映射 ====================

    @Test
    fun `target level mapping tiers`() {
        val tuner = CoolAutoTuner()
        assertEquals(100, tuner.targetLevelFor(50.0))
        assertEquals(100, tuner.targetLevelFor(45.0))
        assertEquals(80, tuner.targetLevelFor(44.9))
        assertEquals(80, tuner.targetLevelFor(40.0))
        assertEquals(60, tuner.targetLevelFor(39.9))
        assertEquals(60, tuner.targetLevelFor(35.0))
        assertEquals(30, tuner.targetLevelFor(34.9))
        assertEquals(30, tuner.targetLevelFor(20.0))
        assertEquals(30, tuner.targetLevelFor(-10.0))
    }

    // ==================== 决策流程 ====================

    @Test
    fun `first temperature sets initial level`() {
        val tuner = CoolAutoTuner()
        val d = tuner.onTemperature(46.0)
        assertTrue(d.changed)
        assertEquals(100, d.newLevel)
        assertEquals(100, tuner.currentLevelValue())
    }

    @Test
    fun `level up immediate on hotter temp`() {
        val tuner = CoolAutoTuner()
        tuner.onTemperature(36.0)
        val d = tuner.onTemperature(44.0)
        assertTrue(d.changed)
        assertEquals(80, d.newLevel)
    }

    @Test
    fun `level down blocked within hysteresis`() {
        val tuner = CoolAutoTuner()
        tuner.onTemperature(46.0)
        // 43 度：低于 45 档线但未达迟滞值 42（45-3）
        val d = tuner.onTemperature(43.0)
        assertFalse(d.changed)
        assertEquals(100, d.newLevel)
        assertTrue(d.reason.contains("迟滞"))
    }

    @Test
    fun `level down allowed past hysteresis`() {
        val tuner = CoolAutoTuner()
        tuner.onTemperature(46.0)
        tuner.onTemperature(43.0)
        // 41 度 ≤ 42 达到迟滞
        val d = tuner.onTemperature(41.0)
        assertTrue(d.changed)
        assertEquals(80, d.newLevel)
    }

    @Test
    fun `tier to tier descent follows hysteresis chain`() {
        val tuner = CoolAutoTuner()
        tuner.onTemperature(50.0)
        // 100 -> 80 需 ≤42
        tuner.onTemperature(43.0)
        assertEquals(100, tuner.currentLevelValue())
        val d80 = tuner.onTemperature(41.0)
        assertEquals(80, d80.newLevel)
        // 80 -> 60 需 ≤37
        val d60blocked = tuner.onTemperature(39.0)
        assertEquals(80, d60blocked.newLevel)
        val d60 = tuner.onTemperature(36.0)
        assertEquals(60, d60.newLevel)
        // 60 -> 30 需 ≤32
        val d30blocked = tuner.onTemperature(33.0)
        assertEquals(60, d30blocked.newLevel)
        val d30 = tuner.onTemperature(31.0)
        assertEquals(30, d30.newLevel)
    }

    @Test
    fun `same temp maintains level`() {
        val tuner = CoolAutoTuner()
        tuner.onTemperature(37.0)
        val d = tuner.onTemperature(37.0)
        assertFalse(d.changed)
        assertEquals(60, d.newLevel)
    }

    @Test
    fun `min tier never gated on descent`() {
        val tuner = CoolAutoTuner()
        tuner.onTemperature(31.0)
        val d = tuner.onTemperature(25.0)
        assertFalse(d.changed)
        assertEquals(30, d.newLevel)
    }

    @Test
    fun `reset clears current level`() {
        val tuner = CoolAutoTuner()
        tuner.onTemperature(50.0)
        tuner.reset()
        assertNull(tuner.currentLevelValue())
        val d = tuner.onTemperature(37.0)
        assertTrue(d.changed)
        assertEquals(60, d.newLevel)
    }
}
