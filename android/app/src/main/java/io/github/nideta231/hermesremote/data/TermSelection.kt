package io.github.nideta231.hermesremote.data

/** A cell of the terminal: [row] counts scrollback rows first, then the visible screen. */
data class TermPoint(val row: Int, val col: Int) : Comparable<TermPoint> {
    override fun compareTo(other: TermPoint) = if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)
}

/** A text selection between two cells, both inclusive, in either order. */
data class TermSelection(val anchor: TermPoint, val focus: TermPoint) {
    val start get() = minOf(anchor, focus)
    val end get() = maxOf(anchor, focus)

    /** Columns selected on [row] as [from, to) (to may run past the line's text), or null. */
    fun colsOn(row: Int): IntRange? {
        val s = start; val e = end
        if (row < s.row || row > e.row) return null
        val from = if (row == s.row) s.col else 0
        val to = if (row == e.row) e.col else Int.MAX_VALUE - 1
        return from..to
    }
}

/** Plain text of one terminal row. */
fun TermLine.plain(): String = joinToString("") { it.text }

/** The selected text: each row's part with its trailing blanks dropped, rows joined by newlines. */
fun selectedText(rows: List<TermLine>, sel: TermSelection): String {
    val s = sel.start; val e = sel.end
    return (s.row..e.row).mapNotNull { r -> rows.getOrNull(r) }.mapIndexed { i, line ->
        val text = line.plain()
        val row = s.row + i
        val from = (if (row == s.row) s.col else 0).coerceIn(0, text.length)
        val to = (if (row == e.row) e.col + 1 else text.length).coerceIn(from, text.length)
        text.substring(from, to).trimEnd()
    }.joinToString("\n")
}

/** The word around [col] on a row (a run of non-blank characters), for long-press selection. */
fun wordAt(line: TermLine, row: Int, col: Int): TermSelection {
    val text = line.plain()
    if (col !in text.indices || text[col].isWhitespace()) return TermSelection(TermPoint(row, col), TermPoint(row, col))
    var a = col; var b = col
    while (a > 0 && !text[a - 1].isWhitespace()) a--
    while (b < text.lastIndex && !text[b + 1].isWhitespace()) b++
    return TermSelection(TermPoint(row, a), TermPoint(row, b))
}
