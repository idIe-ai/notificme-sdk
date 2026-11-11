package me.notific.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors `apps/web/src/pages/insights/richText.test.tsx`: both parsers must agree. */
class InlineMarkdownTest {
    @Test
    fun boldItalicAndCode() {
        assertEquals(
            listOf(
                InlineSpan.Text("a "),
                InlineSpan.Text("b", bold = true),
                InlineSpan.Text(" "),
                InlineSpan.Text("c", italic = true),
                InlineSpan.Text(" "),
                InlineSpan.Text("d", italic = true),
                InlineSpan.Text(" "),
                InlineSpan.Code("e"),
            ),
            InlineMarkdown.parse("a **b** *c* _d_ `e`"),
        )
    }

    @Test
    fun labeledLinkShowsHostWhenLabelHidesIt() {
        assertEquals(
            listOf(InlineSpan.Link("Estudo", "https://claude.ai/artifact/X", "claude.ai", showHost = true)),
            InlineMarkdown.parse("[Estudo](https://claude.ai/artifact/X)"),
        )
        val same = InlineMarkdown.parse("[claude.ai](https://claude.ai/)").single() as InlineSpan.Link
        assertEquals(false, same.showHost)
    }

    @Test
    fun bareUrlWithoutTrailingPunctuation() {
        val links = InlineMarkdown.parse("Veja https://claude.ai/artifact/XyaB. E (https://exemplo.com/a_(b)).")
            .filterIsInstance<InlineSpan.Link>()
        assertEquals(listOf("https://claude.ai/artifact/XyaB", "https://exemplo.com/a_(b)"), links.map { it.text })
        assertEquals("Veja https://claude.ai/artifact/XyaB.", InlineMarkdown.plain("Veja https://claude.ai/artifact/XyaB."))
    }

    @Test
    fun onlyHttpAndHttps() {
        assertEquals(listOf(InlineSpan.Text("clique")), InlineMarkdown.parse("[clique](javascript:alert(1))"))
        assertTrue(InlineMarkdown.parse("ftp://x.com e data:text/html,oi").all { it is InlineSpan.Text })
    }

    @Test
    fun strayMarksStayText() {
        assertEquals("2 * 3 * 4 e snake_case_nome e **sem fim", InlineMarkdown.plain("2 * 3 * 4 e snake_case_nome e **sem fim"))
        assertEquals("*literal* e [x](y)", InlineMarkdown.plain("\\*literal\\* e \\[x\\](y)"))
        assertTrue(InlineMarkdown.parse("\\*literal\\*").all { it is InlineSpan.Text })
    }

    @Test
    fun summaryOnlyLinksBareUrls() {
        val spans = InlineMarkdown.parse("**não** _é_ `md`: https://b.com", markdown = false)
        assertEquals(listOf(InlineSpan.Text("**não** _é_ `md`: ")), spans.take(1))
        assertTrue(spans[1] is InlineSpan.Link)
        assertEquals(2, spans.size)
    }

    @Test
    fun plainTextForPushAndPreviews() {
        assertEquals(
            "Leia o estudo em docs/18 e make run.",
            InlineMarkdown.plain("Leia o **estudo** em [docs/18](https://x.com/18) e `make run`."),
        )
        assertEquals("linha 1\nlinha 2", InlineMarkdown.plain("linha 1\nlinha 2"))
    }

    // ---------------------------------------------------------------- blocks: lists and quotes
    // The same cases run in TypeScript (packages/ui) and Go (server/internal/protocol).

    private fun text(s: String) = InlineSpan.Text(s)
    private fun item(number: String?, vararg spans: InlineSpan) = MdBlock.ListBlock.Item(number, spans.toList())

    @Test
    fun bulletListOfThreeWithBoldAndLink() {
        val src = "- **um** item\n- veja [docs](https://x.com/d)\n- três"
        assertEquals(
            listOf(
                MdBlock.ListBlock(
                    ordered = false,
                    items = listOf(
                        item(null, InlineSpan.Text("um", bold = true), text(" item")),
                        item(null, text("veja "), InlineSpan.Link("docs", "https://x.com/d", "x.com", showHost = true)),
                        item(null, text("três")),
                    ),
                ),
            ),
            InlineMarkdown.blocks(src),
        )
        assertEquals("• um item\n• veja docs\n• três", InlineMarkdown.plain(src))
    }

    @Test
    fun numberedListKeepsNumbersAsWritten() {
        val src = "3. três\n10) dez"
        assertEquals(
            listOf(MdBlock.ListBlock(ordered = true, items = listOf(item("3", text("três")), item("10", text("dez"))))),
            InlineMarkdown.blocks(src),
        )
        assertEquals("3. três\n10. dez", InlineMarkdown.plain(src))
    }

    @Test
    fun quoteOfTwoLines() {
        val src = "> primeira *linha*\n>segunda"
        assertEquals(
            listOf(MdBlock.Quote(listOf(listOf(text("primeira "), InlineSpan.Text("linha", italic = true)), listOf(text("segunda"))))),
            InlineMarkdown.blocks(src),
        )
        assertEquals("primeira linha\nsegunda", InlineMarkdown.plain(src))
    }

    @Test
    fun bulletRightAfterNumberedStartsAnotherList() {
        val src = "1. a\n- b"
        assertEquals(
            listOf(
                MdBlock.ListBlock(ordered = true, items = listOf(item("1", text("a")))),
                MdBlock.ListBlock(ordered = false, items = listOf(item(null, text("b")))),
            ),
            InlineMarkdown.blocks(src),
        )
        assertEquals("1. a\n• b", InlineMarkdown.plain(src))
    }

    @Test
    fun emptyLineBetweenBulletGroupsMakesTwoLists() {
        val src = "- a\n\n- b"
        assertEquals(
            listOf(
                MdBlock.ListBlock(ordered = false, items = listOf(item(null, text("a")))),
                MdBlock.Text(emptyList()),
                MdBlock.ListBlock(ordered = false, items = listOf(item(null, text("b")))),
            ),
            InlineMarkdown.blocks(src),
        )
        assertEquals("• a\n\n• b", InlineMarkdown.plain(src))
    }

    @Test
    fun leadingSpacesMakeText() {
        assertEquals(listOf(MdBlock.Text(listOf(text("  - x")))), InlineMarkdown.blocks("  - x"))
        assertEquals("  - x", InlineMarkdown.plain("  - x"))
        assertEquals(listOf(MdBlock.Text(listOf(text(" > x\n 1. y")))), InlineMarkdown.blocks(" > x\n 1. y"))
    }

    @Test
    fun escapedMarkersAreText() {
        assertEquals(listOf(MdBlock.Text(listOf(text("- x")))), InlineMarkdown.blocks("\\- x"))
        assertEquals("- x", InlineMarkdown.plain("\\- x"))
        assertEquals(listOf(MdBlock.Text(listOf(text("> x")))), InlineMarkdown.blocks("\\> x"))
        assertEquals("> x", InlineMarkdown.plain("\\> x"))
        assertEquals(listOf(MdBlock.Text(listOf(text("1. x")))), InlineMarkdown.blocks("\\1. x"))
        assertEquals("1. x", InlineMarkdown.plain("\\1. x"))
    }

    @Test
    fun markerWithoutSpaceIsText() {
        assertEquals(listOf(MdBlock.Text(listOf(text("-x")))), InlineMarkdown.blocks("-x"))
        assertEquals("-x", InlineMarkdown.plain("-x"))
        assertEquals(listOf(MdBlock.Text(listOf(text("1.x\n1234. x\n-")))), InlineMarkdown.blocks("1.x\n1234. x\n-"))
        assertEquals("1.x\n1234. x\n-", InlineMarkdown.plain("1.x\n1234. x\n-"))
    }

    @Test
    fun paragraphsAroundListsKeepTheirLines() {
        val src = "Antes:\nlinha 2\n- a\n- b\n\nDepois"
        assertEquals(
            listOf(
                MdBlock.Text(listOf(text("Antes:\nlinha 2"))),
                MdBlock.ListBlock(ordered = false, items = listOf(item(null, text("a")), item(null, text("b")))),
                MdBlock.Text(listOf(text("\nDepois"))),
            ),
            InlineMarkdown.blocks(src),
        )
        assertEquals("Antes:\nlinha 2\n• a\n• b\n\nDepois", InlineMarkdown.plain(src))
        // Without blocks the text is a single paragraph, as before.
        assertEquals(listOf(MdBlock.Text(InlineMarkdown.parse("a **b**\nc"))), InlineMarkdown.blocks("a **b**\nc"))
    }

    @Test
    fun summaryHasNoBlocks() {
        assertEquals(
            listOf(MdBlock.Text(listOf(text("- a\n> b")))),
            InlineMarkdown.blocks("- a\n> b", markdown = false),
        )
    }
}
