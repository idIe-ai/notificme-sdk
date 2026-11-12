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

    @Test
    fun questionSetsSayWhenTheyAskForASignature() {
        assertTrue(Wire.json.decodeFromString(QuestionSet.serializer(), """{"id":"q","require_signature":true}""").requireSignature)
        assertEquals(false, Wire.json.decodeFromString(QuestionSet.serializer(), """{"id":"q"}""").requireSignature)
    }

    @Test
    fun deletedAccountsAreMarked() {
        // Sent: a recipient and an answer from a deleted account, with the server's label and no @handle or photo.
        val d = Wire.json.decodeFromString(
            SentDetail.serializer(),
            """{"id":"s1","recipients":[{"person":{"id":"u9","handle":"","display_name":"Deleted account","avatar_url":"","account_deleted":true}},{"person":{"id":"u1","handle":"ana","display_name":"Ana"}}],
               "answers":[{"person":{"id":"u9","handle":"","display_name":"Deleted account","account_deleted":true},"answered_at":"2026-11-11T12:00:00Z","answers":[]}]}""",
        )
        assertTrue(d.recipients[0].person!!.accountDeleted)
        assertEquals(false, d.recipients[1].person!!.accountDeleted)
        assertTrue(d.answers[0].person!!.accountDeleted)
        // The contact and member views carry it into their Person.
        assertTrue(Wire.json.decodeFromString(Contact.serializer(), """{"id":"u9","account_deleted":true}""").person.accountDeleted)
        assertTrue(Wire.json.decodeFromString(OrgMember.serializer(), """{"id":"u9","account_deleted":true}""").person.accountDeleted)
        assertEquals(false, Wire.json.decodeFromString(Contact.serializer(), """{"id":"u1","handle":"ana"}""").person.accountDeleted)

        // Votes (always "Deleted account" in English, the same view goes to the sync).
        val view = Wire.json.decodeFromString(
            DecisionView.serializer(),
            """{"rule":"quorum","required":2,"votes":[{"user_id":"u9","handle":null,"display_name":"Deleted account","approve":true,"at":"2026-11-11T12:00:00Z","account_deleted":true}],
               "decided_by":{"user_id":"u1","handle":"ana","display_name":"Ana","approve":true}}""",
        )
        assertTrue(view.votes.single().accountDeleted)
        assertEquals("Deleted account", view.votes.single().mention)
        assertEquals(false, view.decidedBy!!.accountDeleted)

        // The question set says who answered is gone, but the signed reply keeps the identity untouched.
        val raw = """{"question_set_id":"q","status":"answered","answers":[],"answered_by":{"identity":"rafa@acme.com","level_used":1,"auth_method":"unlock"},"signature":{"alg":"Ed25519","value":"x"}}"""
        val qs = Wire.json.decodeFromString(QuestionSet.serializer(), """{"id":"q","status":"answered","answered_by_deleted":true,"reply":$raw}""")
        assertTrue(qs.answeredByDeleted)
        assertEquals("rafa@acme.com", qs.reply!!.answeredBy!!.identity)
        assertEquals(Wire.json.decodeFromString(Reply.serializer(), raw), qs.reply)
        assertEquals(false, Wire.json.decodeFromString(QuestionSet.serializer(), """{"id":"q"}""").answeredByDeleted)
    }

    @Test
    fun signedReplyCarriesTheSignature() {
        val reply = Wire.json.decodeFromString(
            Reply.serializer(),
            """{"question_set_id":"q","status":"answered","e_signature":{"name":"Ana Souza","initials":"AS","method":"typed","style":"great-vibes","signature_png":"AAEC","signature_sha256":"sha256:x","initials_png":"AwQ=","initials_sha256":"sha256:y","consent_version":"esign-2026-11-09","adopted_at":"2026-11-09T14:03:11Z"}}""",
        )
        val e = reply.eSignature!!
        assertEquals("Ana Souza", e.name)
        assertEquals(SignatureStyle.GreatVibes, e.style)
        assertEquals("esign-2026-11-09", e.consentVersion)
        assertNull(Wire.json.decodeFromString(Reply.serializer(), """{"question_set_id":"q"}""").eSignature)
    }

    @Test
    fun unknownSignatureValuesFallBack() {
        val e = Wire.json.decodeFromString(ESignature.serializer(), """{"name":"A","method":"stylus","style":"comic-sans"}""")
        assertEquals(SignatureMethod.Unknown, e.method)
        assertNull(e.style)
        assertEquals(SignatureStyle.MrDafoe, SignatureStyle.of("mr-dafoe"))
        assertNull(SignatureStyle.of("nope"))
        assertEquals(listOf("great-vibes", "dancing-script", "sacramento", "mr-dafoe"), SignatureStyle.entries.map { it.wire })
    }

    @Test
    fun signatureHashesAreSha256OfThePng() {
        // SHA-256 of "abc", the classic test vector.
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ESignature.sha256Of("abc".toByteArray()))
        val png = byteArrayOf(-119, 80, 78, 71)
        val b64 = java.util.Base64.getEncoder().encodeToString(png)
        val ok = ESignature(signaturePng = b64, signatureSha256 = ESignature.sha256Of(png), initialsPng = b64, initialsSha256 = ESignature.sha256Of(png))
        assertTrue(ok.hashesMatch())
        assertTrue(!ok.copy(initialsSha256 = "sha256:00").hashesMatch())
        assertTrue(!ESignature().hashesMatch())
        assertEquals(0, ESignature(signaturePng = "not base64!").signatureBytes().size)
    }

    @Test
    fun faceVerificationOnTheWire() {
        assertTrue(Wire.json.decodeFromString(QuestionSet.serializer(), """{"id":"q","require_face":true}""").requireFace)
        assertEquals(false, Wire.json.decodeFromString(QuestionSet.serializer(), """{"id":"q"}""").requireFace)
        val reply = Wire.json.decodeFromString(
            Reply.serializer(),
            """{"question_set_id":"q","face_verification":{"result":"fallback","reason":"not_enrolled","verified_at":"2026-11-10T14:03:11Z"}}""",
        )
        assertEquals(FaceVerification.Result.Fallback, reply.faceVerification?.result)
        assertEquals(FaceVerification.Reason.NotEnrolled, reply.faceVerification?.reason)
        assertNull(reply.faceVerification?.verificationId)
        assertNull(Wire.json.decodeFromString(Reply.serializer(), """{"question_set_id":"q"}""").faceVerification)
        assertTrue(
            Wire.json.encodeToString(Compose.serializer(), Compose(kind = Compose.FORM, to = listOf("@ana"), title = "T", requireFace = true))
                .contains(""""require_face":true"""),
        )
    }

    @Test
    fun composeAndSentCarryTheSignature() {
        val wire = Wire.json.parseToJsonElement(
            Wire.json.encodeToString(Compose.serializer(), Compose(kind = Compose.FORM, to = listOf("@ana"), title = "T", requireSignature = true)),
        ).jsonObject
        assertEquals("true", wire["require_signature"]!!.jsonPrimitive.content)
        val d = Wire.json.decodeFromString(
            SentDetail.serializer(),
            """{"id":"s1","kind":"form","require_signature":true,"answers":[{"person":{"id":"u1","handle":"ana"},"answered_at":"2026-11-09T15:00:00Z","answers":[],"e_signature":{"name":"Ana","initials":"A","method":"drawn","signature_png":"AA==","signature_sha256":"sha256:1","initials_png":"AA==","initials_sha256":"sha256:2","adopted_at":"2026-11-09T14:00:00Z"}},{"person":{"id":"u2"},"answers":[]}]}""",
        )
        assertTrue(d.requireSignature)
        assertEquals(SignatureMethod.Drawn, d.answers[0].eSignature?.method)
        assertNull(d.answers[1].eSignature)
        assertTrue(Wire.json.decodeFromString(SentItem.serializer(), """{"id":"s1","require_signature":true}""").requireSignature)
    }
}
