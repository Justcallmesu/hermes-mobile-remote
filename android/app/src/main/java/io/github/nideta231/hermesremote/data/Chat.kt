package io.github.nideta231.hermesremote.data

import org.json.JSONArray
import org.json.JSONObject

enum class ToolStatus { RUNNING, OK, FAILED }

sealed interface ChatItem {
    val key: String

    /** [images]: the pictures sent with it, as PC paths (or a phone `content://` uri while it is still sending). */
    /** [rowId]: Hermes' durable row id, known once the message is read back from the PC (edit needs it). */
    /** [files]: PC paths of the files sent with it (`@file:` references), shown as cards. */
    data class User(override val key: String, val text: String, val pending: Boolean = false,
                    val images: List<String> = emptyList(), val rowId: Long? = null,
                    val files: List<String> = emptyList(), val at: Long? = null) : ChatItem
    /** [at] (both): when it was sent, epoch ms; null when the PC didn't say. Shown under the message. */
    data class Assistant(override val key: String, val text: String, val streaming: Boolean = false, val at: Long? = null) : ChatItem
    /** The model's reasoning for the reply that follows; shown collapsed. */
    data class Thinking(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem
    data class Tool(
        override val key: String,
        val name: String,
        val args: String,
        val result: String? = null,
        val status: ToolStatus = ToolStatus.OK,
        val durationSec: Double? = null,
        val callId: String? = null,
        /** Pictures the tool returned (e.g. vision_analyze, screenshots), as `data:` urls. */
        val images: List<String> = emptyList(),
        /** delegate_task only: the goals it dispatched, in task order (the delegate card's rows). */
        val goals: List<String> = emptyList(),
    ) : ChatItem
    /** [decided]: the choice made (here or on another screen), null while it waits. */
    data class Approval(override val key: String, val request: ApprovalRequest, val decided: String? = null) : ChatItem
    data class Clarify(override val key: String, val request: ClarifyRequest, val answer: String? = null) : ChatItem
    /** A masked prompt (sudo, a secret, a password-manager unlock, a login to save, a 2FA code). [outcome] never holds the value. */
    data class Secret(override val key: String, val request: SecretRequest, val outcome: String? = null) : ChatItem
    data class Notice(override val key: String, val text: String, val error: Boolean = false) : ChatItem
    /** Output of a slash command or a /btw side answer. Local to the app; not part of the transcript. */
    data class CommandOutput(override val key: String, val command: String, val text: String) : ChatItem
}

private const val SKILL_PREFIX = "[IMPORTANT: The user has invoked the "
private const val SKILL_INSTRUCTION = "The user has provided the following instruction alongside the skill invocation: "
private const val PLAN_PREFIX = "[/plan — plan mode]"
private const val STEER_OPEN = "[OUT-OF-BAND USER MESSAGE"
private const val STEER_CLOSE = "[/OUT-OF-BAND USER MESSAGE]"

/**
 * A user turn as the user typed it. Skills and /plan expand into long prompts before they reach
 * the model; show "/humanizer fix this" instead of the whole skill body, like the desktop app does.
 */
private val fileRefRe = Regex("""^@file:(?:`([^`]+)`|"([^"]+)"|'([^']+)'|(\S+))$""")

/** `@file:` reference lines (files sent with a message) shown as "📎 name", like the desktop's chips. */
fun fileRefsAsLabels(text: String): String {
    if (!text.contains("@file:")) return text
    return withoutContextWarnings(text).lines().joinToString("\n") { line ->
        fileRefRe.matchEntire(line.trim())?.let { m -> "📎 " + fileName(m.groupValues.drop(1).first { it.isNotEmpty() }) } ?: line
    }
}

/** The paths of a user message's `@file:` reference lines, in order. */
fun fileRefs(text: String): List<String> {
    if (!text.contains("@file:")) return emptyList()
    return withoutContextWarnings(text).lines().mapNotNull { line ->
        fileRefRe.matchEntire(line.trim())?.groupValues?.drop(1)?.first { it.isNotEmpty() }
    }.distinct()
}

/** Last segment of a PC path (either slash). */
/** What the Info sheet of a chat file or picture shows. [size]/[modified] are null when unknown. */
data class FileInfo(val name: String, val path: String, val exists: Boolean = true, val size: Long? = null,
                    val modified: Long? = null, val mime: String? = null, val note: String? = null)

/** Parses the bridge's `/v1/sessions/{id}/file/info` answer. */
fun parseFileInfo(o: org.json.JSONObject, asked: String): FileInfo = FileInfo(
    name = o.optString("name").ifEmpty { fileName(asked) },
    path = o.optString("path").ifEmpty { asked },
    exists = o.optBoolean("exists", true),
    size = if (o.has("size")) o.optLong("size") else null,
    modified = if (o.has("modified")) o.optLong("modified") else null,
    mime = o.optString("mime").ifEmpty { null })

/** 1536 -> "1.5 KB", in powers of 1024, like file managers. */
fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return (if (v >= 100) "%.0f %s" else "%.1f %s").format(java.util.Locale.US, v, units[i])
}

fun fileName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/** Hermes appends a model-facing "--- Context Warnings ---" block to a message with files; not the user's words. */
private fun withoutContextWarnings(text: String): String {
    val at = text.indexOf("\n--- Context Warnings ---")
    return if (at < 0) text else text.substring(0, at).trimEnd()
}

fun displayUserText(text: String): String = fileRefsAsLabels(displayUserTextRaw(text))

private fun displayUserTextRaw(text: String): String {
    if (text.startsWith(SKILL_PREFIX)) {
        val name = Regex("^" + Regex.escape(SKILL_PREFIX) + "\"([^\"]*)\"").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val label = if (name.startsWith("/")) name else "/$name"
        val at = text.lastIndexOf(SKILL_INSTRUCTION)
        val instruction = if (at < 0) "" else text.substring(at + SKILL_INSTRUCTION.length).substringBefore("\n\n[Runtime note:").trim()
        return if (instruction.isEmpty()) label else "$label $instruction"
    }
    if (text.startsWith(PLAN_PREFIX)) {
        val task = text.substringAfter("Task to plan:\n", "").substringBefore("\n\n").trim()
        return if (task.isEmpty()) "/plan" else "/plan $task"
    }
    // A mid-turn steer is stored inside Hermes' marker wrapper; show the user's own words, as the desktop does.
    val steerOpen = text.indexOf(STEER_OPEN)
    if (steerOpen >= 0) {
        val bodyStart = text.indexOf('\n', steerOpen).let { if (it < 0) return text else it + 1 }
        val bodyEnd = text.indexOf(STEER_CLOSE, bodyStart)
        if (bodyEnd >= 0) return text.substring(bodyStart, bodyEnd).trim()
    }
    return text
}

private val imageRefRe = Regex("""^@image:(?:`([^`]+)`|"([^"]+)"|'([^']+)'|(\S+))\s*$""")
private val visionHintRe = Regex("""^\[Examine it with the vision_analyze tool using image_url: (.+)]$""")
private val attachedNoteRe = Regex("""^\[(?:The user attached an image|User attached image): [^\]]*]$""")
private val mediaLineRe = Regex("""^\s*MEDIA:\s*(\S.*?)\s*$""")
private val imageExt = Regex("""\.(png|jpe?g|gif|webp|bmp)$""", RegexOption.IGNORE_CASE)

/**
 * A user message with its pictures taken out: Hermes stores an attached image as an `@image:<path>`
 * line (the model-facing form names it in a vision_analyze hint instead). Returns the text left and
 * the image paths, in order.
 */
fun splitUserImages(text: String): Pair<String, List<String>> {
    if (!text.contains("@image:") && !text.contains("image_url: ") && !text.contains("attached")) return text to emptyList()
    val images = mutableListOf<String>()
    val kept = text.lines().filter { line ->
        val t = line.trim()
        imageRefRe.matchEntire(t)?.let { m -> images += m.groupValues.drop(1).first { it.isNotEmpty() }; return@filter false }
        visionHintRe.matchEntire(t)?.let { m -> images += m.groupValues[1].trim(); return@filter false }
        !attachedNoteRe.matches(t)
    }
    return kept.joinToString("\n").trim() to images.distinct()
}

/** A reply with its `MEDIA:/path/to/image.png` lines (how Hermes hands over a file) taken out as images. */
fun splitReplyImages(text: String): Pair<String, List<String>> = splitReplyMedia(text).let { it.text to it.images }

/** A reply's text, and its `MEDIA:` files split into pictures (drawn) and other files (shown as cards). */
data class ReplyMedia(val text: String, val images: List<String>, val files: List<String>)

fun splitReplyMedia(text: String): ReplyMedia {
    if (!text.contains("MEDIA:")) return ReplyMedia(text, emptyList(), emptyList())
    val images = mutableListOf<String>()
    val files = mutableListOf<String>()
    val kept = text.lines().filter { line ->
        val path = mediaLineRe.matchEntire(line)?.groupValues?.get(1)?.trim('`', '"', '\'')
        when {
            path.isNullOrEmpty() -> true
            imageExt.containsMatchIn(path) -> { images += path; false }
            else -> { files += path; false }
        }
    }
    return ReplyMedia(kept.joinToString("\n").trim(), images.distinct(), files.distinct())
}

/** Human-sized summary of a tool call's arguments (the command for terminal, else compact JSON). */
fun summarizeArgs(args: JSONObject?): String {
    args ?: return ""
    for (k in listOf("command", "path", "url", "query", "name", "goal", "code", "question")) {
        args.str(k)?.takeIf { it.isNotBlank() }?.let { return it.take(400) }
    }
    return if (args.length() == 0) "" else args.toString().take(300)
}

/** A tool result reads as a failure when it says so: non-zero exit, an error, success=false. */
fun toolFailed(result: Any?): Boolean {
    val o = when (result) {
        is JSONObject -> result
        is String -> runCatching { JSONObject(result) }.getOrNull()
        else -> null
    } ?: return false
    return (o.has("exit_code") && !o.isNull("exit_code") && o.optInt("exit_code", 0) != 0) ||
        (o.has("success") && !o.optBoolean("success", true)) ||
        (o.has("error") && !o.isNull("error") && o.opt("error").let { it == true || (it is String && it.isNotBlank()) })
}

private fun resultPreview(p: JSONObject): String? {
    p.str("summary")?.takeIf { it.isNotBlank() }?.let { return it }
    p.str("result_text")?.takeIf { it.isNotBlank() }?.let { return multimodal(it)?.first ?: it.take(4000) }
    return when (val r = p.opt("result")) {
        null, JSONObject.NULL -> null
        is JSONObject -> multimodal(r)?.first ?: r.str("output")?.takeIf { it.isNotBlank() } ?: r.toString()
        else -> multimodal(r.toString())?.first ?: r.toString()
    }?.take(4000)
}

/** Pictures in a tool.complete event's result. */
private fun resultImages(p: JSONObject): List<String> =
    (multimodal(p.opt("result")) ?: multimodal(p.str("result_text")))?.second.orEmpty()

/**
 * A multimodal tool result (`{"_multimodal": true, "content": [{"type": "text"}, {"type": "image_url"}]}`,
 * as a JSON object or its text) split into its text and its `data:` image urls; null for anything else.
 */
fun multimodal(raw: Any?): Pair<String, List<String>>? {
    val o = when (raw) {
        is JSONObject -> raw
        is String -> raw.trim().takeIf { it.startsWith("{") && "_multimodal" in it }?.let { runCatching { JSONObject(it) }.getOrNull() }
        else -> null
    } ?: return null
    if (!o.optBoolean("_multimodal")) return null
    val parts = o.optJSONArray("content").objects()
    val text = o.str("text_summary")?.takeIf { it.isNotBlank() }
        ?: parts.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") }.trim()
    val images = parts.filter { it.optString("type") == "image_url" }.mapNotNull { part ->
        (part.optJSONObject("image_url")?.str("url") ?: part.str("image_url"))?.takeIf { it.startsWith("data:image/") }
    }
    return text to images
}

/** What a synthetic user row (written by Hermes, not typed by the user) shows as. */
sealed interface SyntheticRow {
    /** Not shown at all: a compaction restatement or a model-facing note. */
    data object Drop : SyntheticRow
    /** A one-line timeline notice, like the desktop's "background process finished". */
    data class Notice(val text: String) : SyntheticRow
}

private fun displayText(metadata: Any?): String? {
    val o = when (metadata) {
        is JSONObject -> metadata
        is String -> runCatching { JSONObject(metadata) }.getOrNull()
        else -> null
    } ?: return null
    return o.str("display_text")?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Hermes stores its own notices (finished background processes and agents, model switches,
 * the request it restates after compacting context) as user rows so strict providers accept
 * them. They are not the user's words: classify them by `display_kind`, or by their fixed
 * prefix for rows written before Hermes typed them. Null: a real user message.
 */
fun syntheticUserRow(text: String, kind: String?, metadata: Any? = null): SyntheticRow? {
    when (kind) {
        "hidden" -> return SyntheticRow.Drop
        "model_switch" -> return SyntheticRow.Notice("Model changed")
        "personality_switch" -> return SyntheticRow.Notice("Personality changed")
        "auto_continue" -> return SyntheticRow.Notice("Resumed interrupted turn")
        "async_delegation_complete" -> return SyntheticRow.Notice(displayText(metadata) ?: "Background agent work finished")
        "process_complete", "internal_notification" -> return SyntheticRow.Notice(displayText(metadata) ?: "Background process finished")
    }
    val t = text.trimStart()
    return when {
        t.startsWith("[CONTEXT COMPACTION") || t.startsWith("[STILL IN PROGRESS") ||
            t.startsWith("[System:") || t.startsWith("[System note:") -> SyntheticRow.Drop
        t.startsWith("[IMPORTANT: Background process") || Regex("^\\[IMPORTANT: \\d+ background processes").containsMatchIn(t) ->
            SyntheticRow.Notice("Background process finished")
        t.startsWith("[ASYNC DELEGATION") -> SyntheticRow.Notice("Background agent work finished")
        else -> null
    }
}

/** Hermes' display transcript (`session.resume` / `session.history` messages) as chat items. */
object HistoryMapper {
    fun map(messages: JSONArray?): List<ChatItem> {
        val items = mutableListOf<ChatItem>()
        messages.objects().forEachIndexed { i, m ->
            if (m.str("display_kind") == "hidden") return@forEachIndexed
            val id = if (m.has("row_id") && !m.isNull("row_id")) "r${m.optLong("row_id")}" else "i$i"
            val text = m.str("text").orEmpty()
            val at = m.optDouble("timestamp", Double.NaN).takeIf { !it.isNaN() && it > 0 }?.let { (it * 1000).toLong() }
            when (m.optString("role")) {
                "user" -> if (text.isNotBlank()) when (val s = syntheticUserRow(text, m.str("display_kind"), m.opt("display_metadata"))) {
                    SyntheticRow.Drop -> {}
                    is SyntheticRow.Notice -> items += ChatItem.Notice("h-$id", s.text)
                    null -> {
                        val (body, images) = splitUserImages(text)
                        items += ChatItem.User("h-$id", displayUserText(body), images = images,
                            rowId = if (m.has("row_id") && !m.isNull("row_id")) m.optLong("row_id") else null, files = fileRefs(body), at = at)
                    }
                }
                "assistant" -> {
                    m.str("reasoning")?.takeIf { it.isNotBlank() }?.let { items += ChatItem.Thinking("h-$id-r", it.trim()) }
                    if (text.isNotBlank()) items += ChatItem.Assistant("h-$id", text.trim(), at = at)
                }
                "tool" -> {
                    val name = m.str("name") ?: "tool"
                    val args = m.optJSONObject("args")
                    items += ChatItem.Tool("h-$id", name, m.str("context")?.takeIf { it.isNotBlank() } ?: summarizeArgs(args),
                        callId = m.str("tool_call_id"), goals = if (name == Delegate.TOOL) Delegate.goals(args) else emptyList())
                }
                "system" -> if (text.isNotBlank()) items += ChatItem.Notice("h-$id", text.trim())
            }
        }
        return items
    }
}

/**
 * Folds one live Hermes event into the chat. Pure. [key] must be unique per event (the caller
 * uses the event's seq), so a list diff never confuses two items.
 */
object LiveReducer {
    fun apply(items: List<ChatItem>, type: String, p: JSONObject, seqKey: String): List<ChatItem> {
        val out = items.toMutableList()
        // Event seqs restart (new run, reconnect replay), so "live-38" can already be on screen;
        // a repeated LazyColumn key crashes the app. Suffix until every derived key is free.
        val key = uniqueLiveKey(items, seqKey)
        fun seal() {
            for (i in out.indices.reversed()) {
                when (val it = out[i]) {
                    is ChatItem.Assistant -> if (it.streaming) {
                        if (it.text.isBlank()) out.removeAt(i) else out[i] = it.copy(text = it.text.trim(), streaming = false)
                    }
                    is ChatItem.Thinking -> if (it.streaming) out[i] = it.copy(text = it.text.trim(), streaming = false)
                    else -> {}
                }
                if (out.getOrNull(i) is ChatItem.User) break
            }
        }
        fun settleTools(to: ToolStatus) = out.replaceAll { if (it is ChatItem.Tool && it.status == ToolStatus.RUNNING) it.copy(status = to) else it }
        when (type) {
            "message.delta" -> {
                val text = p.optString("text")
                if (text.isEmpty()) return items
                val last = out.lastOrNull()
                if (last is ChatItem.Assistant && last.streaming) out[out.lastIndex] = last.copy(text = last.text + text)
                else {
                    if (last is ChatItem.Thinking && last.streaming) out[out.lastIndex] = last.copy(streaming = false)
                    out += ChatItem.Assistant("live-$key", text.trimStart(), streaming = true, at = System.currentTimeMillis())
                }
            }
            // thinking.delta is the spinner's status text ("( ˘⌣˘)♡ reasoning..."), not reasoning:
            // the desktop keeps it out of the transcript, and so does this reducer.
            "reasoning.delta" -> {
                val text = p.optString("text")
                if (text.isEmpty()) return items
                val last = out.lastOrNull()
                if (last is ChatItem.Thinking && last.streaming) out[out.lastIndex] = last.copy(text = last.text + text)
                else out += ChatItem.Thinking("live-$key", text.trimStart(), streaming = true)
            }
            "reasoning.available" -> {
                val text = p.optString("text").trim()
                if (text.isNotEmpty() && out.lastOrNull() !is ChatItem.Thinking) out += ChatItem.Thinking("live-$key", text)
            }
            "message.interim" -> {
                val text = p.optString("text").trim()
                if (p.optBoolean("already_streamed")) seal()
                else if (text.isNotEmpty()) { seal(); out += ChatItem.Assistant("live-$key", text) }
            }
            "tool.start" -> {
                seal()
                val name = p.str("name") ?: "tool"
                val label = p.str("context")?.takeIf { it.isNotBlank() } ?: p.str("preview")?.takeIf { it.isNotBlank() }
                    ?: summarizeArgs(p.optJSONObject("args"))
                val callId = p.str("tool_id")
                if (callId != null && out.any { it is ChatItem.Tool && it.callId == callId }) return out
                out += ChatItem.Tool("live-$key", name, label, status = ToolStatus.RUNNING, callId = callId,
                    goals = if (name == Delegate.TOOL) Delegate.goals(p.optJSONObject("args")) else emptyList())
            }
            "tool.complete" -> {
                val callId = p.str("tool_id")
                val name = p.str("name")
                var idx = if (callId != null) out.indexOfLast { it is ChatItem.Tool && it.callId == callId } else -1
                if (idx < 0) idx = out.indexOfLast { it is ChatItem.Tool && it.status == ToolStatus.RUNNING && (name == null || it.name == name) }
                val failed = toolFailed(p.opt("result"))
                if (idx >= 0) {
                    val t = out[idx] as ChatItem.Tool
                    out[idx] = t.copy(status = if (failed) ToolStatus.FAILED else ToolStatus.OK,
                        result = resultPreview(p) ?: t.result, durationSec = p.dbl("duration_s"),
                        images = resultImages(p).ifEmpty { t.images })
                } else {
                    out += ChatItem.Tool("live-$key", name ?: "tool", summarizeArgs(p.optJSONObject("args")), resultPreview(p),
                        if (failed) ToolStatus.FAILED else ToolStatus.OK, p.dbl("duration_s"), callId, resultImages(p))
                }
            }
            "message.complete" -> {
                seal()
                val text = p.str("text")?.trim().orEmpty()
                val sinceUser = out.subList(out.indexOfLast { it is ChatItem.User } + 1, out.size)
                val last = out.lastOrNull()
                if (text.isNotEmpty() && sinceUser.none { it is ChatItem.Assistant && sameText(it.text, text) }) {
                    if (last !is ChatItem.Assistant) out += ChatItem.Assistant("live-$key", text)
                    // The streamed reply missed pieces (screen off, a reconnect): the final text is authoritative.
                    else if (coversReply(text, last.text)) out[out.lastIndex] = last.copy(text = text, streaming = false)
                }
                when (p.str("status")) {
                    "error" -> {
                        settleTools(ToolStatus.FAILED)
                        out += ChatItem.Notice("live-$key-n", p.str("error") ?: p.str("failure_reason") ?: "The turn failed.", error = true)
                    }
                    "interrupted" -> { settleTools(ToolStatus.FAILED); out += ChatItem.Notice("live-$key-n", "Stopped.") }
                    else -> settleTools(ToolStatus.OK)
                }
                p.str("warning")?.takeIf { it.isNotBlank() }?.let { out += ChatItem.Notice("live-$key-w", it) }
            }
            "error" -> out += ChatItem.Notice("live-$key", p.str("message") ?: "Hermes reported an error.", error = true)
            "btw.complete" -> out += ChatItem.CommandOutput("live-$key", "/btw ${p.str("question").orEmpty()}".trim(), p.optString("text"))
            "background.complete" -> out += ChatItem.CommandOutput("live-$key", "/background", p.optString("text"))
            else -> return items
        }
        return out
    }

    /** [seqKey], or [seqKey].2, .3… when "live-<key>" (or its -n/-w notices) is already taken. */
    internal fun uniqueLiveKey(items: List<ChatItem>, seqKey: String): String {
        val used = items.mapTo(HashSet()) { it.key }
        fun free(k: String) = "live-$k" !in used && "live-$k-n" !in used && "live-$k-w" !in used
        if (free(seqKey)) return seqKey
        var i = 2
        while (!free("$seqKey.$i")) i++
        return "$seqKey.$i"
    }

    /** A question Hermes asked arrived (or was replayed on resume): add it once. */
    fun withApproval(items: List<ChatItem>, r: ApprovalRequest): List<ChatItem> =
        if (items.any { it is ChatItem.Approval && it.request.id == r.id }) items else sealAll(items) + ChatItem.Approval("ask-${r.id}", r)

    fun withClarify(items: List<ChatItem>, r: ClarifyRequest): List<ChatItem> =
        if (items.any { it is ChatItem.Clarify && it.request.id == r.id }) items else sealAll(items) + ChatItem.Clarify("ask-${r.id}", r)

    fun withSecret(items: List<ChatItem>, r: SecretRequest): List<ChatItem> =
        if (items.any { it is ChatItem.Secret && it.request.id == r.id }) items else sealAll(items) + ChatItem.Secret("ask-${r.id}", r)

    /** The question [id] was answered (here, on the desktop) or withdrawn. */
    fun resolve(items: List<ChatItem>, id: String, outcome: String): List<ChatItem> = items.map {
        when {
            it is ChatItem.Approval && it.request.id == id && it.decided == null -> it.copy(decided = outcome)
            it is ChatItem.Clarify && it.request.id == id && it.answer == null -> it.copy(answer = outcome)
            it is ChatItem.Secret && it.request.id == id && it.outcome == null -> it.copy(outcome = outcome)
            else -> it
        }
    }

    /** Questions still open: what the dock shows. */
    fun openApproval(items: List<ChatItem>) = items.lastOrNull { it is ChatItem.Approval && it.decided == null } as ChatItem.Approval?
    fun openClarify(items: List<ChatItem>) = items.lastOrNull { it is ChatItem.Clarify && it.answer == null } as ChatItem.Clarify?
    fun openSecret(items: List<ChatItem>) = items.lastOrNull { it is ChatItem.Secret && it.outcome == null } as ChatItem.Secret?

    private fun sealAll(items: List<ChatItem>) = items.map {
        when (it) {
            is ChatItem.Assistant -> if (it.streaming) it.copy(streaming = false) else it
            is ChatItem.Thinking -> if (it.streaming) it.copy(streaming = false) else it
            else -> it
        }
    }
}

/** Whitespace-insensitive equality: the same message as typed, stored and replayed. */
fun sameText(a: String, b: String): Boolean = squash(a) == squash(b)

private fun squash(s: String) = s.trim().replace(Regex("\\s+"), " ")

/** [final] is the whole of a reply [partial] showed only part of (its start, or its end). */
fun coversReply(final: String, partial: String): Boolean {
    val f = squash(final)
    val p = squash(partial)
    if (p.isEmpty() || f.length <= p.length) return false
    val k = minOf(40, p.length)
    return f.startsWith(p.take(k)) || f.endsWith(p.takeLast(k))
}

/**
 * Key of the running turn's prompt in a resumed transcript: the first user row stored at or after
 * the turn started. Null when the PC sent no timestamps or hasn't stored the prompt yet.
 */
fun turnPromptKey(messages: JSONArray?, turnStartedAt: Double?): String? {
    turnStartedAt ?: return null
    messages.objects().forEach { m ->
        if (m.optString("role") != "user" || m.str("display_kind") == "steer" || m.str("display_kind") == "hidden") return@forEach
        val ts = m.optDouble("timestamp", Double.NaN)
        if (ts.isNaN() || !m.has("row_id") || m.isNull("row_id")) return@forEach
        if (ts >= turnStartedAt - 2.0) return "h-r${m.optLong("row_id")}"
    }
    return null
}

/** What [reply] (everything streamed this turn) adds past the [persisted] pieces already in the transcript. */
fun replyRemainder(reply: String, persisted: List<String>): String {
    var cursor = 0
    for (chunk in persisted) {
        val c = chunk.trim()
        if (c.isEmpty()) continue
        val at = reply.indexOf(c, cursor)
        if (at >= 0) cursor = at + c.length
    }
    return reply.substring(cursor)
}

/**
 * The in-flight turn of a session the phone attaches to mid-run (`inflight` from session.resume):
 * the user's message and the reply so far, after the persisted history. Hermes stores the prompt
 * and each finished step while the turn runs, so only what isn't in the transcript yet is added;
 * re-adding it put the prompt (and its reply) a second time at the bottom of the chat.
 * [promptKey]: the prompt's item when [turnPromptKey] found it.
 */
fun withInflight(history: List<ChatItem>, inflight: JSONObject?, promptKey: String? = null): List<ChatItem> {
    inflight ?: return history
    val out = history.toMutableList()
    val raw = inflight.str("user").orEmpty()
    var at = promptKey?.let { k -> out.indexOfFirst { it.key == k } } ?: -1
    if (at < 0) when (val s = syntheticUserRow(raw, inflight.str("display_kind"), inflight.opt("display_metadata"))) {
        SyntheticRow.Drop -> {}
        is SyntheticRow.Notice -> {
            if ((out.lastOrNull() as? ChatItem.Notice)?.text != s.text) out += ChatItem.Notice("inflight-u", s.text)
            at = out.lastIndex
        }
        null -> {
            val (body, images) = splitUserImages(raw)
            val user = displayUserText(body).trim()
            val lastUser = out.indexOfLast { it is ChatItem.User }
            val shown = out.getOrNull(lastUser) as? ChatItem.User
            if (shown != null && sameText(shown.text, user)) at = lastUser
            else if (user.isNotEmpty() || images.isNotEmpty()) {
                out += ChatItem.User("inflight-u", user, images = images, files = fileRefs(body))
                at = out.lastIndex
            }
        }
    }
    val reply = inflight.str("assistant").orEmpty()
    val persisted = if (at >= 0) out.drop(at + 1).filterIsInstance<ChatItem.Assistant>().map { it.text } else emptyList()
    val rest = replyRemainder(reply, persisted)
    val error = inflight.str("error")?.trim().orEmpty()
    // A retained failed turn (e.g. "The conversation is too long for this model", with its hint and details):
    // the reply so far is final, and the failure shows the way the desktop's error banner does.
    if (rest.isNotBlank()) out += ChatItem.Assistant("inflight-a", rest.trimStart(), streaming = error.isEmpty() && inflight.optBoolean("streaming", true))
    if (error.isNotEmpty()) out += ChatItem.Notice("inflight-e", error, error = true)
    return out
}

/**
 * A turn another screen (the desktop) started while this chat is open: Hermes' `message.start`
 * carries no text, so the user's message comes from the session's `inflight` snapshot and goes
 * right after [anchorKey], the last item before the turn began (null = the list was empty).
 * Nothing changes if the anchor is gone, or the message is already shown: the phone's own send
 * (its bubble is the newest user row, still unanswered) or a rebuilt list that has it after the
 * anchor.
 */
fun withLiveUser(items: List<ChatItem>, anchorKey: String?, inflight: JSONObject?): List<ChatItem> {
    inflight ?: return items
    val key = "live-u-${anchorKey ?: "start"}"
    if (items.any { it.key == key }) return items
    val at = if (anchorKey == null) 0 else items.indexOfFirst { it.key == anchorKey }.takeIf { it >= 0 }?.plus(1) ?: return items
    val raw = inflight.str("user").orEmpty()
    val row = when (val s = syntheticUserRow(raw, inflight.str("display_kind"), inflight.opt("display_metadata"))) {
        SyntheticRow.Drop -> return items
        is SyntheticRow.Notice -> {
            if (items.drop(at).any { it is ChatItem.Notice && it.text == s.text }) return items
            ChatItem.Notice(key, s.text)
        }
        null -> {
            val (body, images) = splitUserImages(raw)
            val text = displayUserText(body).trim()
            if (text.isEmpty() && images.isEmpty()) return items
            // From the anchor on: the anchor itself is the phone's own bubble when it sent this turn.
            if (items.drop(maxOf(at - 1, 0)).any { it is ChatItem.User && sameText(it.text, text) }) return items
            val lastUser = items.indexOfLast { it is ChatItem.User }
            if (lastUser >= 0 && sameText((items[lastUser] as ChatItem.User).text, text) &&
                items.drop(lastUser + 1).none { it is ChatItem.Assistant }) return items
            ChatItem.User(key, text, images = images, files = fileRefs(body))
        }
    }
    return items.toMutableList().apply { add(at, row) }
}

/**
 * A rebuilt list (resume after a reconnect, turn end) replaces the live one. Keep each item's key
 * from the item it replaces, otherwise the list sees every message as removed + new and
 * re-animates it (the visible flash).
 */
fun reuseKeys(old: List<ChatItem>, fresh: List<ChatItem>): List<ChatItem> {
    val used = BooleanArray(old.size)
    fun take(match: (ChatItem) -> Boolean): ChatItem? {
        val i = old.indices.firstOrNull { !used[it] && match(old[it]) } ?: return null
        used[i] = true
        return old[i]
    }
    return fresh.map { n ->
        when (n) {
            is ChatItem.Assistant -> take { it is ChatItem.Assistant && samePrefix(it.text, n.text) }?.let { n.copy(key = it.key) } ?: n
            is ChatItem.User -> take { it is ChatItem.User && it.text == n.text }?.let { n.copy(key = it.key) } ?: n
            is ChatItem.Thinking -> take { it is ChatItem.Thinking }?.let { n.copy(key = it.key) } ?: n
            is ChatItem.Tool -> (take { it is ChatItem.Tool && (n.callId != null && it.callId == n.callId || it.name == n.name) } as ChatItem.Tool?)
                ?.let { old -> n.copy(key = old.key, result = n.result ?: old.result, durationSec = n.durationSec ?: old.durationSec,
                    images = n.images.ifEmpty { old.images },
                    status = if (old.status == ToolStatus.FAILED) ToolStatus.FAILED else n.status) } ?: n
            else -> n
        }
    }.distinctBy { it.key }
}

/** Same reply at two moments (streamed so far vs. final): one is a prefix of the other. */
private fun samePrefix(a: String, b: String): Boolean {
    val x = a.trim()
    val y = b.trim()
    val k = minOf(40, x.length, y.length)
    return k > 0 && x.regionMatches(0, y, 0, k)
}

/** "14:05" when [at] is today (device time zone), "Oct 26, 14:05" this year, else "Oct 26 2025, 14:05". */
fun messageTime(at: Long, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    val a = java.util.Calendar.getInstance(zone).apply { timeInMillis = at }
    val n = java.util.Calendar.getInstance(zone).apply { timeInMillis = now }
    val sameYear = a.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR)
    val pattern = when {
        sameYear && a.get(java.util.Calendar.DAY_OF_YEAR) == n.get(java.util.Calendar.DAY_OF_YEAR) -> "HH:mm"
        sameYear -> "MMM d, HH:mm"
        else -> "MMM d yyyy, HH:mm"
    }
    return java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply { timeZone = zone }.format(java.util.Date(at))
}
