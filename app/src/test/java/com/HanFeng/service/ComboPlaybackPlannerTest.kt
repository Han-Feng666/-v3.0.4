package com.HanFeng.service

import com.HanFeng.model.ComboGestureType
import com.HanFeng.model.ComboPlayMode
import com.HanFeng.model.ComboScript
import com.HanFeng.model.ComboStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ComboPlaybackPlanner 单元测试。
 * 覆盖：循环模式展开、变速缩放（时长/间隔 ÷倍速）、最小值钳制、多模式计划。
 */
class ComboPlaybackPlannerTest {

    private val taps = listOf(
        ComboStep(
            type = ComboGestureType.TAP, startX = 100, startY = 200,
            endX = 100, endY = 200, durationMs = 100, startDelayMs = 50
        ),
        ComboStep(
            type = ComboGestureType.SWIPE, startX = 0, startY = 0,
            endX = 300, endY = 400, durationMs = 300, startDelayMs = 100
        )
    )

    private fun script(
        mode: ComboPlayMode = ComboPlayMode.SINGLE,
        loopCount: Int = 1,
        speed: Float = 1f,
        steps: List<ComboStep> = taps
    ) = ComboScript(id = "s1", name = "测试", steps = steps, speed = speed, playMode = mode, loopCount = loopCount)

    @Test
    fun `single mode plans one round with all steps`() {
        val plan = ComboPlaybackPlanner.plan(script())
        assertEquals(2, plan.size)
        assertEquals(ComboGestureType.TAP, plan[0].type)
        assertEquals(ComboGestureType.SWIPE, plan[1].type)
    }

    @Test
    fun `count mode multiplies steps by loop count`() {
        val plan = ComboPlaybackPlanner.plan(script(mode = ComboPlayMode.COUNT, loopCount = 3))
        assertEquals(6, plan.size)
    }

    @Test
    fun `infinite mode estimates a single round in plan`() {
        val plan = ComboPlaybackPlanner.plan(script(mode = ComboPlayMode.INFINITE))
        assertEquals(2, plan.size)
    }

    @Test
    fun `loop count below minimum is coerced to one`() {
        val plan = ComboPlaybackPlanner.plan(script(mode = ComboPlayMode.COUNT, loopCount = 0))
        assertEquals(2, plan.size)
    }

    @Test
    fun `speed 2x halves duration and delay`() {
        val plan = ComboPlaybackPlanner.plan(script(speed = 2f))
        assertEquals(50, plan[0].durationMs)
        assertEquals(25, plan[0].startDelayMs)
        assertEquals(150, plan[1].durationMs)
    }

    @Test
    fun `duration never scales below 1ms`() {
        val step = ComboStep(
            type = ComboGestureType.TAP, startX = 1, startY = 1,
            endX = 1, endY = 1, durationMs = 1, startDelayMs = 0
        )
        val scaled = ComboPlaybackPlanner.scale(step, speed = 4f)
        assertEquals(1, scaled.durationMs)
    }

    @Test
    fun `delay scales down to zero when small enough`() {
        val step = ComboStep(
            type = ComboGestureType.TAP, startX = 1, startY = 1,
            endX = 1, endY = 1, durationMs = 10, startDelayMs = 1
        )
        val scaled = ComboPlaybackPlanner.scale(step, speed = 8f)
        assertEquals(0, scaled.startDelayMs)
    }

    @Test
    fun `coordinates and type pass through`() {
        val plan = ComboPlaybackPlanner.plan(script())
        assertTrue(plan[1].startX == 0 && plan[1].startY == 0)
        assertTrue(plan[1].endX == 300 && plan[1].endY == 400)
    }

    @Test
    fun `empty script yields empty plan`() {
        val plan = ComboPlaybackPlanner.plan(script(steps = emptyList()))
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `plan one round ignores loop multiplier`() {
        val oneRound = ComboPlaybackPlanner.planOneRound(script(mode = ComboPlayMode.COUNT, loopCount = 5))
        assertEquals(2, oneRound.size)
    }
}