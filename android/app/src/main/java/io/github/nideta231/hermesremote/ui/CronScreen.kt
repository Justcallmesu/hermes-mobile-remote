package io.github.nideta231.hermesremote.ui

import android.text.format.DateUtils
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nideta231.hermesremote.data.CronJob
import io.github.nideta231.hermesremote.data.CronRun
import io.github.nideta231.hermesremote.data.CronState

class CronActions(
    val refresh: () -> Unit,
    val loadRuns: (String) -> Unit,
    val pause: (CronJob) -> Unit,
    val resume: (CronJob) -> Unit,
    val runNow: (CronJob) -> Unit,
    /** (name, schedule, prompt, onDone(error or null)). */
    val create: (String, String, String, (String?) -> Unit) -> Unit = { _, _, _, _ -> },
)

/** The scheduled (cron) jobs of the Hermes profile in use. New jobs can be made here; editing stays on the PC. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(state: CronState, profile: String?, actions: CronActions, onBack: () -> Unit) {
    LaunchedEffect(profile) { actions.refresh() }
    PageScaffold("Scheduled", onBack) {
        PullToRefreshBox(isRefreshing = state.loading, onRefresh = actions.refresh, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var creating by remember { mutableStateOf(false) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull("Cron jobs", profile?.let { "profile $it" }).joinToString(" · ") +
                        ". Edit and delete them on the PC.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    if (!state.unsupported) TextButton(onClick = { creating = true }) {
                        Icon(Glyphs.Plus, null, tint = Gold, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New job", color = Gold)
                    }
                }
                if (creating) NewJobDialog(actions.create) { creating = false }
                when {
                    state.unsupported -> Note("Your PC runs an older bridge without scheduled jobs. Update it: run install.sh on the PC.")
                    state.error != null -> Note(state.error, Bad)
                    state.jobs.isEmpty() && !state.loading -> Note("No scheduled jobs in this profile.")
                }
                state.jobs.forEach { job ->
                    JobCard(job, state.runs[job.id], busy = job.id in state.busy, actions)
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

/** Schedules Hermes understands, offered as one-tap examples. */
private val scheduleExamples = listOf("every 30m", "every day 8am", "weekdays at 9am", "every monday 9am", "0 20 * * *")

@Composable
private fun NewJobDialog(create: (String, String, String, (String?) -> Unit) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var schedule by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!saving) onClose() },
        title = { Text("New scheduled job") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                androidx.compose.material3.OutlinedTextField(schedule, { schedule = it }, label = { Text("Schedule") },
                    placeholder = { Text("every day 8am") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    scheduleExamples.forEach { ex ->
                        androidx.compose.material3.SuggestionChip(onClick = { schedule = ex }, label = { Text(ex) })
                    }
                }
                androidx.compose.material3.OutlinedTextField(prompt, { prompt = it }, label = { Text("What Hermes should do") },
                    placeholder = { Text("Summarise today's unread email") }, minLines = 3, maxLines = 8,
                    modifier = Modifier.fillMaxWidth())
                Text("Runs with this profile's tools; the result is saved on the PC and shows in this page's runs.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && schedule.isNotBlank() && prompt.isNotBlank(), onClick = {
                saving = true; error = null
                create(name, schedule, prompt) { err -> saving = false; if (err == null) onClose() else error = err }
            }) { Text(if (saving) "Saving…" else "Create", color = Gold) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onClose) { Text("Cancel") } },
    )
}

@Composable
private fun Note(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, color = color, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp))
}

@Composable
private fun JobCard(job: CronJob, runs: List<CronRun>?, busy: Boolean, actions: CronActions) {
    var open by remember(job.id) { mutableStateOf(false) }
    Panel(Modifier.animateContentSize()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open; if (open && runs == null) actions.loadRuns(job.id) }
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(when { job.paused -> Warn; job.failing -> Bad; else -> Ok }, pulse = busy)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(job.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary(job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Glyphs.Chevron, if (open) "Hide runs" else "Show runs", modifier = Modifier.size(16.dp))
        }
        job.lastError?.let {
            Text(it, color = Bad, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp))
        }
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(enabled = !busy, onClick = { actions.runNow(job) }) { Text("Run now", color = Gold) }
            if (job.paused) TextButton(enabled = !busy, onClick = { actions.resume(job) }) { Text("Resume") }
            else if (job.state == "scheduled") TextButton(enabled = !busy, onClick = { actions.pause(job) }) { Text("Pause") }
        }
        if (open) {
            PanelDivider()
            when {
                runs == null -> Row(Modifier.padding(16.dp)) { TypingDots() }
                runs.isEmpty() -> Note("No runs yet.")
                else -> runs.forEach { RunRow(it) }
            }
        }
    }
}

@Composable
private fun RunRow(run: CronRun) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(listOfNotNull(run.startedAt?.let(::ago), run.title).joinToString(" · "),
            style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        run.preview?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "every day at 08:00 · next in 3 h · last 5 h ago, ok", or "Paused" first when it is. */
private fun summary(job: CronJob): String = listOfNotNull(
    if (job.paused) "Paused" else null,
    job.schedule.takeIf { it.isNotBlank() },
    job.nextRunAt?.takeIf { !job.paused }?.let { "next ${ago(it)}" },
    job.lastRunAt?.let { "last ${ago(it)}" + (job.lastStatus?.let { s -> ", $s" } ?: "") },
).joinToString(" · ")

private fun ago(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
