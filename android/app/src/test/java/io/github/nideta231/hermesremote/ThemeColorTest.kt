package io.github.nideta231.hermesremote

import androidx.compose.ui.graphics.Color
import io.github.nideta231.hermesremote.ui.Accent
import io.github.nideta231.hermesremote.ui.ThemeMode
import io.github.nideta231.hermesremote.ui.backgroundSuggestions
import io.github.nideta231.hermesremote.ui.colorScheme
import io.github.nideta231.hermesremote.ui.hsvOf
import io.github.nideta231.hermesremote.ui.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeColorTest {
    @Test fun hsvRoundTrips() {
        val c = Color.hsv(200f, 0.6f, 0.8f)
        val (h, s, v) = hsvOf(c)
        assertEquals(200f, h, 1f); assertEquals(0.6f, s, 0.01f); assertEquals(0.8f, v, 0.01f)
    }

    @Test fun suggestionsStayReadable() {
        backgroundSuggestions(Color(0xFFE8B54A), dark = true).forEach { (_, c) -> assertTrue(luminance(c) < 0.15f) }
        backgroundSuggestions(Color(0xFFE8B54A), dark = false).forEach { (_, c) -> assertTrue(luminance(c) > 0.85f) }
    }

    @Test fun customColoursOverrideTheScheme() {
        val bg = Color(0xFFF5F0E6)  // light background on a dark mode: text must flip to dark
        val s = colorScheme(ThemeMode.DARK, Accent.GOLD, true, customPrimary = Color(0xFF2255FF), customBackground = bg)
        assertEquals(Color(0xFF2255FF), s.primary)
        assertEquals(bg, s.background)
        assertTrue(luminance(s.onSurface) < 0.2f)
        assertEquals(Color.White, s.onPrimary)
    }
}
