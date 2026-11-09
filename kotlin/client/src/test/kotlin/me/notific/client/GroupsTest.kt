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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Groups, the authority ladder and the "To" preview, against a mocked server. */
class GroupsTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): NotificClient {
        val engine = MockEngine { req ->
            requests += req
            handler(req)
        }
        return NotificClient(TokenProvider { "nm_ses_test" }, NotificClient.Config(baseUrl = "https://api.test", httpClient = HttpClient(engine)))
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.bodyJson(): JsonObject = Wire.json.parseToJsonElement((body as TextContent).text).jsonObject

    private val finance = """{"id":"g1","handle":"finance","name":"Finance","description":"Pays the bills","member_count":2,"created_at":"2026-11-09T10:00:00Z","updated_at":"2026-11-09T10:00:00Z"}"""
    private val financeDetail = """{"id":"g1","handle":"finance","name":"Finance","description":"","member_count":2,"members":[
        {"user_id":"u1","handle":"ana","display_name":"Ana","avatar_url":"/v0/avatars/a.webp","email":"ana@acme.com","role":"admin","guest":false,"authority_level":{"id":"l3","key":"manager","name":"Manager","rank":2,"members":1},"added_at":"2026-11-09T10:00:00Z"},
        {"user_id":"u2","handle":null,"display_name":"","avatar_url":"","email":"zed@partner.com","role":"guest","guest":true,"authority_level":null,"added_at":"2026-11-09T10:00:00Z"}]}"""

    @Test
    fun listsGroupsWithWhatThePlanAllows() = runBlocking<Unit> {
        val c = client { json("""{"items":[$finance],"groups_enabled":true,"can_manage":true,"plan":"team","plans":["team","business","enterprise"],"send_limit":500}""") }
        val list = c.groups.list("acme")
        assertEquals("/v0/workspaces/acme/groups", requests.single().url.encodedPath)
        assertEquals("#finance", list.items.single().tag)
        assertEquals(2, list.items.single().memberCount)
        assertTrue(list.groupsEnabled && list.canManage)
        assertEquals(500, list.sendLimit)
        assertEquals(listOf("team", "business", "enterprise"), list.plans)
    }

    @Test
    fun groupDetailHasPeopleWithTheirLevel() = runBlocking<Unit> {
        val c = client { json(financeDetail) }
        val g = c.groups.get("acme", "#finance")
        assertEquals("/v0/workspaces/acme/groups/finance", requests.single().url.encodedPath)
        val (ana, zed) = g.members
        assertEquals("manager", ana.authorityLevel?.key)
        assertEquals(2, ana.authorityLevel?.rank)
        assertEquals(Person("u1", "ana", "Ana", "/v0/avatars/a.webp"), ana.person)
        assertTrue(zed.guest)
        assertNull(zed.authorityLevel)
        assertNull(zed.handle)
        assertEquals("zed@partner.com", zed.name)
        assertEquals("Finance", g.group.name)
    }

    @Test
    fun createUpdateAndDelete() = runBlocking<Unit> {
        val c = client { req ->
            when (req.method.value) {
                "DELETE" -> respond("", HttpStatusCode.NoContent)
                "POST" -> json(financeDetail, HttpStatusCode.Created)
                else -> json(financeDetail)
            }
        }
        c.groups.create("acme", "#finance", " Finance ", description = "Pays the bills")
        val created = requests.last().bodyJson()
        assertEquals("finance", created["handle"]!!.jsonPrimitive.content)
        assertEquals("Finance", created["name"]!!.jsonPrimitive.content)
        assertEquals("Pays the bills", created["description"]!!.jsonPrimitive.content)

        c.groups.update("acme", "g1", name = "Finance team")
        assertEquals("PATCH", requests.last().method.value)
        assertEquals("/v0/workspaces/acme/groups/g1", requests.last().url.encodedPath)
        // Only what changes goes.
        assertEquals(setOf("name"), requests.last().bodyJson().keys)

        c.groups.delete("acme", "finance")
        assertEquals("DELETE", requests.last().method.value)
        assertEquals("/v0/workspaces/acme/groups/finance", requests.last().url.encodedPath)
    }

    @Test
    fun membersAreAddedLeveledAndRemoved() = runBlocking<Unit> {
        val c = client { req ->
            when (req.method.value) {
                "DELETE" -> respond("", HttpStatusCode.NoContent)
                "PATCH" -> json("""{"user_id":"u2","display_name":"Zed","role":"guest","guest":true,"authority_level":null}""")
                else -> json(financeDetail)
            }
        }
        c.groups.addMembers("acme", "finance", listOf("u1", "u2", "u1"), authorityLevel = "operator")
        val add = requests.last().bodyJson()
        assertEquals("/v0/workspaces/acme/groups/finance/members", requests.last().url.encodedPath)
        assertEquals(listOf("u1", "u2"), add["user_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("operator", add["authority_level"]!!.jsonPrimitive.content)

        c.groups.addMembers("acme", "finance", listOf("u3"))
        assertNull(requests.last().bodyJson()["authority_level"])

        val m = c.groups.setMemberLevel("acme", "finance", "u2", null)
        assertEquals("/v0/workspaces/acme/groups/finance/members/u2", requests.last().url.encodedPath)
        // "No level" is an explicit null, not a missing field.
        assertEquals(JsonNull, requests.last().bodyJson()["authority_level"])
        assertNull(m.authorityLevel)

        c.groups.setMemberLevel("acme", "finance", "u2", "manager")
        assertEquals("manager", requests.last().bodyJson()["authority_level"]!!.jsonPrimitive.content)

        c.groups.removeMember("acme", "finance", "u2")
        assertEquals("DELETE", requests.last().method.value)
    }

    @Test
    fun authorityLadderLowestFirst() = runBlocking<Unit> {
        val ladder = """{"items":[
            {"id":null,"key":"operator","name":"Operator","rank":0,"members":0},
            {"id":null,"key":"supervisor","name":"Supervisor","rank":1,"members":0},
            {"id":null,"key":"manager","name":"Manager","rank":2,"members":0},
            {"id":null,"key":"director","name":"Director","rank":3,"members":0}],
            "stored":false,"groups_enabled":true,"can_manage":true,"plan":"team","plans":["team"]}"""
        val c = client { json(ladder) }
        val levels = c.groups.authorityLevels("acme")
        assertFalse(levels.stored)
        assertEquals(listOf("operator", "supervisor", "manager", "director"), levels.items.map { it.key })
        assertNull(levels.items.first().id)

        val edited = listOf(AuthorityLevelInput("l1", "Analyst ", "Analyst"), AuthorityLevelInput(null, "head", "Head"))
        c.groups.setAuthorityLevels("acme", edited)
        val req = requests.last()
        assertEquals("PUT", req.method.value)
        assertEquals("/v0/workspaces/acme/authority-levels", req.url.encodedPath)
        val items = req.bodyJson()["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals("l1", items[0]["id"]!!.jsonPrimitive.content)
        assertEquals("analyst", items[0]["key"]!!.jsonPrimitive.content)
        // A new level goes without an id.
        assertNull(items[1]["id"])
        assertEquals("Head", items[1]["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun levelStillInUseSaysWhoHasIt() = runBlocking<Unit> {
        val c = client {
            json("""{"error":{"code":"authority_level_in_use","message":"x","details":{"key":"manager","name":"Manager","members":3}}}""", HttpStatusCode.Conflict)
        }
        val e = assertFailsWith<NotificMeApiException> { c.groups.setAuthorityLevels("acme", emptyList()) }
        assertEquals(ErrorCodes.AUTHORITY_LEVEL_IN_USE, e.code)
        assertEquals("Manager", e.detail("name"))
        assertEquals(3, e.detailInt("members"))
        assertNull(e.detail("nope"))
    }

    @Test
    fun planWithoutGroups() = runBlocking<Unit> {
        val c = client {
            json("""{"error":{"code":"plan_required","details":{"plan":"pro","plans":["team","business"],"feature":"groups"}}}""", HttpStatusCode.Forbidden)
        }
        val e = assertFailsWith<NotificMeApiException> { c.groups.create("acme", "finance", "Finance") }
        assertEquals(ErrorCodes.PLAN_REQUIRED, e.code)
        assertEquals("pro", e.detail("plan"))
    }

    @Test
    fun workspaceMembersToPick() = runBlocking<Unit> {
        val c = client {
            json("""{"items":[{"user_id":"u1","email":"ana@acme.com","email_id":"e1","handle":"ana","display_name":"Ana","avatar_url":"","role":"owner","joined_at":"2026-11-01T00:00:00Z"},{"user_id":"u9","email":"g@x.com","handle":null,"display_name":"","role":"guest"}]}""")
        }
        val members = c.groups.members("acme")
        assertEquals("/v0/workspaces/acme/members", requests.single().url.encodedPath)
        assertEquals("Ana", members[0].name)
        assertTrue(members[1].guest)
        assertEquals("g@x.com", members[1].name)
    }

    @Test
    fun previewSaysHowManyPeople() = runBlocking<Unit> {
        val c = client { req ->
            val to = req.bodyJson()["to"]!!.jsonArray.map { it.jsonPrimitive.content }
            if (to.contains("#all")) json("""{"count":620,"limit":500,"over_limit":true,"people":[]}""")
            else json("""{"count":3,"limit":500,"over_limit":false,"people":[{"id":"u1","handle":"ana","display_name":"Ana"}]}""")
        }
        val p = c.people.preview(listOf("#finance>=manager", "@bruno"), from = "acme")
        assertEquals("/v0/me/compose/preview", requests.last().url.encodedPath)
        assertEquals("acme", requests.last().bodyJson()["from"]!!.jsonPrimitive.content)
        assertEquals(3, p.count)
        assertFalse(p.overLimit)
        assertEquals("ana", p.people.single().handle)

        val over = c.people.preview(listOf("#all"))
        assertNull(requests.last().bodyJson()["from"])
        assertTrue(over.overLimit)
        assertEquals(500, over.limit)
    }

    @Test
    fun tooManyRecipientsAndBadTags() = runBlocking<Unit> {
        val c = client { req ->
            if (req.bodyJson()["to"]!!.jsonArray.size > 1) {
                json("""{"error":{"code":"validation_failed","details":{"errors":[{"path":"/to/1","message":"unknown group"}]}}}""", HttpStatusCode.UnprocessableEntity)
            } else {
                json("""{"error":{"code":"too_many_recipients","details":{"count":620,"limit":500,"plan":"team"}}}""", HttpStatusCode.UnprocessableEntity)
            }
        }
        val many = assertFailsWith<NotificMeApiException> { c.people.send(Compose(kind = Compose.MESSAGE, to = listOf("#all"), title = "Hi", text = "x", from = "acme")) }
        assertEquals(ErrorCodes.TOO_MANY_RECIPIENTS, many.code)
        assertEquals(620, many.detailInt("count"))
        assertEquals(500, many.detailInt("limit"))

        val bad = assertFailsWith<NotificMeApiException> { c.people.send(Compose(kind = Compose.MESSAGE, to = listOf("@ana", "#nope"), title = "Hi", text = "x")) }
        assertEquals(listOf(FieldError("/to/1", "unknown group")), bad.fieldErrors)
    }
}
