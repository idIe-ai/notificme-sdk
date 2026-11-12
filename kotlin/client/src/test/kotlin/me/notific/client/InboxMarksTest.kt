package me.notific.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The organized inbox (docs/27): marks and rules on the server, never evidence. */
class InboxMarksTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): NotificClient {
        val engine = MockEngine { req ->
            requests += req
            handler(req)
        }
        return NotificClient(TokenProvider { "nm_ses_test" }, NotificClient.Config(baseUrl = "https://api.test/", httpClient = HttpClient(engine), platform = "android"))
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.bodyJson(): JsonObject = Wire.json.parseToJsonElement((body as TextContent).text).jsonObject

    private val result = """{"applied":180,"unchanged":2,"waiting":3,"waiting_by_reason":{"decision":2,"ack":1},"kept_pinned":1,"not_found":["x"],"complete":true}"""

    @Test
    fun markInboxSendsIdsAndOnlyTheChangedMarks() = runBlocking<Unit> {
        val c = client { json(result) }
        val r = c.markInbox(listOf("n1", "n2", "n1"), InboxMarkChange(archived = true))
        assertEquals(180, r.applied)
        assertEquals(2, r.waitingFor(InboxWaiting.Decision))
        assertEquals(1, r.waitingFor(InboxWaiting.Ack))
        assertEquals(0, r.waitingFor(InboxWaiting.Alert))
        assertEquals(listOf("x"), r.notFound)
        val req = requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("https://api.test/v0/me/inbox/marks", req.url.toString())
        val body = req.bodyJson()
        assertEquals(listOf("n1", "n2"), body["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("true", body["archived"]!!.jsonPrimitive.content)
        assertNull(body["seen"])
        assertNull(body["pinned"])
        // Never a reaction: tidying the inbox is not reading it.
        assertTrue(requests.none { it.url.encodedPath == "/v0/reactions" })
    }

    @Test
    fun markInboxAllSendsTheCursorAsText() = runBlocking<Unit> {
        val c = client { json(result) }
        c.markInboxAll(9_007_199_254_740_993L, InboxMarkChange(seen = true), workspaceId = "w1", source = "loja")
        val body = requests.single().bodyJson()
        val all = body["all"]!!.jsonObject
        assertEquals("9007199254740993", all["until_cursor"]!!.jsonPrimitive.content)
        assertTrue(all["until_cursor"]!!.jsonPrimitive.isString)
        assertEquals("w1", all["workspace_id"]!!.jsonPrimitive.content)
        assertEquals("loja", all["source"]!!.jsonPrimitive.content)
        assertNull(all["type"])
        assertEquals("true", body["seen"]!!.jsonPrimitive.content)
    }

    @Test
    fun aChangeNeedsSomething() {
        assertFailsWith<IllegalArgumentException> { InboxMarkChange() }
    }

    @Test
    fun rulesRoundTrip() = runBlocking<Unit> {
        val rule = """{"id":"r1","workspace_id":"w1","source":"loja","type":null,"group":true,"mute":true,"auto_archive_after":null,"created_at":"2026-11-11T12:00:00Z","updated_at":"2026-11-11T12:00:00Z"}"""
        val c = client { req ->
            when (req.method) {
                HttpMethod.Get -> json("""{"rules":[$rule]}""")
                HttpMethod.Delete -> respond("", HttpStatusCode.NoContent)
                else -> json(rule)
            }
        }
        val list = c.inboxRules("w1")
        assertEquals("w1", requests.last().url.parameters["workspace_id"])
        assertEquals(1, list.size)
        assertTrue(list[0].mute)
        assertNull(list[0].type)
        assertNull(list[0].autoArchiveAfter)

        c.saveInboxRule("w1", "loja", mute = true, autoArchiveAfter = 24)
        var body = requests.last().bodyJson()
        assertEquals("loja", body["source"]!!.jsonPrimitive.content)
        assertEquals("24", body["auto_archive_after"]!!.jsonPrimitive.content)
        assertNull(body["group"])
        assertNull(body["type"])

        c.updateInboxRule("r1", clearAutoArchive = true)
        assertEquals(HttpMethod.Patch, requests.last().method)
        assertEquals("/v0/me/inbox/rules/r1", requests.last().url.encodedPath)
        body = requests.last().bodyJson()
        assertEquals(JsonNull, body["auto_archive_after"])

        c.deleteInboxRule("r1")
        assertEquals(HttpMethod.Delete, requests.last().method)
    }

    @Test
    fun inboxKeepsMarksRulesAndWaiting() = runBlocking<Unit> {
        fun n(id: String, source: String, type: String, extra: String = "") =
            """{"seq":0,"entity":"notification","id":"$id","op":"upsert","workspace_id":"w1","data":{"id":"$id","workspace_id":"w1","created_at":"2026-11-11T10:0$id:00Z","source":"$source","type":"$type","status":"active","deliveries":[{"read_at":null}]$extra}}"""
        fun mark(id: String, fields: String) =
            """{"seq":0,"entity":"inbox_mark","id":"$id","op":"upsert","workspace_id":"w1","data":{"notification_id":"$id","workspace_id":"w1",$fields,"auto_archived":false}}"""
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> when (req.url.parameters["cursor"]) {
                    "0" -> json(
                        """{"changes":[
                        ${n("1", "loja", "pedido")},
                        ${n("2", "loja", "pedido", ""","waiting":"ack"""")},
                        ${n("3", "loja", "pedido", ""","waiting":"something-new"""")},
                        ${n("4", "erp", "deploy")},
                        ${mark("1", "\"seen_at\":\"2026-11-11T11:00:00Z\",\"archived_at\":null,\"pinned_at\":null")},
                        ${mark("4", "\"seen_at\":null,\"archived_at\":\"2026-11-11T11:00:00Z\",\"pinned_at\":\"2026-11-11T11:00:00Z\"")},
                        {"seq":0,"entity":"inbox_rule","id":"r1","op":"upsert","workspace_id":"w1","data":{"id":"r1","workspace_id":"w1","source":"loja","type":null,"group":false,"mute":true,"auto_archive_after":12}},
                        {"seq":0,"entity":"inbox_rule","id":"r2","op":"upsert","workspace_id":"w1","data":{"id":"r2","workspace_id":"w1","source":"loja","type":"pedido","group":true,"mute":false,"auto_archive_after":null}},
                        {"seq":0,"entity":"some_future_entity","id":"z","op":"upsert","workspace_id":"w1","data":{}}
                        ],"next_cursor":10,"has_more":false,"reset":false}""",
                    )
                    else -> json(
                        """{"changes":[
                        {"seq":11,"entity":"inbox_mark","id":"4","op":"delete","workspace_id":"w1"},
                        {"seq":12,"entity":"inbox_rule","id":"r2","op":"delete","workspace_id":"w1","data":{"id":"r2","workspace_id":"w1"}}
                        ],"next_cursor":12,"has_more":false,"reset":false}""",
                    )
                }
                "/v0/question-sets" -> json("""{"question_sets":[]}""")
                "/v0/me/inbox/marks" -> json(result)
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.refresh()
        var s = inbox.state.value
        assertNull(s.error)
        val byId = s.notifications.associateBy { it.id }
        assertEquals(InboxWaiting.Ack, byId["2"]!!.waiting)
        assertNull(byId["3"]!!.waiting) // unknown reason from a newer server: not waiting
        assertTrue(s.isWaiting(byId["2"]!!))
        assertEquals(listOf("2"), s.waitingItems.map { it.id })
        // Seen is not unread; archived is out of the count.
        assertFalse(s.isUnread(byId["1"]!!))
        assertTrue(s.isArchived(byId["4"]!!))
        assertTrue(s.isPinned(byId["4"]!!))
        assertEquals(2, s.unreadCount)
        // The type rule wins over the service rule.
        assertEquals("r2", s.ruleFor(byId["1"]!!)?.id)
        assertNull(s.ruleFor(byId["4"]!!))

        inbox.refresh()
        s = inbox.state.value
        assertFalse(s.isArchived(s.notifications.single { it.id == "4" }))
        assertEquals(listOf("r1"), s.rules.map { it.id })
        assertEquals("r1", s.ruleFor(s.notifications.single { it.id == "1" })?.id)

        // "Mark all as seen" goes up to the cursor this inbox has, never as reactions.
        inbox.markAll(InboxMarkChange(seen = true))
        val all = requests.last { it.url.encodedPath == "/v0/me/inbox/marks" }.bodyJson()["all"]!!.jsonObject
        assertEquals("12", all["until_cursor"]!!.jsonPrimitive.content)
        assertTrue(requests.none { it.url.encodedPath == "/v0/reactions" })
    }

    @Test
    fun anOlderCacheResyncsForTheNewFields() {
        assertTrue(InboxSnapshot.SCHEMA >= 4)
        val snap = Wire.json.decodeFromString(InboxSnapshot.serializer(), """{"cursor":5,"schema":3}""")
        assertTrue(snap.marks.isEmpty() && snap.rules.isEmpty())
    }

    @Test
    fun theGatewayAnnouncesInboxChanges() {
        assertEquals(RealtimeEvent.InboxChanged, Realtime.parse("""{"type":"inbox_changed"}"""))
    }

    private class MemoryCache : InboxCache {
        var snap: InboxSnapshot? = null
        override suspend fun load() = snap
        override suspend fun save(snapshot: InboxSnapshot) {
            snap = snapshot
        }
        override suspend fun clear() {
            snap = null
        }
    }

    @Test
    fun marksMadeOfflineShowAtOnceWaitAndGoInOrder() = runBlocking<Unit> {
        fun n(id: String, extra: String = "") =
            """{"seq":0,"entity":"notification","id":"$id","op":"upsert","workspace_id":"w1","data":{"id":"$id","workspace_id":"w1","created_at":"2026-11-11T10:0$id:00Z","source":"loja","type":"pedido","status":"active","deliveries":[{"read_at":null}]$extra}}"""
        var online = true
        val sent = CopyOnWriteArrayList<JsonObject>()
        val c = client { req ->
            if (!online) throw java.io.IOException("offline")
            when (req.url.encodedPath) {
                "/v0/sync" -> json(
                    when {
                        req.url.parameters["cursor"] == "0" -> """{"changes":[${n("1")},${n("2", ""","waiting":"decision"""")},${n("3")}],"next_cursor":5,"has_more":false,"reset":false}"""
                        // After "archive all", the feed brings the server's real marks.
                        sent.size == 2 -> """{"changes":[{"seq":6,"entity":"inbox_mark","id":"1","op":"upsert","workspace_id":"w1","data":{"notification_id":"1","workspace_id":"w1","archived_at":"2026-11-11T12:00:00Z"}}],"next_cursor":6,"has_more":false,"reset":false}"""
                        else -> """{"changes":[],"next_cursor":5,"has_more":false,"reset":false}"""
                    },
                )
                "/v0/question-sets" -> json("""{"question_sets":[]}""")
                "/v0/me/inbox/marks" -> {
                    sent += req.bodyJson()
                    json("""{"applied":2,"unchanged":0,"waiting":1,"waiting_by_reason":{"decision":1},"kept_pinned":0,"complete":true}""")
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val cache = MemoryCache()
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default), cache = cache)
        inbox.refresh()

        online = false
        val pin = inbox.mark(listOf("3"), InboxMarkChange(pinned = true))
        assertTrue(pin.queued)
        val r = inbox.markAll(InboxMarkChange(archived = true))
        assertTrue(r.queued)
        // The waiting decision stays, the pinned one stays ("archive all"), the other goes.
        assertEquals(1, r.applied)
        assertEquals(1, r.waitingFor(InboxWaiting.Decision))
        assertEquals(1, r.keptPinned)
        var s = inbox.state.value
        assertEquals(2, s.queuedMarks)
        val byId = s.notifications.associateBy { it.id }
        assertTrue(s.isArchived(byId["1"]!!))
        assertFalse(s.isArchived(byId["2"]!!))
        assertFalse(s.isArchived(byId["3"]!!))
        assertTrue(s.isPinned(byId["3"]!!))
        assertTrue(sent.isEmpty())

        // Kept with the cache: a new process sends them too.
        inbox.flush()
        assertEquals(2, cache.snap!!.pendingMarks.size)
        val again = Inbox(c, CoroutineScope(Dispatchers.Default), cache = cache)
        again.restore()
        assertEquals(2, again.state.value.queuedMarks)
        assertTrue(again.state.value.isArchived(again.state.value.notifications.single { it.id == "1" }))

        online = true
        again.refresh()
        s = again.state.value
        assertEquals(0, s.queuedMarks)
        assertEquals(2, sent.size)
        // In the order they were made: the pin first, then "archive all" up to the cursor of then.
        assertEquals(listOf("3"), sent[0]["ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("5", sent[1]["all"]!!.jsonObject["until_cursor"]!!.jsonPrimitive.content)
        // What the server marked comes with the feed.
        assertTrue(s.isArchived(s.notifications.single { it.id == "1" }))
        assertTrue(requests.none { it.url.encodedPath == "/v0/reactions" })
    }

    @Test
    fun aRefusedMarkIsUndone() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> json("""{"changes":[{"seq":0,"entity":"notification","id":"1","op":"upsert","workspace_id":"w1","data":{"id":"1","workspace_id":"w1","created_at":"2026-11-11T10:00:00Z","source":"loja","type":"x","status":"active","deliveries":[{"read_at":null}]}}],"next_cursor":5,"has_more":false,"reset":false}""")
                "/v0/question-sets" -> json("""{"question_sets":[]}""")
                "/v0/me/inbox/marks" -> json("""{"error":{"code":"validation_failed","message":"no"}}""", HttpStatusCode.UnprocessableEntity)
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.refresh()
        assertFailsWith<NotificMeException> { inbox.mark(listOf("1"), InboxMarkChange(seen = true)) }
        val s = inbox.state.value
        assertEquals(0, s.queuedMarks)
        assertTrue(s.isUnread(s.notifications.single()))
    }

    private fun notif(id: String, extra: String = "") =
        """{"seq":0,"entity":"notification","id":"$id","op":"upsert","workspace_id":"w1","data":{"id":"$id","workspace_id":"w1","created_at":"2026-11-11T10:0$id:00Z","source":"loja","type":"pedido","status":"active","deliveries":[{"read_at":null}]$extra}}"""

    @Test
    fun markAllGoesOnFromNextAfterAndAddsTheRoundsUp() = runBlocking<Unit> {
        val sent = CopyOnWriteArrayList<JsonObject>()
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> json("""{"changes":[${notif("1")}],"next_cursor":5,"has_more":false,"reset":false}""")
                "/v0/question-sets" -> json("""{"question_sets":[]}""")
                "/v0/me/inbox/marks" -> {
                    sent += req.bodyJson()
                    if (sent.size == 1) json("""{"applied":1000,"unchanged":2,"waiting":1,"waiting_by_reason":{"decision":1},"kept_pinned":0,"complete":false,"next_after":"7000"}""")
                    else json("""{"applied":10,"unchanged":1,"waiting":2,"waiting_by_reason":{"decision":2},"kept_pinned":0,"complete":true}""")
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.refresh()
        val r = inbox.markAll(InboxMarkChange(seen = true))
        assertEquals(2, sent.size)
        assertNull(sent[0]["all"]!!.jsonObject["after"])
        assertEquals("7000", sent[1]["all"]!!.jsonObject["after"]!!.jsonPrimitive.content)
        // Rounds that do not overlap: everything adds up once.
        assertEquals(1010, r.applied)
        assertEquals(3, r.unchanged)
        assertEquals(3, r.waitingFor(InboxWaiting.Decision))
        assertTrue(r.complete)
    }

    @Test
    fun markAllWithoutNextAfterDoesNotInflateTheWaitingCount() = runBlocking<Unit> {
        var calls = 0
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> json("""{"changes":[${notif("1")}],"next_cursor":5,"has_more":false,"reset":false}""")
                "/v0/question-sets" -> json("""{"question_sets":[]}""")
                "/v0/me/inbox/marks" -> {
                    calls++
                    json("""{"applied":5,"unchanged":2,"waiting":3,"waiting_by_reason":{"decision":3},"kept_pinned":1,"complete":${calls >= 3}}""")
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.refresh()
        val r = inbox.markAll(InboxMarkChange(archived = true))
        assertEquals(3, calls)
        assertEquals(15, r.applied)
        assertEquals(3, r.waiting)
        assertEquals(3, r.waitingFor(InboxWaiting.Decision))
        assertEquals(1, r.keptPinned)
        assertEquals(2, r.unchanged)
    }

    @Test
    fun theLocalGuessOfAllIsNotKeptOverTheServersMarks() = runBlocking<Unit> {
        var marked = false
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> json(
                    if (req.url.parameters["cursor"] == "0") """{"changes":[${notif("1")},${notif("2")}],"next_cursor":5,"has_more":false,"reset":false}"""
                    // The server reached only "1" ("2" was past its horizon or cursor).
                    else if (marked) """{"changes":[{"seq":6,"entity":"inbox_mark","id":"1","op":"upsert","workspace_id":"w1","data":{"notification_id":"1","workspace_id":"w1","seen_at":"2026-11-11T12:00:00Z"}}],"next_cursor":6,"has_more":false,"reset":false}"""
                    else """{"changes":[],"next_cursor":5,"has_more":false,"reset":false}""",
                )
                "/v0/question-sets" -> json("""{"question_sets":[]}""")
                "/v0/me/inbox/marks" -> {
                    marked = true
                    json("""{"applied":1,"unchanged":0,"waiting":0,"waiting_by_reason":{},"kept_pinned":0,"complete":true}""")
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val cache = MemoryCache()
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default), cache = cache)
        inbox.refresh()
        inbox.markAll(InboxMarkChange(seen = true))
        val s = inbox.state.value
        val byId = s.notifications.associateBy { it.id }
        assertFalse(s.isUnread(byId["1"]!!))
        // Not reached by the server: unread here too, as on the other devices.
        assertTrue(s.isUnread(byId["2"]!!))
        inbox.flush()
        assertEquals(listOf("1"), cache.snap!!.marks.map { it.notificationId })
    }

    @Test
    fun rulesComeBackAfterAReset() = runBlocking<Unit> {
        val rule = """{"id":"r1","workspace_id":"w1","source":"loja","type":null,"group":true,"mute":true,"auto_archive_after":null}"""
        var reset = false
        var ruleLoads = 0
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> json(
                    if (reset && req.url.parameters["cursor"] != "0") """{"changes":[],"next_cursor":0,"has_more":false,"reset":true}"""
                    else """{"changes":[${notif("1")}],"next_cursor":5,"has_more":false,"reset":false}""",
                )
                "/v0/question-sets" -> json("""{"question_sets":[]}""")
                "/v0/me/inbox/rules" -> {
                    ruleLoads++
                    json("""{"rules":[$rule]}""")
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.refresh()
        inbox.reloadRules()
        assertEquals(1, inbox.state.value.rules.size)
        reset = true
        inbox.refresh()
        // The reset cleared them; the inbox loads them again by itself.
        assertEquals(2, ruleLoads)
        assertEquals(listOf("r1"), inbox.state.value.rules.map { it.id })
    }
}
