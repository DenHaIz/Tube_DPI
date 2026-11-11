package com.tube.dpi.data

import android.content.Context
import com.tube.byedpi.Probe
import com.tube.byedpi.ProbeKind
import com.tube.byedpi.ProbeResult
import com.tube.byedpi.Strategies
import com.tube.byedpi.Strategy
import com.tube.byedpi.StrategyScore
import com.tube.byedpi.StrategyTester
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Итоги проверок в одной сети: что работает и какую стратегию там использовать. */
data class NetworkResults(
    val testedAt: Long = 0,
    val systemDns: Boolean? = null,
    val baseline: StrategyScore? = null,
    val scores: Map<String, StrategyScore> = emptyMap(),
    /** Стратегия, выбранная для этой сети (вручную или сторожем). */
    val chosen: String? = null,
    /** Настоящий видеоузел сети (rrN---sn-….googlevideo.com), найденный при подборе. */
    val videoHost: String? = null,
) {
    val ranking: List<StrategyScore> get() = StrategyTester.rank(scores.values.toList())
    val working: List<StrategyScore> get() = ranking.filter { it.works }
}

/** Результаты по сетям в filesDir/results.json — переживают перезапуск. */
class ResultsStore(context: Context) {
    private val file = File(context.filesDir, "results.json")
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, NetworkResults>> = _all.asStateFlow()

    fun of(key: String?): NetworkResults? = key?.let { _all.value[it] }

    @Synchronized
    fun update(key: String, transform: (NetworkResults) -> NetworkResults) {
        val next = _all.value + (key to transform(_all.value[key] ?: NetworkResults()))
        _all.value = next
        runCatching { file.writeText(encode(next).toString()) }
    }

    @Synchronized
    fun clear() {
        _all.value = emptyMap()
        file.delete()
    }

    private fun load(): Map<String, NetworkResults> = runCatching {
        if (!file.exists()) return emptyMap()
        val root = JSONObject(file.readText())
        root.keys().asSequence().associateWith { decodeNetwork(root.getJSONObject(it)) }
    }.getOrDefault(emptyMap())

    companion object {
        /**
         * Версия методики проверки. Результаты старой методики сбрасываются (выбор стратегии остаётся):
         * в 0.2 не было проверки ClientHello «как у Chrome», и часть стратегий помечалась рабочими зря.
         */
        const val PROBE_VERSION = 2

        internal fun encode(all: Map<String, NetworkResults>): JSONObject = JSONObject().apply {
            all.forEach { (key, value) -> put(key, encodeNetwork(value)) }
        }

        private fun encodeNetwork(n: NetworkResults) = JSONObject().apply {
            put("probeVersion", PROBE_VERSION)
            put("testedAt", n.testedAt)
            n.systemDns?.let { put("systemDns", it) }
            n.chosen?.let { put("chosen", it) }
            n.videoHost?.let { put("videoHost", it) }
            n.baseline?.let { put("baseline", encodeScore(it)) }
            put("scores", JSONArray().apply { n.scores.values.forEach { put(encodeScore(it)) } })
        }

        private fun encodeScore(s: StrategyScore) = JSONObject().apply {
            put("id", s.strategy.id)
            put("name", s.strategy.name)
            put("args", s.strategy.args)
            s.startError?.let { put("startError", it) }
            put("results", JSONArray().apply {
                s.results.forEach { r ->
                    put(JSONObject().apply {
                        put("host", r.probe.host)
                        put("path", r.probe.path)
                        put("min", r.probe.minBytes)
                        put("kind", r.probe.kind.name)
                        put("ok", r.ok)
                        put("ms", r.millis)
                        put("bytes", r.bytes)
                        r.error?.let { put("error", it) }
                    })
                }
            })
        }

        internal fun decodeNetwork(o: JSONObject): NetworkResults {
            val scores = o.optJSONArray("scores")?.let { arr -> (0 until arr.length()).map { decodeScore(arr.getJSONObject(it)) } }.orEmpty()
            if (o.optInt("probeVersion") < PROBE_VERSION) {
                return NetworkResults(chosen = o.optString("chosen").ifEmpty { null })
            }
            return NetworkResults(
                testedAt = o.optLong("testedAt"),
                systemDns = if (o.has("systemDns")) o.getBoolean("systemDns") else null,
                baseline = o.optJSONObject("baseline")?.let(::decodeScore),
                scores = scores.associateBy { it.strategy.id },
                chosen = o.optString("chosen").ifEmpty { null },
                videoHost = o.optString("videoHost").ifEmpty { null },
            )
        }

        private fun decodeScore(o: JSONObject): StrategyScore {
            val id = o.getString("id")
            // Встроенная стратегия берётся из текущей версии приложения (описания могли обновиться).
            val strategy = Strategies.byId(id) ?: Strategy(id, o.optString("name", id), o.optString("args"), "", "")
            val results = o.optJSONArray("results")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val r = arr.getJSONObject(i)
                    ProbeResult(
                        Probe(
                            r.getString("host"), r.optString("path", "/"), r.optInt("min"),
                            kind = runCatching { ProbeKind.valueOf(r.optString("kind")) }.getOrDefault(ProbeKind.Https),
                        ),
                        r.getBoolean("ok"), r.optLong("ms"), r.optInt("bytes"), r.optString("error").ifEmpty { null },
                    )
                }
            }.orEmpty()
            return StrategyScore(strategy, results, o.optString("startError").ifEmpty { null })
        }
    }
}
