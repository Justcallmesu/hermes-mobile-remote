package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.nideta231.hermesremote.data.ProjectDetails
import kotlinx.coroutines.CancellationException

/** Everything the phone can do to a saved project, like the desktop sidebar's project menu. */
class ProjectActions(
    /** (name, folder or blank, description or blank, done(error or null)) */
    val create: (String, String, String, (String?) -> Unit) -> Unit = { _, _, _, _ -> },
    val details: suspend (String) -> ProjectDetails = { throw IllegalStateException("Not available") },
    /** (before, name, folder or blank, description, done(error or null)) */
    val save: (ProjectDetails, String, String, String, (String?) -> Unit) -> Unit = { _, _, _, _, _ -> },
    val delete: (String, (String?) -> Unit) -> Unit = { _, _ -> },
)

private const val DESCRIPTION_HINT =
    "Saved as IDEA.md in the project folder, like the desktop's New project. Markdown is fine."

@Composable
private fun ProjectFields(
    name: String, onName: (String) -> Unit, path: String, onPath: (String) -> Unit,
    description: String, onDescription: (String) -> Unit, error: String?, pathHint: String,
) {
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, onName, label = { Text("Name") }, singleLine = true,
            placeholder = { Text("My app") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(path, onPath, label = { Text("Folder on the PC (optional)") }, singleLine = true,
            placeholder = { Text("~/Workspace/my-app") }, modifier = Modifier.fillMaxWidth())
        Text(pathHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // The description is written to IDEA.md on the PC through the bridge: a gateway host has no such route.
        if (io.github.nideta231.hermesremote.data.HostFeature.PROJECT_IDEA !in LocalHiddenFeatures.current) {
            OutlinedTextField(description, onDescription, label = { Text("Description (optional)") }, minLines = 4, maxLines = 12,
                placeholder = { Text("# My app\nWhat it is, the stack, goals…") }, modifier = Modifier.fillMaxWidth())
            Text(DESCRIPTION_HINT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

/** Name, optional PC folder and optional description (IDEA.md) for `projects.create`. */
@Composable
fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (String, String, String, (String?) -> Unit) -> Unit, onCreated: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New project") },
        text = {
            ProjectFields(name, { name = it; error = null }, path, { path = it; error = null },
                description, { description = it; error = null }, error,
                "A path on the PC. New chats in this project start there. You can add it later.")
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                onCreate(name, path, description) { err -> busy = false; if (err == null) onCreated() else error = err }
            }) { Text(if (busy) "Creating…" else "Create") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Rename, change the folder, edit the description, or delete a saved project. */
@Composable
fun EditProjectDialog(projectId: String, label: String, actions: ProjectActions, onDismiss: () -> Unit) {
    var loaded by remember { mutableStateOf<ProjectDetails?>(null) }
    var name by remember { mutableStateOf(label) }
    var path by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(projectId) {
        try {
            val d = actions.details(projectId)
            loaded = d; name = d.name; path = d.path.orEmpty(); description = d.description
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            error = t.message ?: "Couldn't load the project"
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmDelete = false },
            title = { Text("Delete \"${loaded?.name ?: label}\"?") },
            text = { Text("This removes the saved project from Hermes. Its chats, files and folder on the PC stay untouched.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    actions.delete(projectId) { err -> busy = false; if (err == null) onDismiss() else { error = err; confirmDelete = false } }
                }) { Text(if (busy) "Deleting…" else "Delete", color = Bad) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Edit project") },
        text = {
            if (loaded == null && error == null) {
                CircularProgressIndicator()
            } else {
                ProjectFields(name, { name = it; error = null }, path, { path = it; error = null },
                    description, { description = it; error = null }, error,
                    "Changing it moves the project to that folder; files are not moved. Blank = no folder.")
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && loaded != null && name.isNotBlank(), onClick = {
                val before = loaded ?: return@TextButton
                busy = true
                actions.save(before, name, path, description) { err -> busy = false; if (err == null) onDismiss() else error = err }
            }) { Text(if (busy) "Saving…" else "Save") }
        },
        dismissButton = {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                androidx.compose.foundation.layout.Row {
                    TextButton(enabled = !busy && loaded != null, onClick = { confirmDelete = true }) { Text("Delete…", color = Bad) }
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                }
            }
        },
    )
}
