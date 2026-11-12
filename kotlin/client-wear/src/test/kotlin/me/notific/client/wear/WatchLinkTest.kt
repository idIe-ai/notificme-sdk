package me.notific.client.wear

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.notific.client.Question
import me.notific.client.QuestionType
import me.notific.client.Severity

class WatchLinkTest {
    @Test
    fun `link codes survive the trip`() {
        val codes = listOf(LinkCode("a1", "https://api-notificme.idie.me", "Home lab", 3, "nm_dl_x", "2026-11-09T20:02:00Z"))
        assertEquals(codes, WatchLink.decodeLinkCodes(WatchLink.encodeLinkCodes(codes)))
        assertEquals(emptyList(), WatchLink.decodeLinkCodes("not json".toByteArray()))
        // A newer phone may add fields.
        val newer = """{"version":2,"codes":[{"account_id":"a1","base_url":"https://x","code":"nm_dl_y","extra":1}],"more":true}"""
        assertEquals("nm_dl_y", WatchLink.decodeLinkCodes(newer.toByteArray()).single().code)
    }

    @Test
    fun `link request names the accounts the watch already has`() {
        assertEquals(setOf("a1", "a2"), WatchLink.decodeLinkRequest(WatchLink.encodeLinkRequest(listOf("a1", "a2", "a1"))))
        // An older watch sends nothing: codes for every account.
        assertEquals(emptySet(), WatchLink.decodeLinkRequest(ByteArray(0)))
        assertEquals(emptySet(), WatchLink.decodeLinkRequest("garbage".toByteArray()))
    }

    @Test
    fun `relay carries what the wrist needs, in few bytes`() {
        val item = RelayItem(
            accountId = "a1", id = "n1", severity = Severity.Critical, title = "Servidor fora do ar",
            summary = WatchLink.shortSummary("x ".repeat(400)), source = "dokploy", createdAt = "2026-11-09T20:00:00Z",
            asksAck = true, longText = true,
            decision = RelayDecision("q1", version = 2, contentHash = "sha256:a", level = 0, questions = listOf(Question("ok", QuestionType.YesNo, "Reiniciar?"))),
        )
        val bytes = WatchLink.encodeRelay(listOf(item))
        assertEquals(listOf(item), WatchLink.decodeRelay(bytes))
        assertTrue(bytes.size < 1024, "relay of one item: ${bytes.size} bytes")
        assertTrue(item.summary.length <= WatchLink.SUMMARY_MAX + 1)
    }

    @Test
    fun `short summaries cut on a word`() {
        assertEquals("curto", WatchLink.shortSummary("  curto "))
        val long = ("palavra ").repeat(60)
        val cut = WatchLink.shortSummary(long)
        assertTrue(cut.endsWith("…") && !cut.contains("palavr…") && cut.length <= WatchLink.SUMMARY_MAX + 1, cut)
    }

    @Test
    fun `handled items survive the trip`() {
        val h = Handled("a1", "q1", Handled.Kind.Answered, decision = true)
        assertEquals(h, WatchLink.decodeHandled(WatchLink.encodeHandled(h)))
        assertTrue(h.done)
        assertFalse(Handled("a1", "n1", Handled.Kind.Engaged).done)
        assertFalse(Handled("a1", "n1", Handled.Kind.Snoozed).done)
        assertEquals(Handled.Kind.Acknowledged, WatchLink.decodeHandled("""{"account_id":"a1","item_id":"n1"}""".toByteArray())?.kind)
        assertNull(WatchLink.decodeHandled("""{"account_id":"","item_id":"n1"}""".toByteArray()))
        assertNull(WatchLink.decodeHandled("nope".toByteArray()))
    }

    @Test
    fun `screen uris carry the account and the item`() {
        val uri = WatchLink.screenUri("alert", "a 1", "n/1")
        assertEquals("notificme-wear://alert/a%201/n%2F1", uri)
        assertEquals(Triple("alert", "a 1", "n/1"), WatchLink.parseScreenUri(uri))
        assertNull(WatchLink.parseScreenUri("https://evil.example/alert/a/n"))
        assertNull(WatchLink.parseScreenUri("notificme-wear://alert/a"))
    }

    @Test
    fun `icon key is the sha1 of the url plus the shape, empty without one`() {
        val url = "https://api.notific.me/v0/avatars/u1-abc.png"
        val round = WatchLink.iconKey(url, round = true)
        val square = WatchLink.iconKey(url, round = false)
        // sha1("https://api.notific.me/v0/avatars/u1-abc.png"), the same name the phone's cache uses.
        val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        assertEquals("$sha1-round", round)
        assertEquals("$sha1-square", square)
        assertTrue(WatchLink.iconIsRound(round) && !WatchLink.iconIsRound(square))
        assertEquals("", WatchLink.iconKey(null, true))
        assertEquals("", WatchLink.iconKey(" ", false))
        assertEquals(round, WatchLink.iconKeyOf(WatchLink.iconPath(round)))
        assertEquals(null, WatchLink.iconKeyOf("/notificme/icon/../../etc"))
        assertEquals(null, WatchLink.iconKeyOf("/notificme/relay"))
    }

    @Test
    fun `the relay carries only the icon key, and an older payload reads without it`() {
        val item = RelayItem(accountId = "a1", id = "n1", source = "claude_code", sourceName = "Claude", icon = WatchLink.iconKey("https://x/y.png", true))
        assertEquals(listOf(item), WatchLink.decodeRelay(WatchLink.encodeRelay(listOf(item))))
        val old = """{"version":1,"items":[{"account_id":"a1","id":"n1"}]}""".toByteArray()
        assertEquals("", WatchLink.decodeRelay(old).single().icon)
        assertEquals("", WatchLink.decodeRelay(old).single().sourceName)
    }

    @Test
    fun `icon ledger sends each key once and drops the oldest past the limit`() {
        val ledger = IconLedger(max = 3)
        assertTrue(!ledger.has("a"))
        assertEquals(emptyList(), ledger.mark("a"))
        ledger.mark("b")
        ledger.mark("c")
        assertTrue(ledger.has("a"))
        // Used again: it moves to the end, so "b" is the oldest now.
        ledger.mark("a")
        assertEquals(listOf("b"), ledger.mark("d"))
        assertEquals(listOf("c", "a", "d"), ledger.sent)
        // Restored from disk, it keeps only the newest.
        assertEquals(listOf("c", "d"), IconLedger(listOf("a", "b", "c", "d"), max = 2).sent)
    }

    @Test
    fun `icon request keeps only well-formed wants, at most the limit`() {
        val good = (1..40).map { i -> "https://api.notific.me/v0/avatars/$i.png".let { IconWant(WatchLink.iconKey(it, i % 2 == 0), it, i % 2 == 0) } }
        val forged = IconWant(WatchLink.iconKey("https://a/x.png", true), "https://evil/x.png", true)
        val notHttp = "file:///etc/passwd".let { IconWant(WatchLink.iconKey(it, false), it, false) }
        val decoded = WatchLink.decodeIconRequest(WatchLink.encodeIconRequest(listOf(forged, notHttp) + good))
        // The encoder already cuts at the limit; the decoder drops what doesn't match its key.
        assertEquals(good.take(WatchLink.ICON_REQUEST_MAX - 2), decoded)
        assertTrue(WatchLink.decodeIconRequest("nonsense".toByteArray()).isEmpty())
    }
}
