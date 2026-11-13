package me.notific.client

import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Envelopes: documents sent for signature (docs/28,
 * server/docs/api-envelopes.md). This is the signer's side: the list of
 * envelopes waiting for the person, opening one, signing it (the official
 * app signs with the device key; see [EnvelopeDevicePayload]), declining it
 * and downloading the signed PDF and its receipt. Creating envelopes is done
 * on the web in v1.
 *
 * A company-issued delegated session never signs (`step_up_required`).
 */
public class Envelopes internal constructor(private val client: NotificClient) {

    /**
     * Envelopes of the person. [role] [EnvelopeRole.Signer] lists what was
     * sent to them to sign; [status] is a comma-separated [EnvelopeStatus]
     * list, or [STATUS_ACTION] (with the signer role): still waiting for the
     * person's signature.
     */
    public suspend fun list(
        role: EnvelopeRole = EnvelopeRole.Signer,
        status: String? = null,
        from: String? = null,
        cursor: String? = null,
        limit: Int? = null,
    ): EnvelopeListPage = client.call(
        HttpMethod.Get, "/v0/me/envelopes", EnvelopeListPage.serializer(),
        query = mapOf("role" to role.wire, "status" to status, "from" to from?.removePrefix("@"), "cursor" to cursor, "limit" to limit),
    )

    /** "To sign": envelopes still waiting for the person's signature (`role=signer&status=action`). */
    public suspend fun toSign(cursor: String? = null, limit: Int? = null): EnvelopeListPage =
        list(EnvelopeRole.Signer, STATUS_ACTION, cursor = cursor, limit = limit)

    /** One envelope (the person sent it or signs it). `envelope_not_found` otherwise. */
    public suspend fun get(id: String): Envelope =
        client.call(HttpMethod.Get, "/v0/me/envelopes/${id.path()}", Envelope.serializer())

    /**
     * Opens an envelope to sign: what the person signs (document, layout,
     * fields), the methods that work here and, with [EnvelopeSignMethod.DeviceKey],
     * a fresh single-use challenge (5 minutes). The first open records the
     * `opened` step. A signer who already signed or declined gets the view
     * without methods. Errors: `envelope_not_open`, `envelope_expired`.
     */
    public suspend fun open(id: String): EnvelopeSigningView =
        client.call(HttpMethod.Post, "/v0/me/envelopes/${id.path()}/open", EnvelopeSigningView.serializer())

    /** Sends the 6-digit code ([EnvelopeSignMethod.EmailCode]) to the account's e-mail. 10 minutes, 5 per hour. */
    public suspend fun sendCode(id: String): EnvelopeCodeResponse =
        client.call(HttpMethod.Post, "/v0/me/envelopes/${id.path()}/code", EnvelopeCodeResponse.serializer())

    /**
     * Signs what [view] showed. [values] are the person's `text` fields
     * (field id → text, exactly as typed; every text field of the signer
     * goes, an empty optional one as `""`: see [EnvelopeSigningView.textValues]).
     * [signature] is the adopted signature shown ([Account.signature]).
     *
     * Give exactly the proof of [method]: [deviceProof] (the app, see
     * [EnvelopeDevicePayload]), [totpCode] or [emailCode]. Call it only after
     * the person accepted the consent text: it sends `consent: true`.
     *
     * Errors worth handling: `document_changed` (open again), `challenge_invalid`
     * (open again for a new challenge), `signature_changed`,
     * `signature_not_adopted`, `device_key_not_registered`,
     * `device_signature_invalid`, `totp_invalid`, `invalid_code`,
     * `envelope_already_signed`, `envelope_not_open`, `envelope_expired`.
     * Safe to retry with the same [idempotencyKey].
     */
    public suspend fun sign(
        view: EnvelopeSigningView,
        values: Map<String, String>,
        method: EnvelopeSignMethod,
        signature: ESignature?,
        deviceProof: EnvelopeDeviceProof? = null,
        totpCode: String? = null,
        emailCode: String? = null,
        idempotencyKey: String = UUID.randomUUID().toString(),
        clientAt: Instant = Instant.now(),
    ): SignEnvelopeResponse {
        val body = SignEnvelopeRequest(
            documentSha256 = view.envelope.document.sha256,
            layoutSha256 = view.envelope.layoutSha256,
            values = values,
            method = method,
            eSignature = signature?.let { ESignatureRef(it.signatureSha256) },
            consent = true,
            deviceProof = deviceProof,
            totpCode = totpCode?.trim(),
            emailCode = emailCode?.trim(),
            clientAt = clientAt.toString(),
            idempotencyKey = idempotencyKey,
        )
        return client.call(
            HttpMethod.Post, "/v0/me/envelopes/${view.envelope.id.path()}/sign", SignEnvelopeResponse.serializer(),
            body = Wire.json.encodeToJsonElement(SignEnvelopeRequest.serializer(), body),
            retry = true,
        )
    }

    /**
     * Declines with a reason (1 to 500 characters). It ends the envelope for
     * everyone: the sender and the other signers are told.
     */
    public suspend fun decline(id: String, reason: String): DeclineEnvelopeResponse =
        client.call(
            HttpMethod.Post, "/v0/me/envelopes/${id.path()}/decline", DeclineEnvelopeResponse.serializer(),
            body = buildJsonObject { put("reason", reason.trim()) },
        )

    /**
     * Claims an e-mail signing link (`nm_esg_…`, from the link fragment) for
     * the signed-in account, when one of its verified e-mails is the one the
     * invitation went to. Then sign as the account. Errors:
     * `envelope_claim_email_mismatch`, `invalid_link`, `envelope_not_open`.
     */
    public suspend fun claim(token: String): String =
        client.call(
            HttpMethod.Post, "/v0/me/envelopes/claim", EnvelopeClaimResponse.serializer(),
            body = buildJsonObject { put("token", token.trim()) },
        ).envelopeId

    /**
     * A short-lived URL (about 10 minutes) of a file of the envelope:
     * [EnvelopeFileKind.Document] while signing, [EnvelopeFileKind.Signed] and
     * [EnvelopeFileKind.Certificate] once it is completed. Errors:
     * `file_not_ready` (the final PDF is being generated), `envelope_not_open`,
     * `vault_expired`.
     */
    public suspend fun file(id: String, kind: EnvelopeFileKind): EnvelopeFileLink =
        client.call(HttpMethod.Get, "/v0/me/envelopes/${id.path()}/files/${kind.wire}", EnvelopeFileLink.serializer())

    /**
     * Downloads a file URL ([EnvelopeFileLink.url], [EnvelopeSigningDocument.url])
     * into [sink], streaming, and returns its `sha256:<hex>` (compare it with
     * the hash the server gave before showing or saving the file). The URL is
     * signed: no token goes with it. Stops with `content_too_large` past
     * [maxBytes].
     */
    public suspend fun download(url: String, sink: OutputStream, maxBytes: Long = MAX_DOWNLOAD_BYTES, timeoutMillis: Long = 300_000): String {
        val full = client.urlOf(url) ?: throw NotificMeException("content_unavailable", "The file has no URL")
        try {
            return withTimeout(timeoutMillis) {
                client.http.prepareRequest(full) {
                    method = HttpMethod.Get
                    header(HttpHeaders.UserAgent, client.config.userAgent)
                }.execute { r ->
                    val status = r.status.value
                    if (status !in 200..299) throw apiError(status, r.bodyAsText(), r.headers[HttpHeaders.RetryAfter], r.headers["X-Request-Id"])
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    withContext(Dispatchers.IO) {
                        val input = r.bodyAsChannel().toInputStream()
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) throw NotificMeException("content_too_large", "The file has more than $maxBytes bytes")
                            digest.update(buffer, 0, n)
                            sink.write(buffer, 0, n)
                        }
                        sink.flush()
                    }
                    "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw NotificMeException("timeout", "Download timed out", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotificMeException) {
            throw e
        } catch (e: Exception) {
            throw NotificMeException("network_error", e.message ?: e.toString(), e)
        }
    }

    /**
     * Registers this device's signing key (`PUT /v0/devices/current/signing-key`):
     * [publicKeySpki] is the P-256 public key as SubjectPublicKeyInfo DER
     * (Android's `KeyPair.public.encoded`). Replaces the previous key; open
     * challenges of this device stop working. Needs a session tied to an
     * Android device.
     */
    public suspend fun registerSigningKey(publicKeySpki: ByteArray): DeviceSigningKey =
        client.call(
            HttpMethod.Put, "/v0/devices/current/signing-key", DeviceSigningKey.serializer(),
            body = buildJsonObject {
                put("alg", "ES256")
                put("public_key", Base64.getEncoder().encodeToString(publicKeySpki))
            },
        )

    public companion object {
        /** With [EnvelopeRole.Signer]: still waiting for the person's signature. */
        public const val STATUS_ACTION: String = "action"

        /** Biggest file [download] takes by default (50 MiB plan limit plus the signature page). */
        public const val MAX_DOWNLOAD_BYTES: Long = 80L shl 20

        /** Most characters of a `text` field and of a decline reason. */
        public const val TEXT_MAX: Int = 200
        public const val REASON_MAX: Int = 500
    }
}

private fun String.path(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

// ---------------------------------------------------------------------------
// Enums. Unknown values from newer servers arrive as `Unknown` (properties
// with a default; Wire.json coerces).
// ---------------------------------------------------------------------------

/** State of an envelope. */
@Serializable
public enum class EnvelopeStatus(public val wire: String) {
    @SerialName("draft") Draft("draft"),
    @SerialName("in_progress") InProgress("in_progress"),

    /** Everyone signed; the final PDF is being generated. */
    @SerialName("completing") Completing("completing"),
    @SerialName("completed") Completed("completed"),
    @SerialName("declined") Declined("declined"),
    @SerialName("voided") Voided("voided"),
    @SerialName("expired") Expired("expired"),
    @SerialName("unknown") Unknown("unknown"),
    ;

    /** Over: nothing more happens (signed by all, declined, voided or expired). */
    public val closed: Boolean get() = this == Completed || this == Declined || this == Voided || this == Expired
}

@Serializable
public enum class EnvelopeSignerStatus {
    @SerialName("pending") Pending,
    @SerialName("opened") Opened,
    @SerialName("signed") Signed,
    @SerialName("declined") Declined,
    @SerialName("unknown") Unknown,
    ;

    /** Still has to sign (or decline). */
    public val waiting: Boolean get() = this == Pending || this == Opened
}

/**
 * `signature` and `initials` (the signer's adopted images), `date` and
 * `name` (filled by the server), `text` (typed by the signer).
 */
@Serializable
public enum class EnvelopeFieldType {
    @SerialName("signature") Signature,
    @SerialName("initials") Initials,
    @SerialName("date") Date,
    @SerialName("name") Name,
    @SerialName("text") Text,
    @SerialName("unknown") Unknown,
}

/** How the signer proves it is them. */
@Serializable
public enum class EnvelopeSignMethod(public val wire: String) {
    /** The app's device key, unlocked by biometrics (level 3). */
    @SerialName("device_key") DeviceKey("device_key"),

    /** Authenticator code on the web (level 3). */
    @SerialName("totp") Totp("totp"),

    /** 6-digit code sent by e-mail (level 1, simple signature). */
    @SerialName("email_code") EmailCode("email_code"),
    @SerialName("unknown") Unknown("unknown"),
    ;

    public companion object {
        public fun of(wire: String?): EnvelopeSignMethod = entries.firstOrNull { it.wire == wire } ?: Unknown
    }
}

public enum class EnvelopeRole(public val wire: String) { Sender("sender"), Signer("signer") }

public enum class EnvelopeFileKind(public val wire: String) {
    /** The uploaded file (sender only). */
    Original("original"),

    /** The PDF everyone signs. */
    Document("document"),

    /** The final PDF: stamps, signature page and seal. */
    Signed("signed"),

    /** The signed JSON receipt. */
    Certificate("certificate"),
}

// ---------------------------------------------------------------------------
// Models
// ---------------------------------------------------------------------------

/** A page as displayed (rotation applied), in points (1/72 inch). */
@Serializable
public data class EnvelopePage(
    val number: Int = 1,
    val width: Double = 612.0,
    val height: Double = 792.0,
    val rotate: Int = 0,
) {
    /** Width over height of the displayed page. */
    public val aspect: Float get() = if (height > 0) (width / height).toFloat() else 0.773f
}

@Serializable
public data class EnvelopeDocumentError(val code: String = "", val message: String = "")

@Serializable
public data class EnvelopeDocument(
    /** `none`, `processing`, `ready` or `failed`. */
    val status: String = "none",
    /** `pdf`, `docx` or `markdown`. */
    val format: String? = null,
    val filename: String? = null,
    @SerialName("original_bytes") val originalBytes: Long? = null,
    @SerialName("original_sha256") val originalSha256: String? = null,
    /** `sha256:<hex>` of the PDF everyone signs. */
    val sha256: String? = null,
    val bytes: Long? = null,
    val pages: List<EnvelopePage> = emptyList(),
    @SerialName("has_forms") val hasForms: Boolean = false,
    @SerialName("has_javascript") val hasJavascript: Boolean = false,
    val error: EnvelopeDocumentError? = null,
    @SerialName("uploaded_at") val uploadedAt: String? = null,
)

@Serializable
public data class EnvelopeSigner(
    val id: String,
    val position: Int = 1,
    @SerialName("routing_order") val routingOrder: Int = 1,
    /** The account, for a signer by @handle (or an e-mail signer who claimed the link). */
    val person: Person? = null,
    /** The e-mail the invitation went to; null for a signer by @handle. */
    val email: String? = null,
    val name: String = "",
    val status: EnvelopeSignerStatus = EnvelopeSignerStatus.Unknown,
    @SerialName("opened_at") val openedAt: String? = null,
    @SerialName("signed_at") val signedAt: String? = null,
    @SerialName("declined_at") val declinedAt: String? = null,
    @SerialName("decline_reason") val declineReason: String? = null,
    /** `device_key`, `totp` or `email_code`, once signed. */
    @SerialName("method") val methodWire: String? = null,
    @SerialName("level_used") val levelUsed: Int? = null,
    /** This signer is the viewer. */
    @SerialName("is_me") val isMe: Boolean = false,
) {
    val method: EnvelopeSignMethod? get() = methodWire?.let(EnvelopeSignMethod::of)
}

/**
 * A field on a page. [x], [y], [width] and [height] are fractions (0..1) of
 * the displayed page, origin at the top-left corner, [y] growing down.
 */
@Serializable
public data class EnvelopeField(
    val id: String,
    @SerialName("signer_id") val signerId: String = "",
    val type: EnvelopeFieldType = EnvelopeFieldType.Unknown,
    val page: Int = 1,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
    val required: Boolean = true,
    /** `text` only: the hint shown to the signer ("Company"). */
    val label: String = "",
    /** Created by "initials on every page" when sending. */
    val auto: Boolean = false,
    /** After that signer signed: the text, name or date stamped. */
    val value: String? = null,
)

@Serializable
public data class EnvelopeWorkspace(
    val id: String = "",
    val handle: String = "",
    val name: String = "",
    val kind: String = "",
)

@Serializable
public data class EnvelopeCounts(val signers: Int = 0, val opened: Int = 0, val signed: Int = 0)

@Serializable
public data class EnvelopeService(val id: String = "", val slug: String = "", val name: String = "")

/** `GET /v0/me/envelopes/{id}`: the envelope as its sender or one of its signers sees it. */
@Serializable
public data class Envelope(
    val id: String,
    val workspace: EnvelopeWorkspace = EnvelopeWorkspace(),
    /** The person who sent; null when a service sent it by the API ([senderService]). */
    val sender: Person? = null,
    @SerialName("sender_service") val senderService: EnvelopeService? = null,
    val title: String = "",
    /** Note to the signers. Links in it are not part of what is signed. */
    val message: String = "",
    val status: EnvelopeStatus = EnvelopeStatus.Unknown,
    val level: Int = 1,
    val locale: String = "en",
    val timezone: String = "UTC",
    @SerialName("expires_in_days") val expiresInDays: Int = 30,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("initials_on_every_page") val initialsOnEveryPage: Boolean = false,
    @SerialName("initials_position") val initialsPosition: String = "bottom_right",
    val document: EnvelopeDocument = EnvelopeDocument(),
    val signers: List<EnvelopeSigner> = emptyList(),
    val fields: List<EnvelopeField> = emptyList(),
    val counts: EnvelopeCounts = EnvelopeCounts(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("declined_at") val declinedAt: String? = null,
    @SerialName("voided_at") val voidedAt: String? = null,
    @SerialName("void_reason") val voidReason: String? = null,
    @SerialName("expired_at") val expiredAt: String? = null,
    @SerialName("layout_sha256") val layoutSha256: String? = null,
    @SerialName("verify_code") val verifyCode: String? = null,
    @SerialName("verify_url") val verifyUrl: String? = null,
    @SerialName("signed_sha256") val signedSha256: String? = null,
    @SerialName("vault_until") val vaultUntil: String? = null,
    /** `sender` and/or `signer`. */
    val roles: List<String> = emptyList(),
    @SerialName("sent_id") val sentId: String? = null,
) {
    /** The viewer as signer, when they sign it. */
    val me: EnvelopeSigner? get() = signers.firstOrNull { it.isMe }

    /** Who sent it: the person, or the service. */
    val senderName: String get() = sender?.displayName?.ifBlank { null } ?: sender?.handle?.ifBlank { null }?.let { "@$it" } ?: senderService?.name.orEmpty()
}

/** An item of `GET /v0/me/envelopes`. */
@Serializable
public data class EnvelopeListItem(
    val id: String,
    val workspace: EnvelopeWorkspace = EnvelopeWorkspace(),
    val sender: Person? = null,
    val title: String = "",
    val status: EnvelopeStatus = EnvelopeStatus.Unknown,
    val filename: String? = null,
    val pages: Int = 0,
    val counts: EnvelopeCounts = EnvelopeCounts(),
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val roles: List<String> = emptyList(),
    /** The viewer's own status when they sign it. */
    @SerialName("my_status") val myStatus: EnvelopeSignerStatus? = null,
) {
    /** Still waiting for the viewer's signature. */
    val waitsForMe: Boolean get() = status == EnvelopeStatus.InProgress && myStatus?.waiting == true
}

@Serializable
public data class EnvelopeListPage(
    val items: List<EnvelopeListItem> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** Who sent the envelope, as the signer sees it. */
@Serializable
public data class EnvelopeSigningSender(
    val name: String = "",
    val handle: String? = null,
    @SerialName("workspace_name") val workspaceName: String = "",
    @SerialName("workspace_logo_url") val workspaceLogoUrl: String = "",
)

/** The PDF the signer signs, with a short-lived URL (about 15 minutes). */
@Serializable
public data class EnvelopeSigningDocument(
    val filename: String = "",
    /** `sha256:<hex>`: what everyone signs. Check the downloaded file against it before showing it. */
    val sha256: String = "",
    val bytes: Long = 0,
    val pages: List<EnvelopePage> = emptyList(),
    val url: String = "",
    @SerialName("url_expires_at") val urlExpiresAt: String = "",
)

/** The envelope as a signer sees it while signing. */
@Serializable
public data class EnvelopeSigningEnvelope(
    val id: String,
    val title: String = "",
    val message: String = "",
    val status: EnvelopeStatus = EnvelopeStatus.Unknown,
    val level: Int = 1,
    val locale: String = "en",
    val timezone: String = "UTC",
    @SerialName("expires_at") val expiresAt: String? = null,
    val sender: EnvelopeSigningSender = EnvelopeSigningSender(),
    val document: EnvelopeSigningDocument = EnvelopeSigningDocument(),
    @SerialName("layout_sha256") val layoutSha256: String = "",
)

/** A single-use challenge for the device key: goes as is in the signed payload. */
@Serializable
public data class EnvelopeChallenge(val value: String = "", @SerialName("expires_at") val expiresAt: String = "")

/** A signer's name and status, as every signer sees it (no e-mails). */
@Serializable
public data class EnvelopeSignerBrief(
    val id: String,
    val name: String = "",
    val status: EnvelopeSignerStatus = EnvelopeSignerStatus.Unknown,
)

/** `POST /v0/me/envelopes/{id}/open`: what the signer signs, and how they can sign it here. */
@Serializable
public data class EnvelopeSigningView(
    val envelope: EnvelopeSigningEnvelope,
    /** The viewer as signer. */
    val signer: EnvelopeSigner,
    val signers: List<EnvelopeSignerBrief> = emptyList(),
    /** Every field; the viewer's are [myFields]. */
    val fields: List<EnvelopeField> = emptyList(),
    /** Methods that work here and now, in the order to offer (see [signMethods]). */
    @SerialName("methods") val methodsWire: List<String> = emptyList(),
    /**
     * Why there is no method: `strong_auth_required`, `device_key_not_registered`
     * (register the device key and open again) or `delegated_session`.
     */
    val blocked: String? = null,
    /** The account's adopted signature (null = adopt one first). */
    val signature: ESignature? = null,
    val account: Boolean = true,
    /** Only with [EnvelopeSignMethod.DeviceKey] in the methods. */
    val challenge: EnvelopeChallenge? = null,
    @SerialName("email_hint") val emailHint: String? = null,
    @SerialName("consent_version") val consentVersion: String = "",
) {
    /** The known methods, in order. */
    val signMethods: List<EnvelopeSignMethod>
        get() = methodsWire.map(EnvelopeSignMethod::of).filter { it != EnvelopeSignMethod.Unknown }

    /** The viewer's fields, in reading order (page, top, left). */
    val myFields: List<EnvelopeField>
        get() = fields.filter { it.signerId == signer.id }.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))

    /** The viewer's `text` fields with what goes for each: the typed text, `""` when left empty. */
    public fun textValues(typed: Map<String, String>): Map<String, String> =
        fields.filter { it.signerId == signer.id && it.type == EnvelopeFieldType.Text }.associate { it.id to (typed[it.id] ?: "") }

    /** The viewer can sign now (the envelope is open and they still have to sign). */
    val canSign: Boolean get() = envelope.status == EnvelopeStatus.InProgress && signer.status.waiting && blocked == null && signMethods.isNotEmpty()

    /**
     * The payload the device key signs ([EnvelopeSignMethod.DeviceKey]): what
     * this view showed, with [signature] (the adopted one shown), the text
     * [values] (see [textValues]), the session's [deviceId] and the [keyId]
     * of [Envelopes.registerSigningKey]. Null without a challenge.
     */
    public fun devicePayload(
        deviceId: String,
        keyId: String,
        signature: ESignature,
        values: Map<String, String>,
        signedAt: Instant = Instant.now(),
    ): EnvelopeDevicePayload? {
        val c = challenge ?: return null
        val mine = fields.filter { it.signerId == signer.id && it.type == EnvelopeFieldType.Text }
        return EnvelopeDevicePayload(
            challenge = c.value,
            consentVersion = consentVersion,
            deviceId = deviceId,
            documentSha256 = envelope.document.sha256,
            envelopeId = envelope.id,
            fields = mine.map { EnvelopeDevicePayload.FieldValue(it.id, values[it.id] ?: "") },
            initialsSha256 = signature.initialsSha256,
            keyId = keyId,
            layoutSha256 = envelope.layoutSha256,
            signatureSha256 = signature.signatureSha256,
            signedAt = EnvelopeDevicePayload.timestamp(signedAt),
            signerId = signer.id,
        )
    }
}

/** `POST …/code`: where the code went and how long it lasts. */
@Serializable
public data class EnvelopeCodeResponse(
    @SerialName("email_hint") val emailHint: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    @SerialName("code_length") val codeLength: Int = 6,
)

/** The device proof of [EnvelopeSignMethod.DeviceKey]: both base64url without padding. */
@Serializable
public data class EnvelopeDeviceProof(val payload: String, val signature: String) {
    public companion object {
        /** The proof of [payload] (the canonical bytes that were signed) and its ECDSA DER [signature]. */
        public fun of(payload: ByteArray, signature: ByteArray): EnvelopeDeviceProof {
            val b64 = Base64.getUrlEncoder().withoutPadding()
            return EnvelopeDeviceProof(b64.encodeToString(payload), b64.encodeToString(signature))
        }
    }
}

/**
 * The JSON the device key signs (server/docs/api-envelopes.md, "JSON
 * canônico que o aparelho assina"). Sign [canonicalBytes] with
 * `SHA256withECDSA` after the biometric prompt, then send
 * [EnvelopeDeviceProof.of] (the same bytes and the DER signature).
 */
public data class EnvelopeDevicePayload(
    val challenge: String,
    val consentVersion: String,
    val deviceId: String,
    val documentSha256: String,
    val envelopeId: String,
    /** The signer's `text` fields; sorted by id when serialized. */
    val fields: List<FieldValue>,
    val initialsSha256: String,
    val keyId: String,
    val layoutSha256: String,
    val signatureSha256: String,
    /** UTC with milliseconds, `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` ([timestamp]). */
    val signedAt: String,
    val signerId: String,
) {
    public data class FieldValue(val id: String, val value: String)

    /** The payload as a JSON object (every key of the contract). */
    public fun toJson(): JsonElement = buildJsonObject {
        put("alg", "ES256")
        put("auth_method", "device_key")
        put("challenge", challenge)
        put("consent_version", consentVersion)
        put("device_id", deviceId)
        put("document_sha256", documentSha256)
        put("envelope_id", envelopeId)
        put("fields", kotlinx.serialization.json.JsonArray(
            fields.sortedWith { a, b -> EnvelopeCanon.compareUtf8(a.id, b.id) }.map { f ->
                buildJsonObject {
                    put("id", f.id)
                    put("value", f.value)
                }
            },
        ))
        put("initials_sha256", initialsSha256)
        put("key_id", keyId)
        put("layout_sha256", layoutSha256)
        put("signature_sha256", signatureSha256)
        put("signed_at", signedAt)
        put("signer_id", signerId)
        put("type", TYPE)
        put("v", 1)
    }

    /** The exact UTF-8 bytes the device signs and sends (canonical JSON). */
    public fun canonicalBytes(): ByteArray = EnvelopeCanon.bytes(toJson())

    public companion object {
        public const val TYPE: String = "notificme.envelope_signature"

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** [at] as the payload writes it: `2026-11-13T17:03:11.123Z`. */
        public fun timestamp(at: Instant): String = STAMP.format(at)
    }
}

/** Body of `POST /v0/me/envelopes/{id}/sign`. */
@Serializable
internal data class SignEnvelopeRequest(
    @SerialName("document_sha256") val documentSha256: String,
    @SerialName("layout_sha256") val layoutSha256: String,
    val values: Map<String, String>,
    val method: EnvelopeSignMethod,
    @SerialName("e_signature") val eSignature: ESignatureRef? = null,
    val consent: Boolean,
    @SerialName("device_proof") val deviceProof: EnvelopeDeviceProof? = null,
    @SerialName("totp_code") val totpCode: String? = null,
    @SerialName("email_code") val emailCode: String? = null,
    @SerialName("client_at") val clientAt: String? = null,
    @SerialName("idempotency_key") val idempotencyKey: String? = null,
)

@Serializable
public data class SignEnvelopeResponse(
    val status: String = "signed",
    val signer: EnvelopeSigner? = null,
    @SerialName("envelope_status") val envelopeStatus: EnvelopeStatus = EnvelopeStatus.Unknown,
    /** `sha256:<hex>` of the signing record. */
    @SerialName("record_sha256") val recordSha256: String = "",
)

@Serializable
public data class DeclineEnvelopeResponse(@SerialName("envelope_status") val envelopeStatus: EnvelopeStatus = EnvelopeStatus.Declined)

@Serializable
internal data class EnvelopeClaimResponse(@SerialName("envelope_id") val envelopeId: String)

/** `PUT /v0/devices/current/signing-key`. */
@Serializable
public data class DeviceSigningKey(
    @SerialName("device_id") val deviceId: String = "",
    val alg: String = "ES256",
    /** base64url (no padding) of SHA-256(SPKI DER). */
    @SerialName("key_id") val keyId: String = "",
) {
    public companion object {
        /** The key id the server derives from [publicKeySpki]: base64url(SHA-256(SPKI DER)), no padding. */
        public fun keyIdOf(publicKeySpki: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(publicKeySpki))
    }
}

/** A file of an envelope with a short-lived URL. */
@Serializable
public data class EnvelopeFileLink(
    /** `original`, `document`, `signed` or `certificate`. */
    val kind: String = "",
    val filename: String = "",
    @SerialName("content_type") val contentType: String = "",
    val bytes: Long = 0,
    val sha256: String = "",
    val url: String = "",
    @SerialName("url_expires_at") val urlExpiresAt: String = "",
)
