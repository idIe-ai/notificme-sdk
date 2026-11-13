package me.notific.client

import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Canonical JSON of envelopes (server/docs/api-envelopes.md, "JSON canônico
 * que o aparelho assina"): the subset of JCS (RFC 8785) the device payload,
 * the field layout and the signing record use.
 *
 * - keys of each object in ascending order of their UTF-8 bytes; no spaces;
 * - strings escape only `"`, `\` and U+0000..U+001F (`\b`, `\t`, `\n`, `\f`,
 *   `\r`, the others as `\u00xx` in lowercase hex); everything else goes as
 *   is in UTF-8 (`/`, `<`, `>`, `&`, accents, emoji, U+2028, U+2029). Text is
 *   never normalized. A lone surrogate is refused;
 * - numbers: integers only (no fractions, no exponent); `true`, `false`, `null`.
 *
 * The server writes the same bytes (`internal/modules/envelopes/canon.go`);
 * both are tested against the same vectors
 * (`src/test/resources/envelope-canon-vectors.json`).
 */
public object EnvelopeCanon {

    /** The canonical text of [value]. Throws [IllegalArgumentException] on a fraction or a lone surrogate. */
    public fun encode(value: JsonElement): String = StringBuilder().also { write(value, it) }.toString()

    /** The canonical UTF-8 bytes of [value]. */
    public fun bytes(value: JsonElement): ByteArray = encode(value).toByteArray(Charsets.UTF_8)

    /** `"sha256:" + hex` of the canonical bytes of [value] (the `layout_sha256`, `record_sha256` format). */
    public fun sha256(value: JsonElement): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes(value)).joinToString("") { "%02x".format(it) }

    /** Compares two strings by their UTF-8 bytes (not by UTF-16 code units, which differ past U+FFFF). */
    public fun compareUtf8(a: String, b: String): Int {
        // Code point order is UTF-8 byte order.
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a.codePointAt(i)
            val cb = b.codePointAt(j)
            if (ca != cb) return ca.compareTo(cb)
            i += Character.charCount(ca)
            j += Character.charCount(cb)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

    private fun write(v: JsonElement, out: StringBuilder) {
        when (v) {
            is JsonNull -> out.append("null")
            is JsonObject -> {
                out.append('{')
                v.keys.sortedWith(::compareUtf8).forEachIndexed { i, k ->
                    if (i > 0) out.append(',')
                    string(k, out)
                    out.append(':')
                    write(v.getValue(k), out)
                }
                out.append('}')
            }
            is JsonArray -> {
                out.append('[')
                v.forEachIndexed { i, e ->
                    if (i > 0) out.append(',')
                    write(e, out)
                }
                out.append(']')
            }
            is JsonPrimitive -> when {
                v.isString -> string(v.content, out)
                v.content == "true" || v.content == "false" -> out.append(v.content)
                INTEGER.matches(v.content) -> out.append(v.content)
                else -> throw IllegalArgumentException("canonical JSON takes integers only: ${v.content}")
            }
        }
    }

    private fun string(s: String, out: StringBuilder) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\b' -> out.append("\\b")
                c == '\t' -> out.append("\\t")
                c == '\n' -> out.append("\\n")
                c == '\u000C' -> out.append("\\f")
                c == '\r' -> out.append("\\r")
                c < ' ' -> out.append("\\u00").append(HEX[c.code shr 4]).append(HEX[c.code and 0xF])
                Character.isHighSurrogate(c) -> {
                    require(i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { "lone surrogate in canonical JSON" }
                    out.append(c).append(s[i + 1])
                    i++
                }
                Character.isLowSurrogate(c) -> throw IllegalArgumentException("lone surrogate in canonical JSON")
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private const val HEX = "0123456789abcdef"
}
