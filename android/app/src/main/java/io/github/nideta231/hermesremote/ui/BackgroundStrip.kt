package io.github.nideta231.hermesremote.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import io.github.nideta231.hermesremote.ChatState
import io.github.nideta231.hermesremote.data.BackgroundProcess
import io.github.nideta231.hermesremote.data.ChatItem
import io.github.nideta231.hermesremote.data.Delegate
import io.github.nideta231.hermesremote.data.Subagent

/** What the strip above the composer shows: the one-line [summary] and the rows behind it. */
data class BackgroundStrip(val summary: String, val processes: List<BackgroundProcess>, val subagents: List<Subagent>)

/**
 * The background work of the open chat, or null when there is none to show. Subagents a delegate
 * card already shows stay in the chat; an open approval, clarify or secret hides the strip.
 */
fun backgroundStrip(state: ChatState, loop: Boolean, heartbeat: Boolean): BackgroundStrip? {
    if (state.waiting) return null
    val tools = state.items.filterIsInstance<ChatItem.Tool>().filter { it.name == Delegate.TOOL }
    val unclaimed = Delegate.merge(tools, state.subagents).second
    val running = state.processes.count { it.running }
    val children = unclaimed.count { it.running }
    val parts = listOfNotNull(
        "$running running".takeIf { running > 0 },
        "$children subagent${if (children == 1) "" else "s"}".takeIf { children > 0 },
        "loop".takeIf { loop },
        "heartbeat".takeIf { heartbeat },
    )
    if (parts.isEmpty()) return null
    return BackgroundStrip(parts.joinToString(" · "), state.processes, unclaimed)
}

/** One line above the composer; tap opens the process and subagent panels (height capped), tap again closes. */
@Composable
internal fun BackgroundStripBar(strip: BackgroundStrip, subagentActions: SubagentActions, stopProcess: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)
            .animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                StatusDot(Gold, pulse = true, size = 8)
                Spacer(Modifier.width(10.dp))
                Text(strip.summary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                val rot by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                Icon(Glyphs.Chevron, if (open) "Hide background work" else "Show background work",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).rotate(rot))
            }
            if (open) Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(bottom = 6.dp)) {
                if (strip.processes.isNotEmpty()) ProcessPanel(strip.processes, stopProcess)
                if (strip.subagents.isNotEmpty()) SubagentPanel(strip.subagents, subagentActions)
            }
        }
    }
}
