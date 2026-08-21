package com.HanFeng.model

/**
 * 自动连招触摸操作类型：
 * - TAP：单击（start==end）
 * - LONG_PRESS：按压保持
 * - SWIPE：滑动（start != end）
 */
enum class ComboGestureType {
    TAP, LONG_PRESS, SWIPE
}

/**
 * 连招脚本中的单一操作步骤。
 * startX/startY 为起点；endX/endY 为终点（TAP/LONG_PRESS 时与起点一致）。
 * durationMs 为按压持续时长；startDelayMs 为执行到本步前等待的间隔。
 */
data class ComboStep(
    val type: ComboGestureType,
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Int,
    val startDelayMs: Int = 0
)

/** 回放循环模式：单次 / 指定次数 / 无限直到手动停止 */
enum class ComboPlayMode {
    SINGLE, COUNT, INFINITE
}

/** 自动连招脚本：步骤序列 + 整体变速 + 循环模式 + 元数据 */
data class ComboScript(
    val id: String,
    val name: String,
    val steps: List<ComboStep> = emptyList(),
    val speed: Float = 1f,
    val playMode: ComboPlayMode = ComboPlayMode.SINGLE,
    val loopCount: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 音量键快捷功能动作选择（全局唯一，弱网与连招共用音量键二选一）。
 * - NONE：音量键保持系统原生音量行为
 * - WEAK_NET：音量键切换弱网总开关
 * - COMBO：音量键切换自动连招总开关
 */
enum class VolumeKeyAction {
    NONE, WEAK_NET, COMBO
}