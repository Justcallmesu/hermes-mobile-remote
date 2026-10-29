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
import org.json.JSONArray
import org.json.JSONObject

/** One note, as the bridge stores it (a Markdown file Hermes can read and edit too). */
data class Note(
    val id: String,
    val title: String = "",
    val body: String = "",
    val color: String = "default",
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val labels: List<String> = emptyList(),
    val created: Double = 0.0,
    val updated: Double = 0.0,
) {
    val isEmpty get() = title.isBlank() && body.isBlank()

    companion object {
        fun parse(o: JSONObject) = Note(o.optString("id"), o.optString("title"), o.optString("body"),
            o.optString("color", "default"), o.optBoolean("pinned"), o.optBoolean("archived"),
            o.optJSONArray("labels").strings().filter { it.isNotBlank() }, o.optDouble("created", 0.0), o.optDouble("updated", 0.0))
    }
}

/** A `- [ ]` / `- [x]` line of a note, or plain text. */
sealed interface NoteLine {
    data class Text(val text: String) : NoteLine
    data class Check(val text: String, val done: Boolean, val indent: String = "") : NoteLine
}

object NoteText {
    private val check = Regex("""^(\s*)[-*] \[([ xX])\] ?(.*)$""")

    fun lines(body: String): List<NoteLine> = body.trimEnd('\n').split('\n').map { l ->
        check.find(l)?.let { m -> NoteLine.Check(m.groupValues[3], m.groupValues[2] != " ", m.groupValues[1]) } ?: NoteLine.Text(l)
    }

    fun join(lines: List<NoteLine>): String = lines.joinToString("\n") {
        when (it) { is NoteLine.Text -> it.text; is NoteLine.Check -> "${it.indent}- [${if (it.done) "x" else " "}] ${it.text}" }
    }.let { if (it.isEmpty()) it else "$it\n" }

    /** Tick or untick line [index] (a checklist line). */
    fun toggle(body: String, index: Int): String {
        val ls = lines(body).toMutableList()
        (ls.getOrNull(index) as? NoteLine.Check)?.let { ls[index] = it.copy(done = !it.done) }
        return join(ls)
    }

    fun isChecklist(body: String) = lines(body).any { it is NoteLine.Check }

    /** Turn plain lines into checklist items, or a checklist back into plain lines. */
    fun toggleChecklist(body: String): String {
        val ls = lines(body)
        return if (ls.any { it is NoteLine.Check }) join(ls.map { if (it is NoteLine.Check) NoteLine.Text(it.indent + it.text) else it })
        else join(ls.filter { it !is NoteLine.Text || it.text.isNotBlank() }.map { NoteLine.Check((it as NoteLine.Text).text.trimStart('-', '*', ' '), false) })
    }

    /** Open items first in the card preview; done ones count only. */
    fun progress(body: String): Pair<Int, Int> = lines(body).filterIsInstance<NoteLine.Check>().let { c -> c.count { it.done } to c.size }
}

data class NotesState(
    val notes: List<Note> = emptyList(),
    val labels: List<String> = emptyList(),
    val query: String = "",
    val label: String? = null,
    val archived: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** The bridge has no notes (older than 1.30, or `notes = false`). */
    val unsupported: Boolean = false,
)

/**
 * The Notes page. Saves are debounced per note and sent last-write-wins; while the page is open
 * the list is re-read every few seconds, so notes Hermes writes on the PC show up on their own.
 */
class NotesRepo(
    private val scope: CoroutineScope,
    private val client: () -> BridgeClient?,
    private val describe: (Throwable) -> String,
    private val say: (String) -> Unit,
) {
    private val _state = MutableStateFlow(NotesState())
    val state: StateFlow<NotesState> = _state.asStateFlow()
    private var poll: Job? = null
    private val pending = HashMap<String, Job>()

    fun clear() { stop(); _state.value = NotesState() }

    fun watch() {
        if (poll?.isActive == true) return
        poll = scope.launch { while (true) { load(); delay(POLL_MS) } }
    }

    fun stop() { poll?.cancel(); poll = null }

    fun setQuery(q: String) { _state.update { it.copy(query = q) }; scope.launch { load() } }
    fun setLabel(l: String?) { _state.update { it.copy(label = l, archived = false) }; scope.launch { load() } }
    fun setArchived(a: Boolean) { _state.update { it.copy(archived = a, label = null) }; scope.launch { load() } }

    suspend fun load() {
        val c = client() ?: return
        val s = _state.value
        try {
            val o = c.notes(s.query, s.archived, s.label)
            // A note with an unsent edit keeps the local copy: the poll must not undo typing.
            val local = _state.value.notes.associateBy { it.id }
            val list = o.optJSONArray("notes").objects().map(Note::parse).map { n -> if (n.id in pending) local[n.id] ?: n else n }
            _state.update { it.copy(notes = list, labels = o.optJSONArray("labels").strings(), loading = false, error = null, unsupported = false) }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val old = t is BridgeException && t.httpCode == 404
            _state.update { it.copy(loading = false, unsupported = old, error = if (old) null else describe(t)) }
        }
    }

    /** A new note; [onCreated] gets it to open in the editor. */
    fun create(checklist: Boolean = false, onCreated: (Note) -> Unit) {
        val c = client() ?: return
        scope.launch {
            try {
                val label = _state.value.label
                val n = Note.parse(c.createNote(JSONObject().put("body", if (checklist) "- [ ] \n" else "")
                    .put("labels", JSONArray(listOfNotNull(label)))))
                _state.update { it.copy(notes = listOf(n) + it.notes) }
                onCreated(n)
            } catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)) }
        }
    }

    /** Apply [change] now on screen and save it shortly after (typing sends one request, not one per key). */
    fun edit(id: String, debounceMs: Long = 600, change: (Note) -> Note) {
        val before = _state.value.notes.firstOrNull { it.id == id } ?: return
        val after = change(before)
        _state.update { st -> st.copy(notes = st.notes.map { if (it.id == id) after else it }) }
        pending[id]?.cancel()
        pending[id] = scope.launch {
            delay(debounceMs)
            val c = client() ?: return@launch
            try {
                c.updateNote(id, JSONObject().put("title", after.title).put("body", after.body).put("color", after.color)
                    .put("pinned", after.pinned).put("archived", after.archived).put("labels", JSONArray(after.labels)))
            } catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)) }
            finally { pending.remove(id) }
        }
    }

    /** Leaving the editor: an empty new note is dropped, like Keep. */
    fun closed(id: String) {
        val n = _state.value.notes.firstOrNull { it.id == id } ?: return
        if (n.isEmpty) delete(id, quiet = true)
    }

    fun delete(id: String, quiet: Boolean = false) {
        val c = client() ?: return
        pending.remove(id)?.cancel()
        _state.update { st -> st.copy(notes = st.notes.filterNot { it.id == id }) }
        scope.launch {
            try { c.deleteNote(id); if (!quiet) say("Note deleted") }
            catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)); load() }
        }
    }

    companion object { const val POLL_MS = 4_000L }
}

