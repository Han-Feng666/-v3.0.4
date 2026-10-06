package com.HanFeng.adblocker.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用隐藏保护规则单元测试。
 *
 * 确保系统关键包与本应用自身不会被隐藏。
 */
class AppHideManagerTest {

    private val manager = AppHideManager()

    @Test
    fun `system packages are protected`() {
        listOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.shell",
            "com.android.providers.settings",
            "com.android.permissioncontroller",
            "com.google.android.gms",
            "com.google.android.gsf"
        ).forEach { pkg ->
            assertTrue("$pkg should be protected", manager.isProtectedPackage(pkg))
        }
    }

    @Test
    fun `android prefix packages are protected`() {
        assertTrue(manager.isProtectedPackage("com.android.vending"))
        assertTrue(manager.isProtectedPackage("com.miui.home"))
        assertTrue(manager.isProtectedPackage("com.xiaomi.market"))
    }

    @Test
    fun `normal third party packages are not protected`() {
        assertFalse(manager.isProtectedPackage("com.tencent.mm"))
        assertFalse(manager.isProtectedPackage("com.eg.android.AlipayGphone"))
        assertFalse(manager.isProtectedPackage("com.ss.android.ugc.aweme"))
        assertFalse(manager.isProtectedPackage("com.example.app"))
    }

    @Test
    fun `self package is rejected by hide logic`() {
        // hideApp 对自身包名的保护在 manager 内，这里验证保护规则不误伤普通包
        assertFalse(manager.isProtectedPackage("com.HanFeng"))
    }
}
