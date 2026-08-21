package com.HanFeng.service

import android.content.Context
import android.content.Intent
import com.HanFeng.data.AutoComboRepository
import com.HanFeng.data.LogRepository

/**
 * 自动连招总开关控制器（设置页 / 面板 / 音量键共用入口）：
 * - 开启：持久化 enabled → 启动连招悬浮前台服务（显示悬浮球与面板）
 * - 关闭：持久化 disabled → 停止回放并移除悬浮服务（球与面板一起消失）
 */
object AutoComboController {

    fun isEnabled(context: Context): Boolean = AutoComboRepository.isEnabled(context)

    fun toggle(context: Context): Boolean {
        val next = !AutoComboRepository.isEnabled(context)
        applyState(context, next)
        return next
    }

    fun applyState(context: Context, enabled: Boolean) {
        AutoComboRepository.setEnabled(context, enabled)
        if (enabled) {
            startFloatingService(context)
        } else {
            stopAll(context)
        }
        LogRepository.append(context, "AutoCombo enabled changed to $enabled")
    }

    fun startFloatingService(context: Context) {
        if (!hasOverlayPermission(context)) {
            LogRepository.append(context, "AutoCombo start skipped: no overlay permission")
            return
        }
        runCatching {
            context.startForegroundService(Intent(context, AutoComboFloatingService::class.java))
        }.onFailure {
            LogRepository.append(context, "AutoCombo start failed: ${it.message}")
        }
    }

    fun stopAll(context: Context) {
        AutoComboAccessibilityService.current?.stopPlayback()
        runCatching {
            context.stopService(Intent(context, AutoComboFloatingService::class.java))
        }
    }

    fun hasOverlayPermission(context: Context): Boolean = FloatingBallService.hasOverlayPermission(context)
}