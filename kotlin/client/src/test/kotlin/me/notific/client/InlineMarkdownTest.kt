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
}
