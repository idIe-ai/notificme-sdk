package me.notific.client

import io.ktor.http.HttpMethod
import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** How a signature was made. Unknown values from newer servers arrive as [Unknown]. */
@Serializable
public enum class SignatureMethod {
    /** The full name typed and drawn in one of the cursive [SignatureStyle]s. */
    @SerialName("typed") Typed,

    /** Drawn by finger or mouse. */
    @SerialName("drawn") Drawn,

    @SerialName("unknown") Unknown,
}

/**
 * Cursive styles of a typed signature. Each is an OFL font the clients embed
 * (Great Vibes, Dancing Script, Sacramento and Mr Dafoe). [wire] is the value
 * the API uses.
 */
@Serializable
public enum class SignatureStyle(public val wire: String) {
    @SerialName("great-vibes") GreatVibes("great-vibes"),
    @SerialName("dancing-script") DancingScript("dancing-script"),
    @SerialName("sacramento") Sacramento("sacramento"),
    @SerialName("mr-dafoe") MrDafoe("mr-dafoe"),
    ;

    public companion object {
        /** The style of a [wire] value; null when unknown. */
        public fun of(wire: String?): SignatureStyle? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A visible electronic signature: the image of the signature and of the
 * initials, as the person adopted them on their account (`GET /v0/me/signature`)
 * or as they were applied to an answer ([Reply.eSignature]).
 *
 * The images are PNGs with a transparent background and dark ink, in standard
 * base64 ([signaturePng], [initialsPng]); `*Sha256` is
 * `"sha256:" + hex(SHA-256(png bytes))`.
 */
@Serializable
public data class ESignature(
    /** Full name the person adopted. */
    val name: String = "",
    val initials: String = "",
    val method: SignatureMethod = SignatureMethod.Unknown,
    /** Cursive style of a [SignatureMethod.Typed] signature. */
    val style: SignatureStyle? = null,
    @SerialName("signature_png") val signaturePng: String = "",
    @SerialName("signature_sha256") val signatureSha256: String = "",
    @SerialName("initials_png") val initialsPng: String = "",
    @SerialName("initials_sha256") val initialsSha256: String = "",
    /** Version of the consent text the person accepted when adopting. */
    @SerialName("consent_version") val consentVersion: String? = null,
    @SerialName("adopted_at") val adoptedAt: String = "",
) {
    /** The signature PNG bytes; empty when missing or not valid base64. */
    public fun signatureBytes(): ByteArray = decode(signaturePng)

    /** The initials PNG bytes; empty when missing or not valid base64. */
    public fun initialsBytes(): ByteArray = decode(initialsPng)

    /** Both images match their hashes (what a verifier recomputes). */
    public fun hashesMatch(): Boolean =
        signaturePng.isNotEmpty() && initialsPng.isNotEmpty() &&
            sha256Of(signatureBytes()) == signatureSha256 && sha256Of(initialsBytes()) == initialsSha256

    public companion object {
        /** `"sha256:" + lowercase hex` of [bytes], the format of [signatureSha256]. */
        public fun sha256Of(bytes: ByteArray): String =
            "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun decode(b64: String): ByteArray =
            if (b64.isEmpty()) ByteArray(0) else runCatching { Base64.getDecoder().decode(b64) }.getOrDefault(ByteArray(0))
    }
}

/**
 * The signed-in person's own account settings that are not about a device:
 * the electronic signature they adopt once and apply to answers that ask for
 * one ([QuestionSet.requireSignature]).
 *
 * These calls need the person's own sign-in: a delegated session issued by a
 * company gets `forbidden`.
 */
public class Account internal constructor(private val client: NotificClient) {

    /** The adopted signature; null when the person has none yet. */
    public suspend fun signature(): ESignature? =
        client.call(HttpMethod.Get, "/v0/me/signature", SignatureEnvelope.serializer()).signature

    /**
     * Adopts (or replaces) the person's signature. Call it only after the
     * person read and accepted the consent text: it sends `consent: true`.
     *
     * The images are PNG bytes drawn by the app: transparent background,
     * ink `#1D1D1F`, trimmed with a small margin. Limits: the signature up to
     * 128 KiB and 1600×600 px, the initials up to 64 KiB and 800×400 px.
     * [style] goes only with [SignatureMethod.Typed]. Errors:
     * `validation_failed` (paths in [NotificMeApiException.details]),
     * `consent_required`, `payload_too_large`.
     *
     * Answers already signed keep the signature they had.
     */
    public suspend fun adoptSignature(
        name: String,
        initials: String,
        method: SignatureMethod,
        signaturePng: ByteArray,
        initialsPng: ByteArray,
        style: SignatureStyle? = null,
    ): ESignature {
        val body = SignatureAdoption(
            name = name.trim(),
            initials = initials.trim(),
            method = method,
            style = if (method == SignatureMethod.Typed) style else null,
            signaturePng = Base64.getEncoder().encodeToString(signaturePng),
            initialsPng = Base64.getEncoder().encodeToString(initialsPng),
            consent = true,
        )
        val res = client.call(
            HttpMethod.Put, "/v0/me/signature", SignatureEnvelope.serializer(),
            body = Wire.json.encodeToJsonElement(SignatureAdoption.serializer(), body),
        )
        return res.signature ?: throw NotificMeException("invalid_response", "Adopting did not return the signature")
    }

    /** Removes the signature; the next answer that asks for one asks to adopt again. */
    public suspend fun deleteSignature() {
        client.call(HttpMethod.Delete, "/v0/me/signature", JsonElement.serializer())
    }

    @Serializable
    private data class SignatureEnvelope(val signature: ESignature? = null)
}

/** Body of `PUT /v0/me/signature`. */
@Serializable
internal data class SignatureAdoption(
    val name: String,
    val initials: String,
    val method: SignatureMethod,
    val style: SignatureStyle? = null,
    @SerialName("signature_png") val signaturePng: String,
    @SerialName("initials_png") val initialsPng: String,
    val consent: Boolean,
)

/** The signature a reply applies: the hash of the one shown to the person. */
@Serializable
internal data class ESignatureRef(@SerialName("signature_sha256") val signatureSha256: String)
