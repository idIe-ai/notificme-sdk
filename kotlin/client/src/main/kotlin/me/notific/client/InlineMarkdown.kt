package me.notific.client

import java.net.URI

/**
 * Light Markdown of the card protocol: what the `text` block and a question's
 * `context` may use.
 *
 * Inline marks:
 * - `**bold**`, `*italic*` (or `_italic_`), `` `code` ``;
 * - `[label](https://…)` and a bare http(s) address, which becomes a link;
 * - `\` before one of those marks shows the mark as text;
 * - line breaks are kept.
 *
 * Blocks, read line by line (split on `\n`), before the inline marks ([blocks]):
 * - `- item` at column 0: a bullet item;
 * - `1. item` or `1) item` (1 to 3 digits) at column 0: a numbered item, the
 *   number kept as written (never renumbered);
 * - `>` at column 0, then an optional space: a quote line;
 * - consecutive lines of the same kind form one list or one quote; an empty
 *   line, or a line of another kind, ends it;
 * - any other line is paragraph text, as before (line breaks and empty lines
 *   kept); `\- `, `\>` and `\1. ` at the start show the marker as text.
 *
 * Nothing else: no HTML, no images, no headings, no tables, no nesting (a
 * marker after leading spaces is text). Links only with http or https; any
 * other scheme shows just the label, as text. A notification `summary` is not
 * Markdown: with `markdown = false` only bare addresses become links, and
 * there are no blocks.
 *
 * The same algorithm runs on the web (`packages/ui/src/lib/inlineMarkdown.ts`)
 * and on the server (`server/internal/protocol/markdown.go`): change the three
 * together.
 */
public object InlineMarkdown {
    /** Splits [text] into spans (see [InlineMarkdown]), without the block rules. */
    public fun parse(text: String, markdown: Boolean = true): List<InlineSpan> = Parser(text, markdown).run()

    /**
     * Splits [text] into paragraphs, lists and quotes (see [InlineMarkdown]),
     * each with its inline spans. With [markdown] false (a notification
     * summary) the whole text is one [MdBlock.Text] of [parse].
     */
    public fun blocks(text: String, markdown: Boolean = true): List<MdBlock> =
        if (markdown) BlockParser.run(text) else listOf(MdBlock.Text(parse(text, markdown = false)))

    /**
     * Clean text without marks (`[x](url)` → `x`), for push, full-screen
     * alerts and list previews. A bullet item becomes `• item`, a numbered
     * one `3. item` (the number as written) and a quote line just its text;
     * lines stay separated by `\n`.
     */
    public fun plain(text: String): String = blocks(text).joinToString("\n") { block ->
        when (block) {
            is MdBlock.Text -> block.spans.plain()
            is MdBlock.ListBlock -> block.items.joinToString("\n") { item ->
                (if (item.number != null) "${item.number}. " else "• ") + item.spans.plain()
            }
            is MdBlock.Quote -> block.lines.joinToString("\n") { it.plain() }
        }
    }

    private fun List<InlineSpan>.plain(): String = joinToString("") { it.text }

    /** Normalized http(s) URL, or null for any other scheme or an invalid one. */
    public fun safeUrl(url: String): String? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (u.host.isNullOrBlank()) return null
        return u.toASCIIString()
    }

    internal fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
}

/** A block of [InlineMarkdown] text ([InlineMarkdown.blocks]). */
public sealed interface MdBlock {
    /**
     * Paragraph text: one or more lines, joined by `\n` and parsed together,
     * as before blocks existed (an empty line is kept, as spacing).
     */
    public data class Text(val spans: List<InlineSpan>) : MdBlock

    /** A bullet list ([ordered] false) or a numbered one. No nesting. */
    public data class ListBlock(val ordered: Boolean, val items: List<Item>) : MdBlock {
        /** One item: [number] as written (`3`, `10`), null in a bullet list. */
        public data class Item(val number: String?, val spans: List<InlineSpan>)
    }

    /** A quote: its lines without the `>`, each with its spans. */
    public data class Quote(val lines: List<List<InlineSpan>>) : MdBlock
}

/** Groups the lines of a text into [MdBlock]s; the spans of each come from [Parser]. */
private object BlockParser {
    private val NUMBERED = Regex("^(\\d{1,3})[.)] ")

    private sealed interface Line {
        class Para(val text: String) : Line
        class Item(val number: String?, val text: String) : Line
        class Quote(val text: String) : Line
    }

    private fun classify(line: String): Line {
        if (line.startsWith("- ")) return Line.Item(null, line.substring(2))
        NUMBERED.find(line)?.let { m -> return Line.Item(m.groupValues[1], line.substring(m.range.last + 1)) }
        if (line.startsWith(">")) return Line.Quote(line.substring(if (line.startsWith("> ")) 2 else 1))
        // `\- x`, `\> x`, `\1. x`: the marker shown as text, without the backslash.
        if (line.startsWith("\\") && classify(line.substring(1)) !is Line.Para) return Line.Para(line.substring(1))
        return Line.Para(line)
    }

    private fun inline(text: String): List<InlineSpan> = Parser(text, markdown = true).run()

    fun run(text: String): List<MdBlock> {
        val out = mutableListOf<MdBlock>()
        val para = mutableListOf<String>()
        val items = mutableListOf<MdBlock.ListBlock.Item>()
        val quote = mutableListOf<List<InlineSpan>>()

        fun flush() {
            if (para.isNotEmpty()) out += MdBlock.Text(inline(para.joinToString("\n")))
            if (items.isNotEmpty()) out += MdBlock.ListBlock(ordered = items.first().number != null, items = items.toList())
            if (quote.isNotEmpty()) out += MdBlock.Quote(quote.toList())
            para.clear()
            items.clear()
            quote.clear()
        }

        for (raw in text.split("\n")) {
            when (val line = classify(raw)) {
                is Line.Para -> {
                    if (items.isNotEmpty() || quote.isNotEmpty()) flush()
                    para += line.text
                }
                is Line.Item -> {
                    // A bullet after a numbered item (or the other way round) starts another list.
                    val sameList = items.isNotEmpty() && (items.first().number != null) == (line.number != null)
                    if (!sameList) flush()
                    items += MdBlock.ListBlock.Item(line.number, inline(line.text))
                }
                is Line.Quote -> {
                    if (para.isNotEmpty() || items.isNotEmpty()) flush()
                    quote += inline(line.text)
                }
            }
        }
        flush()
        return out
    }
}

/** A piece of [InlineMarkdown] text. */
public sealed interface InlineSpan {
    public val text: String

    public data class Text(override val text: String, val bold: Boolean = false, val italic: Boolean = false) : InlineSpan

    public data class Code(override val text: String) : InlineSpan

    /**
     * A link. [host] is the destination's domain; [showHost] says the label
     * does not reveal the destination, so the domain should be shown next to it.
     */
    public data class Link(
        override val text: String,
        val url: String,
        val host: String,
        val showHost: Boolean,
        val bold: Boolean = false,
        val italic: Boolean = false,
    ) : InlineSpan
}

private class Parser(private val s: String, private val markdown: Boolean) {
    private val out = mutableListOf<InlineSpan>()
    private val buf = StringBuilder()
    private var bold = false
    private var italic = false
    private var italicMark = ' '

    fun run(): List<InlineSpan> {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (markdown && c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
                buf.append(s[i + 1])
                i += 2
                continue
            }
            if (markdown && c == '`') {
                val end = s.indexOf('`', i + 1)
                if (end > i + 1) {
                    flush()
                    out += InlineSpan.Code(s.substring(i + 1, end))
                    i = end + 1
                    continue
                }
            }
            if (markdown && c == '[') {
                val link = labeledLink(i)
                if (link != null) {
                    flush()
                    val url = InlineMarkdown.safeUrl(link.url)
                    when {
                        url != null -> out += linkSpan(link.label, url)
                        link.label.isNotEmpty() -> out += InlineSpan.Text(link.label, bold, italic)
                    }
                    i = link.end
                    continue
                }
            }
            if (markdown && c == '*' && s.getOrNull(i + 1) == '*') {
                if (bold || (hasCloser(i + 2, "**") && !s.getOrElse(i + 2) { ' ' }.isWhitespace())) {
                    flush()
                    bold = !bold
                    i += 2
                    continue
                }
            }
            if (markdown && (c == '*' || c == '_')) {
                if (italic && c == italicMark) {
                    flush()
                    italic = false
                    i += 1
                    continue
                }
                val prev = s.getOrElse(i - 1) { ' ' }
                val next = s.getOrElse(i + 1) { ' ' }
                val opensWord = c == '*' || !prev.isLetterOrDigit()
                if (!italic && opensWord && !next.isWhitespace() && next != c && hasCloser(i + 1, c.toString())) {
                    flush()
                    italic = true
                    italicMark = c
                    i += 1
                    continue
                }
            }
            if ((c == 'h' || c == 'H') && startsUrl(i)) {
                val raw = bareUrl(i)
                val url = raw?.let(InlineMarkdown::safeUrl)
                if (raw != null && url != null) {
                    flush()
                    out += InlineSpan.Link(raw, url, InlineMarkdown.hostOf(url), showHost = false, bold = bold, italic = italic)
                    i += raw.length
                    continue
                }
            }
            buf.append(c)
            i += 1
        }
        flush()
        return out
    }

    private fun flush() {
        if (buf.isEmpty()) return
        out += InlineSpan.Text(buf.toString(), bold, italic)
        buf.setLength(0)
    }

    private fun linkSpan(label: String, url: String): InlineSpan.Link {
        val host = InlineMarkdown.hostOf(url)
        val shown = label.trim().ifEmpty { url }
        return InlineSpan.Link(shown, url, host, showHost = !labelShowsDestination(shown, url, host), bold = bold, italic = italic)
    }

    private fun hasCloser(from: Int, mark: String): Boolean {
        val at = s.indexOf(mark, from)
        if (at <= from) return false
        return !s[at - 1].isWhitespace()
    }

    private class Labeled(val label: String, val url: String, val end: Int)

    private fun labeledLink(start: Int): Labeled? {
        val close = s.indexOf("](", start + 1)
        if (close < 0) return null
        val label = s.substring(start + 1, close)
        if ('\n' in label || '[' in label) return null
        var depth = 0
        var j = close + 2
        while (j < s.length) {
            val ch = s[j]
            if (ch.isWhitespace()) return null
            if (ch == '(') {
                depth++
            } else if (ch == ')') {
                if (depth == 0) {
                    val url = s.substring(close + 2, j)
                    return if (url.isEmpty()) null else Labeled(label, url, j + 1)
                }
                depth--
            }
            j++
        }
        return null
    }

    private fun startsUrl(i: Int): Boolean {
        val prev = s.getOrNull(i - 1)
        if (prev != null && (prev.isLetterOrDigit() || prev == '_' || prev == '/')) return false
        val head = s.substring(i, minOf(s.length, i + 8)).lowercase()
        return head.startsWith("http://") || head.startsWith("https://")
    }

    private fun bareUrl(start: Int): String? {
        var end = start
        while (end < s.length && !s[end].isWhitespace() && s[end] !in "<>\"") end++
        var url = s.substring(start, end)
        while (true) {
            url = when {
                url.isNotEmpty() && url.last() in TRAILING_PUNCT -> url.dropLast(1)
                url.endsWith(")") && url.count { it == '(' } < url.count { it == ')' } -> url.dropLast(1)
                else -> break
            }
        }
        return if (Regex("^https?://[^/]", RegexOption.IGNORE_CASE).containsMatchIn(url)) url else null
    }

    private companion object {
        val ESCAPABLE = setOf('\\', '*', '_', '`', '[', ']', '(', ')')
        const val TRAILING_PUNCT = ".,;:!?'\""

        fun labelShowsDestination(label: String, url: String, host: String): Boolean {
            val l = label.trim().lowercase().trimEnd('/')
            val u = url.lowercase().trimEnd('/')
            val noScheme = u.removePrefix("https://").removePrefix("http://")
            return l == u || l == noScheme || l == host.lowercase() || l == noScheme.removePrefix("www.")
        }
    }
}
