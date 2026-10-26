package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nideta231.hermesremote.data.TermKeys
import io.github.nideta231.hermesremote.data.TermLine
import io.github.nideta231.hermesremote.data.TermStatus
import io.github.nideta231.hermesremote.data.TerminalSession

private val TermBg = Color(0xFF0F1115)
private val TermFg = Color(0xFFD8DEE9)

/** pyte colour names and hex values to colours (xterm-ish palette). */
fun termColor(name: String, fallback: Color): Color = when (name) {
    "default" -> fallback
    "black" -> Color(0xFF2E3440); "red" -> Color(0xFFE06C75); "green" -> Color(0xFF98C379); "brown", "yellow" -> Color(0xFFE5C07B)
    "blue" -> Color(0xFF61AFEF); "magenta" -> Color(0xFFC678DD); "cyan" -> Color(0xFF56B6C2); "white" -> Color(0xFFDCDFE4)
    "brightblack" -> Color(0xFF5C6370); "brightred" -> Color(0xFFFF7A85); "brightgreen" -> Color(0xFFB5E890)
    "brightbrown", "brightyellow" -> Color(0xFFFFD68A); "brightblue" -> Color(0xFF82C4FF); "brightmagenta" -> Color(0xFFE09AF0)
    "brightcyan" -> Color(0xFF7FD6E0); "brightwhite" -> Color(0xFFFFFFFF)
    else -> name.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: fallback
}

private val CursorColor = Color(0xFFE8B54A)

fun lineText(line: TermLine, cursorX: Int? = null): AnnotatedString = buildAnnotatedString {
    var col = 0
    line.forEach { r ->
        var fg = termColor(r.fg, TermFg)
        var bg = termColor(r.bg, Color.Transparent)
        if (r.reverse) { val t = fg; fg = if (bg == Color.Transparent) TermBg else bg; bg = t }
        val style = SpanStyle(color = fg, background = bg, fontWeight = if (r.bold) FontWeight.Bold else null,
            fontStyle = if (r.italic) FontStyle.Italic else null, textDecoration = if (r.underline) TextDecoration.Underline else null)
        if (cursorX != null && cursorX in col until col + r.text.length) {
            val at = cursorX - col
            pushStyle(style); append(r.text.substring(0, at)); pop()
            pushStyle(SpanStyle(color = TermBg, background = CursorColor)); append(r.text[at]); pop()
            pushStyle(style); append(r.text.substring(at + 1)); pop()
        } else {
            pushStyle(style); append(r.text); pop()
        }
        col += r.text.length
    }
    if (cursorX != null && cursorX >= col) {
        append(" ".repeat(cursorX - col))
        pushStyle(SpanStyle(color = TermBg, background = CursorColor)); append(" "); pop()
    }
}

@Composable
fun TerminalScreen(open: (cols: Int, rows: Int) -> TerminalSession?, onBack: () -> Unit) {
    val fontSize = remember { mutableIntStateOf(12) }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.intValue.sp, lineHeight = (fontSize.intValue + 3).sp, color = TermFg)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    DisposableEffect(generation) { onDispose { session?.close(); session = null } }

    PageScaffold("Terminal", onBack) {
        Column(Modifier.fillMaxSize().background(TermBg).imePadding().navigationBarsPadding()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                val cell = remember(fontSize.intValue) { measurer.measure("M".repeat(10), style) }
                val cols = with(density) { (maxWidth.toPx() / (cell.size.width / 10f)).toInt() }.coerceIn(20, 300)
                val rows = with(density) { (maxHeight.toPx() / cell.size.height).toInt() }.coerceIn(5, 200)
                LaunchedEffect(generation) { if (session == null) session = open(cols, rows) }
                LaunchedEffect(cols, rows) { session?.resize(cols, rows) }
                val s = session
                if (s == null) {
                    Text("Not connected to your PC.", color = TermFg, modifier = Modifier.padding(12.dp))
                } else {
                    val st by s.state.collectAsState()
                    val list = rememberLazyListState()
                    val screen = st.screen
                    val total = screen.scrollback.size + screen.lines.size
                    LaunchedEffect(total, screen.cursorY) { if (total > 0) list.scrollToItem(total - 1) }
                    Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                            items(screen.scrollback.size) { i -> Text(lineText(screen.scrollback[i]), style = style, softWrap = false, maxLines = 1) }
                            items(screen.lines.size) { y ->
                                val cur = if (y == screen.cursorY && screen.cursorVisible && st.status == TermStatus.OPEN) screen.cursorX else null
                                Text(lineText(screen.lines[y], cur), style = style, softWrap = false, maxLines = 1)
                            }
                        }
                    }
                    if (st.status == TermStatus.CLOSED || st.status == TermStatus.FAILED) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                            Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(st.message ?: "Disconnected", style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { session?.close(); session = null; generation++ }) { Text("New shell") }
                            }
                        }
                    } else if (st.status == TermStatus.CONNECTING) {
                        Box(Modifier.align(Alignment.Center)) { TypingDots(Gold) }
                    }
                }
            }
            TerminalInput(session, fontSize)
        }
    }
}

@Composable
private fun TerminalInput(session: TerminalSession?, fontSize: androidx.compose.runtime.MutableIntState) {
    var text by remember { mutableStateOf("") }
    var ctrl by remember { mutableStateOf(false) }
    fun send(s: String) { session?.send(s) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Key("Esc") { send(TermKeys.ESC) }
                Key("Tab") { send(TermKeys.TAB) }
                Key("Ctrl", active = ctrl) { ctrl = !ctrl }
                Key("^C") { send("\u0003") }
                Key("^D") { send("\u0004") }
                Key("^L") { send("\u000c") }
                Key("←") { send(TermKeys.LEFT) }
                Key("↑") { send(TermKeys.UP) }
                Key("↓") { send(TermKeys.DOWN) }
                Key("→") { send(TermKeys.RIGHT) }
                Key("Home") { send(TermKeys.HOME) }
                Key("End") { send(TermKeys.END) }
                Key("PgUp") { send(TermKeys.PGUP) }
                Key("PgDn") { send(TermKeys.PGDN) }
                Key("⌫") { send(TermKeys.BACKSPACE) }
                Key("A-") { fontSize.intValue = (fontSize.intValue - 1).coerceAtLeast(8) }
                Key("A+") { fontSize.intValue = (fontSize.intValue + 1).coerceAtMost(20) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { new ->
                    // With Ctrl latched, the next typed letter goes as ^letter immediately.
                    if (ctrl && new.length == text.length + 1) {
                        send(TermKeys.ctrl(new.last())); ctrl = false
                    } else text = new
                }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a command") },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(text + TermKeys.ENTER); text = "" }),
                    shape = RoundedCornerShape(14.dp))
                IconButton(onClick = { send(text + TermKeys.ENTER); text = "" }) { Icon(Glyphs.Send, "Run") }
            }
        }
    }
}

@Composable
private fun Key(label: String, active: Boolean = false, onClick: () -> Unit) {
    key(label) {
        Surface(color = if (active) Gold else MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.clickable(onClick = onClick)) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 8.dp), fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
    }
}
