package com.HanFeng.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * 弱网音量键控制辅助服务。
 * 仅在用户在弱网设置中开启「音量键控制」时使用：捕获全局音量键并切换弱网开关，
 * 从而不影响系统音量调节。需用户在系统辅助功能里为 HanFeng 开启本服务。
 */
class WeakNetVolumeKeyAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    com.HanFeng.data.WeakNetworkController.toggle(this)
                    return true
                }
            }
        }
        return super.onKeyEvent(event)
    }

    companion object {
        private fun serviceName(context: Context): String =
            "${context.packageName}/${WeakNetVolumeKeyAccessibilityService::class.java.name}"

        fun isEnabled(context: Context): Boolean {
            return runCatching {
                val enabled = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                enabled?.split(':')?.contains(serviceName(context)) == true
            }.getOrDefault(false)
        }

        fun openAccessibilitySettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}