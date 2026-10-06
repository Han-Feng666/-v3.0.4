package com.HanFeng.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuFlagsTest {

    @Test
    fun `flag constants match fork server ConfigManager bit definitions`() {
        assertEquals(2, ShizukuFlags.FLAG_ALLOWED)
        assertEquals(4, ShizukuFlags.FLAG_DENIED)
        assertEquals(6, ShizukuFlags.MASK_PERMISSION)
    }

    @Test
    fun `isAllowed true only when ALLOWED bit set`() {
        assertTrue(ShizukuFlags.isAllowed(2))
        assertTrue(ShizukuFlags.isAllowed(6))
        assertFalse(ShizukuFlags.isAllowed(0))
        assertFalse(ShizukuFlags.isAllowed(4))
    }

    @Test
    fun `isDenied true only when DENIED bit set`() {
        assertTrue(ShizukuFlags.isDenied(4))
        assertTrue(ShizukuFlags.isDenied(6))
        assertFalse(ShizukuFlags.isDenied(0))
        assertFalse(ShizukuFlags.isDenied(2))
    }

    @Test
    fun `all bits set -1 means both flags present`() {
        // -1 = 0xFFFFFFFF, 所有位都置位, isAllowed 和 isDenied 都为 true
        assertTrue(ShizukuFlags.isAllowed(-1))
        assertTrue(ShizukuFlags.isDenied(-1))
    }
}
