package com.HanFeng.adblocker.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智能推荐引擎单元测试（纯逻辑，不依赖 Android Context）。
 *
 * 覆盖：温度过高、电池温度过高、内存占用过高、后台应用过多、
 * 低电量高负载、平板优化、未优化、充电高温等场景。
 */
class SmartRecommenderTest {

    private val recommender = SmartRecommender()

    private fun snapshot(
        temperature: Int = 35,
        batteryTemp: Int = 300,
        memoryUsedPercent: Int = 50,
        backgroundAppCount: Int = 5,
        batteryLevel: Int = 80,
        isCharging: Boolean = false,
        cpuUsagePercent: Int = 30,
        topApps: List<String> = emptyList()
    ) = SmartRecommender.DeviceSnapshot(
        temperature = temperature,
        batteryTemp = batteryTemp,
        memoryUsedPercent = memoryUsedPercent,
        backgroundAppCount = backgroundAppCount,
        batteryLevel = batteryLevel,
        isCharging = isCharging,
        cpuUsagePercent = cpuUsagePercent,
        topApps = topApps
    )

    private fun recommend(
        snap: SmartRecommender.DeviceSnapshot,
        deviceType: String = "手机",
        sceneEnabled: Boolean = true,
        appOptEnabled: Boolean = true
    ) = recommender.generateRecommendations(
        snapshot = snap,
        deviceType = deviceType,
        socName = "骁龙 8 Gen 2",
        sceneEnabled = sceneEnabled,
        appOptEnabled = appOptEnabled
    )

    @Test
    fun `high temperature generates recommendation`() {
        val report = recommend(snapshot(temperature = 48))
        assertTrue(report.recommendations.any { it.id == "thermal_high" })
    }

    @Test
    fun `normal temperature generates no thermal recommendation`() {
        val report = recommend(snapshot(temperature = 35))
        assertFalse(report.recommendations.any { it.id == "thermal_high" })
    }

    @Test
    fun `high battery temperature generates recommendation`() {
        val report = recommend(snapshot(batteryTemp = 380))
        assertTrue(report.recommendations.any { it.id == "battery_hot" })
    }

    @Test
    fun `high memory usage generates recommendation`() {
        val report = recommend(snapshot(memoryUsedPercent = 90))
        assertTrue(report.recommendations.any { it.id == "memory_high" })
    }

    @Test
    fun `many background apps generates recommendation`() {
        val report = recommend(snapshot(backgroundAppCount = 20))
        assertTrue(report.recommendations.any { it.id == "bg_apps" })
    }

    @Test
    fun `low battery with high load generates recommendation`() {
        val report = recommend(snapshot(batteryLevel = 15, cpuUsagePercent = 80))
        assertTrue(report.recommendations.any { it.id == "low_battery_load" })
    }

    @Test
    fun `low battery while charging generates no recommendation`() {
        val report = recommend(snapshot(batteryLevel = 15, cpuUsagePercent = 80, isCharging = true))
        assertFalse(report.recommendations.any { it.id == "low_battery_load" })
    }

    @Test
    fun `charging with high temperature generates recommendation`() {
        val report = recommend(snapshot(isCharging = true, temperature = 42))
        assertTrue(report.recommendations.any { it.id == "charging_hot" })
    }

    @Test
    fun `tablet generates performance recommendation when scene disabled`() {
        val report = recommend(snapshot(), deviceType = "平板", sceneEnabled = false)
        assertTrue(report.recommendations.any { it.id == "tablet_perf" })
    }

    @Test
    fun `tablet with scene enabled generates no tablet recommendation`() {
        val report = recommend(snapshot(), deviceType = "平板", sceneEnabled = true)
        assertFalse(report.recommendations.any { it.id == "tablet_perf" })
    }

    @Test
    fun `unoptimized device generates not_optimized recommendation`() {
        val report = recommend(snapshot(), sceneEnabled = false, appOptEnabled = false)
        assertTrue(report.recommendations.any { it.id == "not_optimized" })
    }

    @Test
    fun `optimized device generates no not_optimized recommendation`() {
        val report = recommend(snapshot(), sceneEnabled = true, appOptEnabled = true)
        assertFalse(report.recommendations.any { it.id == "not_optimized" })
    }

    @Test
    fun `recommendations are sorted by priority`() {
        val report = recommend(
            snapshot(temperature = 50, memoryUsedPercent = 90, backgroundAppCount = 20)
        )
        val priorities = report.recommendations.map { it.priority }
        assertEquals(priorities.sortedDescending(), priorities)
    }

    @Test
    fun `healthy optimized device has no recommendations`() {
        val report = recommend(snapshot(), sceneEnabled = true, appOptEnabled = true)
        assertTrue(report.recommendations.isEmpty())
        assertEquals("设备状态良好，暂无优化建议", report.summary)
    }

    @Test
    fun `summary mentions highest priority when recommendations exist`() {
        val report = recommend(snapshot(temperature = 50, memoryUsedPercent = 90))
        assertTrue(report.recommendations.isNotEmpty())
        assertTrue(report.summary.contains("可优化点"))
    }
}
