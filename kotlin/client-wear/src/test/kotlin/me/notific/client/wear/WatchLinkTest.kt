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
}
