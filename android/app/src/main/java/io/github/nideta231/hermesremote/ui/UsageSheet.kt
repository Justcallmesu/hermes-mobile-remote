package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nideta231.hermesremote.data.ClaudeLimit
import io.github.nideta231.hermesremote.data.ClaudeUsage
import io.github.nideta231.hermesremote.data.ContextInfo
import io.github.nideta231.hermesremote.data.PcHistory
import io.github.nideta231.hermesremote.data.fmtClock
import io.github.nideta231.hermesremote.data.fmtResetsIn
import io.github.nideta231.hermesremote.data.fmtTier
import androidx.compose.material3.FilterChip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Claude's mark: a many-pointed burst, drawn so it needs no bitmap asset. */
@Composable
fun ClaudeMark(modifier: Modifier = Modifier, color: Color = ClaudeColor) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        val rays = 12
        for (i in 0 until rays) {
            val a = (2 * PI * i / rays - PI / 2).toFloat()
            val len = if (i % 2 == 0) r else r * 0.78f
            val inner = r * 0.18f
            drawLine(color, Offset(c.x + inner * cos(a), c.y + inner * sin(a)), Offset(c.x + len * cos(a), c.y + len * sin(a)),
                strokeWidth = r * 0.22f, cap = StrokeCap.Round)
        }
    }
}

/** Colour for a context slice; follows the desktop's order (system, tools, rules, skills, mcp, subagents, memory, chat). */
private fun sliceColor(id: String, accent: Color): Color = when (id) {
    "system_prompt" -> Color(0xFF8E8CF0)
    "tool_definitions" -> Color(0xFF5B8DEF)
    "rules" -> Color(0xFF4FC1C9)
    "skills" -> Color(0xFF3DDC97)
    "mcp" -> Color(0xFFB57EDC)
    "subagent_definitions" -> Color(0xFFE08ACB)
    "memory" -> Color(0xFFF5A524)
    "conversation" -> accent
    else -> Color(0xFF9AA0A6)
}

private fun tokens(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> "$n"
}

/**
 * The usage popup behind the top-bar badge: Claude plan limits (session + week), the open chat's
 * context window split by what fills it, and Compact now with an optional focus topic.
 * Claude-only for now; other sources (ChatGPT, OpenCode...) get their own section later.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UsageSheet(
    usage: ClaudeUsage,
    hasChat: Boolean,
    busy: Boolean,
    loadContext: suspend () -> ContextInfo?,
    loadHistory: suspend (String) -> PcHistory? = { null },
    onRefresh: () -> Unit,
    onCompact: (String) -> Unit,
    onOpenMonitor: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val accent = Gold
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    var ctx by remember { mutableStateOf<ContextInfo?>(null) }
    var ctxError by remember { mutableStateOf<String?>(null) }
    var ctxLoading by remember { mutableStateOf(hasChat) }
    var focus by remember { mutableStateOf("") }
    var range by remember { mutableStateOf("24h") }
    var history by remember { mutableStateOf<PcHistory?>(null) }
    var historyError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(range, usage.checkedAt) {
        runCatching { loadHistory(range) }.onSuccess { history = it; historyError = null }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; historyError = it.message ?: "Could not load history" }
    }
    LaunchedEffect(hasChat) {
        if (!hasChat) return@LaunchedEffect
        ctxLoading = true
        runCatching { loadContext() }.onSuccess { ctx = it; ctxError = null }.onFailure { ctxError = it.message ?: "Could not load" }
        ctxLoading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            // ---- Claude plan
            Row(verticalAlignment = Alignment.CenterVertically) {
                ClaudeMark(Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Claude" + (usage.plan?.let { " · " + it.replaceFirstChar { ch -> ch.uppercase() } } ?: ""),
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    fmtTier(usage.tier)?.let { Text("Rate tier: $it", style = MaterialTheme.typography.labelSmall, color = onVariant) }
                }
                Icon(Glyphs.Refresh, "Check now", tint = onVariant, modifier = Modifier.size(32.dp).clip(CircleShape)
                    .clickable(onClick = onRefresh).padding(6.dp))
            }
            val limits = listOfNotNull(usage.session?.let { ClaudeLimit("Session (5 hours)", it, usage.sessionResetsAt, true) },
                usage.weekly?.let { ClaudeLimit("Week", it, usage.weeklyResetsAt, true) }) +
                usage.limits.filter { l -> !l.label.contains("session", true) && !l.label.contains("week", true) }
            limits.forEach { l -> Spacer(Modifier.height(12.dp)); ClaudeLimitRow(l, 8.dp) }
            ClaudeDetails(usage)
            // Usage over time, tap the chart for the exact % at a moment.
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("History", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                listOf("24h", "7d", "30d").forEach { r ->
                    FilterChip(colors = themedChip(), selected = range == r, onClick = { range = r }, label = { Text(r) },
                        modifier = Modifier.padding(start = 6.dp))
                }
            }
            val h = history
            when {
                h != null && h.ts.size >= 2 -> {
                    val pct: (Double) -> String = { "%.0f%%".format(it) }
                    LineChart(listOf(ChartSeries("Session", h["session"], ClaudeColor, pct), ChartSeries("Weekly", h["weekly"], MemColor, pct)),
                        h.ts, 110.dp, Modifier.fillMaxWidth().padding(top = 6.dp))
                    val peak = h["session"].filterNotNull().maxOrNull()
                    if (peak != null) Text("Peak session %.0f%% in this range · %d checks".format(peak, h.ts.size),
                        style = MaterialTheme.typography.labelSmall, color = onVariant)
                }
                historyError != null -> Text(historyError!!, style = MaterialTheme.typography.bodySmall, color = onVariant)
                else -> Text("Not enough checks yet (the PC checks every 5 minutes).", style = MaterialTheme.typography.bodySmall, color = onVariant)
            }
            if (!usage.ok && usage.error != null) {
                Text("Last check failed: ${usage.error}", style = MaterialTheme.typography.labelSmall, color = Warn,
                    modifier = Modifier.padding(top = 6.dp))
            }

            // ---- this chat's context
            Spacer(Modifier.height(22.dp))
            Text("This chat's context", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            when {
                !hasChat -> Text("Open a chat to see its context.", style = MaterialTheme.typography.bodySmall, color = onVariant)
                ctxLoading && ctx == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall, color = onVariant)
                ctxError != null && ctx == null -> Text(ctxError!!, style = MaterialTheme.typography.bodySmall, color = Warn)
                else -> ctx?.let { c -> ContextBreakdown(c, accent, track, onVariant) }
            }

            // ---- compact
            if (hasChat) {
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(focus, { focus = it }, singleLine = true, shape = RoundedCornerShape(14.dp),
                    label = { Text("Focus topic (optional)") },
                    placeholder = { Text("e.g. the login bug fix", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Button(onClick = { onCompact(focus.trim()); onDismiss() }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)) {
                    Text(if (busy) "Wait for the reply to finish" else "Compact now")
                }
                Text("Summarises older messages so the chat has room again. The focus topic tells it what to keep in detail.",
                    style = MaterialTheme.typography.labelSmall, color = onVariant, modifier = Modifier.padding(top = 6.dp))
            }
            onOpenMonitor?.let {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { onDismiss(); it() }, modifier = Modifier.align(Alignment.End)) { Text("Open System Monitor") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContextBreakdown(c: ContextInfo, accent: Color, track: Color, onVariant: Color) {
    Row {
        Text("${tokens(c.used)} of ${tokens(c.max)} tokens", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("${c.percent}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
            color = if (c.percent >= 85) Bad else if (c.percent >= 60) Warn else MaterialTheme.colorScheme.onSurface)
    }
    Spacer(Modifier.height(6.dp))
    // One stacked bar: each slice's share of the whole window, the rest is free space.
    val max = c.max.coerceAtLeast(1).toFloat()
    val total = c.slices.sumOf { it.tokens }.coerceAtLeast(1)
    // Slice tokens are estimates; scale them to the measured used size so the bar matches the %.
    val scale = if (c.used > 0) c.used.toFloat() / total else 1f
    Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(track)) {
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            var x = 0f
            c.slices.forEach { s ->
                val w = size.width * (s.tokens * scale / max).coerceIn(0f, 1f)
                drawRect(sliceColor(s.id, accent), topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(w.coerceAtMost(size.width - x), size.height))
                x += w
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        c.slices.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(sliceColor(s.id, accent)))
                Spacer(Modifier.width(5.dp))
                Text("${s.label} ${tokens(s.tokens)} · ${"%.0f".format(s.tokens * 100.0 / total)}%",
                    style = MaterialTheme.typography.labelMedium, color = onVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(track))
            Spacer(Modifier.width(5.dp))
            Text("Free ${tokens((c.max - c.used).coerceAtLeast(0))}", style = MaterialTheme.typography.labelMedium, color = onVariant)
        }
    }
    if (c.model.isNotBlank()) Text(c.model, style = MaterialTheme.typography.labelSmall, color = onVariant, modifier = Modifier.padding(top = 6.dp))
}


/** "Fri 30 Oct 06:00" from an ISO-8601 instant, in the phone's zone; empty when unknown. */
internal fun fmtResetAt(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val at = runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { java.time.Instant.parse(iso) }.getOrNull() ?: return ""
    return fmtClock(at.epochSecond, withDay = true)
}

/** One plan limit: label, %, bar, and when it resets (relative and the exact time). */
@Composable
fun ClaudeLimitRow(l: ClaudeLimit, barHeight: androidx.compose.ui.unit.Dp = 6.dp) {
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val tone = when {
        l.severity == "critical" || l.severity == "exceeded" || l.percent >= 90 -> Bad
        l.severity == "warning" || l.percent >= 70 -> Warn
        else -> ClaudeColor
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(l.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (!l.active) Text("not in use", style = MaterialTheme.typography.labelSmall, color = onVariant,
            modifier = Modifier.padding(end = 8.dp))
        Text("%.0f%%".format(l.percent), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = tone)
    }
    LinearProgressIndicator(progress = { (l.percent / 100.0).coerceIn(0.0, 1.0).toFloat() }, color = tone, trackColor = track,
        strokeCap = StrokeCap.Round, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).height(barHeight))
    val rel = fmtResetsIn(l.resetsAt)
    val abs = fmtResetAt(l.resetsAt)
    if (rel.isNotBlank()) {
        Text(rel + (if (abs.isNotBlank()) " · $abs" else "") + " · %.0f%% left".format((100 - l.percent).coerceAtLeast(0.0)),
            style = MaterialTheme.typography.labelSmall, color = onVariant)
    }
}

/** Plan tier, lock state, extra-usage credits and when it was last checked. */
@Composable
fun ClaudeDetails(c: ClaudeUsage) {
    val onVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        c.lockedReason?.let { Text("Locked: $it", style = MaterialTheme.typography.labelMedium, color = Bad) }
        c.extra?.let { e ->
            val money: (Double?) -> String = { v -> v?.let { "%.2f %s".format(it, e.currency) } ?: "--" }
            val text = when {
                !e.enabled -> "Extra usage: off" + (e.disabledReason?.let { " ($it)" } ?: "")
                e.limit != null -> "Extra usage: ${money(e.used)} of ${money(e.limit)}" + (e.percent?.let { " · %.0f%%".format(it) } ?: "")
                else -> "Extra usage: ${money(e.used)} spent"
            }
            Text(text + if (e.limitReached) " · limit reached" else "", style = MaterialTheme.typography.labelSmall,
                color = if (e.limitReached) Bad else onVariant)
        }
        c.checkedAt?.let { Text("Checked ${fmtClock(it)}", style = MaterialTheme.typography.labelSmall, color = onVariant) }
    }
}
