package me.notific.client.wear

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.notific.client.Question
import me.notific.client.Severity
import me.notific.client.Wire

/**
 * The protocol between a phone app and its watch app on the Wear OS Data
 * Layer (decided 2026-11-09, docs/20). Google Play services only connects
 * apps with the same package name and signing key, on paired devices,
 * encrypted. Everything goes by `MessageClient` with a few bytes: the watch
 * wakes only when a message arrives, and uses the network only when the
 * person acts.
 *
 * - **Link:** the watch asks ([PATH_LINK_REQUEST]); the phone asks the server
 *   for one-time codes and sends them ([PATH_LINK_CODES]); the watch exchanges
 *   each for a session of its own (`Auth.linkDevice`), as a device of the person.
 * - **Relay:** the phone, which watches the server, forwards what arrives
 *   ([PATH_RELAY]): id, kind, a short summary, severity.
 * - **Handled:** "got it", answered, read, being read, snoozed, either way
 *   ([PATH_HANDLED]): the other side stops ringing for it.
 * - **Open on watch:** the phone opens a watch screen ([PATH_OPEN_ON_WATCH],
 *   [screenUri]) through Google Play services: a critical alert takes the
 *   watch screen even with the watch app in the background.
 *
 * Declare the capability of each side in `res/values/wear.xml` of the app
 * (`notificme_phone` on the phone, `notificme_watch` on the watch).
 */
public object WatchLink {
    /** Watch → phone: "link me" (no payload). */
    public const val PATH_LINK_REQUEST: String = "/notificme/link-request"

    /** Phone → watch: one-time codes, one per account ([LinkCode]). */
    public const val PATH_LINK_CODES: String = "/notificme/link-codes"

    /** Phone → watch: what arrived ([RelayItem]s). */
    public const val PATH_RELAY: String = "/notificme/relay"

    /** Either way: handled on this device ([Handled]). */
    public const val PATH_HANDLED: String = "/notificme/handled"

    /** Watch → phone: "open this on me" ([screenUri]); the phone opens it on the watch that asked. */
    public const val PATH_OPEN_ON_WATCH: String = "/notificme/open-on-watch"

    /** Capability of the phone app. */
    public const val CAPABILITY_PHONE: String = "notificme_phone"

    /** Capability of the watch app. */
    public const val CAPABILITY_WATCH: String = "notificme_watch"

    /** Version of the payloads; a newer side keeps reading an older one (unknown fields are ignored). */
    public const val VERSION: Int = 1

    /** Scheme of the watch app's screens that the phone may open ([screenUri]). */
    public const val SCHEME: String = "notificme-wear"

    /** How long a [RelayItem.summary] may be; longer text is cut and [RelayItem.longText] is set. */
    public const val SUMMARY_MAX: Int = 280

    /** The link request: the accounts the watch already has, so the phone sends codes only for the others. */
    public fun encodeLinkRequest(linked: Collection<String>): ByteArray =
        encode(LinkRequest.serializer(), LinkRequest(VERSION, linked.distinct()))

    /** The accounts the watch already has; empty for an older watch's empty request (codes for all). */
    public fun decodeLinkRequest(bytes: ByteArray): Set<String> =
        if (bytes.isEmpty()) emptySet() else decode(LinkRequest.serializer(), bytes)?.linked.orEmpty().toSet()

    public fun encodeLinkCodes(codes: List<LinkCode>): ByteArray = encode(LinkCodes.serializer(), LinkCodes(VERSION, codes))

    public fun decodeLinkCodes(bytes: ByteArray): List<LinkCode> = decode(LinkCodes.serializer(), bytes)?.codes.orEmpty()

    public fun encodeRelay(items: List<RelayItem>): ByteArray = encode(Relay.serializer(), Relay(VERSION, items))

    public fun decodeRelay(bytes: ByteArray): List<RelayItem> = decode(Relay.serializer(), bytes)?.items.orEmpty()

    public fun encodeHandled(handled: Handled): ByteArray = encode(Handled.serializer(), handled)

    /** Null when the payload can't be read. */
    public fun decodeHandled(bytes: ByteArray): Handled? =
        decode(Handled.serializer(), bytes)?.takeIf { it.accountId.isNotBlank() && it.itemId.isNotBlank() }

    /** `notificme-wear://<screen>/<account id>/<item id>`: declare it on the watch activity (VIEW, BROWSABLE). */
    public fun screenUri(screen: String, accountId: String, itemId: String): String =
        "$SCHEME://$screen/${uriEncode(accountId)}/${uriEncode(itemId)}"

    /** The screen, account id and item id of a [screenUri]; null when it isn't one. */
    public fun parseScreenUri(uri: String): Triple<String, String, String>? {
        val rest = uri.removePrefix("$SCHEME://").takeIf { it != uri } ?: return null
        val parts = rest.split('/')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        return Triple(parts[0], uriDecode(parts[1]), uriDecode(parts[2]))
    }

    /** [text] cut to [SUMMARY_MAX] characters (on a word when it can). */
    public fun shortSummary(text: String): String {
        val t = text.trim()
        if (t.length <= SUMMARY_MAX) return t
        val cut = t.take(SUMMARY_MAX)
        val space = cut.lastIndexOf(' ')
        return (if (space > SUMMARY_MAX * 2 / 3) cut.take(space) else cut).trimEnd() + "…"
    }

    private fun <T> encode(s: kotlinx.serialization.KSerializer<T>, v: T): ByteArray =
        Wire.json.encodeToString(s, v).toByteArray(Charsets.UTF_8)

    private fun <T> decode(s: kotlinx.serialization.KSerializer<T>, bytes: ByteArray): T? =
        runCatching { Wire.json.decodeFromString(s, bytes.toString(Charsets.UTF_8)) }.getOrNull()

    // The String-charset overloads: the Charset ones need Android 13.
    private fun uriEncode(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun uriDecode(s: String) = java.net.URLDecoder.decode(s, "UTF-8")
}

/** A one-time code for one account of the phone: the watch exchanges it for a session of its own. */
@Serializable
public data class LinkCode(
    /** The phone's id for the account (to tell the watch's accounts apart). */
    @SerialName("account_id") val accountId: String,
    /** API origin of the account, e.g. `https://api.notific.me`. */
    @SerialName("base_url") val baseUrl: String,
    /** Short name of the server shown next to what it sends (`notific.me`, `Home lab`). */
    val label: String = "",
    /** The account's color index in the phone app, so both show the same. */
    val color: Int = 0,
    val code: String,
    @SerialName("expires_at") val expiresAt: String = "",
)

/**
 * Something that arrived, forwarded by the phone with only what the watch
 * needs to show it and to answer it from the wrist (no network to show it).
 */
@Serializable
public data class RelayItem(
    @SerialName("account_id") val accountId: String,
    /** Notification id. */
    val id: String,
    val severity: Severity = Severity.Info,
    val title: String = "",
    /** At most [WatchLink.SUMMARY_MAX] characters. */
    val summary: String = "",
    val source: String = "",
    val type: String = "",
    @SerialName("created_at") val createdAt: String = "",
    /** Waits for "got it" (a critical alert or a message from a person). */
    @SerialName("asks_ack") val asksAck: Boolean = false,
    /** There is more to read than [summary] (a long text, a card): open it to read. */
    @SerialName("long_text") val longText: Boolean = false,
    /** It came back after a snooze: ring again even if it was shown. */
    val again: Boolean = false,
    /** The decision it carries, if any. */
    val decision: RelayDecision? = null,
)

/**
 * The decision of a [RelayItem]: what a reply needs ([version], [contentHash],
 * [level]) and the questions, so the watch can answer from the notification
 * with one request.
 */
@Serializable
public data class RelayDecision(
    @SerialName("question_set_id") val questionSetId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    val version: Int = 1,
    @SerialName("content_hash") val contentHash: String? = null,
    val level: Int = 0,
    val title: String = "",
    @SerialName("timeout_at") val timeoutAt: String? = null,
    @SerialName("require_signature") val requireSignature: Boolean = false,
    /** False when a decision rule only lets the person watch it. */
    @SerialName("can_decide") val canDecide: Boolean = true,
    val questions: List<Question> = emptyList(),
)

/**
 * An item handled on one device ([WatchLink.PATH_HANDLED]): the other side
 * stops ringing for it. [itemId] is a notification id, or a question set id
 * when [decision].
 */
@Serializable
public data class Handled(
    @SerialName("account_id") val accountId: String,
    @SerialName("item_id") val itemId: String,
    val kind: Kind = Kind.Acknowledged,
    val decision: Boolean = false,
) {
    @Serializable
    public enum class Kind {
        /** "Got it": it stops for good. */
        @SerialName("acknowledged") Acknowledged,

        /** The decision was answered: it stops for good. */
        @SerialName("answered") Answered,

        /** Read: its notification leaves the other side. */
        @SerialName("read") Read,

        /** Opened to read before "got it": the alarm stops, it still waits. */
        @SerialName("engaged") Engaged,

        /** "Remind me later": the alarm stops and comes back later. */
        @SerialName("snoozed") Snoozed,
    }

    /** It stops for good on the other side (not only the alarm). */
    public val done: Boolean get() = kind == Kind.Acknowledged || kind == Kind.Answered || kind == Kind.Read
}

@Serializable
internal data class LinkRequest(val version: Int = WatchLink.VERSION, val linked: List<String> = emptyList())

@Serializable
internal data class LinkCodes(val version: Int = WatchLink.VERSION, val codes: List<LinkCode> = emptyList())

@Serializable
internal data class Relay(val version: Int = WatchLink.VERSION, val items: List<RelayItem> = emptyList())
