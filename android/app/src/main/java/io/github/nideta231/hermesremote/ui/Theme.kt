package io.github.nideta231.hermesremote.ui

import android.content.Context
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

/** One entry of the Settings theme picker. */
enum class AppTheme(val label: String, val accent: Color, val dark: Boolean) {
    GOLD("Gold", Color(0xFFE8B54A), true),
    OCEAN("Ocean", Color(0xFF6FA8F5), true),
    FOREST("Forest", Color(0xFF6CCB8A), true),
    VIOLET("Violet", Color(0xFFB794F6), true),
    ROSE("Rose", Color(0xFFF28FA8), true),
    MIDNIGHT("Midnight (OLED)", Color(0xFFE8B54A), true),
    LIGHT("Light", Color(0xFF9A6A00), false),
    ;

    val scheme: ColorScheme
        get() = when {
            this == LIGHT -> lightColorScheme(
                primary = accent,
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
            this == MIDNIGHT -> darkColorScheme(
                primary = accent,
                onPrimary = Color(0xFF231A05),
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
                primary = accent,
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
    var current by mutableStateOf(AppTheme.GOLD)
        private set
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        val name = prefs(context).getString("theme", null)
        current = AppTheme.entries.firstOrNull { it.name == name } ?: AppTheme.GOLD
    }

    fun set(theme: AppTheme) {
        current = theme
        ctx?.let { prefs(it).edit().putString("theme", theme.name).apply() }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
}

@Composable
fun HermesTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = ThemePrefs.current.scheme, content = content)
