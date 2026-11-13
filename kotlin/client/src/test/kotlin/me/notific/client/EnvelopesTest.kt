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
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Envelopes on the signer's side: the wire, the canonical JSON and the calls, against a mocked server. */
class EnvelopesTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): NotificClient {
        val engine = MockEngine { req ->
            requests += req
            handler(req)
        }
        return NotificClient(TokenProvider { "nm_ses_test" }, NotificClient.Config(baseUrl = "https://api.test", httpClient = HttpClient(engine), maxRetries = 0))
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun HttpRequestData.bodyJson(): JsonObject = Wire.json.parseToJsonElement((body as TextContent).text).jsonObject

    private val sha = "sha256:" + "9f2c".repeat(16)
    private val layout = "sha256:" + "77d0".repeat(16)

    private val view = """{
      "envelope":{"id":"env1","title":"Contrato","message":"Veja https://x.test/a.pdf","status":"in_progress","level":3,"locale":"pt-BR",
        "timezone":"America/Sao_Paulo","expires_at":"2026-12-13T00:00:00Z",
        "sender":{"name":"Ana","handle":"ana","workspace_name":"Acme","workspace_logo_url":"/v0/logos/a.webp"},
        "document":{"filename":"contrato.pdf","sha256":"$sha","bytes":1234,"pages":[{"number":1,"width":595.28,"height":841.89,"rotate":0},{"number":2,"width":841.89,"height":595.28,"rotate":90}],
          "url":"/v0/envelope-files/f1?e=1&p=s1&s=x","url_expires_at":"2026-11-13T17:15:00Z"},
        "layout_sha256":"$layout"},
      "signer":{"id":"s1","position":1,"routing_order":1,"person":{"id":"u1","handle":"bruno","display_name":"Bruno"},"email":null,"name":"Bruno","status":"opened","is_me":true,"method":null,"level_used":null},
      "signers":[{"id":"s1","name":"Bruno","status":"opened"},{"id":"s2","name":"Carla","status":"signed"}],
      "fields":[
        {"id":"f3","signer_id":"s1","type":"text","page":1,"x":0.1,"y":0.2,"width":0.4,"height":0.04,"required":false,"label":"Empresa","auto":false,"value":null},
        {"id":"f1","signer_id":"s1","type":"signature","page":2,"x":0.1,"y":0.7,"width":0.3,"height":0.06,"required":true,"label":"","auto":false,"value":null},
        {"id":"f2","signer_id":"s1","type":"text","page":1,"x":0.1,"y":0.5,"width":0.4,"height":0.04,"required":true,"label":"CPF","auto":false,"value":null},
        {"id":"f9","signer_id":"s2","type":"checkbox","page":1,"x":0.5,"y":0.5,"width":0.1,"height":0.04,"required":true,"label":"","auto":true,"value":"x"}],
      "methods":["device_key","passkey"],"blocked":null,"signature":null,"account":true,
      "challenge":{"value":"q8Zr","expires_at":"2026-11-13T17:08:00Z"},"email_hint":null,"consent_version":"envelope-2026-11-13"}"""

    private val signature = ESignature(name = "Bruno Lima", initials = "BL", signatureSha256 = "sha256:5be1", initialsSha256 = "sha256:41ab")

    // ------------------------------------------------------------------ wire

    @Test
    fun signingViewDecodesAndKeepsUnknownsHarmless() {
        val v = Wire.json.decodeFromString(EnvelopeSigningView.serializer(), view)
        assertEquals(EnvelopeStatus.InProgress, v.envelope.status)
        assertEquals(listOf(EnvelopeSignMethod.DeviceKey), v.signMethods)
        assertEquals(EnvelopeFieldType.Unknown, v.fields.last().type)
        assertEquals(listOf("f3", "f2", "f1"), v.myFields.map { it.id })
        assertEquals(2, v.envelope.document.pages.size)
        assertTrue(v.envelope.document.pages[1].aspect > 1f)
        assertTrue(v.canSign)
        assertEquals(EnvelopeSignerStatus.Signed, v.signers[1].status)
        // Every text field of the signer goes, an empty one as "".
        assertEquals(mapOf("f3" to "", "f2" to "123"), v.textValues(mapOf("f2" to "123", "f1" to "no")))
    }

    @Test
    fun closedOrBlockedViewsCannotSign() {
        val v = Wire.json.decodeFromString(EnvelopeSigningView.serializer(), view)
        assertFalse(v.copy(blocked = "device_key_not_registered", methodsWire = emptyList()).canSign)
        assertFalse(v.copy(signer = v.signer.copy(status = EnvelopeSignerStatus.Signed)).canSign)
        assertFalse(v.copy(envelope = v.envelope.copy(status = EnvelopeStatus.Completed)).canSign)
        assertTrue(EnvelopeStatus.Expired.closed)
        assertFalse(EnvelopeStatus.Completing.closed)
    }

    @Test
    fun envelopeAndListDecode() {
        val e = Wire.json.decodeFromString(
            Envelope.serializer(),
            """{"id":"e1","status":"completed","sender":{"id":"u1","handle":"ana","display_name":""},"signers":[{"id":"s1","name":"Bruno","status":"signed","method":"device_key","level_used":3,"is_me":true}],
               "verify_url":"https://app.test/verify/7K3M","roles":["signer"],"send_error":null,"brand_new":1}""",
        )
        assertEquals(EnvelopeSignMethod.DeviceKey, e.me?.method)
        assertEquals("@ana", e.senderName)
        val page = Wire.json.decodeFromString(
            EnvelopeListPage.serializer(),
            """{"items":[{"id":"e1","title":"Contrato","status":"in_progress","my_status":"pending","pages":3},{"id":"e2","status":"archived_v2","my_status":"signed"}],"next_cursor":"c2"}""",
        )
        assertTrue(page.items[0].waitsForMe)
        assertEquals(EnvelopeStatus.Unknown, page.items[1].status)
        assertFalse(page.items[1].waitsForMe)
        assertEquals("c2", page.nextCursor)
    }

    @Test
    fun envelopeChangedComesFromTheRealtimeFeed() {
        assertEquals(RealtimeEvent.EnvelopeChanged("e1"), Realtime.parse("""{"type":"envelope_changed","envelope_id":"e1"}"""))
        assertNull(Realtime.parse("""{"type":"envelope_changed"}"""))
    }

    // ------------------------------------------------------------------ canonical JSON

    private val vectors: JsonObject by lazy {
        val text = javaClass.getResourceAsStream("/envelope-canon-vectors.json")!!.readBytes().toString(Charsets.UTF_8)
        Wire.json.parseToJsonElement(text).jsonObject
    }

    /**
     * The vectors file is a byte-for-byte copy of the server's
     * `server/internal/modules/envelopes/testdata/canon-vectors.json`
     * (generated by its `canon_test.go`): the device payload and the layout
     * are among them, so the bytes this SDK signs are the bytes the server
     * re-serializes. In the monorepo the test also reads the server's file,
     * so the copy cannot silently fall behind; the public mirror of the SDK
     * has no server folder and skips that part.
     */
    @Test
    fun canonicalJsonMatchesTheSharedVectors() {
        val list = vectors["vectors"]!!.jsonArray
        val names = list.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("device_payload", "layout", "control_chars", "text_not_normalized_nfd")), names.toString())
        val b64 = Base64.getUrlEncoder().withoutPadding()
        for (v in list) {
            val o = v.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val input = o["input"]!!
            assertEquals(o["canonical"]!!.jsonPrimitive.content, EnvelopeCanon.encode(input), name)
            assertEquals(o["sha256"]!!.jsonPrimitive.content, EnvelopeCanon.sha256(input), name)
            o["canonical_base64url"]?.let { assertEquals(it.jsonPrimitive.content, b64.encodeToString(EnvelopeCanon.bytes(input)), name) }
        }
        for (v in vectors["invalid"]?.jsonArray.orEmpty()) {
            assertFailsWith<IllegalArgumentException>(v.jsonObject["name"]!!.jsonPrimitive.content) { EnvelopeCanon.encode(v.jsonObject["input"]!!) }
        }
        // The server's file, when this runs inside the monorepo: the copy here must be the same bytes.
        val server = generateSequence(java.io.File("").absoluteFile) { it.parentFile }
            .map { java.io.File(it, "server/internal/modules/envelopes/testdata/canon-vectors.json") }
            .firstOrNull { it.isFile }
        if (server != null) {
            val mine = javaClass.getResourceAsStream("/envelope-canon-vectors.json")!!.readBytes()
            assertTrue(mine.contentEquals(server.readBytes()), "envelope-canon-vectors.json differs from $server: copy the server's file here")
        }
    }

    /** Cases the server's vectors do not carry (yet): supplementary-plane key order, refusals. */
    @Test
    fun canonicalJsonExtraVectors() {
        // Keys by UTF-8 bytes; U+FF21 before U+1F600 (UTF-16 order would flip those two).
        val keys = Wire.json.parseToJsonElement("""{"b":1,"a":2,"B":3,"é":4,"z":5,"😀":6,"Ａ":7}""")
        assertEquals("""{"B":3,"a":2,"b":1,"z":5,"é":4,"Ａ":7,"😀":6}""", EnvelopeCanon.encode(keys))
        assertEquals("sha256:1433b91a5dae928322e46f0c24f6446cc49a22f6a2f3f8e312a1d053461fd6ed", EnvelopeCanon.sha256(keys))
        // Numbers: integers only.
        assertFailsWith<IllegalArgumentException> { EnvelopeCanon.encode(Wire.json.parseToJsonElement("""{"a":1.5}""")) }
        assertFailsWith<IllegalArgumentException> { EnvelopeCanon.encode(Wire.json.parseToJsonElement("""{"a":1e21}""")) }
        // A lone surrogate is refused (high without low, and low alone).
        assertFailsWith<IllegalArgumentException> { EnvelopeCanon.encode(JsonObject(mapOf("s" to JsonPrimitive("\uD800x")))) }
        assertFailsWith<IllegalArgumentException> { EnvelopeCanon.encode(JsonObject(mapOf("s" to JsonPrimitive("x\uD800")))) }
    }

    @Test
    fun canonicalJsonRules() {
        // Keys by UTF-8 bytes: U+FF21 before U+1F600 (UTF-16 order would flip them).
        assertEquals("""{"Ａ":1,"😀":2}""", EnvelopeCanon.encode(JsonObject(mapOf("😀" to JsonPrimitive(2), "Ａ" to JsonPrimitive(1)))))
        assertEquals("\"<>&/ \"", EnvelopeCanon.encode(JsonPrimitive("<>&/ ")))
        assertEquals("\"\\u001f\\u0000\\n\"", EnvelopeCanon.encode(JsonPrimitive("\u001f\u0000\n")))
        assertEquals("[1,-2,true,null]", EnvelopeCanon.encode(Wire.json.parseToJsonElement("[1,-2,true,null]")))
        assertFailsWith<IllegalArgumentException> { EnvelopeCanon.encode(JsonPrimitive(0.5)) }
        assertFailsWith<IllegalArgumentException> { EnvelopeCanon.encode(JsonPrimitive("a\uDC00")) }
    }

    @Test
    fun devicePayloadIsTheContractObject() {
        val v = Wire.json.decodeFromString(EnvelopeSigningView.serializer(), view)
        val values = v.textValues(mapOf("f2" to "123.456.789-00", "f3" to "Acme <&> \"Ltda\""))
        val p = v.devicePayload("dev1", "kid1", signature, values, Instant.parse("2026-11-13T17:03:11.123456Z"))!!
        val text = p.canonicalBytes().toString(Charsets.UTF_8)
        assertEquals(
            """{"alg":"ES256","auth_method":"device_key","challenge":"q8Zr","consent_version":"envelope-2026-11-13","device_id":"dev1",""" +
                """"document_sha256":"$sha","envelope_id":"env1","fields":[{"id":"f2","value":"123.456.789-00"},{"id":"f3","value":"Acme <&> \"Ltda\""}],""" +
                """"initials_sha256":"sha256:41ab","key_id":"kid1","layout_sha256":"$layout","signature_sha256":"sha256:5be1",""" +
                """"signed_at":"2026-11-13T17:03:11.123Z","signer_id":"s1","type":"notificme.envelope_signature","v":1}""",
            text,
        )
        assertEquals("2026-11-13T17:03:00.000Z", EnvelopeDevicePayload.timestamp(Instant.parse("2026-11-13T17:03:00Z")))
        assertNull(v.copy(challenge = null).devicePayload("dev1", "kid1", signature, values))
    }

    @Test
    fun deviceProofVerifiesWithTheKey() {
        // What the server does: SHA256withECDSA over the exact payload bytes, P-256, DER signature.
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val v = Wire.json.decodeFromString(EnvelopeSigningView.serializer(), view)
        val bytes = v.devicePayload("dev1", DeviceSigningKey.keyIdOf(kp.public.encoded), signature, v.textValues(emptyMap()))!!.canonicalBytes()
        val der = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(bytes); sign() }
        val proof = EnvelopeDeviceProof.of(bytes, der)
        assertFalse(proof.payload.contains('=') || proof.payload.contains('+') || proof.payload.contains('/'))
        val back = Base64.getUrlDecoder().decode(proof.payload)
        assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(kp.public); update(back); verify(Base64.getUrlDecoder().decode(proof.signature)) })
        assertEquals(43, DeviceSigningKey.keyIdOf(kp.public.encoded).length)
    }

    // ------------------------------------------------------------------ document links

    @Test
    fun documentLinksFollowTheSharedLists() {
        val text = "Contrato: https://drive.google.com/file/d/1/view, e o PDF https://acme.com/Docs/Contrato.PDF?dl=1. " +
            "Agenda https://cal.com/ana; painel https://acme.com/painel. Repetido https://drive.google.com/file/d/1/view! " +
            "Assine https://app.docusign.com/x e https://na3.docusign.net/y e https://tenant.sharepoint.com/z#doc " +
            "www https://www.dropbox.com/s/abc ftp://files.test/a.pdf"
        assertEquals(
            listOf(
                "https://drive.google.com/file/d/1/view", "https://acme.com/Docs/Contrato.PDF?dl=1", "https://cal.com/ana",
                "https://acme.com/painel", "https://app.docusign.com/x", "https://na3.docusign.net/y", "https://tenant.sharepoint.com/z#doc",
                "https://www.dropbox.com/s/abc",
            ),
            DocumentLinks.find(text),
        )
        assertEquals(
            listOf(
                "https://drive.google.com/file/d/1/view", "https://acme.com/Docs/Contrato.PDF?dl=1", "https://app.docusign.com/x",
                "https://na3.docusign.net/y", "https://tenant.sharepoint.com/z#doc", "https://www.dropbox.com/s/abc",
            ),
            DocumentLinks.findDocuments(text),
        )
        assertTrue(DocumentLinks.isDocument("https://user:pw@files.example.com:8443/a/b.docx#p=2"))
        assertFalse(DocumentLinks.isDocument("https://notdocusign.com/x"))
        assertFalse(DocumentLinks.isDocument("https://acme.com/pdf"))
        assertFalse(DocumentLinks.isDocument("https://acme.com/a.pdf.html"))
        assertTrue(DocumentLinks.findDocuments("Veja (https://acme.com/a.pdf) agora").single().endsWith("a.pdf"))
        assertTrue(DocumentLinks.find("sem links aqui").isEmpty())
    }

    // ------------------------------------------------------------------ calls

    @Test
    fun toSignListsWhatWaitsForThePerson() = runBlocking<Unit> {
        val c = client { json("""{"items":[{"id":"e1","title":"Contrato","status":"in_progress","my_status":"opened"}],"next_cursor":null}""") }
        val page = c.envelopes.toSign(limit = 50)
        val req = requests.single()
        assertEquals("/v0/me/envelopes", req.url.encodedPath)
        assertEquals("signer", req.url.parameters["role"])
        assertEquals("action", req.url.parameters["status"])
        assertEquals("50", req.url.parameters["limit"])
        assertNull(req.url.parameters["cursor"])
        assertEquals("Bearer nm_ses_test", req.headers[HttpHeaders.Authorization])
        assertTrue(page.items.single().waitsForMe)
    }

    @Test
    fun openSignAndDecline() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/me/envelopes/env1/open" -> json(view)
                "/v0/me/envelopes/env1/sign" -> json("""{"status":"signed","signer":{"id":"s1","status":"signed","is_me":true},"envelope_status":"completing","record_sha256":"sha256:aa"}""")
                "/v0/me/envelopes/env1/decline" -> json("""{"envelope_status":"declined"}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val v = c.envelopes.open("env1")
        assertEquals("POST", requests[0].method.value)
        val values = v.textValues(mapOf("f2" to "123"))
        val proof = EnvelopeDeviceProof("cGF5", "c2ln")
        val r = c.envelopes.sign(v, values, EnvelopeSignMethod.DeviceKey, signature, deviceProof = proof, idempotencyKey = "k1")
        assertEquals(EnvelopeStatus.Completing, r.envelopeStatus)
        val body = requests[1].bodyJson()
        assertEquals(sha, body["document_sha256"]!!.jsonPrimitive.content)
        assertEquals(layout, body["layout_sha256"]!!.jsonPrimitive.content)
        assertEquals("device_key", body["method"]!!.jsonPrimitive.content)
        assertEquals("true", body["consent"]!!.jsonPrimitive.content)
        assertEquals("sha256:5be1", body["e_signature"]!!.jsonObject["signature_sha256"]!!.jsonPrimitive.content)
        assertEquals("cGF5", body["device_proof"]!!.jsonObject["payload"]!!.jsonPrimitive.content)
        assertEquals("", body["values"]!!.jsonObject["f3"]!!.jsonPrimitive.content)
        assertEquals("k1", body["idempotency_key"]!!.jsonPrimitive.content)
        // Nothing of the other methods goes.
        assertNull(body["totp_code"])
        assertNull(body["email_code"])
        assertNull(body["adopt"])

        assertEquals(EnvelopeStatus.Declined, c.envelopes.decline("env1", "  Valor errado ").envelopeStatus)
        assertEquals("Valor errado", requests[2].bodyJson()["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun errorsKeepTheirCodeAndDetails() = runBlocking<Unit> {
        val c = client {
            json(
                """{"error":{"code":"document_changed","message":"x","details":{"document_sha256":"sha256:bb","layout_sha256":"sha256:cc"}},"request_id":"r1"}""",
                HttpStatusCode.Conflict,
            )
        }
        val v = Wire.json.decodeFromString(EnvelopeSigningView.serializer(), view)
        val e = assertFailsWith<NotificMeApiException> { c.envelopes.sign(v, emptyMap(), EnvelopeSignMethod.DeviceKey, signature) }
        assertEquals(ErrorCodes.DOCUMENT_CHANGED, e.code)
        assertEquals("sha256:bb", e.detail("document_sha256"))
        assertEquals(409, e.status)
    }

    @Test
    fun filesAndDownload() = runBlocking<Unit> {
        val pdf = "%PDF-1.7 fake".toByteArray()
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/me/envelopes/env1/files/signed" -> json(
                    """{"kind":"signed","filename":"Contrato - assinado.pdf","content_type":"application/pdf","bytes":${pdf.size},"sha256":"x","url":"/v0/envelope-files/f9?e=1&p=s1&s=sig","url_expires_at":"2026-11-13T17:15:00Z"}""",
                )
                "/v0/envelope-files/f9" -> respond(pdf, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/pdf"))
                else -> json("""{"error":{"code":"vault_expired"}}""", HttpStatusCode.Gone)
            }
        }
        val link = c.envelopes.file("env1", EnvelopeFileKind.Signed)
        assertEquals("Contrato - assinado.pdf", link.filename)
        val out = ByteArrayOutputStream()
        val hash = c.envelopes.download(link.url, out)
        assertEquals(pdf.toList(), out.toByteArray().toList())
        assertEquals(ESignature.sha256Of(pdf), hash)
        val dl = requests[1]
        assertEquals("https://api.test/v0/envelope-files/f9?e=1&p=s1&s=sig", dl.url.toString())
        // A signed URL: the token never goes with it.
        assertNull(dl.headers[HttpHeaders.Authorization])

        val gone = assertFailsWith<NotificMeApiException> { c.envelopes.download("/v0/envelope-files/old", ByteArrayOutputStream()) }
        assertEquals(ErrorCodes.VAULT_EXPIRED, gone.code)
        val big = assertFailsWith<NotificMeException> { c.envelopes.download(link.url, ByteArrayOutputStream(), maxBytes = 4) }
        assertEquals("content_too_large", big.code)
    }

    @Test
    fun registersTheDeviceKey() = runBlocking<Unit> {
        val spki = ByteArray(91) { it.toByte() }
        val kid = DeviceSigningKey.keyIdOf(spki)
        val c = client { json("""{"device_id":"dev1","alg":"ES256","key_id":"$kid"}""") }
        val k = c.envelopes.registerSigningKey(spki)
        val req = requests.single()
        assertEquals("PUT", req.method.value)
        assertEquals("/v0/devices/current/signing-key", req.url.encodedPath)
        val body = req.bodyJson()
        assertEquals("ES256", body["alg"]!!.jsonPrimitive.content)
        assertEquals(Base64.getEncoder().encodeToString(spki), body["public_key"]!!.jsonPrimitive.content)
        assertEquals(kid, k.keyId)
        val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(spki))
        assertEquals(expected, kid)
    }

    @Test
    fun claimReturnsTheEnvelope() = runBlocking<Unit> {
        val c = client { json("""{"envelope_id":"env7"}""") }
        assertEquals("env7", c.envelopes.claim(" nm_esg_abc "))
        assertEquals("nm_esg_abc", requests.single().bodyJson()["token"]!!.jsonPrimitive.content)
    }
}
