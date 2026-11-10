package me.notific.cards

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The card `table` block: number columns, fitting and scrolling. */
class TableLayoutTest {
    private fun s(v: String) = JsonPrimitive(v)
    private fun n(v: Number) = JsonPrimitive(v)

    @Test
    fun numbersAsSendersWriteThem() {
        listOf("150", "1,090.00", "US$ 3,842.60", "R$ 10,00", "USD 500", "-4%", "+2.1%", "2.41M", "€30", "1 284 930").forEach {
            assertTrue(isNumericCell(s(it)), it)
        }
        assertTrue(isNumericCell(n(1090.5)))
        listOf("31 to 60", "Over 90", "Today 4 PM", "Nov 12", "PO-8841 (laptops)", "Q3", "2026-11-09", "12:00", "").forEach {
            assertFalse(isNumericCell(s(it)), it)
        }
        assertFalse(isNumericCell(JsonNull))
    }

    @Test
    fun aColumnIsNumericWhenEveryValueIs() {
        val rows = listOf(
            listOf(s("Laptop 14\""), n(150), s("1,090.00"), s("163,500.00")),
            listOf(s("Warranty"), n(150), JsonNull, s("16,500.00")),
        )
        assertEquals(listOf(false, true, true, true), numericColumns(4, rows))
        // A column with no values, or mixed, is text.
        assertEquals(listOf(false, false), numericColumns(2, listOf(listOf(JsonNull, s("12")), listOf(JsonNull, s("n/a")))))
    }

    @Test
    fun spareRoomGoesToTheFirstTextColumn() {
        // Three columns that fit on one line, as in the receivables card.
        val plan = planTable(listOf(100, 60, 80), listOf(true, false, false), available = 300, minFlex = 80, maxFlex = 240)
        assertFalse(plan.scroll)
        assertEquals(listOf(160, 60, 80), plan.widths)
        // Numbers only: the room is shared.
        assertEquals(listOf(100, 100, 100), planTable(listOf(90, 90, 90), listOf(false, false, false), 300, 80, 240).widths)
    }

    @Test
    fun textWrapsSoTheNumbersFit() {
        // The purchase order: a long item name and three number columns.
        val plan = planTable(listOf(200, 50, 90, 95), listOf(true, false, false, false), available = 320, minFlex = 80, maxFlex = 240)
        assertFalse(plan.scroll)
        assertEquals(listOf(85, 50, 90, 95), plan.widths)
        assertEquals(320, plan.widths.sum())
    }

    @Test
    fun theWidestTextColumnGivesWayFirst() {
        val plan = planTable(listOf(200, 100, 60), listOf(true, true, false), available = 250, minFlex = 60, maxFlex = 240)
        assertFalse(plan.scroll)
        assertEquals(listOf(95, 95, 60), plan.widths)
    }

    @Test
    fun aTextColumnIsNeverNarrowerThanItsLongestWord() {
        val plan = planTable(listOf(400, 100), listOf(true, false), available = 300, minFlex = 100, maxFlex = 240, minWord = listOf(180, 0))
        assertEquals(listOf(200, 100), plan.widths)
        // Not even to fit: then it scrolls.
        val tight = planTable(listOf(400, 100), listOf(true, false), available = 300, minFlex = 100, maxFlex = 240, minWord = listOf(260, 0))
        assertTrue(tight.scroll)
        assertEquals(listOf(260, 100), tight.widths)
    }

    // The four demo tables, in pixels as drawn on the emulator (2.625x, 8 dp of padding = 42 px per
    // cell, included): a decision card is 880 px wide inside, an answered card 890 px.
    private val card = 880
    private val minFlex = 231 // 72 dp + padding
    private val maxFlex = 620 // 220 dp + padding

    @Test
    fun purchaseOrderShowsEveryNumberInFull() {
        // Item | Qty | Unit price | Total: "163,500.00" and the "Total" header whole; the items wrap.
        val natural = listOf(523, 101, 203, 220)
        val plan = planTable(natural, listOf(true, false, false, false), card, minFlex, maxFlex, minWord = listOf(208, 0, 0, 0))
        assertFalse(plan.scroll)
        assertEquals(listOf(356, 101, 203, 220), plan.widths)
    }

    @Test
    fun receivablesFitOnOneLine() {
        // Days overdue | Invoices | Amount: "1,284,300" in full, the spare room to the first column.
        val plan = planTable(listOf(253, 180, 201), listOf(true, false, false), card, minFlex, maxFlex)
        assertFalse(plan.scroll)
        assertEquals(listOf(499, 180, 201), plan.widths)
    }

    @Test
    fun digestKeepsAShortTextColumnOnOneLine() {
        // Needs attention | Amount | Due: everything on one line on this phone…
        val natural = listOf(380, 207, 235)
        assertEquals(listOf(438, 207, 235), planTable(natural, listOf(true, false, true), card, minFlex, maxFlex).widths)
        // …and on a narrower one "Today 4 PM" still does: the long column gives way.
        val narrow = planTable(natural, listOf(true, false, true), 760, minFlex, maxFlex, minWord = listOf(190, 0, 170))
        assertFalse(narrow.scroll)
        assertEquals(listOf(318, 207, 235), narrow.widths)
    }

    @Test
    fun expenseReportKeepsTheAmountHeader() {
        // Date | Expense | Amount: the bold "Amount" header (wider than the values) whole; dates on one line.
        val plan = planTable(listOf(209, 562, 175), listOf(true, true, false), 890, minFlex, maxFlex, minWord = listOf(118, 190, 0))
        assertFalse(plan.scroll)
        assertEquals(listOf(209, 506, 175), plan.widths)
    }

    @Test
    fun yearsAreLabelsNotAmounts() {
        // Contract CT-4521: Year | Commitment | Discount.
        val rows = listOf(listOf(s("2027"), s("144,000"), s("11%")), listOf(s("2028"), s("144,000"), s("11%")))
        assertEquals(listOf(false, true, true), numericColumns(3, rows))
        assertEquals(listOf(false), numericColumns(1, listOf(listOf(n(2027)), listOf(n(2028)))))
        // A plain quantity that is not a year stays a number.
        assertEquals(listOf(true), numericColumns(1, listOf(listOf(n(150)), listOf(n(2028)))))
    }

    @Test
    fun scrollsWhenEvenWrappedTextDoesNotFit() {
        val plan = planTable(listOf(400, 120, 120, 120), listOf(true, false, false, false), available = 300, minFlex = 80, maxFlex = 240)
        assertTrue(plan.scroll)
        assertEquals(listOf(240, 120, 120, 120), plan.widths)
    }

    @Test
    fun emptyTable() = assertEquals(TablePlan(emptyList(), false), planTable(emptyList(), emptyList(), 300, 80, 240))
}
