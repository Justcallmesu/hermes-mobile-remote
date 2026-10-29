package io.github.nideta231.hermesremote.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/** One styled piece of a terminal row. [fg]/[bg] are pyte colour names ("red", "default") or hex ("ff8800"). */
data class TermRun(val text: String, val fg: String, val bg: String, val flags: Int) {
    val bold get() = flags and 1 != 0
    val reverse get() = flags and 2 != 0
    val underline get() = flags and 4 != 0
    val italic get() = flags and 8 != 0
}

typealias TermLine = List<TermRun>

data class TermScreen(
    val cols: Int = 80,
    val rows: Int = 24,
    /** Visible rows, top to bottom; always [rows] long. */
    val lines: List<TermLine> = List(24) { emptyList() },
    /** Rows that scrolled off the top, oldest first (capped at [SCROLLBACK]). */
    val scrollback: List<TermLine> = emptyList(),
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val cursorVisible: Boolean = true,
    val title: String = "",
    /** The shell turned on bracketed paste (needs the updated bridge; false otherwise). */
    val bracketedPaste: Boolean = false,
) {
    companion object { const val SCROLLBACK = 2000 }

    /** Apply one `screen` frame from the bridge. */
    fun apply(f: JSONObject): TermScreen {
        val cols = f.optInt("cols", cols)
        val rows = f.optInt("rows", rows)
        val base = if (f.optBoolean("full") || rows != this.rows) List(rows) { emptyList<TermRun>() } else lines
        val next = base.toMutableList()
        f.optJSONObject("lines")?.let { ls ->
            ls.keys().forEach { k -> k.toIntOrNull()?.takeIf { it in 0 until rows }?.let { y -> next[y] = parseLine(ls.optJSONArray(k)) } }
        }
        val scrolled = f.optJSONArray("scrolled")?.let { a -> (0 until a.length()).map { parseLine(a.optJSONArray(it)) } }.orEmpty()
        val cursor = f.optJSONArray("cursor")
        return copy(cols = cols, rows = rows, lines = next,
            scrollback = if (scrolled.isEmpty()) scrollback else (scrollback + scrolled).takeLast(SCROLLBACK),
            cursorX = cursor?.optInt(0) ?: cursorX, cursorY = cursor?.optInt(1) ?: cursorY,
            cursorVisible = cursor?.optBoolean(2, true) ?: cursorVisible,
            title = f.optString("title", title), bracketedPaste = f.optBoolean("paste", bracketedPaste))
    }

    /** Scrollback then the visible rows: what the view scrolls through and selects from. */
    val all: List<TermLine> get() = scrollback + lines

    private fun parseLine(a: JSONArray?): TermLine = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val r = a.optJSONArray(i) ?: return@mapNotNull null
        TermRun(r.optString(0), r.optString(1, "default"), r.optString(2, "default"), r.optInt(3))
    }
}

enum class TermStatus { CONNECTING, OPEN, CLOSED, FAILED }

data class TermState(val screen: TermScreen = TermScreen(), val status: TermStatus = TermStatus.CONNECTING,
                     val message: String? = null)

/** Key sequences the shell understands (xterm). */
object TermKeys {
    const val ENTER = "\r"
    const val TAB = "\t"
    const val ESC = "\u001b"
    const val BACKSPACE = "\u007f"
    const val UP = "\u001b[A"
    const val DOWN = "\u001b[B"
    const val RIGHT = "\u001b[C"
    const val LEFT = "\u001b[D"
    const val HOME = "\u001b[H"
    const val END = "\u001b[F"
    const val PGUP = "\u001b[5~"
    const val PGDN = "\u001b[6~"

    const val DELETE = "\u001b[3~"
    const val INSERT = "\u001b[2~"

    /** Ctrl + a key: ^A = 0x01 ... ^Z = 0x1a, ^@ ^[ ^\ ^] ^^ ^_, ^Space = NUL, ^? = DEL. Others pass through. */
    fun ctrl(c: Char): String = when (val l = c.lowercaseChar()) {
        in 'a'..'z' -> (l - 'a' + 1).toChar().toString()
        ' ', '@', '2' -> "\u0000"
        '[', '3' -> ESC
        '\\', '4' -> "\u001c"
        ']', '5' -> "\u001d"
        '^', '6' -> "\u001e"
        '_', '-', '7' -> "\u001f"
        '?', '8' -> BACKSPACE
        else -> c.toString()
    }

    /** F1..F12 (xterm). */
    fun f(n: Int): String = when (n) {
        1 -> "\u001bOP"; 2 -> "\u001bOQ"; 3 -> "\u001bOR"; 4 -> "\u001bOS"
        5 -> "\u001b[15~"; 6 -> "\u001b[17~"; 7 -> "\u001b[18~"; 8 -> "\u001b[19~"
        9 -> "\u001b[20~"; 10 -> "\u001b[21~"; 11 -> "\u001b[23~"; else -> "\u001b[24~"
    }

    /** Escape sequence for an Android key code that isn't a printable character, or null. */
    fun forKeyCode(code: Int): String? = when (code) {
        android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> ENTER
        android.view.KeyEvent.KEYCODE_DEL -> BACKSPACE
        android.view.KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        android.view.KeyEvent.KEYCODE_TAB -> TAB
        android.view.KeyEvent.KEYCODE_ESCAPE -> ESC
        android.view.KeyEvent.KEYCODE_DPAD_UP -> UP
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        android.view.KeyEvent.KEYCODE_MOVE_HOME -> HOME
        android.view.KeyEvent.KEYCODE_MOVE_END -> END
        android.view.KeyEvent.KEYCODE_PAGE_UP -> PGUP
        android.view.KeyEvent.KEYCODE_PAGE_DOWN -> PGDN
        android.view.KeyEvent.KEYCODE_INSERT -> INSERT
        in android.view.KeyEvent.KEYCODE_F1..android.view.KeyEvent.KEYCODE_F12 -> f(code - android.view.KeyEvent.KEYCODE_F1 + 1)
        else -> null
    }

    /** Pasted text: newlines as Enter, wrapped in bracketed-paste markers so shells don't run each line. */
    fun paste(text: String, bracketed: Boolean = true): String {
        val t = text.replace("\r\n", "\n").replace('\n', '\r')
        return if (bracketed) "\u001b[200~$t\u001b[201~" else t
    }
}

/**
 * One shell on the PC over `/v1/terminal`. The bridge runs the emulator (pyte) and sends finished,
 * styled rows; this side keeps the screen and the scrollback and sends keystrokes.
 */
class TerminalSession(private val client: BridgeClient, cols: Int, rows: Int) {
    private val _state = MutableStateFlow(TermState(TermScreen(cols, rows, List(rows) { emptyList() })))
    val state: StateFlow<TermState> = _state.asStateFlow()
    private val socket: WebSocket

    init {
        socket = client.terminal(cols, rows, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.update { it.copy(status = TermStatus.OPEN, message = null) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val f = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (f.optString("type")) {
                    "screen" -> _state.update { it.copy(screen = it.screen.apply(f)) }
                    "exit" -> _state.update { it.copy(status = TermStatus.CLOSED, message = "Shell exited" +
                        (if (f.isNull("code")) "" else " (${f.optInt("code")})")) }
                    "error" -> _state.update { it.copy(status = TermStatus.FAILED, message = f.optString("message")) }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closedWith(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closedWith(code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.update { if (it.status == TermStatus.CLOSED) it else it.copy(status = TermStatus.FAILED,
                    message = when (response?.code) { 404 -> "This PC's bridge has no terminal. Update it."; else -> t.message ?: "Connection lost" }) }
            }
        })
    }

    private fun closedWith(code: Int, reason: String) {
        _state.update {
            if (it.status == TermStatus.CLOSED || it.status == TermStatus.FAILED) it
            else it.copy(status = if (code >= 4000) TermStatus.FAILED else TermStatus.CLOSED, message = when (code) {
                4401 -> "This device was revoked. Re-pair it."
                4404 -> reason.ifBlank { "The terminal is turned off on this PC." }
                else -> reason.ifBlank { "Disconnected" }
            })
        }
    }

    fun send(data: String) {
        if (data.isNotEmpty()) socket.send(JSONObject().put("type", "input").put("data", data).toString())
    }

    fun resize(cols: Int, rows: Int) {
        val s = _state.value.screen
        if (cols == s.cols && rows == s.rows) return
        socket.send(JSONObject().put("type", "resize").put("cols", cols).put("rows", rows).toString())
    }

    fun close() { socket.close(1000, "bye") }
}
