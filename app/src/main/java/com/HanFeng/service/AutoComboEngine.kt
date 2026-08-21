package com.HanFeng.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.HanFeng.model.ComboGestureType
import com.HanFeng.model.ComboPlayMode
import com.HanFeng.model.ComboScript
import com.HanFeng.model.ComboStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 单步回放计划：包含变速处理后的时长/间隔与目标坐标。
 */
data class PlannedStep(
    val type: ComboGestureType,
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Int,
    val startDelayMs: Int
)

/**
 * 纯逻辑规划器：把脚本展开为回放计划（含整体变速与循环）。
 * SINGLE=1 轮；COUNT=固定轮数；INFINITE 受 [AutoComboEngine] 运行时终止。
 */
object ComboPlaybackPlanner {

    fun plan(script: ComboScript): List<PlannedStep> {
        val rounds = when (script.playMode) {
            ComboPlayMode.SINGLE -> 1
            ComboPlayMode.COUNT -> script.loopCount.coerceAtLeast(1)
            ComboPlayMode.INFINITE -> 1 // 无限由引擎在执行时控制，本轮仅用于单轮预估
        }
        return buildList {
            repeat(rounds) {
                script.steps.forEach { step ->
                    add(scale(step, script.speed))
                }
            }
        }
    }

    /** 变速：时长/间隔均 ÷ 倍速；持续时长最小 1ms，间隔最小 0ms。 */
    fun scale(step: ComboStep, speed: Float): PlannedStep {
        val safe = speed.coerceIn(0.05f, 20f)
        return PlannedStep(
            type = step.type,
            startX = step.startX,
            startY = step.startY,
            endX = step.endX,
            endY = step.endY,
            durationMs = (step.durationMs / safe).toInt().coerceAtLeast(1),
            startDelayMs = (step.startDelayMs / safe).toInt().coerceAtLeast(0)
        )
    }

    /** 单一步骤的 [PlannedStep] 列表（用于增量循环，不展开多轮）。 */
    fun planOneRound(script: ComboScript): List<PlannedStep> =
        script.steps.map { scale(it, script.speed) }
}

/**
 * 自动连招回放引擎：通过无障碍服务 [AccessibilityService.dispatchGesture]
 * 按规划串行注入 tap/longPress/swipe。状态：空闲 -> 播放中 -> 空闲。
 *
 * 终止语义：手动 stop() 返回 STOPPED；脚本全部执行完返回 COMPLETED；
 * dispatchGesture 拒绝/异常返回 ERROR。回放过程中不监听用户手动触摸。
 */
class AutoComboEngine(
    private val service: AccessibilityService
) {
    enum class FinishReason { COMPLETED, STOPPED, ERROR }

    interface Listener {
        fun onStepChanged(loopIndex: Int, stepIndex: Int, totalSteps: Int)
        fun onFinished(reason: FinishReason)
        fun onError(message: String)
    }

    @Volatile var isPlaying: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var listener: Listener? = null

    fun start(script: ComboScript, listener: Listener) {
        stop()
        this.listener = listener
        this.isPlaying = true
        job = scope.launch { run(script) }
    }

    fun stop() {
        job?.cancel()
        job = null
        if (isPlaying) {
            isPlaying = false
            listener?.onFinished(FinishReason.STOPPED)
        }
        listener = null
    }

    private suspend fun run(script: ComboScript) {
        val throws = ComboPlaybackPlanner.planOneRound(script)
        if (throws.isEmpty()) {
            finish(FinishReason.COMPLETED)
            return
        }
        val infinite = script.playMode == ComboPlayMode.INFINITE
        val rounds = if (infinite) {
            Int.MAX_VALUE
        } else {
            when (script.playMode) {
                ComboPlayMode.SINGLE -> 1
                ComboPlayMode.COUNT -> script.loopCount.coerceAtLeast(1)
                else -> 1
            }
        }
        var loopIndex = 0
        runCatching {
            while (loopIndex < rounds) {
                for ((stepIndex, step) in throws.withIndex()) {
                    val l = listener ?: return
                    l.onStepChanged(loopIndex, stepIndex, throws.size)
                    if (step.startDelayMs > 0) delay(step.startDelayMs.toLong())
                    if (!dispatch(step)) {
                        listener?.onError("手势注入失败，请检查无障碍服务是否可用")
                        finish(FinishReason.ERROR)
                        return
                    }
                }
                loopIndex++
            }
        }.onFailure { cause ->
            if (job?.isCancelled == true) {
                // 手动停止路径，stop() 已回调 STOPPED
            } else {
                listener?.onError(cause.message ?: "回放异常")
                finish(FinishReason.ERROR)
            }
        }
        if (isPlaying) finish(FinishReason.COMPLETED)
    }

    private suspend fun dispatch(step: PlannedStep): Boolean =
        suspendCancellableCoroutine { cont ->
            val result = service.dispatchGesture(buildGesture(step), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!result) {
                if (cont.isActive) cont.resume(false)
            }
        }

    private fun buildGesture(step: PlannedStep): GestureDescription {
        val path = Path()
        path.moveTo(step.startX.toFloat(), step.startY.toFloat())
        if (step.type == ComboGestureType.SWIPE && (step.endX != step.startX || step.endY != step.startY)) {
            path.lineTo(step.endX.toFloat(), step.endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0,
            step.durationMs.toLong()
        )
        return GestureDescription.Builder().addStroke(stroke).build()
    }

    private fun finish(reason: FinishReason) {
        isPlaying = false
        listener?.onFinished(reason)
        listener = null
        job = null
    }

    fun release() {
        stop()
        scope.cancel()
    }
}