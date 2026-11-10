package me.notific.client

import io.ktor.http.HttpMethod
import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** One step of a face challenge. Unknown steps from newer servers arrive as [Unknown]. */
@Serializable(with = FaceStepSerializer::class)
public enum class FaceStep(public val wire: String) {
    /** Looking straight at the camera. */
    Center("center"),

    /** The person turns their head to their own left. */
    Left("left"),

    /** The person turns their head to their own right. */
    Right("right"),

    /** The person brings the device closer. */
    Near("near"),

    Unknown("unknown"),
}

/** Steps travel in a list, where the JSON coercion of unknown enum values does not apply. */
internal object FaceStepSerializer : KSerializer<FaceStep> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("me.notific.client.FaceStep", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: FaceStep): Unit = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): FaceStep {
        val wire = decoder.decodeString()
        return FaceStep.entries.firstOrNull { it.wire == wire } ?: FaceStep.Unknown
    }
}

/** What a face challenge is for. */
@Serializable
public enum class FacePurpose {
    @SerialName("enroll") Enroll,
    @SerialName("verify") Verify,
}

/** What the organization does with a person whose face cannot be verified. */
@Serializable
public enum class FaceFallbackPolicy {
    /** No answer without a verified face. */
    @SerialName("block") Block,

    /** The authenticator-app code is accepted instead. */
    @SerialName("fallback") Fallback,
}

/** How the organization checks that a live person is in front of the camera. */
@Serializable
public enum class FaceLiveness {
    /** The person follows a drawn sequence of movements (turn, come closer). Stops a still picture. */
    @SerialName("active") Active,

    /** The person only looks at the camera: faster, and a picture of them may be accepted. */
    @SerialName("passive") Passive,
}

/** Where the organization's country stands in the face verification table. */
@Serializable
public enum class FaceCountryStatus {
    @SerialName("allowed") Allowed,

    /** Allowed once the organization accepts every item of [FaceCountry.conditions]. */
    @SerialName("conditional") Conditional,
    @SerialName("blocked") Blocked,
    @SerialName("unknown") Unknown,
}

@Serializable
public data class FaceCountry(
    /** ISO 3166-1 alpha-2. */
    val code: String = "",
    val status: FaceCountryStatus = FaceCountryStatus.Unknown,
    val conditions: List<String> = emptyList(),
)

/** The organization's face verification policy. */
@Serializable
public data class FaceSettings(
    val enabled: Boolean = false,
    /** Cosine similarity a face must reach. */
    val threshold: Double = 0.363,
    /** For a person who enrolled and ran out of attempts. */
    @SerialName("on_fail") val onFail: FaceFallbackPolicy = FaceFallbackPolicy.Block,
    /** For a person who did not enroll their face. */
    val unenrolled: FaceFallbackPolicy = FaceFallbackPolicy.Block,
    /** What a verification asks of the person (enrolling always asks for the movements). */
    val liveness: FaceLiveness = FaceLiveness.Active,
    @SerialName("accepted_conditions") val acceptedConditions: List<String> = emptyList(),
    @SerialName("terms_version") val termsVersion: String? = null,
    @SerialName("enabled_at") val enabledAt: String? = null,
)

/** The signed-in person's own face enrollment in that organization. */
@Serializable
public data class FaceEnrollment(
    val enrolled: Boolean = false,
    @SerialName("enrolled_at") val enrolledAt: String? = null,
    val model: String? = null,
)

/** `GET /v0/workspaces/{handle}/face`. */
@Serializable
public data class FaceStatus(
    /** The server has a face worker configured. */
    val available: Boolean = false,
    /** The organization's plan includes face verification. */
    val allowed: Boolean = false,
    @SerialName("required_plan") val requiredPlan: String = "",
    /** Null until the organization tells its country. */
    val country: FaceCountry? = null,
    val settings: FaceSettings = FaceSettings(),
    val me: FaceEnrollment = FaceEnrollment(),
    /** Version of the consent text the person accepts to enroll. */
    @SerialName("consent_version") val consentVersion: String = "",
    /** Version of the terms the organization accepts to turn it on. */
    @SerialName("terms_version") val termsVersion: String = "",
) {
    /** The person can enroll and verify here now. */
    public val usable: Boolean get() = available && allowed && settings.enabled
}

/** A single-use challenge: one frame per step, in this order, before [expiresAt]. */
@Serializable
public data class FaceChallenge(
    val id: String = "",
    val purpose: FacePurpose = FacePurpose.Verify,
    val steps: List<FaceStep> = emptyList(),
    @SerialName("expires_at") val expiresAt: String = "",
)

/** `POST /v0/workspaces/{handle}/face/verifications`. The similarity score never leaves the server. */
@Serializable
public data class FaceCheck(
    val id: String = "",
    val passed: Boolean = false,
    /** When not passed: `challenge_failed` (the frames did not follow the steps) or `no_match`. */
    val reason: String? = null,
    @SerialName("question_set_id") val questionSetId: String? = null,
    @SerialName("attempts_left") val attemptsLeft: Int = 0,
    /** When there are no attempts left: when the person may try again. */
    @SerialName("retry_at") val retryAt: String? = null,
    /** What the organization chose for a person without attempts. */
    @SerialName("on_fail") val onFail: FaceFallbackPolicy = FaceFallbackPolicy.Block,
    /** A passed verification backs one reply until then. */
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** How the face of the person who answered was checked ([Reply.faceVerification]). */
@Serializable
public data class FaceVerification(
    val result: Result = Result.Unknown,
    /** Only with [Result.Fallback]. */
    val reason: Reason? = null,
    @SerialName("verification_id") val verificationId: String? = null,
    /** Version of the face model that compared the faces. */
    val model: String? = null,
    @SerialName("verified_at") val verifiedAt: String = "",
) {
    @Serializable
    public enum class Result {
        /** The face matched the one the person enrolled in the organization. */
        @SerialName("verified") Verified,

        /** The face was NOT verified; the organization accepted the authenticator code instead. */
        @SerialName("fallback") Fallback,
        @SerialName("unknown") Unknown,
    }

    @Serializable
    public enum class Reason {
        @SerialName("attempts_exhausted") AttemptsExhausted,
        @SerialName("not_enrolled") NotEnrolled,
        @SerialName("unknown") Unknown,
    }
}

/**
 * What a reply presents when the question set has [QuestionSet.requireFace].
 * See [NotificClient.reply].
 */
public sealed interface FaceProof {
    /** A passed [FaceCheck] made for that question set ([FaceCheck.id]). */
    public data class Verified(val verificationId: String) : FaceProof

    /**
     * No face: the organization accepts the authenticator code from this
     * person (they did not enroll, or ran out of attempts). The reply must go
     * with [AuthMethod.Totp] and a fresh code.
     */
    public data object Fallback : FaceProof
}

/**
 * Face verification of the signed-in person in one organization
 * (`/v0/workspaces/{handle}/face`): the 1:1 check that proves the person
 * answering is the member who enrolled.
 *
 * The flow, for both enrolling and verifying: ask for a [challenge], capture
 * one frame per step **in order** (JPEG or PNG, upright and not mirrored, the
 * face at least a third of the width), and send them. The server compares;
 * the device never does. Frames are never stored.
 *
 * These calls need the person's own sign-in: a delegated session gets
 * `user_session_required`. Common errors: `plan_required`, `face_disabled`
 * (the organization did not turn it on), `face_unavailable`,
 * `face_challenge_invalid` (used or expired: ask for another),
 * `face_frame_rejected` (`details.reason` and `details.frame`; it does not
 * count as an attempt), `face_locked` (`details.retry_at`).
 */
public class Face internal constructor(private val client: NotificClient) {

    public suspend fun status(workspace: String): FaceStatus =
        client.call(HttpMethod.Get, path(workspace, ""), FaceStatus.serializer())

    /**
     * Changes the organization's policy (owner or admin); null fields stay as
     * they are. Turning it on needs the country, the current
     * [FaceStatus.termsVersion] in [termsVersion] and, for a
     * [FaceCountryStatus.Conditional] country, every condition in
     * [acceptConditions]. **Turning it off deletes every member's
     * enrollment.** Errors: `face_country_required`, `face_country_blocked`,
     * `face_conditions_required` (`details.missing`), `face_terms_required`.
     */
    public suspend fun updateSettings(
        workspace: String,
        enabled: Boolean? = null,
        threshold: Double? = null,
        onFail: FaceFallbackPolicy? = null,
        unenrolled: FaceFallbackPolicy? = null,
        liveness: FaceLiveness? = null,
        country: String? = null,
        acceptConditions: List<String>? = null,
        termsVersion: String? = null,
    ): FaceStatus = client.call(
        HttpMethod.Put, path(workspace, "/settings"), FaceStatus.serializer(),
        body = Wire.json.encodeToJsonElement(
            SettingsBody.serializer(),
            SettingsBody(enabled, threshold, onFail, unenrolled, liveness, country, acceptConditions, termsVersion),
        ),
    )

    /**
     * Draws a single-use challenge. To answer a question set that has
     * [QuestionSet.requireFace], pass its id: the verification is then bound
     * to that question. Errors: `face_not_enrolled` (verify without an
     * enrollment), `face_locked`, `not_found` (the question is not the
     * person's to answer).
     */
    public suspend fun challenge(workspace: String, purpose: FacePurpose, questionSetId: String? = null): FaceChallenge =
        client.call(
            HttpMethod.Post, path(workspace, "/challenges"), FaceChallenge.serializer(),
            body = Wire.json.encodeToJsonElement(ChallengeBody.serializer(), ChallengeBody(purpose, questionSetId)),
        )

    /**
     * Enrolls (or replaces) the person's face. Call it only after the person
     * read and accepted the consent text of [consentVersion]
     * ([FaceStatus.consentVersion]): it sends `accepted: true`. Errors:
     * `face_consent_required`, `face_challenge_failed` (`details.step`: the
     * frames did not follow the steps).
     */
    public suspend fun enroll(workspace: String, challengeId: String, consentVersion: String, frames: List<ByteArray>): FaceStatus =
        client.call(
            HttpMethod.Post, path(workspace, "/enrollment"), FaceStatus.serializer(),
            body = Wire.json.encodeToJsonElement(
                EnrollBody.serializer(),
                EnrollBody(challengeId, Consent(consentVersion, true), frames.map(::b64)),
            ),
        )

    /** Deletes the person's own enrollment in that organization. */
    public suspend fun deleteEnrollment(workspace: String): FaceStatus =
        client.call(HttpMethod.Delete, path(workspace, "/enrollment"), FaceStatus.serializer())

    /**
     * Verifies the person's face against their enrollment. A failed check is
     * an ordinary result ([FaceCheck.passed] false), not an error. Errors:
     * `face_not_enrolled`, `face_reenroll_required` (the enrollment is from
     * an older model), `face_locked`.
     */
    public suspend fun verify(workspace: String, challengeId: String, frames: List<ByteArray>): FaceCheck =
        client.call(
            HttpMethod.Post, path(workspace, "/verifications"), FaceCheck.serializer(),
            body = Wire.json.encodeToJsonElement(VerifyBody.serializer(), VerifyBody(challengeId, frames.map(::b64))),
        )

    private fun path(workspace: String, rest: String): String =
        "/v0/workspaces/" + java.net.URLEncoder.encode(workspace.removePrefix("@"), Charsets.UTF_8).replace("+", "%20") + "/face" + rest

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Serializable
    private data class SettingsBody(
        val enabled: Boolean? = null,
        val threshold: Double? = null,
        @SerialName("on_fail") val onFail: FaceFallbackPolicy? = null,
        val unenrolled: FaceFallbackPolicy? = null,
        val liveness: FaceLiveness? = null,
        val country: String? = null,
        @SerialName("accept_conditions") val acceptConditions: List<String>? = null,
        @SerialName("terms_version") val termsVersion: String? = null,
    )

    @Serializable
    private data class ChallengeBody(val purpose: FacePurpose, @SerialName("question_set_id") val questionSetId: String? = null)

    @Serializable
    private data class Consent(val version: String, val accepted: Boolean)

    @Serializable
    private data class EnrollBody(@SerialName("challenge_id") val challengeId: String, val consent: Consent, val frames: List<String>)

    @Serializable
    private data class VerifyBody(@SerialName("challenge_id") val challengeId: String, val frames: List<String>)
}

/** The `face` field of a reply. */
@Serializable
internal data class FaceRef(
    @SerialName("verification_id") val verificationId: String? = null,
    val fallback: Boolean? = null,
) {
    companion object {
        fun of(proof: FaceProof): FaceRef = when (proof) {
            is FaceProof.Verified -> FaceRef(verificationId = proof.verificationId)
            FaceProof.Fallback -> FaceRef(fallback = true)
        }
    }
}
