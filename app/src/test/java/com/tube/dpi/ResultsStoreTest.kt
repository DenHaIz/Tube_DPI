package com.tube.dpi

import com.tube.byedpi.Probe
import com.tube.byedpi.ProbeResult
import com.tube.byedpi.Strategies
import com.tube.byedpi.StrategyScore
import com.tube.dpi.data.NetworkResults
import com.tube.dpi.data.ResultsStore
import com.tube.dpi.update.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ResultsStoreTest {
    private val probe = Probe("www.youtube.com", "/", 64)

    @Test
    fun jsonRoundTripKeepsScoresAndChoice() {
        val strategy = Strategies.builtIn[2]
        val score = StrategyScore(strategy, listOf(ProbeResult(probe, true, 321, 70000, null), ProbeResult(probe.copy(host = "x"), false, 7000, 12, "SocketTimeoutException")))
        val original = mapOf("wifi:192.168.1.1|8.8.8.8" to NetworkResults(testedAt = 42, systemDns = false, baseline = score, scores = mapOf(strategy.id to score), chosen = strategy.id))
        val json = ResultsStore.encode(original).toString()
        val decoded = ResultsStore.decodeNetwork(org.json.JSONObject(json).getJSONObject("wifi:192.168.1.1|8.8.8.8"))
        assertEquals(original.values.first().copy(), decoded)
    }

    @Test
    fun rankingAndWorkingList() {
        val a = StrategyScore(Strategies.builtIn[0], listOf(ProbeResult(probe, true, 500, 0, null)))
        val b = StrategyScore(Strategies.builtIn[1], listOf(ProbeResult(probe, true, 100, 0, null)))
        val c = StrategyScore(Strategies.builtIn[3], listOf(ProbeResult(probe, false, 100, 0, "x")))
        val results = NetworkResults(scores = listOf(a, b, c).associateBy { it.strategy.id })
        assertEquals(listOf(b, a, c), results.ranking)
        assertEquals(listOf(b, a), results.working)
    }

    @Test
    fun versionComparison() {
        assertTrue(UpdateChecker.isNewer("0.2", "0.1"))
        assertTrue(UpdateChecker.isNewer("0.10", "0.9"))
        assertTrue(UpdateChecker.isNewer("1.0", "0.9.9"))
        assertFalse(UpdateChecker.isNewer("0.1", "0.1"))
        assertFalse(UpdateChecker.isNewer("0.1", "0.2"))
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1"))
    }
}
