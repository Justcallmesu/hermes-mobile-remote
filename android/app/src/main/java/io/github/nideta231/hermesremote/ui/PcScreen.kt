package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nideta231.hermesremote.data.ClaudeUsage
import io.github.nideta231.hermesremote.data.PcHistory
import io.github.nideta231.hermesremote.data.PcProcess
import io.github.nideta231.hermesremote.data.PcState
import io.github.nideta231.hermesremote.data.ProcessSort
import io.github.nideta231.hermesremote.data.fmtMem
import io.github.nideta231.hermesremote.data.fmtRate
import io.github.nideta231.hermesremote.data.fmtResetsIn
import io.github.nideta231.hermesremote.data.fmtUptime

val CpuColor = Color(0xFF3DDC97)
val MemColor = Color(0xFF5B8DEF)
val TempColor = Color(0xFFF5A524)
val ClaudeColor = Color(0xFFD97757)

class PcActions(
    val watch: () -> Unit,
    val unwatch: () -> Unit,
    val refresh: () -> Unit,
    val setRange: (String) -> Unit,
    val setSort: (ProcessSort) -> Unit,
    val setQuery: (String) -> Unit,
    val stopProcess: (PcProcess, Boolean) -> Unit,
    val refreshClaude: () -> Unit,
    val openTerminal: (() -> Unit)? = null,
)

/** The PC page: what was PC Glance. Live numbers poll every 3 s while the page is open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcScreen(state: PcState, actions: PcActions, onBack: () -> Unit) {
    DisposableEffect(Unit) { actions.watch(); onDispose { actions.unwatch() } }
    var confirm by remember { mutableStateOf<PcProcess?>(null) }
    PageScaffold(state.now?.host?.takeIf { it.isNotBlank() } ?: "PC", onBack) {
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    state.unsupported -> Panel {
                        PanelRow("Update the bridge on this PC", "Stats, processes, Claude usage and the terminal need bridge 1.18 " +
                            "or newer, and `monitor = true` in its config.toml.", Glyphs.Download, iconTint = Warn)
                    }
                    state.now == null && state.error != null -> Panel { PanelRow("Can't read the PC", state.error, Glyphs.Offline, iconTint = Bad) }
                    state.now == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { TypingDots(Gold) }
                    else -> PcContent(state, actions) { confirm = it }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
    confirm?.let { p ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("Stop ${p.name}?") },
            text = { Text("PID ${p.pid}${if (p.cmd.isNotBlank()) "\n${p.cmd.take(160)}" else ""}\n\nStop asks it to quit; Force stop kills it at once (unsaved work is lost).") },
            confirmButton = { TextButton(onClick = { confirm = null; actions.stopProcess(p, false) }) { Text("Stop") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirm = null; actions.stopProcess(p, true) }) { Text("Force stop", color = Bad) }
                    TextButton(onClick = { confirm = null }) { Text("Cancel") }
                }
            })
    }
}

@Composable
private fun PcContent(state: PcState, actions: PcActions, onStop: (PcProcess) -> Unit) {
    val now = state.now ?: return
    val s = now.stats
    now.alerts.forEach { a ->
        Surface(color = Bad.copy(alpha = 0.14f), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text(a, color = Bad, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
    // Big three
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Gauge("CPU", s.cpu, "%.0f%%".format(s.cpu), "${s.cores} cores" + (s.load1?.let { " · load %.2f".format(it) } ?: ""), CpuColor, Modifier.weight(1f))
        Gauge("RAM", s.mem, "%.0f%%".format(s.mem), "%.1f / %.1f GB".format(s.memUsedGb, s.memTotalGb), MemColor, Modifier.weight(1f))
        Gauge("Temp", s.temp?.coerceIn(0.0, 100.0) ?: 0.0, s.temp?.let { "%.0f°C".format(it) } ?: "--", "CPU package", TempColor, Modifier.weight(1f))
    }
    Panel {
        StatRow("Up", fmtUptime(s.uptimeS))
        StatRow("Disk", s.disk?.let { "%.0f%%  ·  %.0f / %.0f GB".format(it, s.diskUsedGb ?: 0.0, s.diskTotalGb ?: 0.0) } ?: "--")
        StatRow("Swap", "%.0f%%  ·  %.1f GB".format(s.swap, s.swapUsedGb))
        StatRow("Network", "↓ ${fmtRate(s.rx)}   ↑ ${fmtRate(s.tx)}")
        StatRow("Disk I/O", "read ${fmtRate(s.rd)}   write ${fmtRate(s.wr)}")
    }
    if (now.corePct.isNotEmpty()) Panel {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Cores", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.size(8.dp))
            CoreBars(now.corePct)
        }
    }
    // History
    Panel {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1h", "6h", "24h", "7d", "30d", "1y").forEach { r ->
                    FilterChip(colors = themedChip(), selected = state.range == r, onClick = { actions.setRange(r) }, label = { Text(r) })
                }
            }
            Spacer(Modifier.size(8.dp))
            val h = state.history
            if (h == null || h.ts.size < 2) {
                Text("Collecting history… (the bridge samples every 10 s)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(4.dp))
            } else {
                Legend(listOf("CPU" to CpuColor, "RAM" to MemColor) + (if (h["temp"].any { it != null }) listOf("Temp °C" to TempColor) else emptyList()))
                LineChart(listOf(h["cpu"] to CpuColor, h["mem"] to MemColor, h["temp"] to TempColor), Modifier.fillMaxWidth().height(140.dp))
                Text("Peak CPU %.0f%% · peak RAM %.0f%%".format(h["cpu_max"].maxOf { it ?: 0.0 }, h["mem_max"].maxOf { it ?: 0.0 }),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    ClaudePanel(now.claude, state.claudeHistory, actions.refreshClaude)
    actions.openTerminal?.let { open -> Panel { PanelRow("Terminal", "A shell on this PC", Glyphs.Terminal, onClick = open) } }
    ProcessPanel(state, actions, onStop)
}

@Composable
private fun Gauge(label: String, pct: Double, value: String, sub: String, color: Color, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = modifier) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                val track = MaterialTheme.colorScheme.surfaceContainerHighest
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(track, 135f, 270f, false, style = stroke)
                    drawArc(color, 135f, (270f * (pct / 100.0).coerceIn(0.0, 1.0)).toFloat(), false, style = stroke)
                }
                Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(label, Modifier.width(84.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CoreBars(cores: List<Double>) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(44.dp)) {
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (cores.size - 1)) / cores.size
        cores.forEachIndexed { i, c ->
            val x = i * (w + gap)
            drawRect(track, Offset(x, 0f), androidx.compose.ui.geometry.Size(w, size.height))
            val h = size.height * (c / 100.0).coerceIn(0.0, 1.0).toFloat()
            drawRect(CpuColor, Offset(x, size.height - h), androidx.compose.ui.geometry.Size(w, h))
        }
    }
}

@Composable
private fun Legend(items: List<Pair<String, Color>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        items.forEach { (name, c) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).padding(0.dp)) { Canvas(Modifier.fillMaxSize()) { drawCircle(c) } }
                Spacer(Modifier.width(4.dp))
                Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Lines on a 0-100 scale; gaps (null) break the line. */
@Composable
fun LineChart(series: List<Pair<List<Double?>, Color>>, modifier: Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    Canvas(modifier) {
        for (k in 0..4) {
            val y = size.height * k / 4f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        series.forEach { (vals, color) ->
            if (vals.size < 2) return@forEach
            val path = Path()
            var started = false
            vals.forEachIndexed { i, v ->
                if (v == null) { started = false; return@forEachIndexed }
                val x = size.width * i / (vals.size - 1f)
                val y = size.height - size.height * (v / 100.0).coerceIn(0.0, 1.0).toFloat()
                if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
fun ClaudePanel(c: ClaudeUsage, history: PcHistory?, onRefresh: () -> Unit) {
    Panel {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Claude" + (c.plan?.let { " · " + it.replaceFirstChar { ch -> ch.uppercase() } } ?: ""),
                    color = ClaudeColor, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Glyphs.Refresh, "Check now", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).clickable(onClick = onRefresh))
            }
            if (!c.hasNumbers) {
                Text(c.error ?: "Not checked yet", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                return@Column
            }
            val limits = c.limits.ifEmpty {
                listOfNotNull(c.session?.let { io.github.nideta231.hermesremote.data.ClaudeLimit("Session (5h)", it, c.sessionResetsAt, true) },
                    c.weekly?.let { io.github.nideta231.hermesremote.data.ClaudeLimit("Weekly", it, c.weeklyResetsAt, true) })
            }
            limits.forEach { l ->
                Spacer(Modifier.size(10.dp))
                Row {
                    Text(l.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("%.0f%%".format(l.percent), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                LinearProgressIndicator(progress = { (l.percent / 100.0).coerceIn(0.0, 1.0).toFloat() },
                    color = if (l.percent >= 90) Bad else if (l.percent >= 70) Warn else ClaudeColor,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(6.dp))
                fmtResetsIn(l.resetsAt).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (history != null && history.ts.size >= 2) {
                Spacer(Modifier.size(10.dp))
                Legend(listOf("Session" to ClaudeColor, "Weekly" to MemColor))
                LineChart(listOf(history["session"] to ClaudeColor, history["weekly"] to MemColor), Modifier.fillMaxWidth().height(90.dp))
            }
            if (!c.ok && c.error != null) {
                Text("Last check failed: ${c.error}", style = MaterialTheme.typography.labelSmall, color = Warn,
                    modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ProcessPanel(state: PcState, actions: PcActions, onStop: (PcProcess) -> Unit) {
    Panel {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Processes", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("${state.processTotal}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(6.dp))
            OutlinedTextField(state.query, actions.setQuery, singleLine = true, placeholder = { Text("Filter by name, command or PID") },
                leadingIcon = { Icon(Glyphs.Search, null, Modifier.size(18.dp)) }, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ProcessSort.entries.forEach { s -> FilterChip(colors = themedChip(), selected = state.sort == s, onClick = { actions.setSort(s) }, label = { Text(s.label) }) }
            }
        }
        state.processes.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { onStop(p) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text("${p.pid}${if (p.user.isNotBlank()) " · ${p.user}" else ""}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                }
                if (p.pid in state.stopping) TypingDots(Gold) else {
                    Text("%.1f%%".format(p.cpu), Modifier.width(56.dp), color = CpuColor, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                    Text(fmtMem(p.memMb), Modifier.width(64.dp), color = MemColor, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
            }
        }
        Text("Tap a process to stop it.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}
