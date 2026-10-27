package io.github.nideta231.hermesremote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.nideta231.hermesremote.data.BackgroundProcess

/**
 * The chat's background processes (dev servers, builds, watchers it started with
 * `background=true`), under the transcript: the command, how long it has run, its latest output on
 * tap, and Stop. Like the desktop's process stack.
 */
@Composable
internal fun ProcessPanel(processes: List<BackgroundProcess>, onStop: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(true) }
    var confirm by remember { mutableStateOf<BackgroundProcess?>(null) }
    val running = processes.count { it.running }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.shimmer(running > 0)
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (running > 0) Gold else MaterialTheme.colorScheme.onSurfaceVariant, pulse = running > 0, size = 8)
                Spacer(Modifier.width(10.dp))
                Text(processHeader(processes), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                Icon(Glyphs.Chevron, if (open) "Hide background processes" else "Show background processes",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).rotate(rot))
            }
            if (open) processes.forEach { ProcessRow(it, onStop = { confirm = it }) }
        }
    }
    confirm?.let { p ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Stop this process?") },
            text = { Text("\"${p.command.take(160)}\" is killed on the PC. The chat gets told it ended.") },
            confirmButton = { TextButton(onClick = { onStop(p.id); confirm = null }) { Text("Stop", color = Bad) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep running") } })
    }
}

@Composable
private fun ProcessRow(p: BackgroundProcess, onStop: () -> Unit) {
    var expanded by rememberSaveable(p.id) { mutableStateOf(false) }
    val color = when {
        p.running -> Gold
        p.failed -> Bad
        else -> Ok
    }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }
        .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color, pulse = p.running, size = 6)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.command.ifBlank { "Process" }, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis)
                Text(processDetail(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (p.running) TextButton(onClick = onStop) { Text("Stop", color = Bad) }
        }
        if (expanded) {
            val out = p.output.ifBlank { "No output yet." }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, end = 8.dp)) {
                // Newest output at the bottom; the box starts scrolled there.
                val v = rememberScrollState(Int.MAX_VALUE)
                Text(out.takeLast(4000), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(max = 240.dp).verticalScroll(v).horizontalScroll(rememberScrollState()).padding(8.dp))
            }
        }
    }
}

internal fun processHeader(list: List<BackgroundProcess>): String {
    val running = list.count { it.running }
    val n = list.size
    return when {
        running == 0 -> if (n == 1) "1 background process ended" else "$n background processes ended"
        running == n -> if (n == 1) "1 background process running" else "$n background processes running"
        else -> "$running of $n background processes running"
    }
}

/** "running · 4:12 · pid 1234", or "exited 1 · ~/app". */
internal fun processDetail(p: BackgroundProcess): String = listOfNotNull(
    when {
        p.running -> "running"
        p.exitCode != null -> "exited ${p.exitCode}"
        else -> p.status
    },
    p.uptimeSec?.takeIf { p.running }?.let(::processClock),
    p.pid?.takeIf { p.running }?.let { "pid $it" },
    p.cwd?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
).joinToString(" · ")

private fun processClock(sec: Long): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec % 3600 / 60, sec % 60)
    else "%d:%02d".format(sec / 60, sec % 60)
