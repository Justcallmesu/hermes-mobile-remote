package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A chat Hermes found by the text inside its messages, with the matching text ([snippet], may be empty). */
data class SearchHit(val session: SessionSummary, val snippet: String)

/** Hermes' own message search (the desktop sidebar's), through the bridge's `/v1/sessions/search`. */
interface SessionSearchSource {
    suspend fun searchSessions(query: String, limit: Int = 20, profile: String? = null): List<SearchHit>
}

/** The bridge's search reply: `/v1/sessions` rows plus `snippet`. Rows without an id are skipped. */
fun parseSearchHits(o: JSONObject): List<SearchHit> =
    o.optJSONArray("sessions").objects().filter { it.str("id") != null }
        .map { SearchHit(parseSession(it), it.str("snippet").orEmpty()) }

/**
 * The hits worth a section of their own: not already in the drawer's local results ([shownIds]),
 * each chat once, in Hermes' order (best match first).
 */
fun serverOnlyHits(hits: List<SearchHit>, shownIds: Set<String>): List<SearchHit> =
    hits.filter { it.session.id !in shownIds }.distinctBy { it.session.id }

data class SessionSearchState(
    /** The query [hits] answer; the drawer shows them only while its box still holds it. */
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    val loading: Boolean = false,
    /** The PC and profile the hits came from, so a tap opens the chat where it lives. */
    val target: Target? = null,
)

/**
 * The drawer's "In messages" search. The local title/preview filter stays instant; this asks the
 * PC after the user pauses typing ([debounceMs]) and only for [MIN_CHARS]+ characters, so a word
 * being typed costs one request rather than one per key.
 *
 * A reply is used only if the query, PC and profile are still the ones it was asked for (the same
 * check every other per-profile feature makes). A bridge without the route (404) is not asked again
 * until [clear]: the drawer simply keeps its local results. Other failures stay silent too; search
 * is a nicety beside the list, not something to put an error over it for.
 */
class SessionSearch(
    private val scope: CoroutineScope,
    private val source: () -> SessionSearchSource?,
    /** The PC and Hermes profile in use now. */
    private val target: () -> Target,
    private val debounceMs: Long = DEBOUNCE_MS,
) {
    private val _state = MutableStateFlow(SessionSearchState())
    val state: StateFlow<SessionSearchState> = _state.asStateFlow()

    private var job: Job? = null
    private var unsupported = false

    /** Forget the hits (another PC or profile is in use) and try the route again next time. */
    fun clear() {
        job?.cancel()
        unsupported = false
        _state.value = SessionSearchState()
    }

    /** The drawer's search box changed to [text]. */
    fun search(text: String) {
        val query = text.trim()
        if (query == _state.value.query && (_state.value.loading || _state.value.target == target())) return
        job?.cancel()
        val src = source()
        if (query.length < MIN_CHARS || src == null || unsupported) {
            _state.value = SessionSearchState(query = query)
            return
        }
        val asked = target()
        _state.value = SessionSearchState(query = query, loading = true, target = asked)
        job = scope.launch {
            delay(debounceMs)
            try {
                val hits = src.searchSessions(query, LIMIT, asked.profile)
                if (isCurrent(query, asked)) _state.value = SessionSearchState(query, hits, false, asked)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is BridgeException && t.httpCode == 404) unsupported = true
                if (isCurrent(query, asked)) _state.update { it.copy(loading = false) }
            }
        }
    }

    private fun isCurrent(query: String, asked: Target) = _state.value.query == query && target() == asked

    companion object {
        const val DEBOUNCE_MS = 350L
        const val MIN_CHARS = 2
        const val LIMIT = 20
    }
}
