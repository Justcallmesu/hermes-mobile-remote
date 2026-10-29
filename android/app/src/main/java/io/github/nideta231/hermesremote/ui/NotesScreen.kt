package io.github.nideta231.hermesremote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nideta231.hermesremote.data.Note
import io.github.nideta231.hermesremote.data.NoteLine
import io.github.nideta231.hermesremote.data.NoteText
import io.github.nideta231.hermesremote.data.NotesState

class NotesActions(
    val watch: () -> Unit,
    val unwatch: () -> Unit,
    val setQuery: (String) -> Unit,
    val setLabel: (String?) -> Unit,
    val setArchived: (Boolean) -> Unit,
    val create: (Boolean, (Note) -> Unit) -> Unit,
    val edit: (String, (Note) -> Note) -> Unit,
    val closed: (String) -> Unit,
    val delete: (String) -> Unit,
    /** Start a chat with Hermes about this note (drafts the message; nothing is sent until Send). */
    val askHermes: (Note) -> Unit,
)

/** Keep's palette: soft tints on a dark theme, so text stays readable on every colour. */
@Composable
private fun noteColor(name: String): Color {
    val dark = MaterialTheme.colorScheme.surface.let { (it.red + it.green + it.blue) / 3 < 0.5f }
    val (d, l) = when (name) {
        "red" -> 0xFF77172E to 0xFFFAAFA8; "orange" -> 0xFF692B17 to 0xFFF39F76; "yellow" -> 0xFF7C4A03 to 0xFFFFF8B8
        "green" -> 0xFF264D3B to 0xFFE2F6D3; "teal" -> 0xFF0C625D to 0xFFB4DDD3; "blue" -> 0xFF256377 to 0xFFD4E4ED
        "purple" -> 0xFF472E5B to 0xFFD3BFDB; "pink" -> 0xFF6C394F to 0xFFF6E2DD; "brown" -> 0xFF4B443A to 0xFFE9E3D4
        "gray" -> 0xFF232427 to 0xFFEFEFF1
        else -> return MaterialTheme.colorScheme.surfaceContainer
    }
    return Color(if (dark) d else l)
}

private val Palette = listOf("default", "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "brown", "gray")

/**
 * Notes, like Google Keep: a two-column grid of coloured cards (pinned on top), search, labels and
 * archive. A card opens a full-screen editor; checklists tick in place. The notes are Markdown files
 * on the PC, so Hermes reads and edits the same ones (the personal-notes skill).
 */
@Composable
fun NotesScreen(state: NotesState, actions: NotesActions, onBack: () -> Unit) {
    DisposableEffect(Unit) { actions.watch(); onDispose { actions.unwatch() } }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openId?.let { id -> state.notes.firstOrNull { it.id == id } }
    AnimatedContent(open != null, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(140)) }, label = "notes") { editing ->
        if (editing && open != null) NoteEditor(open, state.labels, actions, onClose = { actions.closed(open.id); openId = null })
        else NotesGrid(state, actions, onOpen = { openId = it }, onBack = onBack)
    }
}

@Composable
private fun NotesGrid(state: NotesState, actions: NotesActions, onOpen: (String) -> Unit, onBack: () -> Unit) {
    PageScaffold(if (state.archived) "Archive" else state.label?.let { "#$it" } ?: "Notes", onBack) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                SearchField(state.query, actions.setQuery, "Search notes", Modifier.fillMaxWidth().padding(horizontal = 12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(state.label == null && !state.archived, { actions.setLabel(null) }, { Text("All") }, colors = themedChip())
                    state.labels.forEach { l -> FilterChip(state.label == l, { actions.setLabel(if (state.label == l) null else l) }, { Text("#$l") }, colors = themedChip()) }
                    FilterChip(state.archived, { actions.setArchived(!state.archived) }, { Text("Archive") }, colors = themedChip())
                }
                when {
                    state.unsupported -> Panel(Modifier.padding(16.dp)) {
                        PanelRow("Update the bridge on this PC", "Notes need bridge 1.30 or newer (Settings > Bridge > Update).", Glyphs.Download, iconTint = Warn)
                    }
                    state.notes.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(when {
                            state.error != null -> state.error
                            state.query.isNotBlank() -> "No notes match."
                            state.archived -> "Nothing archived."
                            else -> "Braindump here. Hermes can read and plan with these notes too."
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    else -> {
                        val pinned = state.notes.filter { it.pinned }
                        val others = state.notes.filterNot { it.pinned }
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val cols = if (maxWidth > 900.dp) 4 else if (maxWidth > 600.dp) 3 else 2
                            LazyVerticalStaggeredGrid(StaggeredGridCells.Fixed(cols), Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 96.dp),
                                verticalItemSpacing = 8.dp, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (pinned.isNotEmpty()) {
                                    item(span = StaggeredGridItemSpan.FullLine) { SectionLabel("Pinned", Modifier.padding(start = 6.dp, top = 4.dp)) }
                                    items(pinned, key = { it.id }) { n -> NoteCard(n, actions, Modifier.animateItem()) { onOpen(n.id) } }
                                    if (others.isNotEmpty()) item(span = StaggeredGridItemSpan.FullLine) {
                                        SectionLabel("Others", Modifier.padding(start = 6.dp, top = 8.dp))
                                    }
                                }
                                items(others, key = { it.id }) { n -> NoteCard(n, actions, Modifier.animateItem()) { onOpen(n.id) } }
                            }
                        }
                    }
                }
            }
            if (!state.archived && !state.unsupported) Row(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, shadowElevation = 4.dp) {
                    IconButton(onClick = { actions.create(true) { onOpen(it.id) } }) { Icon(Glyphs.Check, "New list") }
                }
                ExtendedFloatingActionButton(onClick = { actions.create(false) { onOpen(it.id) } }, containerColor = Gold,
                    contentColor = MaterialTheme.colorScheme.onPrimary, icon = { Icon(Glyphs.Plus, null) }, text = { Text("Note") })
            }
        }
    }
}

@Composable
private fun NoteCard(n: Note, actions: NotesActions, modifier: Modifier, onOpen: () -> Unit) {
    val bg = noteColor(n.color)
    Surface(color = bg, shape = RoundedCornerShape(14.dp),
        border = if (n.color == "default") BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) else null,
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp)) {
            if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 4.dp))
            val lines = NoteText.lines(n.body)
            var shown = 0
            lines.forEachIndexed { i, l ->
                if (shown >= 9) return@forEachIndexed
                when (l) {
                    is NoteLine.Check -> if (!l.done) {
                        shown++
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.clickable {
                            actions.edit(n.id) { it.copy(body = NoteText.toggle(it.body, i)) } }) {
                            Text("☐ ", style = MaterialTheme.typography.bodySmall)
                            Text(l.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    is NoteLine.Text -> if (l.text.isNotBlank()) {
                        shown++
                        Text(l.text, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            val (done, total) = NoteText.progress(n.body)
            if (done > 0) Text("+ $done done of $total", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            if (n.labels.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                n.labels.take(3).forEach { l ->
                    Text(l, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        modifier = Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun NoteEditor(n: Note, allLabels: List<String>, actions: NotesActions, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    var palette by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var labelDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val bg = noteColor(n.color)
    val text = MaterialTheme.colorScheme.onSurface
    fun edit(f: (Note) -> Note) = actions.edit(n.id, f)
    Column(Modifier.fillMaxSize().background(bg).statusBarsPadding().imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Glyphs.Back, "Back") }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { edit { it.copy(pinned = !it.pinned) } }) { Icon(Glyphs.Pin, if (n.pinned) "Unpin" else "Pin", tint = if (n.pinned) Gold else text) }
            IconButton(onClick = { edit { it.copy(archived = !it.archived) }; onClose() }) { Icon(Glyphs.Download, if (n.archived) "Unarchive" else "Archive") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Glyphs.More, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Ask Hermes about this") }, { menu = false; actions.askHermes(n) }, leadingIcon = { Icon(Glyphs.Spark, null, Modifier.size(18.dp)) })
                    DropdownMenuItem({ Text(if (NoteText.isChecklist(n.body)) "Plain text" else "Checkboxes") },
                        { menu = false; edit { it.copy(body = NoteText.toggleChecklist(it.body)) } }, leadingIcon = { Icon(Glyphs.Check, null, Modifier.size(18.dp)) })
                    DropdownMenuItem({ Text("Labels") }, { menu = false; labelDialog = true }, leadingIcon = { Icon(Glyphs.Filter, null, Modifier.size(18.dp)) })
                    DropdownMenuItem({ Text("Delete", color = Bad) }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Glyphs.Close, null, Modifier.size(18.dp), tint = Bad) })
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            BasicTextField(n.title, { t -> edit { it.copy(title = t) } }, textStyle = MaterialTheme.typography.titleLarge.copy(color = text),
                cursorBrush = SolidColor(Gold), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                decorationBox = { inner -> if (n.title.isEmpty()) Text("Title", style = MaterialTheme.typography.titleLarge, color = text.copy(alpha = 0.45f)); inner() })
            if (NoteText.isChecklist(n.body)) ChecklistEditor(n, ::edit)
            else BasicTextField(n.body, { b -> edit { it.copy(body = b) } }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = text),
                cursorBrush = SolidColor(Gold), modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                decorationBox = { inner -> if (n.body.isEmpty()) Text("Note", style = MaterialTheme.typography.bodyLarge, color = text.copy(alpha = 0.45f)); inner() })
            if (n.labels.isNotEmpty()) Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                n.labels.forEach { l -> Text("#$l", style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.background(text.copy(alpha = 0.08f), RoundedCornerShape(8.dp)).clickable { labelDialog = true }.padding(horizontal = 8.dp, vertical = 4.dp)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { palette = !palette }) { Box(Modifier.size(20.dp).background(Gold, CircleShape)) }
            if (palette) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Palette.forEach { c ->
                    Box(Modifier.size(30.dp).background(noteColor(c), CircleShape)
                        .border(if (n.color == c) 2.dp else 1.dp, if (n.color == c) Gold else text.copy(alpha = 0.25f), CircleShape)
                        .clickable { edit { it.copy(color = c) } })
                }
            } else Text("Edited " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                .format(java.util.Date((n.updated * 1000).toLong())), style = MaterialTheme.typography.labelSmall, color = text.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f), maxLines = 1)
        }
    }
    if (labelDialog) LabelDialog(n.labels, allLabels, onDone = { ls -> labelDialog = false; edit { it.copy(labels = ls) } }, onDismiss = { labelDialog = false })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete this note?") },
        text = { Text("It is removed from the PC too.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; actions.delete(n.id); onClose() }) { Text("Delete", color = Bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

/** Checklist editing: tick, edit, add, remove; done items sink to the bottom like Keep. */
@Composable
private fun ChecklistEditor(n: Note, edit: ((Note) -> Note) -> Unit) {
    val lines = NoteText.lines(n.body)
    val text = MaterialTheme.colorScheme.onSurface
    fun put(f: (MutableList<NoteLine>) -> Unit) = edit { note -> note.copy(body = NoteText.join(NoteText.lines(note.body).toMutableList().also(f))) }
    val order = lines.indices.sortedBy { (lines[it] as? NoteLine.Check)?.done == true }
    order.forEach { i ->
        when (val l = lines[i]) {
            is NoteLine.Check -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(l.done, { d -> put { it[i] = l.copy(done = d) } })
                BasicTextField(l.text, { t -> put { it[i] = l.copy(text = t.replace("\n", "")) } },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (l.done) text.copy(alpha = 0.5f) else text,
                        textDecoration = if (l.done) TextDecoration.LineThrough else null),
                    cursorBrush = SolidColor(Gold), modifier = Modifier.weight(1f))
                IconButton(onClick = { put { it.removeAt(i) } }) { Icon(Glyphs.Close, "Remove item", Modifier.size(16.dp)) }
            }
            is NoteLine.Text -> if (l.text.isNotBlank()) Text(l.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
    TextButton(onClick = { put { ls -> ls.add(ls.indexOfLast { it is NoteLine.Check && !it.done } + 1, NoteLine.Check("", false)) } }) {
        Icon(Glyphs.Plus, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("List item")
    }
}

@Composable
private fun LabelDialog(current: List<String>, all: List<String>, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var picked by remember { mutableStateOf(current) }
    var new by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Labels") }, text = {
        Column {
            OutlinedTextField(new, { new = it.replace(" ", "-").take(40) }, singleLine = true, placeholder = { Text("New label") },
                trailingIcon = { if (new.isNotBlank()) TextButton(onClick = { picked = (picked + new).distinct(); new = "" }) { Text("Add") } })
            (all + picked).distinct().forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable {
                    picked = if (l in picked) picked - l else picked + l }) {
                    Checkbox(l in picked, { picked = if (it) picked + l else picked - l })
                    Text(l)
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { onDone((if (new.isNotBlank()) picked + new else picked).distinct()) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
