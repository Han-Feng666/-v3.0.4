package com.HanFeng.adblocker.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothHeadsetLogicTest {

    // ==================== 电量解析 ====================

    @Test
    fun `battery parsed near device address`() {
        val dumpsys = """
            Bonded devices:
              AA:BB:CC:DD:EE:FF
                name: WH-1000XM5
                battery: 85%
              11:22:33:44:55:66
                name: AirPods Pro
                battery: 42%
        """.trimIndent()
        assertEquals(85, BluetoothHeadsetManager.parseBatteryForDevice(dumpsys, "AA:BB:CC:DD:EE:FF"))
        assertEquals(42, BluetoothHeadsetManager.parseBatteryForDevice(dumpsys, "11:22:33:44:55:66"))
    }

    @Test
    fun `battery null when address missing`() {
        assertNull(BluetoothHeadsetManager.parseBatteryForDevice("no devices", "AA:BB:CC"))
        assertNull(BluetoothHeadsetManager.parseBatteryForDevice("", "AA:BB:CC"))
        assertNull(BluetoothHeadsetManager.parseBatteryForDevice("addr found but no battery", "addr"))
    }

    @Test
    fun `battery rejects out of range values`() {
        val dumpsys = "AA:BB:CC:DD:EE:FF\nbattery: 250%"
        assertNull(BluetoothHeadsetManager.parseBatteryForDevice(dumpsys, "AA:BB:CC:DD:EE:FF"))
    }

    // ==================== 编解码解析 ====================

    @Test
    fun `codec parsed after device address and uppercased`() {
        val dumpsys = """
            A2DP state:
              device: AA:BB:CC:DD:EE:FF
                codec: ldac
                sample rate: 96000
        """.trimIndent()
        assertEquals("LDAC", BluetoothHeadsetManager.parseSelectedCodec(dumpsys, "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `codec falls back to global search when address absent`() {
        val dumpsys = "selected codec: aptx"
        assertEquals("APTX", BluetoothHeadsetManager.parseSelectedCodec(dumpsys, "00:11:22"))
    }

    @Test
    fun `codec null on blank output`() {
        assertNull(BluetoothHeadsetManager.parseSelectedCodec("", "AA:BB"))
    }

    // ==================== 降噪适配匹配 ====================

    @Test
    fun `anc adapter matches sony by prefix`() {
        val adapter = BluetoothHeadsetManager.findAncAdapterForName("WH-1000XM5")
        assertEquals("索尼", adapter?.vendorName)
        assertFalse(adapter?.programmable ?: true)
    }

    @Test
    fun `anc adapter matches case insensitive`() {
        assertEquals("苹果/Beats", BluetoothHeadsetManager.findAncAdapterForName("airpods pro")?.vendorName)
        assertEquals("华为", BluetoothHeadsetManager.findAncAdapterForName("HUAWEI FreeBuds Pro 3")?.vendorName)
    }

    @Test
    fun `anc adapter null for unknown device`() {
        assertNull(BluetoothHeadsetManager.findAncAdapterForName("JBL Tune 500BT"))
        assertNull(BluetoothHeadsetManager.findAncAdapterForName(""))
    }

    @Test
    fun `anc name matcher contains logic`() {
        assertTrue(AncNameMatcher(contains = listOf("BUDS")).matches("Galaxy Buds2 Pro"))
        assertFalse(AncNameMatcher(prefixes = listOf("WH-")).matches("WF-1000XM4"))
        assertTrue(AncNameMatcher(prefixes = listOf("WH-")).matches("wh-1000xm3"))
    }
}
