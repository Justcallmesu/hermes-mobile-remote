package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** The PC's numbers right now (the bridge samples every 10 s). Null fields: the OS doesn't say. */
data class PcStats(
    val cpu: Double = 0.0,
    val mem: Double = 0.0,
    val swap: Double = 0.0,
    val temp: Double? = null,
    val disk: Double? = null,
    val load1: Double? = null,
    val rx: Double = 0.0,
    val tx: Double = 0.0,
    val rd: Double = 0.0,
    val wr: Double = 0.0,
    val memUsedGb: Double = 0.0,
    val memTotalGb: Double = 0.0,
    val swapUsedGb: Double = 0.0,
    val diskUsedGb: Double? = null,
    val diskTotalGb: Double? = null,
    val uptimeS: Long = 0,
    val cores: Int = 0,
)

data class PcProcess(val pid: Int, val name: String, val cpu: Double, val memMb: Long, val user: String = "",
                     val status: String = "", val cmd: String = "")

data class ClaudeLimit(val label: String, val percent: Double, val resetsAt: String?, val active: Boolean)

data class ClaudeUsage(
    val ok: Boolean = false,
    val plan: String? = null,
    val session: Double? = null,
    val weekly: Double? = null,
    val sessionResetsAt: String? = null,
    val weeklyResetsAt: String? = null,
    val limits: List<ClaudeLimit> = emptyList(),
    val checkedAt: Long? = null,
    val error: String? = null,
) {
    val hasNumbers: Boolean get() = session != null || weekly != null
}

data class PcNow(
    val host: String = "",
    val stats: PcStats = PcStats(),
    val corePct: List<Double> = emptyList(),
    val top: List<PcProcess> = emptyList(),
    val claude: ClaudeUsage = ClaudeUsage(),
    val alerts: List<String> = emptyList(),
)

/** A metric over time; [values] lines up with [ts] (epoch seconds), null = no sample there. */
data class PcHistory(val range: String, val ts: List<Long>, val series: Map<String, List<Double?>>) {
    operator fun get(metric: String): List<Double?> = series[metric].orEmpty()
}

fun JSONArray?.doubles(): List<Double?> =
    if (this == null) emptyList() else (0 until length()).map { if (isNull(it)) null else optDouble(it).takeUnless { d -> d.isNaN() } }

fun parseStats(o: JSONObject?): PcStats {
    if (o == null) return PcStats()
    return PcStats(cpu = o.optDouble("cpu", 0.0), mem = o.optDouble("mem", 0.0), swap = o.optDouble("swap", 0.0),
        temp = o.dbl("temp"), disk = o.dbl("disk"), load1 = o.dbl("load1"),
        rx = o.optDouble("rx", 0.0), tx = o.optDouble("tx", 0.0), rd = o.optDouble("rd", 0.0), wr = o.optDouble("wr", 0.0),
        memUsedGb = o.optDouble("mem_used_gb", 0.0), memTotalGb = o.optDouble("mem_total_gb", 0.0),
        swapUsedGb = o.optDouble("swap_used_gb", 0.0), diskUsedGb = o.dbl("disk_used_gb"), diskTotalGb = o.dbl("disk_total_gb"),
        uptimeS = o.optLong("uptime_s"), cores = o.optInt("cores"))
}

fun parseProcess(o: JSONObject) = PcProcess(o.optInt("pid"), o.str("name") ?: "?", o.optDouble("cpu", 0.0), o.optLong("mem_mb"),
    o.str("user").orEmpty(), o.str("status").orEmpty(), o.str("cmd").orEmpty())

fun parseClaude(o: JSONObject?): ClaudeUsage {
    if (o == null) return ClaudeUsage(error = "No answer")
    return ClaudeUsage(ok = o.optBoolean("ok"), plan = o.str("plan")?.takeIf { it.isNotBlank() && it != "null" },
        session = o.dbl("session"), weekly = o.dbl("weekly"),
        sessionResetsAt = o.str("session_resets_at"), weeklyResetsAt = o.str("weekly_resets_at"),
        limits = o.optJSONArray("limits").objects().map {
            ClaudeLimit(it.str("label") ?: "?", it.optDouble("percent", 0.0), it.str("resets_at"), it.optBoolean("active"))
        },
        checkedAt = if (o.has("ts")) o.optLong("ts") else null,
        error = o.str("error"))
}

fun parseNow(o: JSONObject) = PcNow(host = o.str("host").orEmpty(), stats = parseStats(o.optJSONObject("stats")),
    corePct = o.optJSONArray("core_pct").doubles().map { it ?: 0.0 },
    top = o.optJSONArray("top").objects().map(::parseProcess), claude = parseClaude(o.optJSONObject("claude")),
    alerts = o.optJSONArray("alerts").strings())

fun parseHistory(o: JSONObject): PcHistory {
    val ts = o.optJSONArray("ts")?.let { a -> (0 until a.length()).map { a.optLong(it) } }.orEmpty()
    val series = o.keys().asSequence().filter { it != "ts" && o.optJSONArray(it) != null }
        .associateWith { o.optJSONArray(it).doubles() }
    return PcHistory(o.str("range") ?: "1h", ts, series)
}

// ---------------------------------------------------------------- formatting (UI and widgets)

fun fmtRate(bytesPerS: Double): String = when {
    bytesPerS >= 1e9 -> "%.1f GB/s".format(bytesPerS / 1e9)
    bytesPerS >= 1e6 -> "%.1f MB/s".format(bytesPerS / 1e6)
    bytesPerS >= 1e3 -> "%.0f KB/s".format(bytesPerS / 1e3)
    else -> "%.0f B/s".format(bytesPerS)
}

fun fmtUptime(s: Long): String {
    val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
    return when { d > 0 -> "${d}d ${h}h"; h > 0 -> "${h}h ${m}m"; else -> "${m}m" }
}

fun fmtMem(mb: Long): String = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"

/** "resets in 2h 10m" from an ISO-8601 instant; empty when unknown. */
fun fmtResetsIn(iso: String?, now: Instant = Instant.now()): String {
    if (iso.isNullOrBlank()) return ""
    val at = runCatching { Instant.parse(iso) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull() ?: return ""
    val left = Duration.between(now, at)
    if (left.isNegative) return "resets now"
    val d = left.toDays(); val h = left.toHours() % 24; val m = left.toMinutes() % 60
    return "resets in " + when { d > 0 -> "${d}d ${h}h"; h > 0 -> "${h}h ${m}m"; else -> "${m}m" }
}

// ---------------------------------------------------------------- the PC page's state

enum class ProcessSort(val key: String, val label: String) { CPU("cpu", "CPU"), MEM("mem", "Memory"), NAME("name", "Name") }

data class PcState(
    val now: PcNow? = null,
    val history: PcHistory? = null,
    val range: String = "1h",
    val claudeHistory: PcHistory? = null,
    val processes: List<PcProcess> = emptyList(),
    val processTotal: Int = 0,
    val sort: ProcessSort = ProcessSort.CPU,
    val query: String = "",
    val loading: Boolean = false,
    val error: String? = null,
    /** The bridge has no monitor routes (older, or turned off in config.toml). */
    val unsupported: Boolean = false,
    /** Processes with a stop in flight. */
    val stopping: Set<Int> = emptySet(),
)

/**
 * The PC page (stats, processes, Claude usage). Polls only while the page is on screen:
 * [watch] starts a 3 s loop for the live numbers, the caller cancels it when the page leaves.
 */
class PcMonitor(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
) {
    private val _state = MutableStateFlow(PcState())
    val state: StateFlow<PcState> = _state.asStateFlow()
    private var loop: Job? = null

    fun clear() { stop(); _state.value = PcState() }

    private fun fail(t: Throwable) {
        if (t is CancellationException) throw t
        val old = t is BridgeException && t.httpCode == 404
        _state.update { it.copy(loading = false, unsupported = old, error = if (old) null else describe(t)) }
    }

    /** Poll now + processes every [periodMs] until [stop]; history every minute. */
    fun watch(periodMs: Long = 3_000) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var tick = 0
            while (isActive) {
                refreshNow(live = tick == 0)
                if (tick % 20 == 0) loadHistory(_state.value.range)
                tick++
                delay(periodMs)
            }
        }
    }

    fun stop() { loop?.cancel(); loop = null }

    suspend fun refreshNow(live: Boolean = false) {
        val c = client() ?: return
        val s = _state.value
        try {
            val now = c.monitorNow(live)
            val procs = c.processes(s.sort.key, 120, s.query)
            _state.update { it.copy(now = now, processes = procs.second, processTotal = procs.first, loading = false,
                error = null, unsupported = false) }
        } catch (t: Throwable) { fail(t) }
    }

    fun refresh() {
        _state.update { it.copy(loading = true) }
        scope.launch { refreshNow(live = true); loadHistory(_state.value.range); _state.update { it.copy(loading = false) } }
    }

    fun setRange(range: String) {
        _state.update { it.copy(range = range) }
        scope.launch { loadHistory(range) }
    }

    private suspend fun loadHistory(range: String) {
        val c = client() ?: return
        try {
            val h = c.monitorHistory(range)
            if (_state.value.range == range) _state.update { it.copy(history = h) }
            val ch = c.claudeHistory(if (range == "1h" || range == "6h") "24h" else range)
            _state.update { it.copy(claudeHistory = ch) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
        }
    }

    fun setSort(sort: ProcessSort) {
        _state.update { it.copy(sort = sort) }
        scope.launch { refreshNow() }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        scope.launch { refreshNow() }
    }

    fun refreshClaude() {
        val c = client() ?: return
        scope.launch {
            try {
                val u = c.claudeUsage(refresh = true)
                _state.update { st -> st.copy(now = (st.now ?: PcNow()).copy(claude = u)) }
                if (!u.ok) u.error?.let(say)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                say(describe(t))
            }
        }
    }

    fun stopProcess(p: PcProcess, force: Boolean) {
        val c = client() ?: return
        _state.update { it.copy(stopping = it.stopping + p.pid) }
        scope.launch {
            try {
                val stopped = c.stopProcess(p.pid, force)
                say(if (stopped) "Stopped ${p.name}" else "${p.name} is still running; try Force stop")
                refreshNow()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                say(describe(t))
            } finally {
                _state.update { it.copy(stopping = it.stopping - p.pid) }
            }
        }
    }
}
