package io.github.nideta231.hermesremote.ui

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.nideta231.hermesremote.data.TermKeys

/**
 * The terminal's keyboard target, like Termux's: no text field, every key goes straight to the
 * shell. Soft keyboards get a password-style editor (no suggestions, no autocorrect, no
 * composing), so each typed character arrives as a commit or a key event and is sent at once.
 */
class TermInputView(context: Context) : View(context) {
    /** Text or an escape sequence to send to the shell. */
    var onInput: (String) -> Unit = {}
    /** Turns the next typed character into Ctrl/Alt+character, then resets (the extra-keys latch). */
    var modifiers: () -> Pair<Boolean, Boolean> = { false to false }
    var clearModifiers: () -> Unit = {}

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun showKeyboard() {
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(this, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            private fun flush() {
                val e = editable ?: return
                if (e.isNotEmpty()) { typed(e.toString()); e.clear() }
            }

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition); flush(); return true
            }

            override fun finishComposingText(): Boolean { super.finishComposingText(); flush(); return true }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Nothing is kept in the editor, so a delete is the Backspace key.
                repeat(beforeLength.coerceAtLeast(0)) { onInput(TermKeys.BACKSPACE) }
                repeat(afterLength.coerceAtLeast(0)) { onInput(TermKeys.DELETE) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) key(event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) super.onKeyDown(keyCode, event) else key(event)

    /** Characters from the soft keyboard: the latched Ctrl/Alt apply to the first one. */
    private fun typed(text: String) {
        val (ctrl, alt) = modifiers()
        val t = text.replace("\n", TermKeys.ENTER)
        if ((ctrl || alt) && t.isNotEmpty()) {
            val first = t[0].let { if (ctrl) TermKeys.ctrl(it) else it.toString() }
            onInput((if (alt) TermKeys.ESC else "") + first + t.substring(1))
            clearModifiers()
        } else onInput(t)
    }

    /** A key press (hardware keyboard, or a soft key the IME sends as an event). */
    private fun key(e: KeyEvent): Boolean {
        val (latchCtrl, latchAlt) = modifiers()
        val ctrl = e.isCtrlPressed || latchCtrl
        val alt = e.isAltPressed || latchAlt
        val seq = TermKeys.forKeyCode(e.keyCode) ?: run {
            val c = e.getUnicodeChar(e.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
            if (c == 0) return false
            if (ctrl) TermKeys.ctrl(c.toChar()) else String(Character.toChars(c))
        }
        onInput((if (alt) TermKeys.ESC else "") + seq)
        if (latchCtrl || latchAlt) clearModifiers()
        return true
    }
}
