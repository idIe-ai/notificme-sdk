package me.notific.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Question types of `question-set.schema.json`. Unknown types arrive as [Unknown]. */
@Serializable
public enum class QuestionType {
    @SerialName("yesno") YesNo,
    @SerialName("single") Single,
    @SerialName("multi") Multi,
    @SerialName("text") Text,
    @SerialName("voice") Voice,
    @SerialName("number") Number,
    @SerialName("date") Date,
    @SerialName("unknown") Unknown,
}

@Serializable
public data class Question(
    /** Stable id, used in answers and in `options`/`input` blocks. */
    val id: String,
    val type: QuestionType = QuestionType.Unknown,
    val text: String = "",
    /** Choices of `single` and `multi`. */
    val options: List<String> = emptyList(),
    /** Accepts free text besides the [options]. */
    @SerialName("allow_other") val allowOther: Boolean = false,
    val optional: Boolean = false,
    /** Minimum level of this question, when higher than the set's. */
    val level: Int? = null,
)

@Serializable
public enum class QuestionSetStatus {
    @SerialName("open") Open,
    @SerialName("answered") Answered,
    @SerialName("timeout") Timeout,
    @SerialName("expired") Expired,
    @SerialName("cancelled") Cancelled,
    @SerialName("unknown") Unknown,
}

@Serializable
public enum class Offline {
    @SerialName("allowed") Allowed,
    @SerialName("denied") Denied,
}

/**
 * A question set addressed to the person (`GET /v0/question-sets`). The
 * reply is one package with every answer, sent with [NotificClient.reply].
 */
@Serializable
public data class QuestionSet(
    val id: String = "",
    @SerialName("workspace_id") val workspaceId: String = "",
    val status: QuestionSetStatus = QuestionSetStatus.Open,
    /** Version the person saw; sent back with the reply. */
    val version: Int = 1,
    /**
     * Verification level: 0 tap, 1 signed-in session, 2+ signature on a
     * device key (official app) or authenticator code.
     */
    val level: Int = 1,
    val offline: Offline = Offline.Allowed,
    val title: String = "",
    /** Why the person is being asked. */
    val context: String? = null,
    val questions: List<Question> = emptyList(),
    val card: Card? = null,
    @SerialName("timeout_at") val timeoutAt: String? = null,
    /** SHA-256 of what is shown to the person; sent back with the reply. */
    @SerialName("content_hash") val contentHash: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    /** Open and answerable with this session (level 0 or 1). */
    val answerable: Boolean = false,
    /** Level 2 or 3, answerable with an authenticator-app code instead of a device signature. */
    @SerialName("answerable_with_totp") val answerableWithTotp: Boolean = false,
    /**
     * The sender asks for the person's electronic signature on top of the
     * verification [level]: reply with [NotificClient.reply]'s
     * `eSignatureSha256`. The signed [Reply.eSignature] carries it.
     */
    @SerialName("require_signature") val requireSignature: Boolean = false,
    /**
     * The answer is only accepted with the person's face verified against
     * their enrollment in the organization ([Face]): reply with
     * [NotificClient.reply]'s `face`. The signed [Reply.faceVerification]
     * says how it was checked.
     */
    @SerialName("require_face") val requireFace: Boolean = false,
    /** The signed reply, once answered. */
    val reply: Reply? = null,
    /**
     * Who answered ([Reply.answeredBy]) is a deleted account (soft delete).
     * The signed reply keeps their identity untouched, so it still verifies;
     * the screen shows "Deleted account" in its place.
     */
    @SerialName("answered_by_deleted") val answeredByDeleted: Boolean = false,
    /** Rebuilt from the signed archive (the decision left the hot database). */
    val archived: Boolean = false,
    /**
     * Decision rule (quorum, majority, four eyes…) and its votes so far;
     * null for a question the first answer closes. With
     * [DecisionView.canDecide] false the person only sees it ([answerable] is
     * false too).
     */
    val decision: DecisionView? = null,
) {
    /** The card to draw: the sender's card with every question in it. */
    public fun cardWithQuestions(): Card = (card ?: Card(title = title)).withQuestions(questions)

    public fun question(id: String): Question? = questions.firstOrNull { it.id == id }

    /**
     * The person may answer it: there is no decision rule, or the rule lets
     * them vote now (not only watching, not voted yet, not the requester).
     */
    public val canDecide: Boolean get() = decision?.canDecide != false
}

/** How the person proved who they are. Session replies use [Tap], [Unlock] or [Totp]. */
@Serializable
public enum class AuthMethod {
    @SerialName("tap") Tap,
    @SerialName("unlock") Unlock,
    @SerialName("biometric") Biometric,
    @SerialName("device_pin") DevicePin,
    @SerialName("app_password") AppPassword,
    @SerialName("totp") Totp,
    @SerialName("delegated") Delegated,
    @SerialName("unknown") Unknown,
}

@Serializable
public enum class ReplyStatus {
    @SerialName("answered") Answered,
    @SerialName("timeout") Timeout,
    @SerialName("rejected") Rejected,
    @SerialName("unknown") Unknown,
}

/** The signed reply (`reply.schema.json`). */
@Serializable
public data class Reply(
    @SerialName("question_set_id") val questionSetId: String,
    val version: Int = 1,
    val status: ReplyStatus = ReplyStatus.Unknown,
    val answers: List<ReplyAnswer> = emptyList(),
    /** Natural-language summary. */
    @SerialName("as_text") val asText: String? = null,
    @SerialName("answered_by") val answeredBy: AnsweredBy? = null,
    @SerialName("answered_at") val answeredAt: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    /** The electronic signature applied, when the question set had [QuestionSet.requireSignature]. */
    @SerialName("e_signature") val eSignature: ESignature? = null,
    /** How the person's face was checked, when the question set had [QuestionSet.requireFace]. */
    @SerialName("face_verification") val faceVerification: FaceVerification? = null,
    /** The server's signature of the whole reply (it covers [eSignature] too). */
    val signature: ReplySignature? = null,
    /** How the decision rule closed it (questions with a rule). [status] is [ReplyStatus.Rejected] when approval became impossible. */
    val decision: ReplyDecision? = null,
    /** Every vote, in order (questions with a rule). */
    val votes: List<ReplyVote> = emptyList(),
)

@Serializable
public data class ReplyAnswer(
    @SerialName("question_id") val questionId: String,
    @SerialName("question_text") val questionText: String = "",
    /** Boolean, string, list of strings, number or ISO date. */
    val value: JsonElement? = null,
    /** Free text from a person. Untrusted input: validate it before acting on it. */
    @SerialName("free_text") val freeText: String? = null,
    @SerialName("audio_ref") val audioRef: String? = null,
)

@Serializable
public data class AnsweredBy(
    val identity: String,
    @SerialName("device_id") val deviceId: String? = null,
    @SerialName("level_used") val levelUsed: Int = 0,
    @SerialName("auth_method") val authMethod: AuthMethod? = null,
    /** The group the person decided through (`#finance`), with a decision rule. */
    val group: String? = null,
    /** Their authority level in that group (`manager`). */
    @SerialName("authority_level") val authorityLevel: String? = null,
)

@Serializable
public data class ReplySignature(
    /** `Ed25519` or `ES256`. */
    val alg: String,
    @SerialName("key_id") val keyId: String? = null,
    /** base64url. */
    val value: String,
)

/**
 * `POST /v0/replies` answer. A question with a decision rule records a vote:
 * [closed] says whether it closed the question, and [decision] is the state
 * of the rule after it.
 */
@Serializable
public data class ReplyAccepted(
    @SerialName("question_set_id") val questionSetId: String,
    @SerialName("answer_set_id") val answerSetId: String,
    /** The signed reply; only when this answer closed the question. */
    val reply: Reply? = null,
    /** Questions with a decision rule: false = vote recorded, the rule waits for others. */
    val closed: Boolean? = null,
    /** Questions with a decision rule: the shared state after this vote. */
    val decision: DecisionView? = null,
    val status: String = "accepted",
) {
    /** The question is over (always, without a decision rule). */
    public val isClosed: Boolean get() = closed != false
}
