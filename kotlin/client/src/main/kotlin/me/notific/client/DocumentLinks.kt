package me.notific.client

/**
 * Links in a text and which of them point to a document (docs/28, E19). The
 * same rule as `findDocumentLinks` in `packages/api-types` and the server
 * (`contacts/doclinks.go`):
 *
 * - on a questionnaire that asks for the signature, a document link in the
 *   title or text shows "To sign a document, send it as an envelope" (never
 *   blocks);
 * - on the screen of whoever signs, every link ([find]) shows "This link is
 *   not part of what you sign".
 *
 * The signature covers only the text shown: a link and the file behind it
 * are never part of what was signed.
 */
public object DocumentLinks {

    /** Extensions that make a link a document link, at the end of the path (before `?` and `#`), ignoring case. */
    public val EXTENSIONS: List<String> = listOf(".pdf", ".docx", ".doc", ".odt", ".rtf", ".pages")

    /** Hosts of document storage and e-signature services: the host itself or any subdomain of it. */
    public val HOSTS: List<String> = listOf(
        "docs.google.com",
        "drive.google.com",
        "dropbox.com",
        "dropboxusercontent.com",
        "onedrive.live.com",
        "1drv.ms",
        "sharepoint.com",
        "box.com",
        "icloud.com",
        "docusign.com",
        "docusign.net",
        "clicksign.com",
        "zapsign.com.br",
        "d4sign.com.br",
        "autentique.com.br",
        "adobesign.com",
        "echosign.com",
        "hellosign.com",
        "dropboxsign.com",
        "pandadoc.com",
        "signnow.com",
        "docsend.com",
    )

    // `\s` as in JavaScript: any Unicode space.
    private val LINK = Regex("""(?iU)https?://[^\s<>()"'`]+""")
    private val TRAILING = Regex("[.,;:!?]+$")

    /** Every `http`/`https` link in [text], in order and without repeats (trailing punctuation left out). */
    public fun find(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (m in LINK.findAll(text)) {
            val url = m.value.replace(TRAILING, "")
            if (url.isNotEmpty()) out += url
        }
        return out.toList()
    }

    /** The document links in [text], in order and without repeats. */
    public fun findDocuments(text: String): List<String> = find(text).filter(::isDocument)

    /** Whether one URL is a document link (by host or by extension). */
    public fun isDocument(url: String): Boolean {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return false
        val rest = url.substringAfter("://")
        val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
        var host = authority.substringAfterLast('@').lowercase()
        host = if (host.startsWith("[")) host.substringBefore(']') + "]" else host.substringBefore(':')
        if (host.isEmpty()) return false
        host = host.removePrefix("www.")
        if (HOSTS.any { host == it || host.endsWith(".$it") }) return true
        val path = rest.substring(authority.length).substringBefore('#').substringBefore('?').lowercase()
        return EXTENSIONS.any { path.endsWith(it) }
    }
}
