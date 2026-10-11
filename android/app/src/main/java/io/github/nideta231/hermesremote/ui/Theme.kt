package io.github.nideta231.hermesremote.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/** The accent of the chosen theme. Kept under its old name so every call site follows the picker. */
val Gold: Color
    @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.primary

val Ok = Color(0xFF5BC27A)
val Bad = Color(0xFFE5675C)
val Warn = Color(0xFFE0A53A)

/** Brightness of the app. Every [Accent] works with every mode. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
    MIDNIGHT("Midnight"),
}

/** Accent colour. [dark] is used on dark/midnight backgrounds, [light] (deeper, for contrast) on light. */
enum class Accent(val label: String, val dark: Color, val light: Color) {
    GOLD("Gold", Color(0xFFE8B54A), Color(0xFF8A5F00)),
    OCEAN("Ocean", Color(0xFF6FA8F5), Color(0xFF1F5FB8)),
    FOREST("Forest", Color(0xFF6CCB8A), Color(0xFF1E7A43)),
    VIOLET("Violet", Color(0xFFB794F6), Color(0xFF6A3FC4)),
    ROSE("Rose", Color(0xFFF28FA8), Color(0xFFB0305A)),
    ;

    fun color(mode: ThemeMode, systemDark: Boolean) = if (mode.isDark(systemDark)) dark else light
}

fun ThemeMode.isDark(systemDark: Boolean) = when (this) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK, ThemeMode.MIDNIGHT -> true
}

fun colorScheme(mode: ThemeMode, accent: Accent, systemDark: Boolean): ColorScheme {
    val dark = mode.isDark(systemDark)
    val primary = accent.color(mode, systemDark)
    return when {
        !dark -> lightColorScheme(
            primary = primary,
            onPrimary = Color.White,
            secondary = Color(0xFF3E5F8A),
            background = Color(0xFFF7F6F3),
            surface = Color(0xFFF7F6F3),
            surfaceVariant = Color(0xFFE8E6E1),
            surfaceContainerLow = Color(0xFFF2F0EC),
            surfaceContainer = Color(0xFFEFEDE8),
            surfaceContainerHigh = Color(0xFFE8E6E1),
            surfaceContainerHighest = Color(0xFFE0DDD6),
            onSurface = Color(0xFF1C1B19),
            onSurfaceVariant = Color(0xFF5A5852),
            outline = Color(0xFFC9C5BC),
            error = Color(0xFFC0392B),
        )
        mode == ThemeMode.MIDNIGHT -> darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF15130F),
            secondary = Color(0xFF9DB4D6),
            background = Color.Black,
            surface = Color.Black,
            surfaceVariant = Color(0xFF17181B),
            surfaceContainerLow = Color(0xFF070708),
            surfaceContainer = Color(0xFF0E0F11),
            surfaceContainerHigh = Color(0xFF17181B),
            surfaceContainerHighest = Color(0xFF202227),
            onSurface = Color(0xFFE6E6E9),
            onSurfaceVariant = Color(0xFFA9ABB3),
            outline = Color(0xFF30333A),
            error = Bad,
        )
        else -> darkColorScheme(
            primary = primary,
            onPrimary = Color(0xFF15130F),
            secondary = Color(0xFF9DB4D6),
            background = Color(0xFF121316),
            surface = Color(0xFF121316),
            surfaceVariant = Color(0xFF22252C),
            surfaceContainerLow = Color(0xFF16181C),
            surfaceContainer = Color(0xFF1A1C21),
            surfaceContainerHigh = Color(0xFF22252C),
            surfaceContainerHighest = Color(0xFF2A2D35),
            onSurface = Color(0xFFE6E6E9),
            onSurfaceVariant = Color(0xFFA9ABB3),
            outline = Color(0xFF3A3E47),
            error = Bad,
        )
    }
}

/** App-wide (not per PC) theme choice, persisted in its own prefs file. */
object ThemePrefs {
    var mode by mutableStateOf(ThemeMode.DARK)
        private set
    var accent by mutableStateOf(Accent.GOLD)
        private set
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        val p = prefs(context)
        val legacy = p.getString("theme", null)  // v1.8.0 stored one combined value
        mode = ThemeMode.entries.firstOrNull { it.name == p.getString("mode", null) } ?: when (legacy) {
            null -> ThemeMode.DARK  // the app was always dark before the picker
            "LIGHT" -> ThemeMode.LIGHT
            "MIDNIGHT" -> ThemeMode.MIDNIGHT
            else -> ThemeMode.DARK
        }
        accent = Accent.entries.firstOrNull { it.name == p.getString("accent", null) }
            ?: Accent.entries.firstOrNull { it.name == legacy } ?: Accent.GOLD
    }

    fun pickMode(value: ThemeMode) {
        mode = value
        ctx?.let { prefs(it).edit().putString("mode", value.name).remove("theme").apply() }
    }

    fun pickAccent(value: Accent) {
        accent = value
        ctx?.let { prefs(it).edit().putString("accent", value.name).remove("theme").apply() }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
}

@Composable
fun HermesTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colorScheme(ThemePrefs.mode, ThemePrefs.accent, isSystemInDarkTheme()), content = content)
