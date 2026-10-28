package io.github.nideta231.hermesremote.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.nideta231.hermesremote.data.Subagent
import io.github.nideta231.hermesremote.data.SubagentOutput
import io.github.nideta231.hermesremote.data.SubagentTail
import kotlinx.coroutines.delay

/** What a subagent row can do: stop the child, follow its output, steer it. */
internal class SubagentActions(
    val stop: (String) -> Unit,
    /** Polls the child's transcript tail into the callback until the caller is cancelled. */
    val follow: suspend (String, (SubagentTail) -> Unit) -> Unit,
    /** (id, text, done: queued or not). */
    val steer: (String, String, (Boolean) -> Unit) -> Unit,
)

/**
 * The chat's child agents (delegate_task), under the transcript: what each is doing while it
 * runs, how it ended once it stops. Stopping one leaves the chat's own turn running.
 */
@Composable
internal fun SubagentPanel(subagents: List<Subagent>, actions: SubagentActions) {
    var open by rememberSaveable { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<Subagent?>(null) }
    val running = subagents.count { it.running }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.shimmer(running > 0)
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (running > 0) Gold else MaterialTheme.colorScheme.onSurfaceVariant, pulse = running > 0, size = 8)
                Spacer(Modifier.width(10.dp))
                Text(header(subagents), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                Icon(Glyphs.Chevron, if (open) "Hide subagents" else "Show subagents",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).rotate(rot))
            }
            if (open) subagents.forEach { SubagentRow(it, actions, onStop = { confirm = it }) }
        }
    }
    confirm?.let { s ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Stop this subagent?") },
            text = { Text("\"${s.goal.take(160)}\" stops now. The chat goes on and gets told it was stopped.") },
            confirmButton = { TextButton(onClick = { actions.stop(s.id); confirm = null }) { Text("Stop", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep running") } })
    }
}

@Composable
private fun SubagentRow(s: Subagent, actions: SubagentActions, onStop: () -> Unit) {
    var expanded by rememberSaveable(s.id) { mutableStateOf(false) }
    var showOutput by rememberSaveable(s.id) { mutableStateOf(false) }
    var steering by rememberSaveable(s.id) { mutableStateOf(false) }
    val color by animateColorAsState(when {
        s.running -> Gold
        s.failed -> Bad
        else -> Ok
    }, label = "subagent-status")
    Column(Modifier.fillMaxWidth().clickable(enabled = s.summary != null) { expanded = !expanded }
        .padding(start = 12.dp + (s.depth.coerceIn(0, 3) * 14).dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, pulse = s.running, size = 6)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.goal.ifBlank { "Subagent" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                Text(detail(s, elapsed(s)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (s.running) TextButton(onClick = onStop) { Text("Stop", color = Bad) }
        }
        if (s.running) Row(Modifier.padding(start = 8.dp)) {
            TextButton(onClick = { showOutput = !showOutput }) { Text(if (showOutput) "Hide output" else "Output") }
            if (s.steerable) TextButton(onClick = { steering = !steering }) { Text("Steer") }
        }
        if (s.running && showOutput) SubagentOutputBox(s.id, actions.follow)
        if (s.steerable && steering) SteerField(onSend = { text, done -> actions.steer(s.id, text, done) },
            onClose = { steering = false })
        if (expanded && s.summary != null) {
            Text(s.summary, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 8.dp))
        }
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
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, end = 8.dp)) {
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
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, end = 8.dp)) {
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

/** Seconds since a running child started, ticking; null once it ended or when unknown. */
@Composable
private fun elapsed(s: Subagent): Long? {
    val start = s.startedAt ?: return null
    if (!s.running) return null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.id) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    return ((now - start) / 1000).coerceAtLeast(0)
}

private fun header(list: List<Subagent>): String {
    val running = list.count { it.running }
    val n = list.size
    return when {
        running == 0 -> if (n == 1) "1 subagent finished" else "$n subagents finished"
        running == n -> if (n == 1) "1 subagent running" else "$n subagents running"
        else -> "$running of $n subagents running"
    }
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
