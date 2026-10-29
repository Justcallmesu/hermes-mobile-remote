package io.github.nideta231.hermesremote.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.nideta231.hermesremote.data.Delegate
import io.github.nideta231.hermesremote.data.DelegateRow
import io.github.nideta231.hermesremote.data.DelegateStatus
import io.github.nideta231.hermesremote.data.Subagent
import io.github.nideta231.hermesremote.data.SubagentActivity
import io.github.nideta231.hermesremote.data.SubagentOutput
import io.github.nideta231.hermesremote.data.SubagentTail
import io.github.nideta231.hermesremote.data.displayModelName
import io.github.nideta231.hermesremote.data.formatDuration
import kotlinx.coroutines.delay

/** What a subagent row can do: stop the child, follow its output, steer it, open its own chat. */
internal class SubagentActions(
    val stop: (String) -> Unit,
    /** Polls the child's transcript tail into the callback until the caller is cancelled. */
    val follow: suspend (String, (SubagentTail) -> Unit) -> Unit,
    /** (id, text, done: queued or not). */
    val steer: (String, String, (Boolean) -> Unit) -> Unit,
    val openSession: (String) -> Unit = {},
)

/**
 * A `delegate_task` call as the fan-out it is, like the desktop's delegate card: one bordered row
 * per child with its goal, model and elapsed time on the first line and a single ticking line of
 * its latest activity under it. Tap a row for its output, Steer and Stop.
 */
@Composable
internal fun DelegateCard(rows: List<DelegateRow>, actions: SubagentActions) {
    var confirm by remember { mutableStateOf<Subagent?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { DelegateRowView(it, actions, onStop = { s -> confirm = s }) }
    }
    confirm?.let { s -> StopDialog(s, actions) { confirm = null } }
}

/**
 * Children no delegate call on screen claimed (attached mid-run after the call scrolled out of the
 * loaded history, or a child of a child): the same rows, under the transcript.
 */
@Composable
internal fun SubagentPanel(subagents: List<Subagent>, actions: SubagentActions) {
    var confirm by remember { mutableStateOf<Subagent?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        subagents.forEach { s ->
            Box(Modifier.padding(start = (s.depth.coerceIn(0, 3) * 14).dp)) {
                DelegateRowView(Delegate.fromSubagent(s), actions, onStop = { confirm = it })
            }
        }
    }
    confirm?.let { s -> StopDialog(s, actions) { confirm = null } }
}

@Composable
private fun StopDialog(s: Subagent, actions: SubagentActions, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss,
        title = { Text("Stop this subagent?") },
        text = { Text("\"${s.goal.take(160)}\" stops now. The chat goes on and gets told it was stopped.") },
        confirmButton = { TextButton(onClick = { actions.stop(s.id); dismiss() }) { Text("Stop", color = Bad) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Keep running") } })
}

@Composable
private fun DelegateRowView(row: DelegateRow, actions: SubagentActions, onStop: (Subagent) -> Unit) {
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    var showOutput by rememberSaveable(row.key) { mutableStateOf(false) }
    var steering by rememberSaveable(row.key) { mutableStateOf(false) }
    val s = row.live
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val meta = listOfNotNull(
        row.model?.let(::displayModelName),
        row.durationSec?.takeIf { !row.running && it > 0 }?.let(::formatDuration),
        s?.toolCount?.takeIf { it > 0 }?.let { if (it == 1) "1 tool" else "$it tools" },
    ).joinToString(" · ")
    Surface(shape = RoundedCornerShape(12.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))) {
        Column(Modifier.clickable { open = !open }.shimmer(row.running, tint = muted).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusGlyph(row.status)
                Spacer(Modifier.width(8.dp))
                Text(row.goal.ifBlank { "Subagent" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = if (open) 6 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (meta.isNotEmpty()) Text("  $meta", style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(0.001f))
                if (row.running) elapsed(s)?.let { Text(" ${tick(it)}", style = MaterialTheme.typography.labelSmall, color = muted) }
                Spacer(Modifier.width(6.dp))
                Icon(Glyphs.Agent, null, tint = muted, modifier = Modifier.size(13.dp))
            }
            ActivityTicker(row, open)
            if (open) {
                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (s != null && s.running) {
                        TextButton(onClick = { showOutput = !showOutput }) { Text(if (showOutput) "Hide output" else "Output") }
                        if (s.steerable) TextButton(onClick = { steering = !steering }) { Text("Steer") }
                        TextButton(onClick = { onStop(s) }) { Text("Stop", color = Bad) }
                    }
                    s?.sessionId?.let { sid -> TextButton(onClick = { actions.openSession(sid) }) { Text("Open chat") } }
                }
                if (s != null && s.running && showOutput) SubagentOutputBox(s.id, actions.follow)
                if (s != null && s.steerable && steering) SteerField(onSend = { text, done -> actions.steer(s.id, text, done) },
                    onClose = { steering = false })
                if (s?.summary != null && !s.running) Text(s.summary, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 15.sp,
                    color = muted, modifier = Modifier.padding(start = 20.dp, top = 4.dp, end = 4.dp))
                tokens(s)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(start = 20.dp, top = 4.dp)) }
            }
        }
    }
}

/** Spinner while it works, a red alert when it failed, a green check when done, a grey dot when parked. */
@Composable
private fun StatusGlyph(status: DelegateStatus) {
    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
        when (status) {
            DelegateStatus.RUNNING, DelegateStatus.QUEUED -> StatusDot(Gold, pulse = true, size = 7)
            DelegateStatus.FAILED -> Icon(Glyphs.Alert, "Failed", tint = Bad, modifier = Modifier.size(14.dp))
            DelegateStatus.DONE -> Icon(Glyphs.CheckCircle, "Done", tint = Ok, modifier = Modifier.size(14.dp))
            DelegateStatus.DISPATCHED -> StatusDot(MaterialTheme.colorScheme.onSurfaceVariant, size = 6)
        }
    }
}

/** The newest activity line, sliding the previous one up and out (the desktop's one-line ticker). Open: the last few. */
@Composable
private fun ActivityTicker(row: DelegateRow, open: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val lines = row.activity.ifEmpty {
        if (row.status == DelegateStatus.QUEUED) listOf(SubagentActivity(SubagentActivity.Kind.PROGRESS, "Queued"))
        else if (row.running) listOf(SubagentActivity(SubagentActivity.Kind.PROGRESS, "Starting…")) else emptyList()
    }
    if (lines.isEmpty()) return
    @Composable fun line(a: SubagentActivity, maxLines: Int) = Text(a.text, style = MaterialTheme.typography.bodySmall,
        fontFamily = if (a.kind == SubagentActivity.Kind.TOOL) FontFamily.Monospace else null,
        fontStyle = if (a.kind == SubagentActivity.Kind.THINKING) FontStyle.Italic else null,
        color = if (a.error) Bad else muted, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    Column(Modifier.padding(start = 22.dp, top = 2.dp)) {
        if (open) lines.takeLast(6).forEach { line(it, 3) }
        else AnimatedContent(lines.size to lines.last(), transitionSpec = {
            (slideInVertically(tween(220)) { it } + fadeIn(tween(220))) togetherWith (slideOutVertically(tween(220)) { -it } + fadeOut(tween(160)))
        }, label = "ticker") { (_, a) -> Box(Modifier.shimmer(row.running, tint = muted)) { line(a, 1) } }
    }
}

/**
 * The child's live transcript, newest at the bottom. Polls only while this box is composed (row
 * open, child running, chat on screen) and the app is in the foreground; leaving any of those
 * cancels the poll.
 */
@Composable
private fun SubagentOutputBox(id: String, follow: suspend (String, (SubagentTail) -> Unit) -> Unit) {
    var tail by remember(id) { mutableStateOf<SubagentTail?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(id, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { follow(id) { tail = it } }
    }
    val scroll = rememberScrollState()
    // Follow the end as output grows, like a terminal.
    LaunchedEffect(tail?.text?.length) { scroll.scrollTo(scroll.maxValue) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp)) {
        Column(Modifier.padding(8.dp)) {
            if (tail?.truncated == true) Text("… earlier output cut off", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            Text(tailText(tail), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.heightIn(max = 240.dp).verticalScroll(scroll).horizontalScroll(rememberScrollState()))
        }
    }
}

/** A one-off instruction for the child; it reads it after its current tool call. */
@Composable
private fun SteerField(onSend: (String, (Boolean) -> Unit) -> Unit, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp)) {
        OutlinedTextField(text, { text = it.take(SubagentOutput.MAX_STEER_CHARS) }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Tell this subagent…") }, minLines = 1, maxLines = 5, shape = RoundedCornerShape(10.dp),
            textStyle = MaterialTheme.typography.bodyMedium)
        Row(Modifier.align(Alignment.End)) {
            TextButton(onClick = onClose) { Text("Cancel") }
            TextButton(enabled = text.isNotBlank() && !sending, onClick = {
                sending = true
                onSend(text) { queued ->
                    sending = false
                    if (queued) { text = ""; onClose() }
                }
            }) { Text("Send") }
        }
    }
}

/** What the output box shows before and while the child writes its transcript. */
internal fun tailText(tail: SubagentTail?): String = when {
    tail == null -> "Loading…"
    !tail.available -> "No output yet."
    else -> tail.text.trimEnd().ifEmpty { "No output yet." }
}

private fun tokens(s: Subagent?): String? {
    val i = s?.inputTokens; val o = s?.outputTokens
    if (i == null && o == null) return null
    return listOfNotNull(i?.let { "${compactCount(it)} in" }, o?.let { "${compactCount(it)} out" }).joinToString(" · ") + " tokens"
}

private fun compactCount(n: Int) = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()

/** Seconds since a running child started, ticking; null once it ended or when unknown. */
@Composable
private fun elapsed(s: Subagent?): Long? {
    val start = s?.startedAt ?: return null
    if (!s.running) return null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.id) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    return ((now - start) / 1000).coerceAtLeast(0)
}

/** "running · terminal · 4 tools · gpt-5 · 0:42", or "done in 1:05 · 7 tools". */
internal fun detail(s: Subagent, elapsedSec: Long?): String = listOfNotNull(
    when {
        s.running -> if (s.status == "queued") "queued" else s.lastTool ?: "running"
        s.failed -> s.status
        else -> s.durationSec?.let { "done in ${clock(it.toLong())}" } ?: "done"
    },
    s.toolCount.takeIf { it > 0 }?.let { if (it == 1) "1 tool" else "$it tools" },
    s.model,
    elapsedSec?.let(::clock),
).joinToString(" · ")

private fun clock(sec: Long): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
    else "%d:%02d".format(sec / 60, sec % 60)

/** The desktop's running timer: 42s, then 1:05. */
private fun tick(sec: Long): String = if (sec < 60) "${sec}s" else clock(sec)
