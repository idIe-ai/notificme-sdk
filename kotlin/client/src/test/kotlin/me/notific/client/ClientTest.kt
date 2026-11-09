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
import kotlin.test.assertFalse
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
    fun photosAndLogosBecomeFullUrls() = runBlocking<Unit> {
        val c = client {
            json("""{"user":{"id":"u1","avatar_url":"/v0/avatars/a.webp"},"workspaces":[{"id":"w1","logo_url":"/v0/avatars/l.png"},{"id":"w2","logo_url":""}]}""")
        }
        val me = c.me()
        assertEquals("https://api.test/v0/avatars/a.webp", c.urlOf(me.user.avatarUrl))
        assertEquals("https://api.test/v0/avatars/l.png", c.urlOf(me.workspaces[0].logoUrl))
        assertNull(c.urlOf(me.workspaces[1].logoUrl))
        assertNull(c.urlOf(null))
        assertEquals("https://cdn.test/x.png", c.urlOf("https://cdn.test/x.png"))
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
    fun inboxKnowsHowEachDecisionEnded() = runBlocking<Unit> {
        fun n(id: String, qs: String, status: String = "active") =
            """{"seq":0,"entity":"notification","id":"$id","op":"upsert","workspace_id":"w1","data":{"id":"$id","created_at":"2026-11-06T10:0$id:00Z","title":"T","status":"$status","question_set_id":"$qs","deliveries":[{"read_at":null}]}}"""
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> when (req.url.parameters["cursor"]) {
                    // 1: open; 2: ran out (the feed says so later); 3: closed as expired; 4: older, ended before this inbox kept it.
                    "0" -> json("""{"changes":[${n("1", "q1")},${n("2", "q2")},${n("3", "q3", "expired")},${n("4", "q4")}],"next_cursor":4,"has_more":false,"reset":false}""")
                    else -> json("""{"changes":[{"seq":5,"entity":"question_set","id":"q2","op":"upsert","workspace_id":"w1","data":{"id":"q2","status":"timeout"}}],"next_cursor":5,"has_more":false,"reset":false}""")
                }
                "/v0/question-sets" -> json("""{"question_sets":[{"id":"q1","workspace_id":"w1","title":"Open"}]}""")
                "/v0/question-sets/q2" -> json("""{"id":"q2","status":"open"}""")
                "/v0/question-sets/q4" -> json("""{"id":"q4","status":"answered"}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.refresh()
        fun end(id: String) = inbox.state.value.let { s -> s.decisionEnd(s.notifications.single { it.id == id }) }
        assertNull(end("1"))
        assertEquals(QuestionSetStatus.Timeout, end("3"))
        // Asked once, because nothing said how it ended.
        assertEquals(QuestionSetStatus.Answered, end("4"))
        assertEquals(1, requests.count { it.url.encodedPath == "/v0/question-sets/q4" })
        assertEquals(0, requests.count { it.url.encodedPath == "/v0/question-sets/q3" })

        inbox.refresh()
        assertEquals(QuestionSetStatus.Timeout, end("2"))
        assertEquals(1, requests.count { it.url.encodedPath == "/v0/question-sets/q4" })
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

    @Test
    fun typedEmailIsThePersonOrNobody() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.parameters["email"]) {
                "ana@acme.com" -> json("""{"person":{"id":"u1","handle":"ana","display_name":"Ana","avatar_url":"/v0/avatars/a.webp"}}""")
                "zed@x.com" -> json("""{"error":{"code":"recipient_not_found"}}""", HttpStatusCode.UnprocessableEntity)
                else -> json("""{"error":{"code":"validation_failed"}}""", HttpStatusCode.UnprocessableEntity)
            }
        }
        val ana = c.people.recipientByEmail("ana@acme.com", from = "acme")
        assertEquals(Person("u1", "ana", "Ana", "/v0/avatars/a.webp"), ana)
        assertEquals("/v0/me/compose/recipient", requests.last().url.encodedPath)
        assertEquals("acme", requests.last().url.parameters["from"])
        // Not a contact (or no account at all): nobody, and personal sends no "from".
        assertNull(c.people.recipientByEmail("zed@x.com"))
        assertNull(requests.last().url.parameters["from"])
        // Any other error still reaches the caller.
        val e = assertFailsWith<NotificMeApiException> { c.people.recipientByEmail("nope") }
        assertEquals("validation_failed", e.code)
        assertEquals(3, requests.size)
    }

    @Test
    fun replyCarriesTheSignatureShown() = runBlocking<Unit> {
        val c = client { json(accepted) }
        val qs = QuestionSet(id = "q1", version = 2, level = 1, requireSignature = true)
        c.reply(qs, listOf(Answer.yesNo("ok", true)), eSignatureSha256 = "sha256:9f2c")
        val body = requests.single().bodyJson()
        assertEquals("sha256:9f2c", body["e_signature"]!!.jsonObject["signature_sha256"]!!.jsonPrimitive.content)
        assertEquals(1, body["e_signature"]!!.jsonObject.size)

        // Without a signature the field is left out (the server refuses unknown or extra fields).
        requests.clear()
        c.reply(QuestionSet(id = "q1"), listOf(Answer.yesNo("ok", true)))
        assertNull(requests.single().bodyJson()["e_signature"])
    }

    @Test
    fun inboxReplyPassesTheSignatureOn() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/replies" -> json(accepted)
                "/v0/sync" -> json("""{"changes":[],"next_cursor":0,"has_more":false,"reset":false}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.reply(QuestionSet(id = "q1", requireSignature = true), listOf(Answer.yesNo("ok", true)), eSignatureSha256 = "sha256:abc")
        val body = requests.first { it.url.encodedPath == "/v0/replies" }.bodyJson()
        assertEquals("sha256:abc", body["e_signature"]!!.jsonObject["signature_sha256"]!!.jsonPrimitive.content)
    }

    @Test
    fun signatureOfTheAccount() = runBlocking<Unit> {
        val sig = """{"name":"Ana Souza","initials":"AS","method":"typed","style":"great-vibes","signature_png":"AAEC","signature_sha256":"sha256:aa","initials_png":"AwQ=","initials_sha256":"sha256:bb","consent_version":"esign-2026-11-09","adopted_at":"2026-11-09T14:03:11Z"}"""
        var adopted = false
        val c = client { req ->
            when (req.method.value) {
                "GET" -> json(if (adopted) """{"signature":$sig}""" else """{"signature":null}""")
                "PUT" -> {
                    adopted = true
                    json("""{"signature":$sig}""")
                }
                "DELETE" -> respond("", HttpStatusCode.NoContent)
                else -> json("{}", HttpStatusCode.MethodNotAllowed)
            }
        }
        assertNull(c.account.signature())
        assertEquals("/v0/me/signature", requests.last().url.encodedPath)

        val e = c.account.adoptSignature(
            name = " Ana Souza ", initials = "AS", method = SignatureMethod.Typed,
            signaturePng = byteArrayOf(0, 1, 2), initialsPng = byteArrayOf(3, 4), style = SignatureStyle.GreatVibes,
        )
        val put = requests.last()
        assertEquals("PUT", put.method.value)
        assertEquals("Bearer nm_ses_test", put.headers[HttpHeaders.Authorization])
        val body = put.bodyJson()
        assertEquals("Ana Souza", body["name"]!!.jsonPrimitive.content)
        assertEquals("AS", body["initials"]!!.jsonPrimitive.content)
        assertEquals("typed", body["method"]!!.jsonPrimitive.content)
        assertEquals("great-vibes", body["style"]!!.jsonPrimitive.content)
        // Standard base64, no data: prefix.
        assertEquals("AAEC", body["signature_png"]!!.jsonPrimitive.content)
        assertEquals("AwQ=", body["initials_png"]!!.jsonPrimitive.content)
        assertEquals("true", body["consent"]!!.jsonPrimitive.content)
        assertEquals(SignatureStyle.GreatVibes, e.style)
        assertEquals(SignatureMethod.Typed, e.method)
        assertEquals("sha256:aa", e.signatureSha256)
        assertEquals(listOf<Byte>(0, 1, 2), e.signatureBytes().toList())
        assertEquals("Ana Souza", c.account.signature()?.name)

        c.account.deleteSignature()
        assertEquals("DELETE", requests.last().method.value)
        assertEquals("/v0/me/signature", requests.last().url.encodedPath)
    }

    @Test
    fun drawnSignatureHasNoStyle() = runBlocking<Unit> {
        val c = client { json("""{"signature":{"name":"Ana","initials":"A","method":"drawn","signature_png":"AA==","initials_png":"AA==","adopted_at":"2026-11-09T14:03:11Z"}}""") }
        val e = c.account.adoptSignature("Ana", "A", SignatureMethod.Drawn, byteArrayOf(0), byteArrayOf(0), style = SignatureStyle.Sacramento)
        assertNull(requests.single().bodyJson()["style"])
        assertEquals(SignatureMethod.Drawn, e.method)
        assertNull(e.style)
    }

    @Test
    fun signatureErrorsKeepTheirCode() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/replies" -> json(
                    """{"error":{"code":"signature_changed","message":"changed","details":{"signature_sha256":"sha256:new"}},"request_id":"r-3"}""",
                    HttpStatusCode.Conflict,
                )
                "/v0/me/signature" -> json("""{"error":{"code":"consent_required"}}""", HttpStatusCode.UnprocessableEntity)
                "/v0/me/compose" -> json("""{"error":{"code":"plan_required","details":{"feature":"e_signature","plan":"free","required_plan":"pro"}}}""", HttpStatusCode.Forbidden)
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val changed = assertFailsWith<NotificMeApiException> {
            c.reply(QuestionSet(id = "q1", requireSignature = true), emptyList(), eSignatureSha256 = "sha256:old")
        }
        assertEquals(409, changed.status)
        assertEquals("signature_changed", changed.code)
        assertEquals("sha256:new", changed.details!!.jsonObject["signature_sha256"]!!.jsonPrimitive.content)
        // A conflict is final: not retried.
        assertEquals(1, requests.count { it.url.encodedPath == "/v0/replies" })

        val consent = assertFailsWith<NotificMeApiException> {
            c.account.adoptSignature("Ana", "A", SignatureMethod.Drawn, byteArrayOf(0), byteArrayOf(0))
        }
        assertEquals("consent_required", consent.code)

        val plan = assertFailsWith<NotificMeApiException> {
            c.people.send(Compose(kind = Compose.FORM, to = listOf("@ana"), title = "Sign", requireSignature = true))
        }
        assertEquals("plan_required", plan.code)
        assertEquals("e_signature", plan.details!!.jsonObject["feature"]!!.jsonPrimitive.content)
        assertEquals("true", requests.last().bodyJson()["require_signature"]!!.jsonPrimitive.content)
    }

    @Test
    fun typedEmailOutsideSaysHowItGoes() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.parameters["email"]) {
                "pro@x.com" -> json("""{"error":{"code":"recipient_not_found","details":{"delivery":"email"}}}""", HttpStatusCode.UnprocessableEntity)
                "free@x.com" -> json("""{"error":{"code":"recipient_not_found","details":{"delivery":"email","plan_required":"pro"}}}""", HttpStatusCode.UnprocessableEntity)
                "guest@x.com" -> json("""{"error":{"code":"recipient_not_found","details":{"delivery":"invite"}}}""", HttpStatusCode.UnprocessableEntity)
                else -> json("""{"error":{"code":"recipient_not_found","details":{}}}""", HttpStatusCode.UnprocessableEntity)
            }
        }
        val pro = c.people.recipientCheck("pro@x.com")
        assertEquals(RecipientCheck(delivery = "email"), pro)
        assertTrue(pro.goesByEmail)
        val free = c.people.recipientCheck("free@x.com")
        assertEquals("pro", free.planRequired)
        assertFalse(free.goesByEmail)
        assertTrue(c.people.recipientCheck("guest@x.com", from = "acme").goesByEmail)
        val member = c.people.recipientCheck("no@x.com", from = "acme")
        assertNull(member.delivery)
        assertFalse(member.goesByEmail)
    }

    @Test
    fun sentShowsRecipientsByEmail() {
        val item = Wire.json.decodeFromString(SentDetail.serializer(), """
            {"id":"s1","kind":"form","recipients":[
               {"person":{"id":"u1","handle":"ana","display_name":"Ana","avatar_url":""},"delivered_at":"2026-11-09T10:00:00Z"},
               {"person":null,"email":"nova@x.com","delivered_at":"2026-11-09T10:00:00Z","read_at":"2026-11-09T10:05:00Z",
                "acknowledged_at":null,"answered_at":"2026-11-09T10:06:00Z"}],
             "answers":[{"person":null,"email":"nova@x.com","answered_at":"2026-11-09T10:06:00Z",
                         "answers":[{"question_id":"q1","question_text":"Dia?","value":"Terça","free_text":null}]}]}
        """.trimIndent())
        assertEquals("ana", item.recipients[0].person?.handle)
        assertNull(item.recipients[1].person)
        assertEquals("nova@x.com", item.recipients[1].email)
        assertEquals("nova@x.com", item.answers.single().email)
        val res = Wire.json.decodeFromString(ComposeResult.serializer(), """{"kind":"message","recipients":[],"emails":["nova@x.com"]}""")
        assertEquals(listOf("nova@x.com"), res.emails)
    }
}
