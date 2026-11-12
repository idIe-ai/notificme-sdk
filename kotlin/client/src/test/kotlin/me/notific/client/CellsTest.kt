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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Login between cells (contract 24.11.2): the handoff, `GET /v0/auth/cells`
 * and links with `c=`. Only against fake servers; no real cell sent these.
 */
class CellsTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): NotificClient {
        val engine = MockEngine { req ->
            requests += req
            handler(req)
        }
        return NotificClient(null, NotificClient.Config(baseUrl = "https://api.notific.me", httpClient = HttpClient(engine)))
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.notFound() =
        json("""{"error":{"code":"not_found","message":"not found"}}""", HttpStatusCode.NotFound)

    private fun HttpRequestData.bodyJson(): JsonObject = Wire.json.parseToJsonElement((body as TextContent).text).jsonObject

    private val session = """{"status":"authenticated","token":"nm_ses_h","token_type":"Bearer","session_id":"s1","expires_at":"2027-02-01T00:00:00Z","user":{"id":"u2","email":"bia@acme.com","handle":"bia"}}"""
    private val handoff = """{"status":"handoff","handoff":{"cell_id":"br-2","token":"nm_ho_secret","expires_at":"2026-11-11T12:01:00Z"}}"""
    private val cells = """{"self":"br-1","cells":[{"cell_id":"br-1","url":"https://api.notific.me","region":"br"},{"cell_id":"br-2","url":"https://api-br2.notific.me/","region":"br"},{"cell_id":"us-1","url":"http://api-us1.notific.me","region":"us"}]}"""

    // ------------------------------------------------------------ today's flow

    @Test
    fun withoutHandoffNothingChanges() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/auth/verify" -> json(session)
                "/v0/auth/magic" -> json(session)
                else -> notFound()
            }
        }
        assertIs<SignInResult.SignedIn>(c.auth.verify("bia@acme.com", "12345678"))
        // A link without c= is today's magic link on the same server, with no other call.
        val r = c.auth.magicLinkAt(null, "nm_ml_x")
        assertEquals("https://api.notific.me", r.baseUrl)
        assertIs<SignInResult.SignedIn>(r.result)
        assertEquals(listOf("https://api.notific.me/v0/auth/verify", "https://api.notific.me/v0/auth/magic"), requests.map { it.url.toString() })
    }

    @Test
    fun singleCellServerAnswers404() = runBlocking<Unit> {
        val c = client { notFound() }
        assertNull(c.auth.cells())
    }

    // ------------------------------------------------------------ handoff

    @Test
    fun verifyReturnsTheHandoffWithoutLeakingTheToken() = runBlocking<Unit> {
        val c = client { json(handoff) }
        val h = assertIs<SignInResult.Handoff>(c.auth.verify("bia@acme.com", "12345678"))
        assertEquals("br-2", h.cellId)
        assertEquals("nm_ho_secret", h.token)
        assertEquals("2026-11-11T12:01:00Z", h.expiresAt)
        assertFalse(h.toString().contains("nm_ho_secret"))
    }

    @Test
    fun handoffIsExchangedAtTheListedHomeCell() = runBlocking<Unit> {
        val c = client { req ->
            when ("${req.url.host}${req.url.encodedPath}") {
                "api.notific.me/v0/auth/cells" -> json(cells)
                "api-br2.notific.me/v0/auth/handoff" -> json(session)
                else -> notFound()
            }
        }
        val h = SignInResult.Handoff("br-2", "nm_ho_secret")
        val r = c.auth.completeHandoff(h, DeviceInfo(name = "Pixel"), "pt-BR")
        assertEquals("https://api-br2.notific.me", r.baseUrl)
        assertEquals("nm_ses_h", assertIs<SignInResult.SignedIn>(r.result).session.token)
        val exchange = requests.last()
        assertEquals("https://api-br2.notific.me/v0/auth/handoff", exchange.url.toString())
        assertNull(exchange.headers[HttpHeaders.Authorization])
        val body = exchange.bodyJson()
        assertEquals("nm_ho_secret", body["token"]!!.jsonPrimitive.content)
        assertEquals("pt-BR", body["locale"]!!.jsonPrimitive.content)
        assertEquals("Pixel", body["device"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun handoffMayEndInSignupAtTheHomeCell() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/auth/cells" -> json(cells)
                "/v0/auth/handoff" -> json("""{"status":"signup_required","signup_token":"nm_sut_h","email":"new@acme.com","suggested_handle":"new"}""")
                else -> notFound()
            }
        }
        val r = c.auth.completeHandoff(SignInResult.Handoff("br-2", "nm_ho_secret"))
        assertEquals("https://api-br2.notific.me", r.baseUrl)
        assertEquals("nm_sut_h", assertIs<SignInResult.SignupRequired>(r.result).signupToken)
    }

    @Test
    fun aCellOutsideTheListIsNeverCalled() = runBlocking<Unit> {
        val c = client { req -> if (req.url.encodedPath == "/v0/auth/cells") json(cells) else json(session) }
        val e = assertFailsWith<NotificMeException> { c.auth.completeHandoff(SignInResult.Handoff("eu-9", "nm_ho_secret")) }
        assertEquals(ErrorCodes.UNKNOWN_CELL, e.code)
        assertEquals(listOf("/v0/auth/cells"), requests.map { it.url.encodedPath })
    }

    @Test
    fun aListedCellWithoutHttpsIsRefused() = runBlocking<Unit> {
        val c = client { req -> if (req.url.encodedPath == "/v0/auth/cells") json(cells) else json(session) }
        val e = assertFailsWith<NotificMeException> { c.auth.completeHandoff(SignInResult.Handoff("us-1", "nm_ho_secret")) }
        assertEquals(ErrorCodes.UNKNOWN_CELL, e.code)
        assertTrue(requests.none { it.url.host == "api-us1.notific.me" })
    }

    @Test
    fun aHandoffFromASingleCellServerIsRefused() = runBlocking<Unit> {
        val c = client { req -> if (req.url.encodedPath == "/v0/auth/cells") notFound() else json(session) }
        val e = assertFailsWith<NotificMeException> { c.auth.completeHandoff(SignInResult.Handoff("br-2", "nm_ho_secret")) }
        assertEquals(ErrorCodes.UNKNOWN_CELL, e.code)
        assertEquals(1, requests.size)
    }

    @Test
    fun aMalformedCellIdIsRefusedBeforeAnyCall() = runBlocking<Unit> {
        val c = client { json(cells) }
        for (bad in listOf("api.evil.com", "https://evil.com", "BR-1", "br-1/../x")) {
            val e = assertFailsWith<NotificMeException> { c.auth.magicLinkAt(bad, "nm_ml_x") }
            assertEquals(ErrorCodes.UNKNOWN_CELL, e.code)
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun anExpiredPassIsInvalidHandoff() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/auth/cells" -> json(cells)
                else -> json("""{"error":{"code":"invalid_handoff","message":"x"}}""", HttpStatusCode.Unauthorized)
            }
        }
        val e = assertFailsWith<NotificMeApiException> { c.auth.completeHandoff(SignInResult.Handoff("br-2", "nm_ho_old")) }
        assertEquals(ErrorCodes.INVALID_HANDOFF, e.code)
        assertFalse(e.message.orEmpty().contains("nm_ho_old"))
    }

    @Test
    fun aHandoffNeverChainsToAnother() = runBlocking<Unit> {
        val c = client { req -> if (req.url.encodedPath == "/v0/auth/cells") json(cells) else json(handoff) }
        val e = assertFailsWith<NotificMeException> { c.auth.completeHandoff(SignInResult.Handoff("br-2", "nm_ho_secret")) }
        assertEquals("invalid_response", e.code)
    }

    // ------------------------------------------------------------ links with c=

    @Test
    fun linkWithCellSignsInAtThatCell() = runBlocking<Unit> {
        val c = client { req -> if (req.url.encodedPath == "/v0/auth/cells") json(cells) else json(session) }
        val r = c.auth.magicLinkAt("br-2", "nm_ml_x", locale = "es")
        assertEquals("https://api-br2.notific.me", r.baseUrl)
        assertEquals("https://api-br2.notific.me/v0/auth/magic", requests.last().url.toString())
        assertEquals("nm_ml_x", requests.last().bodyJson()["token"]!!.jsonPrimitive.content)
    }

    @Test
    fun linkWithOwnCellOrSingleCellStaysOnTheEntry() = runBlocking<Unit> {
        val listed = client { req -> if (req.url.encodedPath == "/v0/auth/cells") json(cells) else json(session) }
        assertEquals("https://api.notific.me", listed.auth.magicLinkAt("br-1", "nm_ml_x").baseUrl)
        assertEquals("https://api.notific.me/v0/auth/magic", requests.last().url.toString())
        requests.clear()
        val single = client { req -> if (req.url.encodedPath == "/v0/auth/cells") notFound() else json(session) }
        assertEquals("https://api.notific.me", single.auth.magicLinkAt("br-2", "nm_ml_x").baseUrl)
        assertEquals("https://api.notific.me/v0/auth/magic", requests.last().url.toString())
    }

    @Test
    fun linkWithAnUnlistedCellCallsNothing() = runBlocking<Unit> {
        val c = client { req -> if (req.url.encodedPath == "/v0/auth/cells") json(cells) else json(session) }
        val e = assertFailsWith<NotificMeException> { c.auth.magicLinkAt("eu-9", "nm_ml_x") }
        assertEquals(ErrorCodes.UNKNOWN_CELL, e.code)
        assertEquals(listOf("/v0/auth/cells"), requests.map { it.url.encodedPath })
    }

    // ------------------------------------------------------------ the list

    @Test
    fun onlyPlainHttpsOriginsAreAccepted() {
        assertEquals("https://api-br2.notific.me", CellDirectory.safeHttpsBase("https://api-br2.notific.me/"))
        assertEquals("https://cell.example:8443", CellDirectory.safeHttpsBase("https://cell.example:8443"))
        for (bad in listOf(
            "http://api.notific.me", "ftp://x", "https://", "https://user@evil.com", "https://evil.com?x=1",
            "https://evil.com#a", "javascript:alert(1)", "api.notific.me", "not a url",
        )) {
            assertNull(CellDirectory.safeHttpsBase(bad), bad)
        }
        assertTrue(CellDirectory.isValidCellId("br-1"))
        assertTrue(CellDirectory.isValidCellId("lab"))
        assertFalse(CellDirectory.isValidCellId("br_1"))
        assertFalse(CellDirectory.isValidCellId(null))
    }

    @Test
    fun loopbackHttpOnlyWhenTheBuildAllowsIt() {
        val lab = listOf("http://127.0.0.1:58190", "http://localhost:58191/", "http://10.0.2.2:58190", "HTTP://LOCALHOST:1")
        for (url in lab) {
            // Store builds (the default): https only, as before.
            assertNull(CellDirectory.safeCellBase(url, allowLoopbackHttp = false), url)
            assertNull(CellDirectory.safeHttpsBase(url), url)
        }
        assertEquals("http://127.0.0.1:58190", CellDirectory.safeCellBase("http://127.0.0.1:58190", true))
        assertEquals("http://localhost:58191", CellDirectory.safeCellBase("http://localhost:58191/", true))
        assertEquals("http://10.0.2.2:58190", CellDirectory.safeCellBase("http://10.0.2.2:58190", true))
        // Debug/QA still refuses http anywhere else, and the usual tricks.
        for (bad in listOf(
            "http://api-br2.notific.me", "http://192.168.0.10:58190", "http://localhost.evil.com", "http://10.0.2.20",
            "http://127.0.0.1@evil.com", "http://user@127.0.0.1", "http://127.0.0.1?x=1", "http://localhost#a", "ws://127.0.0.1",
        )) {
            assertNull(CellDirectory.safeCellBase(bad, allowLoopbackHttp = true), bad)
        }
        // https keeps working with the flag on.
        assertEquals("https://api-br2.notific.me", CellDirectory.safeCellBase("https://api-br2.notific.me/", true))
    }

    @Test
    fun handoffToALabCellOnlyWithTheFlag() = runBlocking<Unit> {
        val labCells = """{"self":"lab","cells":[{"cell_id":"lab","url":"http://10.0.2.2:58190"},{"cell_id":"la-2","url":"http://10.0.2.2:58191"}]}"""
        fun entry(allow: Boolean) = NotificClient(
            null,
            NotificClient.Config(
                baseUrl = "http://10.0.2.2:58190",
                allowLoopbackHttpCells = allow,
                httpClient = HttpClient(MockEngine { req ->
                    requests += req
                    if (req.url.encodedPath == "/v0/auth/cells") json(labCells) else json(session)
                }),
            ),
        )
        // Default (store build): the http lab cell is unknown and nothing else is called.
        val e = assertFailsWith<NotificMeException> { entry(false).auth.completeHandoff(SignInResult.Handoff("la-2", "nm_ho_1")) }
        assertEquals(ErrorCodes.UNKNOWN_CELL, e.code)
        assertEquals(listOf("/v0/auth/cells"), requests.map { it.url.encodedPath })
        requests.clear()
        // Debug/QA: the pass goes to the lab cell.
        val r = entry(true).auth.completeHandoff(SignInResult.Handoff("la-2", "nm_ho_1"))
        assertEquals("http://10.0.2.2:58191", r.baseUrl)
        assertEquals("http://10.0.2.2:58191/v0/auth/handoff", requests.last().url.toString())
        requests.clear()
        val link = entry(true).auth.magicLinkAt("la-2", "nm_ml_x")
        assertEquals("http://10.0.2.2:58191", link.baseUrl)
    }

    @Test
    fun theFlagNeverOpensHttpOutsideLoopback() = runBlocking<Unit> {
        // cells lists us-1 at http://api-us1.notific.me.
        val c = NotificClient(
            null,
            NotificClient.Config(
                baseUrl = "https://api.notific.me", allowLoopbackHttpCells = true,
                httpClient = HttpClient(MockEngine { req -> requests += req; if (req.url.encodedPath == "/v0/auth/cells") json(cells) else json(session) }),
            ),
        )
        val e = assertFailsWith<NotificMeException> { c.auth.magicLinkAt("us-1", "nm_ml_x") }
        assertEquals(ErrorCodes.UNKNOWN_CELL, e.code)
        assertEquals(listOf("/v0/auth/cells"), requests.map { it.url.encodedPath })
    }

    @Test
    fun handleActionIsReadWhenTheServerSendsIt() {
        val me = Wire.json.decodeFromString(
            Me.serializer(),
            """{"user":{"id":"u1","handle":"ana","handle_action":"choose_new"},"workspaces":[{"id":"w1","handle":"acme","role":"owner","handle_action":"choose_new"}]}""",
        )
        assertEquals("choose_new", me.user.handleAction)
        assertEquals("choose_new", me.workspaces.single().handleAction)
        // Today's servers do not send it.
        assertNull(Wire.json.decodeFromString(Me.serializer(), """{"user":{"id":"u1"}}""").user.handleAction)
    }
}
