package com.HanFeng.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.HanFeng.data.FeatureSettingsRepository
import com.HanFeng.model.ComboGestureType
import com.HanFeng.model.ComboScript
import com.HanFeng.model.VolumeKeyAction

/**
 * 自动连招无障碍服务：
 * 1) 作为回放引擎宿主，通过 [accessibilityService.dispatchGesture] 注入手势；
 * 2) 统一分发"音量键快捷功能"（弱网 / 连招 / 原生音量，三选一）。
 */
class AutoComboAccessibilityService : AccessibilityService() {

    private var engine: AutoComboEngine? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        if (engine == null) engine = AutoComboEngine(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        engine?.release()
        engine = null
        if (current === this) current = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        engine?.release()
        engine = null
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_UP) {
            return super.onKeyEvent(event)
        }
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP && event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.onKeyEvent(event)
        }
        return when (FeatureSettingsRepository.getVolumeKeyAction(this)) {
            VolumeKeyAction.WEAK_NET -> {
                com.HanFeng.data.WeakNetworkController.toggle(this)
                true
            }
            VolumeKeyAction.COMBO -> {
                AutoComboController.toggle(this)
                true
            }
            VolumeKeyAction.NONE -> super.onKeyEvent(event)
        }
    }

    // ---------------- 回放宿主 ----------------

    fun startPlayback(script: ComboScript, listener: AutoComboEngine.Listener) {
        engine?.start(script, listener)
    }

    fun stopPlayback() {
        engine?.stop()
    }

    fun isPlaying(): Boolean = engine?.isPlaying == true

    /** 录制即时回放：不进入引擎状态机，直接把单步手势注入给下层应用。 */
    fun injectForRecording(step: PlannedStep): Boolean {
        return runCatching { dispatchGesture(step.toGestureDescription(), null, null) }.getOrDefault(false)
    }

    companion object {
        /** 当前绑定中的无障碍服务实例（onServiceConnected 后可用）。 */
        @Volatile
        var current: AutoComboAccessibilityService? = null
            private set

        fun serviceName(context: Context): String =
            "${context.packageName}/${AutoComboAccessibilityService::class.java.name}"

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

    private fun PlannedStep.toGestureDescription(): GestureDescription {
        val path = Path()
        path.moveTo(startX.toFloat(), startY.toFloat())
        if (type == ComboGestureType.SWIPE && (endX != startX || endY != startY)) {
            path.lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.toLong())
        return GestureDescription.Builder().addStroke(stroke).build()
    }
}