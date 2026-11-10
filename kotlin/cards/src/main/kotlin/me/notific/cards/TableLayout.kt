package me.notific.cards

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// How a card's `table` block is laid out. Pure logic, so it can be tested
// without a screen; TableBlock (NotificCard.kt) measures the cells and draws.

/** A number as a sender writes it: 150, 1,090.00, US$ 3,842.60, R$ 10,00, USD 500, -4%, 2.41M, €30. */
private val NumberLike = Regex(
    """^[+\-−]?\s*(?:\p{Sc}|[A-Z]{1,3}\p{Sc}|[A-Z]{3}\s)?\s*[+\-−]?\d[\d.,   ]*\s*(?:%|[kKMB]|\p{Sc})?$""",
)

/** A year as people write it: four digits, no separators (2027). */
private val YearLike = Regex("""^(1[89]|2[01])\d\d$""")

/** A cell that reads as a number (a JSON number, or a string that is one). */
internal fun isNumericCell(cell: JsonPrimitive): Boolean =
    if (cell.isString) NumberLike.matches(cell.content.trim()) else cell !is JsonNull && cell.content.toDoubleOrNull() != null

/**
 * Columns whose cells are all numbers (empty cells aside): they are
 * right-aligned and never wrap. A column with no values is text, and so is
 * a column of years (2027, 2028…): a year is a label, read from the left.
 */
internal fun numericColumns(columnCount: Int, rows: List<List<JsonPrimitive>>): List<Boolean> = List(columnCount) { c ->
    val cells = rows.mapNotNull { it.getOrNull(c) }.filter { it !is JsonNull && !(it.isString && it.content.isBlank()) }
    cells.isNotEmpty() && cells.all(::isNumericCell) && !cells.all { YearLike.matches(it.content.trim()) }
}

/** Column widths (px, padding included) and whether the table scrolls sideways. */
internal data class TablePlan(val widths: List<Int>, val scroll: Boolean)

/**
 * Fits the columns into [available] when it can. Widths are the cells' own
 * measurements: [natural] is each column's widest cell on one line (header
 * included), [minWord] its longest word, both with the cell padding.
 * - Everything fits on one line: each column gets its natural width and the
 *   spare room goes to the first text column (the numbers then sit at the
 *   right edge, as in a spreadsheet).
 * - Otherwise only text columns ([flexible]) give way, the widest first, so a
 *   short one ("Today 4 PM") stays on one line; none goes below [minFlex]
 *   (or its natural width, if smaller) nor below its longest word. Number
 *   columns always keep their natural width: a number is never cut.
 * - If even that does not fit, the table scrolls sideways at natural widths,
 *   text columns capped at [maxFlex] (they wrap).
 */
internal fun planTable(
    natural: List<Int>,
    flexible: List<Boolean>,
    available: Int,
    minFlex: Int,
    maxFlex: Int,
    minWord: List<Int> = List(natural.size) { 0 },
): TablePlan {
    if (natural.isEmpty()) return TablePlan(emptyList(), scroll = false)
    val total = natural.sum()
    if (total <= available) {
        val widths = natural.toMutableList()
        val extra = available - total
        val first = flexible.indexOfFirst { it }
        if (first >= 0) {
            widths[first] += extra
        } else {
            val share = extra / widths.size
            widths.indices.forEach { widths[it] += share }
            widths[widths.lastIndex] += extra - share * widths.size
        }
        return TablePlan(widths, scroll = false)
    }
    val flex = natural.indices.filter { flexible[it] }
    val fixed = natural.indices.filter { !flexible[it] }.sumOf { natural[it] }
    // The narrowest a text column may get: minFlex (or less if it is shorter), never under its longest word.
    val low = flex.associateWith { maxOf(minOf(natural[it], minFlex), minWord[it]).coerceAtMost(natural[it]) }
    val room = available - fixed
    if (flex.isNotEmpty() && room >= low.values.sum()) {
        // Water level: every text column is capped at `level`, the widest ones give way first.
        fun width(c: Int, level: Int) = maxOf(low.getValue(c), minOf(natural[c], level))
        fun used(level: Int) = flex.sumOf { width(it, level) }
        var lo = 0
        var hi = flex.maxOf { natural[it] }
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (used(mid) <= room) lo = mid else hi = mid - 1
        }
        val widths = natural.toMutableList()
        flex.forEach { widths[it] = width(it, lo) }
        // Pixels left by the rounding go, one each, to the columns that were capped.
        var left = room - flex.sumOf { widths[it] }
        for (c in flex) {
            if (left == 0) break
            if (widths[c] < natural[c]) {
                widths[c]++
                left--
            }
        }
        if (left > 0) widths[flex.first()] += left
        return TablePlan(widths, scroll = false)
    }
    return TablePlan(natural.mapIndexed { c, w -> if (flexible[c]) minOf(w, maxOf(maxFlex, minWord[c])) else w }, scroll = true)
}
