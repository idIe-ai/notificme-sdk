package me.notific.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WireTest {
    @Test
    fun unknownBlockIsKeptAsIs() {
        val json = """{"blocks":[{"type":"map","lat":-23.5,"lng":-46.6},{"type":"text","text":"hi"}]}"""
        val card = Wire.json.decodeFromString(Card.serializer(), json)
        val unknown = assertIs<Block.Unknown>(card.blocks[0])
        assertEquals("map", unknown.type)
        assertEquals(Block.Text("hi"), card.blocks[1])
        // Round trip keeps the unknown block untouched.
        val back = Wire.json.parseToJsonElement(Wire.json.encodeToString(Card.serializer(), card)).jsonObject
        assertEquals(Wire.json.parseToJsonElement(json).jsonObject["blocks"], back["blocks"])
    }

    @Test
    fun knownBlockWithBadFieldsDoesNotBreakTheCard() {
        val card = Wire.json.decodeFromString(Card.serializer(), """{"blocks":[{"type":"metric","label":"x"}]}""")
        assertIs<Block.Unknown>(card.blocks.single())
    }

    @Test
    fun blocksAreWrittenWithTheirType() {
        val out = Wire.json.encodeToString(ListSerializer(Block.serializer()), listOf(Block.Options("ok"), Block.Progress(value = 0.5)))
        assertEquals("""[{"type":"options","question_id":"ok"},{"type":"progress","value":0.5,"steps":[]}]""", out)
    }

    @Test
    fun cardDecodesWithAStrictJsonToo() {
        val strict = Json { ignoreUnknownKeys = false }
        val card = strict.decodeFromString(Card.serializer(), """{"blocks":[{"type":"input","question_id":"note"}]}""")
        assertEquals(Block.Input("note"), card.blocks.single())
    }

    @Test
    fun unknownEnumValuesFallBack() {
        val q = Wire.json.decodeFromString(Question.serializer(), """{"id":"a","type":"signature","text":"Sign"}""")
        assertEquals(QuestionType.Unknown, q.type)
        val n = Wire.json.decodeFromString(Notification.serializer(), """{"id":"1","severity":"apocalyptic"}""")
        assertEquals(Severity.Info, n.severity)
        val by = Wire.json.decodeFromString(AnsweredBy.serializer(), """{"identity":"a@b.c","auth_method":"passkey"}""")
        assertNull(by.authMethod)
        val delegated = Wire.json.decodeFromString(AnsweredBy.serializer(), """{"identity":"a@b.c","auth_method":"delegated"}""")
        assertEquals(AuthMethod.Delegated, delegated.authMethod)
    }

    @Test
    fun answersFollowTheServerRules() {
        fun enc(a: Answer) = Wire.json.encodeToString(Answer.serializer(), a)
        assertEquals("""{"question_id":"ok","value":true}""", enc(Answer.yesNo("ok", true)))
        assertEquals("""{"question_id":"s","value":"Dell"}""", enc(Answer.choice("s", "Dell")))
        assertEquals("""{"question_id":"s","free_text":"HP"}""", enc(Answer.other("s", " HP ")))
        assertEquals("""{"question_id":"m","value":["a","b"],"free_text":"c"}""", enc(Answer.choices("m", listOf("a", "b", "a"), other = "c")))
        assertEquals("""{"question_id":"m","value":["a"]}""", enc(Answer.choices("m", listOf("a"), other = "  ")))
        assertEquals("""{"question_id":"t","free_text":"ship it"}""", enc(Answer.text("t", "ship it ")))
        assertEquals("""{"question_id":"n","value":12}""", enc(Answer.number("n", 12)))
        assertEquals("""{"question_id":"d","value":"2026-11-30"}""", enc(Answer.date("d", "2026-11-30")))
    }

    @Test
    fun notificationHeadline() {
        val weak = Notification(id = "1", source = "dokploy", title = "dokploy", summary = "api-v2 died")
        assertEquals("api-v2 died", weak.headline)
        assertEquals("", weak.subtitle)
        val strong = Notification(id = "2", source = "dokploy", title = "Container died", summary = "api-v2 died")
        assertEquals("Container died", strong.headline)
        assertEquals("api-v2 died", strong.subtitle)
        assertTrue(Notification(id = "3", deliveries = listOf(DeliveryState(readAt = null))).unread)
    }

    @Test
    fun realtimeMessages() {
        assertIs<RealtimeEvent.Connected>(Realtime.parse("""{"type":"hello","user_id":"u","session_id":"s","heartbeat_ms":25000}"""))
        val sync = assertIs<RealtimeEvent.Changes>(Realtime.parse("""{"type":"sync","changes":[{"seq":3,"entity":"notification","id":"n1","op":"upsert","workspace_id":"w"}],"next_cursor":3,"has_more":false,"reset":false}"""))
        assertEquals(3, sync.page.nextCursor)
        assertEquals(RealtimeEvent.Ready(7), Realtime.parse("""{"type":"ready","cursor":7}"""))
        val d = assertIs<RealtimeEvent.Delivered>(Realtime.parse("""{"type":"delivery","seq":8,"delivery":{"id":"d1","created_at":"2026-11-06T10:00:00Z","notification_id":"n1","question_set_id":"q1","seq":8,"severity":"high","summary":"x"}}"""))
        assertEquals("q1", d.delivery.questionSetId)
        assertEquals(Severity.High, d.delivery.severity)
        assertEquals(RealtimeEvent.ServerError("bad", "nope"), Realtime.parse("""{"type":"error","code":"bad","message":"nope"}"""))
        assertNull(Realtime.parse("""{"type":"pong"}"""))
        assertNull(Realtime.parse("not json"))
    }

    @Test
    fun sessionExpiry() {
        val user = User(id = "u")
        assertTrue(Session("t", "s", "2000-01-01T00:00:00Z", user).isExpired())
        assertTrue(!Session("t", "s", "2999-01-01T00:00:00+00:00", user).isExpired())
        assertTrue(!Session("t", "s", "garbage", user).isExpired())
        assertEquals("x", Wire.json.parseToJsonElement("""{"a":"x"}""").jsonObject["a"]!!.jsonPrimitive.content)
    }
}
