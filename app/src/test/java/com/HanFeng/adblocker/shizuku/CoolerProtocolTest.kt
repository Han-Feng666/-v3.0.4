package com.HanFeng.adblocker.shizuku

import com.HanFeng.data.PeripheralRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoolerProtocolTest {

    private val redMagic = CoolerManager.RedMagicCoolerProtocol()

    // ==================== 红魔帧构造 ====================

    @Test
    fun `redmagic power frame structure and checksum`() {
        val frame = redMagic.encodePower(80)
        assertEquals(4, frame.size)
        assertEquals(0xAA, frame[0].toInt() and 0xFF)
        assertEquals(0x01, frame[1].toInt() and 0xFF)
        assertEquals(80, frame[2].toInt() and 0xFF)
        assertTrue(CoolerManager.RedMagicCoolerProtocol.verifyChecksum(frame))
    }

    @Test
    fun `redmagic power clamped to 0-100`() {
        assertEquals(0, redMagic.encodePower(-5)[2].toInt() and 0xFF)
        assertEquals(100, redMagic.encodePower(150)[2].toInt() and 0xFF)
    }

    @Test
    fun `redmagic rgb frame carries effect color brightness`() {
        val frame = redMagic.encodeRgb(PeripheralRepository.RGB_EFFECT_BREATH, 0x1A2B3C, 60)
        assertEquals(8, frame.size)
        assertEquals(0x02, frame[1].toInt() and 0xFF)
        assertEquals(0x02, frame[2].toInt() and 0xFF)
        assertEquals(0x1A, frame[3].toInt() and 0xFF)
        assertEquals(0x2B, frame[4].toInt() and 0xFF)
        assertEquals(0x3C, frame[5].toInt() and 0xFF)
        assertEquals(60, frame[6].toInt() and 0xFF)
        assertTrue(CoolerManager.RedMagicCoolerProtocol.verifyChecksum(frame))
    }

    @Test
    fun `redmagic rgb effect codes`() {
        val off = redMagic.encodeRgb(PeripheralRepository.RGB_EFFECT_OFF, 0, 0)
        val rainbow = redMagic.encodeRgb(PeripheralRepository.RGB_EFFECT_RAINBOW, 0, 0)
        val static = redMagic.encodeRgb(PeripheralRepository.RGB_EFFECT_STATIC, 0, 0)
        assertEquals(0x00, off[2].toInt() and 0xFF)
        assertEquals(0x03, rainbow[2].toInt() and 0xFF)
        assertEquals(0x01, static[2].toInt() and 0xFF)
    }

    @Test
    fun `verifyChecksum rejects tampered frames`() {
        val frame = redMagic.encodePower(50)
        frame[2] = 99
        assertFalse(CoolerManager.RedMagicCoolerProtocol.verifyChecksum(frame))
        assertFalse(CoolerManager.RedMagicCoolerProtocol.verifyChecksum(byteArrayOf(0x01, 0x02)))
    }

    // ==================== 红魔温度解析 ====================

    @Test
    fun `temperature parsed from notify frame`() {
        // AA 05 90 01 checksum: 0xAA+0x05+0x90+0x01 = 0x140 -> 0x40
        val frame = byteArrayOf(0xAA.toByte(), 0x05, 0x90.toByte(), 0x01, 0x40.toByte())
        assertEquals(40.0, redMagic.parseTemperature(frame)!!, 0.001)
    }

    @Test
    fun `temperature rejects malformed frames and out of range`() {
        assertNull(redMagic.parseTemperature(byteArrayOf(0x55, 0x05, 0x00, 0x00, 0x00)))
        assertNull(redMagic.parseTemperature(ByteArray(0)))
        // 600.0 度超出 -20..120 范围
        val bad = byteArrayOf(0xAA.toByte(), 0x05, 0x70.toByte(), 0x17, 0x00)
        assertNull(redMagic.parseTemperature(bad))
    }

    // ==================== 黑鲨 / 通用协议 ====================

    @Test
    fun `blackshark power scales to byte range`() {
        val shark = CoolerManager.BlackSharkCoolerProtocol()
        val frame = shark.encodePower(100)
        assertEquals(0x51.toByte(), frame[0])
        assertEquals(0xFF.toByte(), frame[1])
        assertEquals(0, shark.encodePower(0)[1].toInt())
    }

    @Test
    fun `blackshark rgb frame layout`() {
        val shark = CoolerManager.BlackSharkCoolerProtocol()
        val frame = shark.encodeRgb(PeripheralRepository.RGB_EFFECT_RAINBOW, 0x112233, 90)
        assertEquals(0x52.toByte(), frame[0])
        assertEquals(0x04.toByte(), frame[1])
        assertEquals(0x11, frame[2].toInt() and 0xFF)
        assertEquals(90, frame[5].toInt() and 0xFF)
    }

    @Test
    fun `generic protocol emits ascii frames`() {
        val generic = CoolerManager.GenericCoolerProtocol()
        val power = String(generic.encodePower(75), Charsets.US_ASCII)
        assertEquals("PWR:75\n", power)
        val rgb = String(
            generic.encodeRgb(PeripheralRepository.RGB_EFFECT_STATIC, 0xFF0000, 40),
            Charsets.US_ASCII
        )
        assertEquals("RGB:static:16711680:40\n", rgb)
    }

    // ==================== 设备名匹配与温度工具 ====================

    @Test
    fun `protocol name matching`() {
        assertTrue(redMagic.matches("RedMagic 冰封散热背夹"))
        assertTrue(redMagic.matches("NUBIA Cooler"))
        assertFalse(redMagic.matches("Mi Band 6"))
        val shark = CoolerManager.BlackSharkCoolerProtocol()
        assertTrue(shark.matches("Black Shark FunCooler 3"))
        assertTrue(shark.matches("黑鲨散热背夹"))
        assertFalse(shark.matches("RedMagic Cooler"))
        assertFalse(CoolerManager.GenericCoolerProtocol().matches("anything"))
    }

    @Test
    fun `parseMilliCelsius converts thermal zone output`() {
        assertEquals(45.123, CoolerManager.parseMilliCelsius("45123")!!, 0.001)
        assertEquals(0.0, CoolerManager.parseMilliCelsius("0")!!, 0.001)
        assertNull(CoolerManager.parseMilliCelsius(""))
        assertNull(CoolerManager.parseMilliCelsius("abc"))
        assertNull(CoolerManager.parseMilliCelsius("999999"))
    }
}
