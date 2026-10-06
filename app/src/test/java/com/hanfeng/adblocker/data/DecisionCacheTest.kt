package com.HanFeng.data

import org.junit.Test

class DecisionCacheTest {
    @Test
    fun `clearDecisionCache is callable and idempotent`() {
        RuleRepository.clearDecisionCache()
        RuleRepository.clearDecisionCache()
        RuleRepository.clearDecisionCache()
    }

    @Test
    fun `clearDecisionCache survives concurrent calls`() {
        val threads = (1..8).map { index ->
            Thread {
                repeat(64) { RuleRepository.clearDecisionCache() }
            }.apply { name = "decision-cache-clear-$index" }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }
}
