package me.notific.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue

/**
 * Runs against a real server when these are set (skipped otherwise):
 * NOTIFICME_E2E_URL, NOTIFICME_E2E_SERVICE_TOKEN (scopes members:token,
 * asks:write, asks:read) and NOTIFICME_E2E_EMAIL.
 *
 * It plays the company backend (issues a person token and asks a question)
 * and the company app (this client): the question must arrive in realtime,
 * the answer must come back signed as `delegated`, and higher levels must
 * be refused.
 */
class LiveServerTest {
    private val url = System.getenv("NOTIFICME_E2E_URL")?.trimEnd('/')
    private val service = System.getenv("NOTIFICME_E2E_SERVICE_TOKEN")
    private val email = System.getenv("NOTIFICME_E2E_EMAIL")

    private val http = HttpClient(OkHttp) { expectSuccess = false }

    private suspend fun backend(method: String, path: String, body: String? = null): Pair<Int, JsonObject> {
        val res = if (method == "GET") {
            http.get(url + path) { header(HttpHeaders.Authorization, "Bearer $service") }
        } else {
            http.post(url + path) {
                header(HttpHeaders.Authorization, "Bearer $service")
                setBody(TextContent(body ?: "{}", ContentType.Application.Json))
            }
        }
        val text = res.bodyAsText()
        return res.status.value to (runCatching { Wire.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: JsonObject(emptyMap()))
    }

    private suspend fun ask(handle: String, title: String, level: Int): String {
        val (status, body) = backend(
            "POST", "/v0/asks",
            """{"to":["@$handle"],"title":"$title","level":$level,"timeout":"10m","questions":[{"id":"ok","type":"yesno","text":"Approve?"},{"id":"note","type":"text","text":"Note","optional":true}]}""",
        )
        assertEquals(201, status, "create ask: $body")
        return body["id"]!!.jsonPrimitive.content
    }

    @Test
    fun companyAppAnswersThroughAPersonToken() = runBlocking<Unit> {
        assumeTrue("set NOTIFICME_E2E_URL, NOTIFICME_E2E_SERVICE_TOKEN and NOTIFICME_E2E_EMAIL", url != null && service != null && email != null)

        // The company backend issues a person token.
        val (issued, token) = backend("POST", "/v0/member-tokens", """{"email":"$email","external_id":"e2e-kotlin","ttl":"15m"}""")
        assertEquals(201, issued, "issue: $token")
        val personToken = token["token"]!!.jsonPrimitive.content
        val workspaceId = token["workspace_id"]!!.jsonPrimitive.content
        val handle = token["user"]!!.jsonObject["handle"]!!.jsonPrimitive.content
        assertTrue(personToken.startsWith("nm_dlg_"))

        val client = NotificClient(TokenProvider { refresh -> if (refresh) null else personToken }, NotificClient.Config(baseUrl = url!!, platform = "android"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // The token only sees the company's workspace.
            val me = client.me()
            assertEquals(listOf(workspaceId), me.workspaces.map { it.id })

            val inbox = Inbox(client, scope)
            inbox.start()
            withTimeout(20_000) { inbox.state.first { it.connection == Connection.Live } }

            // A question asked now arrives over the realtime feed.
            val id = ask(handle, "E2E Kotlin level 1", level = 1)
            val qs = withTimeout(20_000) { inbox.state.first { s -> s.questions.any { it.id == id } } }.questions.first { it.id == id }
            assertTrue(qs.answerable)
            assertEquals(workspaceId, qs.workspaceId)

            inbox.reply(qs, listOf(Answer.yesNo("ok", true), Answer.text("note", "from the Kotlin E2E")))
            val (_, view) = backend("GET", "/v0/asks/$id")
            assertEquals("answered", view["status"]!!.jsonPrimitive.content)
            val by = view["reply"]!!.jsonObject["answered_by"]!!.jsonObject
            assertEquals("delegated", by["auth_method"]!!.jsonPrimitive.content)

            // Level 2 needs a stronger proof than the company's session.
            val id2 = ask(handle, "E2E Kotlin level 2", level = 2)
            val qs2 = withTimeout(20_000) { inbox.state.first { s -> s.questions.any { it.id == id2 } } }.questions.first { it.id == id2 }
            assertTrue(!qs2.answerable)
            val refused = assertFailsWith<NotificMeApiException> { client.reply(qs2, listOf(Answer.yesNo("ok", true))) }
            assertEquals("step_up_required", refused.code)

            // Revoked tokens stop working.
            val (_, revoked) = backend("POST", "/v0/member-tokens/revoke", """{"email":"$email"}""")
            assertTrue(revoked["revoked"]!!.jsonPrimitive.content.toInt() >= 1)
            val gone = assertFailsWith<NotificMeException> { client.me() }
            assertEquals("unauthorized", gone.code)
        } finally {
            scope.cancel()
            client.close()
            http.close()
        }
    }
}
