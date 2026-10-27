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
import io.github.nideta231.hermesremote.data.FolderListing
import io.github.nideta231.hermesremote.data.ProjectDetails
import io.github.nideta231.hermesremote.data.shortPath
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** Everything the phone can do to a saved project, like the desktop sidebar's project menu. */
class ProjectActions(
    /** (name, folder or blank, description or blank, done(error or null)) */
    val create: (String, String, String, (String?) -> Unit) -> Unit = { _, _, _, _ -> },
    val details: suspend (String) -> ProjectDetails = { throw IllegalStateException("Not available") },
    /** (before, name, folder or blank, description, done(error or null)) */
    val save: (ProjectDetails, String, String, String, (String?) -> Unit) -> Unit = { _, _, _, _, _ -> },
    val delete: (String, (String?) -> Unit) -> Unit = { _, _ -> },
    /** Lists a PC folder's sub-folders for the picker; null hides the folder button (older bridge / gateway host). */
    val browse: (suspend (String) -> FolderListing)? = null,
    val makeFolder: (suspend (String) -> FolderListing)? = null,
)

private const val DESCRIPTION_HINT =
    "Saved as IDEA.md in the project folder, like the desktop's New project. Markdown is fine."

@Composable
private fun ProjectFields(
    name: String, onName: (String) -> Unit, path: String, onPath: (String) -> Unit,
    description: String, onDescription: (String) -> Unit, error: String?, pathHint: String,
    actions: ProjectActions,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) actions.browse?.let { browse ->
        FolderPickerDialog(start = path, browse = browse, makeFolder = actions.makeFolder,
            onDismiss = { picking = false }, onPick = { picked ->
                picking = false; onPath(picked)
                // An empty name takes the folder's name, like picking a folder on the desktop.
                if (name.isBlank()) onName(picked.trimEnd('/').substringAfterLast('/'))
            })
    }
    Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, onName, label = { Text("Name") }, singleLine = true,
            placeholder = { Text("My app") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(path, onPath, label = { Text("Folder on the PC (optional)") }, singleLine = true,
            placeholder = { Text("~/Workspace/my-app") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = actions.browse?.let { { IconButton(onClick = { picking = true }) { Icon(Glyphs.Folder, "Browse folders on the PC", tint = Gold) } } })
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
fun NewProjectDialog(onDismiss: () -> Unit, actions: ProjectActions, onCreated: (hasFolder: Boolean) -> Unit) {
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
                "New chats in this project start in this folder. Without one the project is saved, " +
                    "but chats can't start in it until you add a folder.", actions)
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                busy = true
                actions.create(name, path, description) { err -> busy = false; if (err == null) onCreated(path.isNotBlank()) else error = err }
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
                    "Changing it moves the project to that folder; files are not moved. Blank = no folder.", actions)
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


/**
 * Browse the PC's folders and pick one, instead of typing a path from memory. Folders only;
 * git repositories are marked. "New folder" makes one inside the folder on screen.
 */
@Composable
fun FolderPickerDialog(start: String, browse: suspend (String) -> FolderListing, makeFolder: (suspend (String) -> FolderListing)?,
                       onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    fun open(path: String, fallbackHome: Boolean = false) {
        loading = true
        scope.launch {
            try { listing = browse(path); error = null } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // A typed path that doesn't exist (yet): start at home instead of a dead end.
                if (fallbackHome) runCatching { listing = browse("") }
                error = t.message ?: "Couldn't read that folder"
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { open(start.trim(), fallbackHome = true) }
    val l = listing
    newFolder?.let { draft ->
        AlertDialog(onDismissRequest = { newFolder = null }, title = { Text("New folder") },
            text = {
                Column {
                    Text("Inside ${l?.let { shortPath(it.path, it.home) } ?: ""}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                    OutlinedTextField(draft, { newFolder = it.replace("/", "") }, singleLine = true, label = { Text("Name") },
                        placeholder = { Text("my-app") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = draft.isNotBlank() && l != null && makeFolder != null, onClick = {
                    val parent = l ?: return@TextButton
                    val mk = makeFolder ?: return@TextButton
                    newFolder = null; loading = true
                    scope.launch {
                        try { listing = mk(parent.path.trimEnd('/') + "/" + draft.trim()); error = null } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            error = t.message ?: "Couldn't create the folder"
                        }
                        loading = false
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Cancel") } })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (l != null) {
                    // Where we are, plus quick jumps: up one level and home.
                    Text(shortPath(l.path, l.home), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = l.parent != null && !loading, onClick = { l.parent?.let { open(it) } }) {
                            Icon(Glyphs.Back, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Up")
                        }
                        TextButton(enabled = l.path != l.home && !loading, onClick = { open(l.home) }) { Text("Home") }
                        if (makeFolder != null) TextButton(enabled = !loading, onClick = { newFolder = "" }) {
                            Icon(Glyphs.Plus, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("New folder")
                        }
                    }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (loading && l == null) CircularProgressIndicator(Modifier.padding(16.dp))
                if (l != null) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    if (l.dirs.isEmpty()) item {
                        Text("No folders inside.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                    items(l.dirs, key = { it.path }) { d ->
                        Row(Modifier.fillMaxWidth().clickable(enabled = !loading) { open(d.path) }.padding(horizontal = 4.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Glyphs.Folder, null, Modifier.size(18.dp), tint = if (d.git) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            Text(d.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (d.git) Text("git", style = MaterialTheme.typography.labelSmall, color = Gold)
                        }
                    }
                    if (l.truncated) item {
                        Text("Showing the first 1000 folders.", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = l != null && !loading, onClick = { l?.let { onPick(shortPath(it.path, it.home)) } }) {
                Text("Use this folder")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
