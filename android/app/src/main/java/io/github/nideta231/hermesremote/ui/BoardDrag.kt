package io.github.nideta231.hermesremote.ui

/**
 * Pure helpers for dragging a board card: where it would land, and how fast to auto-scroll when the
 * finger nears an edge. Kept out of the composable so they can be unit tested.
 */

/**
 * The slot the finger points at, as an index into [others] (the shown cards of the column without
 * the dragged one). [visible] are the on-screen cards of [others], top to bottom, with their centre
 * y; cards scrolled out of view are counted by their position in [others].
 */
internal fun dropSlot(others: List<String>, visible: List<Pair<String, Float>>, fingerY: Float): Int {
    val shown = visible.filter { it.first in others }
    shown.firstOrNull { it.second > fingerY }?.let { return others.indexOf(it.first) }
    return shown.lastOrNull()?.let { others.indexOf(it.first) + 1 } ?: others.size
}

/**
 * [slot] counts only the cards a tag filter shows; the move op wants an index among all the column's
 * cards (without the dragged one). Lands right before the shown card at [slot], or right after the
 * last shown card.
 */
internal fun fullIndex(shown: List<String>, all: List<String>, slot: Int): Int = when {
    slot < shown.size -> all.indexOf(shown[slot]).takeIf { it >= 0 } ?: all.size
    shown.isNotEmpty() -> (all.indexOf(shown.last()) + 1).takeIf { it > 0 } ?: all.size
    else -> all.size
}

/**
 * Pixels to scroll this frame: negative near [start], positive near [end], growing to [max] at the
 * edge (and beyond it, so holding the finger past the edge keeps scrolling); 0 in the middle.
 */
internal fun edgeScroll(pos: Float, start: Float, end: Float, zone: Float, max: Float): Float {
    if (end - start <= 2 * zone || zone <= 0f) return 0f
    return when {
        pos < start + zone -> -max * ((start + zone - pos) / zone).coerceIn(0f, 1f)
        pos > end - zone -> max * ((pos - (end - zone)) / zone).coerceIn(0f, 1f)
        else -> 0f
    }
}
