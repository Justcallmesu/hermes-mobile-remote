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
// Names of the first five (GOLD, OCEAN, FOREST, VIOLET, ROSE) are stored in prefs: never rename them.
// Order is the picker order, grouped by hue family.
enum class Accent(val label: String, val dark: Color, val light: Color) {
    // yellows / oranges
    GOLD("Gold", Color(0xFFE8B54A), Color(0xFF8A5F00)),
    AMBER("Amber", Color(0xFFFFC145), Color(0xFF9A6700)),
    LEMON("Lemon", Color(0xFFE6DA5A), Color(0xFF7A6E00)),
    ORANGE("Orange", Color(0xFFFF9F4A), Color(0xFFB45309)),
    COPPER("Copper", Color(0xFFD9895B), Color(0xFF9A4A1E)),
    // reds
    CORAL("Coral", Color(0xFFFF8A75), Color(0xFFC2412D)),
    RED("Red", Color(0xFFF26B6B), Color(0xFFB91C1C)),
    CRIMSON("Crimson", Color(0xFFE5576F), Color(0xFF9F1239)),
    MAROON("Maroon", Color(0xFFC77B85), Color(0xFF7F1D2D)),
    // pinks
    ROSE("Rose", Color(0xFFF28FA8), Color(0xFFB0305A)),
    PINK("Pink", Color(0xFFF7A1D0), Color(0xFFBE185D)),
    MAGENTA("Magenta", Color(0xFFE879E0), Color(0xFFA21CAF)),
    // purples
    LAVENDER("Lavender", Color(0xFFC9B6F7), Color(0xFF7056B8)),
    VIOLET("Violet", Color(0xFFB794F6), Color(0xFF6A3FC4)),
    PURPLE("Purple", Color(0xFFA77BEA), Color(0xFF6B21A8)),
    PLUM("Plum", Color(0xFFB98BC0), Color(0xFF6B2D6F)),
    // blues
    SKY("Sky", Color(0xFF7DD3FC), Color(0xFF0369A1)),
    OCEAN("Ocean", Color(0xFF6FA8F5), Color(0xFF1F5FB8)),
    INDIGO("Indigo", Color(0xFF8C9EFF), Color(0xFF3730A3)),
    NAVY("Navy", Color(0xFF7F95C8), Color(0xFF1E3A6E)),
    // cyans / teals
    CYAN("Cyan", Color(0xFF5FDDE8), Color(0xFF0E7490)),
    TEAL("Teal", Color(0xFF4FD1B5), Color(0xFF0F766E)),
    // greens
    MINT("Mint", Color(0xFF8EEBC0), Color(0xFF15803D)),
    FOREST("Forest", Color(0xFF6CCB8A), Color(0xFF1E7A43)),
    LIME("Lime", Color(0xFFB5E05A), Color(0xFF4D7C0F)),
    OLIVE("Olive", Color(0xFFB8BE6A), Color(0xFF5B6113)),
    EMERALD("Emerald", Color(0xFF34D399), Color(0xFF047857)),
    // neutrals
    SLATE("Slate", Color(0xFFA3B1C6), Color(0xFF475569)),
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
        collapsedGroups = p.getStringSet("collapsed_groups", null)?.toSet() ?: emptySet()
    }

    fun pickMode(value: ThemeMode) {
        mode = value
        ctx?.let { prefs(it).edit().putString("mode", value.name).remove("theme").apply() }
    }

    fun pickAccent(value: Accent) {
        accent = value
        ctx?.let { prefs(it).edit().putString("accent", value.name).remove("theme").apply() }
    }

    /** Drawer groups the user collapsed ("GROUPING/key"), kept across app restarts. */
    var collapsedGroups by mutableStateOf<Set<String>>(emptySet())
        private set

    fun toggleCollapsed(key: String) {
        collapsedGroups = if (key in collapsedGroups) collapsedGroups - key else collapsedGroups + key
        ctx?.let { prefs(it).edit().putStringSet("collapsed_groups", HashSet(collapsedGroups)).apply() }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("ui", Context.MODE_PRIVATE)
}

@Composable
fun HermesTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colorScheme(ThemePrefs.mode, ThemePrefs.accent, isSystemInDarkTheme()), content = content)
