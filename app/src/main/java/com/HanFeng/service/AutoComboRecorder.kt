package com.HanFeng.service

import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import com.HanFeng.model.ComboGestureType
import com.HanFeng.model.ComboStep
import kotlin.math.abs

/**
 * 自动连招录制器（方案 B：浮层捕获 + 尽力即时注入）。
 *
 * 录制时在屏幕上方加一层全屏透明图层接收用户触摸，把每次 DOWN→UP 聚合成一个
 * [ComboStep]（TAP / LONG_PRESS / SWIPE），并按时间戳计算距上一步的等待间隔。
 *
 * 边录边响应：拿到坐标后尽力用 [AutoComboAccessibilityService.injectForRecording]
 * 把同一手势注入给系统；受 overlay 窗口命中限制，注入手势可能被自身图层拦截，
 * 因此该注入为最佳努力，不阻塞步骤记录——回放阶段不受影响。
 */
class AutoComboRecorder(
    private val windowManager: WindowManager
) {
    interface Callback {
        fun onStepChanged(stepCount: Int)
    }

    var callback: Callback? = null
        set(value) {
            field = value
            callback?.onStepChanged(steps.size)
        }

    var isRecording: Boolean = false
        private set

    val stepCount: Int
        get() = steps.size

    private val steps = mutableListOf<ComboStep>()
    private var lastUpTime = 0L
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var layerView: View? = null

    private val touchSlopPx = 24f

    fun start(accessibilityService: AutoComboAccessibilityService?) {
        if (isRecording) return
        steps.clear()
        lastUpTime = SystemClockCompat.now()
        isRecording = true
        addLayer(accessibilityService)
        callback?.onStepChanged(0)
    }

    /** 停止录制并返回记录到的步骤（调用方负责保存脚本）。 */
    fun stop(): List<ComboStep> {
        if (!isRecording) return steps.toList()
        isRecording = false
        removeLayer()
        return steps.toList()
    }

    private fun addLayer(accessibilityService: AutoComboAccessibilityService?) {
        val layer = View(null)
        layer.setBackgroundColor(0x22000000)
        val params = LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT,
            getLayoutType(),
            LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        layer.setOnTouchListener { _, event ->
            handleTouch(event, accessibilityService)
        }
        windowManager.addView(layer, params)
        layerView = layer
    }

    private fun removeLayer() {
        layerView?.let { runCatching { windowManager.removeView(it) } }
        layerView = null
    }

    private fun handleTouch(event: MotionEvent, accessibilityService: AutoComboAccessibilityService?): Boolean {
        val now = SystemClockCompat.now()
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                onDown(event, now)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                lastMoveX = event.rawX
                lastMoveY = event.rawY
                true
            }
            MotionEvent.ACTION_UP -> {
                onUp(event, now, accessibilityService)
                true
            }
            else -> false
        }
    }

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var stepStartDelay = 0L

    private fun onDown(event: MotionEvent, now: Long) {
        downX = event.rawX
        downY = event.rawY
        lastMoveX = event.rawX
        lastMoveY = event.rawY
        downTime = now
        stepStartDelay = (now - lastUpTime).coerceAtLeast(0)
    }

    private fun onUp(event: MotionEvent, now: Long, accessibilityService: AutoComboAccessibilityService?) {
        val endX = event.rawX
        val endY = event.rawY
        val duration = (now - downTime).coerceAtLeast(1).toInt()
        val moved = abs(endX - downX) > touchSlopPx || abs(endY - downY) > touchSlopPx

        val type = when {
            moved -> ComboGestureType.SWIPE
            duration >= LONG_PRESS_THRESHOLD_MS -> ComboGestureType.LONG_PRESS
            else -> ComboGestureType.TAP
        }
        val step = ComboStep(
            type = type,
            startX = downX.toInt(),
            startY = downY.toInt(),
            endX = if (moved) endX.toInt() else downX.toInt(),
            endY = if (moved) endY.toInt() else downY.toInt(),
            durationMs = duration,
            startDelayMs = stepStartDelay.toInt()
        )
        steps.add(step)
        lastUpTime = now

        // 尽力而为的即时注入（不阻塞记录）
        if (accessibilityService != null) {
            val planned = ComboPlaybackPlanner.scale(step, 1f)
            accessibilityService.injectForRecording(planned)
        }
        callback?.onStepChanged(steps.size)
    }

    private fun getLayoutType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            LayoutParams.TYPE_PHONE
        }
    }

    private object SystemClockCompat {
        fun now(): Long = android.os.SystemClock.uptimeMillis()
    }

    companion object {
        private const val LONG_PRESS_THRESHOLD_MS = 500L
    }
}