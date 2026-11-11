package com.tube.byedpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyArgsTest {

    @Test
    fun builtInStrategiesAreValid() {
        Strategies.all.forEach { strategy ->
            val result = StrategyArgs.parse(strategy.args)
            assertTrue("${strategy.id}: $result", result is StrategyArgs.Result.Ok)
        }
    }

    @Test
    fun strategyIdsAreUnique() {
        assertEquals(Strategies.all.size, Strategies.all.map { it.id }.toSet().size)
        assertTrue(Strategies.extended.size >= 50)
    }

    @Test
    fun tokenizeKeepsQuotedValues() {
        assertEquals(listOf("-l", ":GET / HTTP/1.1", "-d1"), StrategyArgs.tokenize("-l \":GET / HTTP/1.1\"  -d1"))
        assertEquals(listOf("-n", ""), StrategyArgs.tokenize("-n ''"))
    }

    @Test
    fun longOptionsWithAndWithoutEquals() {
        assertTrue(StrategyArgs.parse("--disorder 1 --auto=torst --tlsrec 1+s") is StrategyArgs.Result.Ok)
    }

    @Test
    fun rejectsReservedAndUnknownOptions() {
        assertEquals(StrategyArgs.Result.Error("-p"), StrategyArgs.parse("-d1 -p 2000"))
        assertEquals(StrategyArgs.Result.Error("--port"), StrategyArgs.parse("--port=1"))
        assertEquals(StrategyArgs.Result.Error("-G"), StrategyArgs.parse("-G"))
        assertEquals(StrategyArgs.Result.Error("@fake/../datastore/x"), StrategyArgs.parse("-f-1 -l @fake/../datastore/x"))
        assertEquals(StrategyArgs.Result.Error("-s"), StrategyArgs.parse("-d1 -s"))
        assertEquals(StrategyArgs.Result.Error("junk"), StrategyArgs.parse("junk"))
        assertTrue(StrategyArgs.parse("  ") is StrategyArgs.Result.Error)
    }

    @Test
    fun combinedFlags() {
        assertTrue(StrategyArgs.parse("-SY -d1") is StrategyArgs.Result.Ok)
        assertTrue(StrategyArgs.parse("-Sd") is StrategyArgs.Result.Error)
    }

    @Test
    fun commandLinePutsListenAddressFirst() {
        assertEquals(
            listOf("-i", "127.0.0.1", "-p", "1080", "-U", "-d1"),
            StrategyArgs.commandLine(1080, listOf("-d1"), blockUdp = true),
        )
    }

    @Test
    fun followsRedirects() {
        val response = listOf("HTTP/1.1 302 Found", "Content-Type: text/html", "Location: https://m.youtube.com/?app=m", "", "body")
            .joinToString(CRLF).toByteArray()
        assertEquals("https://m.youtube.com/?app=m", StrategyTester.redirectTarget(response))
        assertEquals("m.youtube.com" to "/?app=m", StrategyTester.resolveLocation("https://m.youtube.com/?app=m", "www.youtube.com"))
        assertEquals("www.youtube.com" to "/", StrategyTester.resolveLocation("https://www.youtube.com", "x"))
        assertEquals("i.ytimg.com" to "/vi/x", StrategyTester.resolveLocation("/vi/x", "i.ytimg.com"))
        assertEquals(null, StrategyTester.resolveLocation("http://plain.example/", "x"))
        assertEquals(null, StrategyTester.redirectTarget(listOf("HTTP/1.1 200 OK", "", "").joinToString(CRLF).toByteArray()))
    }

    private companion object {
        const val CRLF = "\r\n"
    }

    @Test
    fun rankingPrefersPassedThenSpeed() {
        val probe = Probe("a", "/", 0)
        fun score(id: String, vararg results: Pair<Boolean, Long>) = StrategyScore(
            Strategy(id, id, "-d1", "", ""),
            results.map { ProbeResult(probe, it.first, it.second, 0, null) },
        )
        val ranked = StrategyTester.rank(
            listOf(score("slow", true to 900L, true to 900L), score("half", true to 10L, false to 10L), score("fast", true to 100L, true to 100L)),
        )
        assertEquals(listOf("fast", "slow", "half"), ranked.map { it.strategy.id })
    }
}
