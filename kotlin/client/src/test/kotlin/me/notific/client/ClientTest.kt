package me.notific.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ClientTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun client(
        tokens: TokenProvider? = TokenProvider { "nm_ses_test" },
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): NotificClient {
        val engine = MockEngine { req ->
            requests += req
            handler(req)
        }
        return NotificClient(tokens, NotificClient.Config(baseUrl = "https://api.test/", httpClient = HttpClient(engine), platform = "android"))
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, vararg extra: Pair<String, String>) =
        respond(body, status, headersOf(*(listOf(HttpHeaders.ContentType to listOf("application/json")) + extra.map { it.first to listOf(it.second) }).toTypedArray()))

    private fun HttpRequestData.bodyJson(): JsonObject = Wire.json.parseToJsonElement((body as TextContent).text).jsonObject

    private val accepted = """{"status":"accepted","question_set_id":"q1","answer_set_id":"a1","reply":{"question_set_id":"q1","version":2,"status":"answered","answers":[],"answered_by":{"identity":"ana@acme.com","device_id":"d","level_used":1,"auth_method":"unlock"},"signature":{"alg":"Ed25519","value":"x"}}}"""

    @Test
    fun replySendsEverythingTheServerChecks() = runBlocking<Unit> {
        val c = client { json(accepted) }
        val qs = QuestionSet(id = "q1", version = 2, level = 1, contentHash = "sha256:abc")
        val res = c.reply(qs, listOf(Answer.yesNo("ok", true)))
        assertEquals("a1", res.answerSetId)
        val req = requests.single()
        assertEquals("https://api.test/v0/replies", req.url.toString())
        assertEquals("Bearer nm_ses_test", req.headers[HttpHeaders.Authorization])
        val body = req.bodyJson()
        assertEquals("q1", body["question_set_id"]!!.jsonPrimitive.content)
        assertEquals("2", body["version"]!!.jsonPrimitive.content)
        assertEquals("unlock", body["auth_method"]!!.jsonPrimitive.content)
        assertEquals("sha256:abc", body["content_hash"]!!.jsonPrimitive.content)
        assertTrue(body["idempotency_key"]!!.jsonPrimitive.content.isNotBlank())
        assertEquals("ok", body["answers"]!!.jsonArray[0].jsonObject["question_id"]!!.jsonPrimitive.content)
        assertNull(body["totp_code"])
    }

    @Test
    fun levelZeroIsATap() = runBlocking<Unit> {
        val c = client { json(accepted) }
        c.reply(QuestionSet(id = "q1", level = 0), listOf(Answer.yesNo("ok", false)))
        assertEquals("tap", requests.single().bodyJson()["auth_method"]!!.jsonPrimitive.content)
    }

    @Test
    fun rejectedTokenIsRefreshedOnce() = runBlocking<Unit> {
        val asked = mutableListOf<Boolean>()
        var token = "old"
        val provider = TokenProvider { refresh ->
            asked += refresh
            if (refresh) token = "new"
            token
        }
        val c = client(provider) { req ->
            if (req.headers[HttpHeaders.Authorization] == "Bearer old") json("""{"error":{"code":"unauthorized"}}""", HttpStatusCode.Unauthorized)
            else json("""{"user":{"id":"u1","handle":"ana"},"workspaces":[{"id":"w1","handle":"acme"}],"sync_cursor":42}""")
        }
        val me = c.me()
        assertEquals("ana", me.user.handle)
        assertEquals(42, me.syncCursor)
        assertEquals(listOf(false, true, false), asked)
        assertEquals(2, requests.size)
    }

    @Test
    fun expiredLoginSessionIsCleared() = runBlocking<Unit> {
        val tokens = SessionTokens()
        tokens.set(Session("nm_ses_x", "s1", "2999-01-01T00:00:00Z", User(id = "u")))
        val c = client(tokens) { json("""{"error":{"code":"unauthorized","message":"expired"},"request_id":"r-1"}""", HttpStatusCode.Unauthorized) }
        val e = assertFailsWith<NotificMeApiException> { c.me() }
        assertEquals(401, e.status)
        assertEquals("unauthorized", e.code)
        assertEquals("r-1", e.requestId)
        assertNull(tokens.session.value)
        assertEquals(1, requests.size)
    }

    @Test
    fun noTokenFailsBeforeTheNetwork() = runBlocking<Unit> {
        val c = client(SessionTokens()) { json("{}") }
        val e = assertFailsWith<NotificMeException> { c.sync() }
        assertEquals("unauthorized", e.code)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun serverErrorsOfGetsAreRetried() = runBlocking<Unit> {
        var calls = 0
        val c = client {
            calls++
            if (calls == 1) json("""{"error":{"code":"unavailable"}}""", HttpStatusCode.ServiceUnavailable, HttpHeaders.RetryAfter to "0")
            else json("""{"question_sets":[{"id":"q1","workspace_id":"w1","title":"Deploy?","questions":[{"id":"ok","type":"yesno","text":"Deploy?"}],"answerable":true}]}""")
        }
        val list = c.questionSets()
        assertEquals("Deploy?", list.single().title)
        assertTrue(list.single().answerable)
        assertEquals(2, calls)
        assertEquals("status=open", requests.last().url.encodedQuery)
    }

    @Test
    fun validationErrorsCarryDetails() = runBlocking<Unit> {
        val c = client {
            json("""{"error":{"code":"validation_failed","message":"bad","details":{"errors":[{"path":"/answers/0"}]}},"request_id":"r-9"}""", HttpStatusCode.UnprocessableEntity)
        }
        val e = assertFailsWith<NotificMeApiException> { c.reply(QuestionSet(id = "q1"), emptyList()) }
        assertEquals("validation_failed", e.code)
        assertEquals(422, e.status)
        assertNotNull(e.details)
        assertEquals(1, requests.size)
    }

    @Test
    fun reactionsCarryThePlatform() = runBlocking<Unit> {
        val c = client { json("""{"reactions":[]}""") }
        c.markOpened(listOf("n1", "n2"))
        val reactions = requests.single().bodyJson()["reactions"]!!.jsonArray
        assertEquals(2, reactions.size)
        val first = reactions[0].jsonObject
        assertEquals("opened", first["kind"]!!.jsonPrimitive.content)
        assertEquals("n1", first["notification_id"]!!.jsonPrimitive.content)
        assertEquals("android", first["platform"]!!.jsonPrimitive.content)
        assertNotNull(first["tz"])
    }

    @Test
    fun signInByCode() = runBlocking<Unit> {
        val c = client(tokens = null) { req ->
            when (req.url.encodedPath) {
                "/v0/auth/start" -> json("""{"status":"pending","expires_in":600,"code_length":6}""", HttpStatusCode.Accepted)
                "/v0/auth/verify" -> if (req.bodyJson()["email"]!!.jsonPrimitive.content == "new@acme.com") {
                    json("""{"status":"signup_required","signup_token":"nm_sut_1","email":"new@acme.com","expires_at":"2026-11-06T12:00:00Z","suggested_handle":"new","invites":[]}""")
                } else {
                    json("""{"status":"authenticated","token":"nm_ses_1","token_type":"Bearer","session_id":"s1","expires_at":"2026-12-06T12:00:00Z","user":{"id":"u1","email":"ana@acme.com","handle":"ana","display_name":"Ana"},"created":false}""")
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        assertEquals(6, c.auth.start("ana@acme.com", locale = "pt-BR").codeLength)
        assertNull(requests[0].headers[HttpHeaders.Authorization])
        assertEquals("pt-BR", requests[0].bodyJson()["locale"]!!.jsonPrimitive.content)

        val signedIn = assertIs<SignInResult.SignedIn>(c.auth.verify("ana@acme.com", "123456", DeviceInfo(name = "Pixel")))
        assertEquals("nm_ses_1", signedIn.session.token)
        assertEquals("android", requests[1].bodyJson()["device"]!!.jsonObject["platform"]!!.jsonPrimitive.content)

        val signup = assertIs<SignInResult.SignupRequired>(c.auth.verify("new@acme.com", "123456"))
        assertEquals("new", signup.suggestedHandle)
    }

    @Test
    fun inboxSyncsPagesFiltersAndAppliesDeletes() = runBlocking<Unit> {
        fun n(id: String, ws: String, at: String, read: Boolean = false, qs: String? = null) =
            """{"seq":0,"entity":"notification","id":"$id","op":"upsert","workspace_id":"$ws","data":{"id":"$id","workspace_id":"$ws","created_at":"$at","source":"bot","type":"x","severity":"high","title":"T $id","summary":"S $id"${qs?.let { ",\"question_set_id\":\"$it\"" } ?: ""},"deliveries":[{"read_at":${if (read) "\"2026-11-06T10:00:00Z\"" else "null"}}]}}"""
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> when (req.url.parameters["cursor"]) {
                    "0" -> json("""{"changes":[${n("n1", "w1", "2026-11-06T10:00:00Z")},${n("n2", "w2", "2026-11-06T11:00:00Z")},${n("n3", "w1", "2026-11-06T09:00:00Z", qs = "q1")}],"next_cursor":3,"has_more":true,"reset":false}""")
                    "3" -> json("""{"changes":[{"seq":4,"entity":"notification","id":"n1","op":"delete","workspace_id":"w1"},${n("n4", "w1", "2026-11-06T12:00:00Z", read = true)}],"next_cursor":5,"has_more":false,"reset":false}""")
                    else -> json("""{"changes":[],"next_cursor":5,"has_more":false,"reset":false}""")
                }
                "/v0/question-sets" -> json("""{"question_sets":[{"id":"q2","workspace_id":"w2","title":"Other"},{"id":"q1","workspace_id":"w1","title":"Mine","timeout_at":"2026-11-07T00:00:00Z"}]}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default), workspaceId = "w1")
        inbox.refresh()
        val s = inbox.state.value
        assertNull(s.error)
        assertEquals(listOf("n4", "n3"), s.notifications.map { it.id })
        assertEquals(listOf("q1"), s.questions.map { it.id })
        assertEquals("n3", s.notificationFor("q1")?.id)
        // n3 is an open decision (counted as a question) and n4 was read.
        assertEquals(0, s.unreadCount)

        // Next refresh continues from the cursor instead of starting over.
        inbox.refresh()
        assertEquals("5", requests.filter { it.url.encodedPath == "/v0/sync" }.last().url.parameters["cursor"])
    }
    @Test
    fun inboxStartsFromTheCacheAndSavesWhatChanged() = runBlocking<Unit> {
        val saved = CopyOnWriteArrayList<InboxSnapshot>()
        val cache = object : InboxCache {
            override suspend fun load() = InboxSnapshot(
                cursor = 7,
                notifications = listOf(Notification(id = "old", createdAt = "2026-11-05T10:00:00Z", title = "Old")),
                questions = listOf(QuestionSet(id = "q1", title = "Cached")),
            )
            override suspend fun save(snapshot: InboxSnapshot) { saved += snapshot }
            override suspend fun clear() = Unit
        }
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> json("""{"changes":[{"seq":8,"entity":"notification","id":"new","op":"upsert","workspace_id":"w1","data":{"id":"new","created_at":"2026-11-06T10:00:00Z","title":"New","deliveries":[{"read_at":null}]}}],"next_cursor":8,"has_more":false,"reset":false}""")
                "/v0/question-sets" -> json("""{"question_sets":[]}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default), cache = cache)
        inbox.restore()
        assertEquals(listOf("old"), inbox.state.value.notifications.map { it.id })
        assertEquals(listOf("q1"), inbox.state.value.questions.map { it.id })

        inbox.refresh()
        // Only what changed since the cached cursor is asked for.
        assertEquals("7", requests.single { it.url.encodedPath == "/v0/sync" }.url.parameters["cursor"])
        assertEquals(listOf("new", "old"), inbox.state.value.notifications.map { it.id })
        assertTrue(inbox.state.value.questions.isEmpty())

        inbox.flush()
        val last = saved.last()
        assertEquals(8, last.cursor)
        assertEquals(setOf("new", "old"), last.notifications.map { it.id }.toSet())
        assertTrue(last.questions.isEmpty())
    }
    @Test
    fun inboxAppliesReadsMadeOnAnotherDevice() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> when (req.url.parameters["cursor"]) {
                    "0" -> json("""{"changes":[{"seq":1,"entity":"notification","id":"n1","op":"upsert","workspace_id":"w1","data":{"id":"n1","created_at":"2026-11-06T10:00:00Z","title":"Hi","deliveries":[{"id":"d1","channel":"app","read_at":null}]}}],"next_cursor":1,"has_more":false,"reset":false}""")
                    else -> json("""{"changes":[{"seq":2,"entity":"delivery","id":"d1","op":"upsert","workspace_id":"w1","data":{"id":"d1","notification_id":"n1","channel":"app","status":"read","read_at":"2026-11-06T10:05:00Z"}}],"next_cursor":2,"has_more":false,"reset":false}""")
                }
                "/v0/question-sets" -> json("""{"question_sets":[]}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.refresh()
        assertTrue(inbox.state.value.notifications.single().unread)
        inbox.refresh()
        val n = inbox.state.value.notifications.single()
        assertEquals(false, n.unread)
        assertEquals("read", n.deliveries.single().status)
    }

    @Test
    fun devicesAndContent() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/devices" -> json("""{"devices":[{"id":"dev1","name":"Pixel","platform":"android","has_push_token":true,"current":true}]}""")
                "/v0/devices/dev1" -> if (req.method.value == "DELETE") respond("", HttpStatusCode.NoContent) else json("""{"id":"dev1","has_push_token":false}""")
                "/v0/content/c1" -> json("""{"id":"c1","kind":"html","content_type":"text/html","size_bytes":12,"url":"/v0/content/c1/raw?s=x"}""")
                "/v0/content/c1/raw" -> respond("<p>Hello</p>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        assertEquals("dev1", c.devices().single { it.current }.id)
        val updated = c.updateDevice("dev1", DeviceUpdate(pushToken = ""))
        assertEquals(false, updated.hasPushToken)
        assertEquals("""{"push_token":""}""", (requests.last().body as TextContent).text)
        c.revokeDevice("dev1")
        assertEquals("<p>Hello</p>", c.contentText("c1"))
        // The signed URL is fetched without the session token.
        assertNull(requests.last().headers[HttpHeaders.Authorization])
        assertEquals("c1", RichContent(htmlRef = "content:c1").contentId)
    }
}
