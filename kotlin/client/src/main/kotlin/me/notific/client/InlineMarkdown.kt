package me.notific.client

import java.net.URI

/**
 * Light Markdown of the card protocol: what the `text` block and a question's
 * `context` may use.
 *
 * - `**bold**`, `*italic*` (or `_italic_`), `` `code` ``;
 * - `[label](https://…)` and a bare http(s) address, which becomes a link;
 * - `\` before one of those marks shows the mark as text;
 * - line breaks are kept.
 *
 * Nothing else: no HTML, no images, no headings or lists. Links only with
 * http or https; any other scheme shows just the label, as text. A
 * notification `summary` is not Markdown: with `markdown = false` only bare
 * addresses become links.
 *
 * The same algorithm runs on the web (`packages/ui/src/lib/inlineMarkdown.ts`):
 * change both together.
 */
public object InlineMarkdown {
    /** Splits [text] into spans (see [InlineMarkdown]). */
    public fun parse(text: String, markdown: Boolean = true): List<InlineSpan> = Parser(text, markdown).run()

    /** Clean text without marks (`[x](url)` → `x`), for push, full-screen alerts and list previews. */
    public fun plain(text: String): String = parse(text).joinToString("") { it.text }

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
