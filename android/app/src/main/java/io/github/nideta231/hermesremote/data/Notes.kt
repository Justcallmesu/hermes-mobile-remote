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
    /** When each note's last local save finished: a poll that started before that holds a stale copy. */
    private val savedAt = HashMap<String, Long>()
    /** The newest local version of each note with unsent or just-sent changes. */
    private val local = HashMap<String, Note>()
    /** The note open in the editor: kept on screen even when a filter or search would hide it. */
    var editing: String? = null

    fun clear() { stop(); local.clear(); savedAt.clear(); _state.value = NotesState() }

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
        val started = System.nanoTime()
        try {
            val o = c.notes(s.query, s.archived, s.label)
            // A note edited here keeps the local copy while its save is pending, and also when this
            // poll left before the save landed (its server copy is older than what is on screen).
            val list = o.optJSONArray("notes").objects().map(Note::parse).map { n ->
                val mine = local[n.id]
                if (mine != null && (n.id in pending || (savedAt[n.id] ?: Long.MIN_VALUE) > started)) mine else n
            }.let { l -> val e = editing; val keep = e?.let { id -> _state.value.notes.firstOrNull { it.id == id } }
                if (keep != null && l.none { it.id == e }) listOf(keep) + l else l }
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
                editing = n.id
                onCreated(n)
            } catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)) }
        }
    }

    /** Apply [change] now on screen and save it shortly after (typing sends one request, not one per key). */
    fun edit(id: String, debounceMs: Long = 600, change: (Note) -> Note) {
        val before = _state.value.notes.firstOrNull { it.id == id } ?: return
        val after = change(before)
        local[id] = after
        _state.update { st -> st.copy(notes = st.notes.map { if (it.id == id) after else it }) }
        pending[id]?.cancel()
        pending[id] = scope.launch {
            delay(debounceMs)
            save(after)
        }
    }

    private suspend fun save(n: Note) {
        val c = client() ?: return
        try {
            c.updateNote(n.id, JSONObject().put("title", n.title).put("body", n.body).put("color", n.color)
                .put("pinned", n.pinned).put("archived", n.archived).put("labels", JSONArray(n.labels)))
            savedAt[n.id] = System.nanoTime()
        } catch (t: Throwable) { if (t is CancellationException) throw t; say("Note not saved: ${describe(t)}") }
        finally { if (pending[n.id]?.isActive != true) pending.remove(n.id) }
    }

    /** Leaving the editor: what was written is saved right away; a note left completely empty is dropped, like Keep. */
    fun closed(id: String) {
        val n = _state.value.notes.firstOrNull { it.id == id } ?: local[id] ?: return
        if (editing == id) editing = null
        if (n.isEmpty) return delete(id, quiet = true)
        val job = pending[id] ?: return
        job.cancel()
        pending[id] = scope.launch { save(local[id] ?: n) }
    }

    /** Archive (or bring back) with a clear message, since the note leaves the current list. */
    fun setArchived(id: String, archived: Boolean) {
        edit(id, debounceMs = 0) { it.copy(archived = archived) }
        say(if (archived) "Note archived (find it under Archive)" else "Note moved back to Notes")
    }

    fun delete(id: String, quiet: Boolean = false) {
        val c = client() ?: return
        pending.remove(id)?.cancel()
        local.remove(id)
        _state.update { st -> st.copy(notes = st.notes.filterNot { it.id == id }) }
        scope.launch {
            try { c.deleteNote(id); if (!quiet) say("Note deleted") }
            catch (t: Throwable) { if (t is CancellationException) throw t; say(describe(t)); load() }
        }
    }

    companion object { const val POLL_MS = 4_000L }
}

