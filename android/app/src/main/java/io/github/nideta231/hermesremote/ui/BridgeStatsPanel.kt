package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nideta231.hermesremote.data.BridgeStats
import io.github.nideta231.hermesremote.data.Timing
import io.github.nideta231.hermesremote.data.fmtUptime
import io.github.nideta231.hermesremote.data.formatSeconds

/**
 * The bridge's own counters on the PC page: how the phone's link is doing (requests, Hermes
 * latency, approvals waiting on you). Folded to one line until tapped. Same figures as
 * `hermes-remote-bridge metrics`; they restart from zero with the bridge.
 */
@Composable
internal fun BridgeStatsPanel(s: BridgeStats) {
    var open by rememberSaveable { mutableStateOf(false) }
    Panel {
        Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bridge", style = MaterialTheme.typography.labelLarge)
                    Text(headline(s), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Glyphs.Chevron, if (open) "Hide bridge stats" else "Show bridge stats",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).rotate(if (open) 180f else 0f))
            }
            if (open) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatLine("Devices", "${s.pairedDevices} paired · ${s.connectedDevices} connected now" +
                    (if (s.terminals > 0) " · ${s.terminals} terminal(s)" else ""))
                StatLine("Requests", "${s.requests}" + (if (s.failedRequests > 0) " · ${s.failedRequests} failed" else "") +
                    timingSuffix(s.requestTiming), if (s.failedRequests > 0) Warn else null)
                StatLine("Hermes replies", if (s.rpcTiming.empty) "none yet" else "${s.rpcTiming.count}${timingSuffix(s.rpcTiming)}" +
                    (if (s.rpcErrors > 0) " · ${s.rpcErrors} error(s)" else ""), if (s.rpcErrors > 0) Warn else null)
                StatLine("Live link", "${s.connectionsSinceStart} connection(s) · ${s.framesToHermes} sent · ${s.framesFromHermes} received" +
                    (if (s.framesRefused > 0) " · ${s.framesRefused} refused" else ""))
                StatLine("Approvals", "${s.questionsAsked} asked" + (if (s.questionsOpen > 0) " · ${s.questionsOpen} waiting" else "") +
                    (if (s.questionWait.empty) "" else " · answered in ${formatSeconds(s.questionWait.meanSec)} on average"),
                    if (s.questionsOpen > 0) Gold else null)
                StatLine("Turns", "${s.turnsStarted} started · ${s.turnsFinished} finished" +
                    s.turnsByStatus.filterKeys { it != "complete" }.filterValues { it > 0 }.entries
                        .joinToString("") { " · ${it.value} ${it.key}" })
                if (s.topMethods.isNotEmpty()) StatLine("Most used", s.topMethods.joinToString(", ") { "${it.first} ${it.second}" })
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, tint: Color? = null) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = tint ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.62f))
    }
}

/** "v1.0.0 · up 3h 12m · 1 connected · Hermes 6 ms avg". */
internal fun headline(s: BridgeStats): String = listOfNotNull(
    s.version.takeIf { it.isNotBlank() }?.let { "v$it" },
    s.startedAt?.let { "up " + fmtUptime(((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0)) },
    "${s.connectedDevices} connected",
    s.rpcTiming.takeIf { !it.empty }?.let { "Hermes ${formatSeconds(it.meanSec)} avg" },
    s.questionsOpen.takeIf { it > 0 }?.let { "$it approval(s) waiting" },
).joinToString(" · ")

/** " · avg 42 ms · p95 ≤ 100 ms", or "" before anything was timed. */
internal fun timingSuffix(t: Timing): String = if (t.empty) "" else
    " · avg ${formatSeconds(t.meanSec)}" + (t.p95Sec?.let { " · p95 ≤ ${formatSeconds(it)}" } ?: "")
