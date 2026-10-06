package com.HanFeng.adblocker.shizuku

/**
 * 散热器智能温控：按手机温度自动调整散热器功率档位。
 *
 * 规则：温度越高档位越高；升档即时生效，降档带迟滞（温度回落到当前档位
 * 下限减去迟滞值以下才降档），避免温度在阈值附近波动导致频繁跳档。
 * 纯逻辑，可单测。
 */
class CoolAutoTuner {

    data class TuningDecision(
        val changed: Boolean,
        val newLevel: Int?,
        val reason: String
    )

    private var currentLevel: Int? = null

    /** 按温度决定目标档位（纯映射，可测） */
    fun targetLevelFor(celsius: Double): Int = when {
        celsius >= TIER_CRITICAL -> 100
        celsius >= TIER_HIGH -> 80
        celsius >= TIER_WARM -> 60
        else -> 30
    }

    /**
     * 输入新温度，返回调档决策。
     * 升档即时生效；降档需温度回落超过迟滞值，防止阈值附近频繁跳档。
     */
    fun onTemperature(celsius: Double): TuningDecision {
        val target = targetLevelFor(celsius)
        val cur = currentLevel
        if (cur == null) {
            currentLevel = target
            return TuningDecision(true, target, "初始档位 ${target}%")
        }
        if (target > cur) {
            currentLevel = target
            return TuningDecision(true, target, "温度 ${celsius}°C，升到 ${target}%")
        }
        if (target < cur) {
            if (hysteresisReached(celsius, cur)) {
                currentLevel = target
                return TuningDecision(true, target, "温度回落 ${celsius}°C，降到 ${target}%")
            }
            return TuningDecision(false, cur, "温度 ${celsius}°C，维持 ${cur}%（迟滞中）")
        }
        return TuningDecision(false, cur, "温度 ${celsius}°C，维持 ${cur}%")
    }

    /** 迟滞判定：温度低于当前档位下限减去迟滞值 */
    private fun hysteresisReached(celsius: Double, cur: Int): Boolean =
        celsius <= tierLowerBound(cur) - HYSTERESIS_CELSIUS

    private fun tierLowerBound(cur: Int): Double = when (cur) {
        100 -> TIER_CRITICAL
        80 -> TIER_HIGH
        60 -> TIER_WARM
        else -> 0.0
    }

    fun reset() {
        currentLevel = null
    }

    fun currentLevelValue(): Int? = currentLevel

    companion object {
        const val TIER_CRITICAL = 45.0
        const val TIER_HIGH = 40.0
        const val TIER_WARM = 35.0
        const val HYSTERESIS_CELSIUS = 3.0
    }
}
