package io.github.nideta231.hermesremote.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nideta231.hermesremote.data.TermKeys
import io.github.nideta231.hermesremote.data.TermLine
import io.github.nideta231.hermesremote.data.TermPoint
import io.github.nideta231.hermesremote.data.TermSelection
import io.github.nideta231.hermesremote.data.TermStatus
import io.github.nideta231.hermesremote.data.TerminalSession
import io.github.nideta231.hermesremote.data.selectedText
import io.github.nideta231.hermesremote.data.wordAt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

// Konsole's default "Breeze" colour scheme, so the phone looks like the terminal on the PC.
private val TermBg = Color(0xFF232627)
private val TermFg = Color(0xFFFCFCFC)
private val CursorColor = Color(0xFFFCFCFC)
private val SelectionColor = Color(0x663DAEE9)
private val HandleColor = Color(0xFF3DAEE9)
private val KeysBg = Color(0xFF1B1E20)

/** pyte colour names and hex values to colours (Konsole Breeze palette). */
fun termColor(name: String, fallback: Color): Color = when (name) {
    "default" -> fallback
    "black" -> Color(0xFF232627); "red" -> Color(0xFFED1515); "green" -> Color(0xFF11D116); "brown", "yellow" -> Color(0xFFF67400)
    "blue" -> Color(0xFF1D99F3); "magenta" -> Color(0xFF9B59B6); "cyan" -> Color(0xFF1ABC9C); "white" -> Color(0xFFFCFCFC)
    "brightblack" -> Color(0xFF7F8C8D); "brightred" -> Color(0xFFC0392B); "brightgreen" -> Color(0xFF1CDC9A)
    "brightbrown", "brightyellow" -> Color(0xFFFDBC4B); "brightblue" -> Color(0xFF3DAEE9); "brightmagenta" -> Color(0xFF8E44AD)
    "brightcyan" -> Color(0xFF16A085); "brightwhite" -> Color(0xFFFFFFFF)
    else -> name.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: fallback
}

/** One row as styled text; [cursorX] draws a block cursor on that column. */
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

private enum class Gesture { TAP, DRAG, MULTI, LONG }

/**
 * A shell on the PC, drawn and driven like Termux: the screen is a character grid, typing goes
 * straight to the shell (no text field), swipe scrolls the scrollback, pinch zooms, long press
 * selects a word (drag to extend, or drag the handles) with Copy / Paste, and two extra-key rows
 * give Esc, Ctrl, Alt, Tab and the arrows.
 */
@Composable
fun TerminalScreen(open: (cols: Int, rows: Int) -> TerminalSession?, onBack: () -> Unit) {
    var fontSize by rememberSaveable { mutableFloatStateOf(12f) }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, lineHeight = (fontSize * 1.25f).sp, color = TermFg)
    val measurer = rememberTextMeasurer(cacheSize = 256)
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var generation by remember { mutableIntStateOf(0) }
    DisposableEffect(generation) { onDispose { session?.close(); session = null } }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf<TermInputView?>(null) }
    var selection by remember { mutableStateOf<TermSelection?>(null) }
    val st = session?.state?.collectAsState()?.value
    // Rows scrolled back from the bottom; 0 follows the output.
    var back by remember { mutableFloatStateOf(0f) }
    fun send(s: String) { session?.send(s) }
    fun paste() {
        val text = clipboard.getText()?.text?.takeIf { it.isNotEmpty() } ?: return
        back = 0f; selection = null
        send(TermKeys.paste(text, st?.screen?.bracketedPaste == true))
    }

    Column(Modifier.fillMaxSize().background(TermBg).statusBarsPadding().imePadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Glyphs.Back, "Back", tint = TermFg) }
            Text(st?.screen?.title?.takeIf { it.isNotBlank() } ?: "Terminal", color = TermFg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { fontSize = (fontSize - 1f).coerceAtLeast(7f) }) { Text("A−", color = TermFg) }
            TextButton(onClick = { fontSize = (fontSize + 1f).coerceAtMost(28f) }) { Text("A+", color = TermFg) }
            TextButton(onClick = { paste() }) { Text("Paste", color = TermFg) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 4.dp)) {
            val cell = remember(fontSize) { measurer.measure("M".repeat(20), style).size.let { Size(it.width / 20f, it.height.toFloat()) } }
            val cols = with(density) { (maxWidth.toPx() / cell.width).toInt() }.coerceIn(20, 400)
            val rows = with(density) { (maxHeight.toPx() / cell.height).toInt() }.coerceIn(5, 300)
            LaunchedEffect(generation) { if (session == null) { session = open(cols, rows); delay(150); input?.showKeyboard() } }
            LaunchedEffect(cols, rows) { session?.resize(cols, rows) }

            val screen = st?.screen
            val all = remember(screen) { screen?.all.orEmpty() }
            val shown = screen?.rows ?: rows
            val maxBack = (all.size - shown).coerceAtLeast(0)
            var lastScrollback by remember { mutableIntStateOf(0) }
            LaunchedEffect(screen?.scrollback?.size) {
                val n = screen?.scrollback?.size ?: 0
                if (back > 0f && n > lastScrollback) back += (n - lastScrollback)  // keep the rows being read in place
                lastScrollback = n
            }
            val top = (all.size - shown - back.coerceIn(0f, maxBack.toFloat()).roundToInt()).coerceAtLeast(0)

            val latest = rememberUpdatedState(Triple(all, top, cell))
            val maxBackNow = rememberUpdatedState(maxBack)
            val handleR = with(density) { 24.dp.toPx() }
            fun pointAt(o: Offset): TermPoint {
                val (_, t, c) = latest.value
                return TermPoint((t + floor(o.y / c.height).toInt()).coerceAtLeast(0), floor(o.x / c.width).toInt().coerceAtLeast(0))
            }
            fun handleOffset(p: TermPoint, end: Boolean): Offset {
                val (_, t, c) = latest.value
                return Offset((p.col + if (end) 1 else 0) * c.width, (p.row - t + 1) * c.height)
            }
            fun scrollBy(rowsDelta: Float) { back = (back + rowsDelta).coerceIn(0f, maxBackNow.value.toFloat()) }

            Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Grabbing a selection handle moves that end of the selection.
                    val sel0 = selection
                    val grabbed = sel0?.let { s ->
                        when {
                            (handleOffset(s.start, false) - down.position).getDistance() < handleR -> 0
                            (handleOffset(s.end, true) - down.position).getDistance() < handleR -> 1
                            else -> null
                        }
                    }
                    val kind = if (grabbed != null) Gesture.LONG else withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var k: Gesture? = null
                        while (k == null) {
                            val e = awaitPointerEvent()
                            val c = e.changes.first()
                            k = when {
                                e.changes.size > 1 -> Gesture.MULTI
                                !c.pressed -> Gesture.TAP
                                (c.position - down.position).getDistance() > viewConfiguration.touchSlop -> Gesture.DRAG
                                else -> null
                            }
                        }
                        k
                    } ?: Gesture.LONG
                    when {
                        grabbed != null && sel0 != null -> {
                            val fixed = if (grabbed == 0) sel0.end else sel0.start
                            while (true) {
                                val c = awaitPointerEvent().changes.first()
                                if (!c.pressed) break
                                selection = TermSelection(fixed, pointAt(c.position - Offset(0f, handleR / 2)))
                                c.consume()
                            }
                        }
                        kind == Gesture.TAP -> if (selection != null) selection = null else input?.showKeyboard()
                        kind == Gesture.LONG -> {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            val p = pointAt(down.position)
                            val word = latest.value.first.getOrNull(p.row)?.let { wordAt(it, p.row, p.col) } ?: TermSelection(p, p)
                            selection = word
                            while (true) {
                                val c = awaitPointerEvent().changes.first()
                                if (!c.pressed) break
                                val q = pointAt(c.position)
                                selection = if (q < word.start) TermSelection(word.end, q) else TermSelection(word.start, maxOf(word.end, q))
                                c.consume()
                            }
                        }
                        kind == Gesture.MULTI -> while (true) {
                            val e = awaitPointerEvent()
                            if (e.changes.none { it.pressed }) break
                            val z = e.calculateZoom()
                            if (z != 1f) fontSize = (fontSize * z).coerceIn(7f, 28f)
                            e.changes.forEach { it.consume() }
                        }
                        else -> {  // swipe through the scrollback, with a fling
                            val tracker = VelocityTracker()
                            tracker.addPosition(down.uptimeMillis, down.position)
                            while (true) {
                                val c = awaitPointerEvent().changes.first()
                                if (!c.pressed) break
                                tracker.addPosition(c.uptimeMillis, c.position)
                                scrollBy(c.positionChange().y / latest.value.third.height)
                                c.consume()
                            }
                            var v = tracker.calculateVelocity().y / latest.value.third.height  // rows per second
                            if (abs(v) > 3f) scope.launch {
                                while (abs(v) > 1f) { scrollBy(v * 0.016f); v *= 0.94f; delay(16) }
                            }
                        }
                    }
                }
            }) {
                val s = screen ?: return@Canvas
                val sel = selection
                val cursorRow = s.scrollback.size + s.cursorY
                val showCursor = s.cursorVisible && st.status == TermStatus.OPEN
                for (i in 0 until shown) {
                    val r = top + i
                    val line = all.getOrNull(r) ?: continue
                    val y = i * cell.height
                    sel?.colsOn(r)?.let { range ->
                        val to = range.last.coerceAtMost(cols - 1)
                        if (to >= range.first) drawRect(SelectionColor, Offset(range.first * cell.width, y),
                            Size((to - range.first + 1) * cell.width, cell.height))
                    }
                    val cur = if (showCursor && r == cursorRow) s.cursorX else null
                    if (line.isEmpty() && cur == null) continue
                    drawText(measurer.measure(lineText(line, cur), style, softWrap = false, maxLines = 1), topLeft = Offset(0f, y))
                }
                if (sel != null) listOf(handleOffset(sel.start, false), handleOffset(sel.end, true)).forEach { h ->
                    drawCircle(HandleColor, radius = 7.dp.toPx(), center = h + Offset(0f, 7.dp.toPx()))
                }
            }

            if (back >= 1f) Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).clickable { back = 0f }) {
                Text("↓ ${back.roundToInt()} lines", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
            selection?.let { sel ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), shadowElevation = 6.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { clipboard.setText(AnnotatedString(selectedText(all, sel))); selection = null }) { Text("Copy") }
                        TextButton(onClick = { paste() }) { Text("Paste") }
                        TextButton(onClick = {
                            selection = TermSelection(TermPoint(0, 0), TermPoint((all.size - 1).coerceAtLeast(0), cols))
                        }) { Text("Select all") }
                        IconButton(onClick = { selection = null }) { Icon(Glyphs.Close, "Clear selection", modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            when (st?.status) {
                null -> Text("Not connected to your PC.", color = TermFg, modifier = Modifier.padding(12.dp))
                TermStatus.CONNECTING -> Box(Modifier.align(Alignment.Center)) { TypingDots(Gold) }
                TermStatus.CLOSED, TermStatus.FAILED -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)) {
                    Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(st.message ?: "Disconnected", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { session?.close(); session = null; generation++ }) { Text("New shell") }
                    }
                }
                else -> {}
            }
            // The keyboard target: invisible, takes every key and sends it straight to the shell.
            AndroidView(factory = { ctx -> TermInputView(ctx).also { input = it } }, modifier = Modifier.size(1.dp).alpha(0f),
                update = { v ->
                    v.onInput = { s -> back = 0f; selection = null; send(s) }
                    v.modifiers = { ctrl to alt }
                    v.clearModifiers = { ctrl = false; alt = false }
                })
        }
        ExtraKeys(ctrl, alt, onCtrl = { ctrl = !ctrl }, onAlt = { alt = !alt }, onKeyboard = { input?.showKeyboard() }) { seq ->
            val out = (if (alt) TermKeys.ESC else "") + seq
            ctrl = false; alt = false; back = 0f
            send(out)
        }
    }
}

/** Termux's default extra keys, two rows under the screen. CTRL/ALT latch for the next key. */
@Composable
private fun ExtraKeys(ctrl: Boolean, alt: Boolean, onCtrl: () -> Unit, onAlt: () -> Unit, onKeyboard: () -> Unit, send: (String) -> Unit) {
    val rows = listOf(
        listOf("ESC" to TermKeys.ESC, "/" to "/", "-" to "-", "HOME" to TermKeys.HOME, "↑" to TermKeys.UP, "END" to TermKeys.END, "PGUP" to TermKeys.PGUP),
        listOf("TAB" to TermKeys.TAB, "CTRL" to "", "ALT" to "", "←" to TermKeys.LEFT, "↓" to TermKeys.DOWN, "→" to TermKeys.RIGHT, "⌨" to ""),
    )
    Column(Modifier.fillMaxWidth().background(KeysBg).padding(vertical = 2.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { (label, seq) ->
                    val active = (label == "CTRL" && ctrl) || (label == "ALT" && alt)
                    Box(Modifier.weight(1f).height(40.dp).clickable {
                        when (label) {
                            "CTRL" -> onCtrl(); "ALT" -> onAlt(); "⌨" -> onKeyboard()
                            "/", "-" -> send(if (ctrl) TermKeys.ctrl(label[0]) else label)
                            else -> send(seq)
                        }
                    }, contentAlignment = Alignment.Center) {
                        Text(label, color = if (active) HandleColor else TermFg, fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else null)
                    }
                }
            }
        }
    }
}
