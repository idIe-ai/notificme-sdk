package me.notific.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonTransformingSerializer

/**
 * Which rule closes a question that several people may decide. Rules added
 * by newer servers arrive as [Unknown].
 */
@Serializable
public enum class DecisionRule {
    /** The first answer closes it. */
    @SerialName("any") Any,

    /** [DecisionView.required] approvals. */
    @SerialName("quorum") Quorum,

    /** More than half of the eligible people. */
    @SerialName("majority") Majority,

    /** Every eligible person. */
    @SerialName("unanimous") Unanimous,

    /** One approval from each group in [DecisionView.by] ("four eyes"). */
    @SerialName("each_group") EachGroup,

    @SerialName("unknown") Unknown,
}

@Serializable
public enum class DecisionOutcome {
    @SerialName("approved") Approved,

    /** Approval could no longer be reached, or someone vetoed. */
    @SerialName("rejected") Rejected,
}

/** One person's vote on a question with a decision rule. */
@Serializable
public data class Vote(
    @SerialName("user_id") val userId: String = "",
    val handle: String? = null,
    @SerialName("display_name") val displayName: String = "",
    /** Path on the API; full URL with [NotificClient.urlOf]. */
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val approve: Boolean = false,
    /** ISO timestamp. */
    val at: String = "",
    /** The group the person decided through (`#finance`). */
    val group: String? = null,
    /** Key of their authority level in that group (`manager`). */
    @SerialName("authority_level") val authorityLevel: String? = null,
    /** Name of that authority level (`Manager`). */
    @SerialName("authority_level_name") val authorityLevelName: String? = null,
) {
    /** Name to show: the display name, else the @handle. */
    public val name: String get() = displayName.ifBlank { handle?.let { "@$it" }.orEmpty() }

    /** `@ana` when there is a handle, else the name. */
    public val mention: String get() = handle?.takeIf { it.isNotBlank() }?.let { "@$it" } ?: displayName
}

/**
 * State of the decision rule of a question set, partial or final, the same
 * for every recipient ("1 of 2 approvals, approved by @ana at 14:32"). The
 * fields from [canDecide] on only come in the signed-in person's own view
 * (`GET /v0/question-sets`); the sync feed and [ReplyAccepted] carry the
 * shared summary.
 */
@Serializable
public data class DecisionView(
    val rule: DecisionRule = DecisionRule.Unknown,
    /** Approvals the rule needs. */
    val required: Int = 1,
    /** People who may decide. */
    val eligible: Int = 0,
    val approvals: Int = 0,
    val rejections: Int = 0,
    /** Eligible people who have not voted yet (0 once closed). */
    val pending: Int = 0,
    /** A single rejection closes it as rejected. */
    val veto: Boolean = false,
    /** Set once the rule closed the question. */
    val outcome: DecisionOutcome? = null,
    /** Who may decide, as asked (`#board`, `#finance>=manager`, `@ana`). */
    @Serializable(with = OneOrMany::class) val by: List<String> = emptyList(),
    /** Every vote, in order. */
    val votes: List<Vote> = emptyList(),
    /** The vote that closed the question ("decided by"). */
    @SerialName("decided_by") val decidedBy: Vote? = null,
    /** How many escalation steps went out. */
    @SerialName("escalation_step") val escalationStep: Int = 0,
    /** Steps in the escalation chain. */
    @SerialName("escalation_steps") val escalationSteps: Int = 0,
    /** When the next step goes out, while open. */
    @SerialName("next_escalation_at") val nextEscalationAt: String? = null,
    /** The signed-in person may vote now. */
    @SerialName("can_decide") val canDecide: Boolean? = null,
    /** The signed-in person's vote. */
    @SerialName("my_vote") val myVote: Vote? = null,
    /** Why the person may not vote: one of the `REASON_` constants. */
    val reason: String? = null,
    /** The group the person decides through (`#board`). */
    @SerialName("my_group") val myGroup: String? = null,
    /** Name of the person's authority level in that group (`Director`). */
    @SerialName("my_authority_level") val myAuthorityLevel: String? = null,
) {
    /**
     * A vote closed the question: there is an outcome or the vote that
     * closed it (a question that ran out says so in [QuestionSet.status]).
     */
    public val closed: Boolean get() = outcome != null || decidedBy != null

    /** Approvals still needed (0 once enough). */
    public val missing: Int get() = (required - approvals).coerceAtLeast(0)

    /** The most recent approval, if any. */
    public val lastApproval: Vote? get() = votes.lastOrNull { it.approve }

    /**
     * This shared summary (from the feed or a reply) with the signed-in
     * person's own fields kept from [previous], their last full view.
     */
    public fun withViewerOf(previous: DecisionView?): DecisionView {
        if (previous == null || canDecide != null) return this
        val mine = previous.myVote?.let { v -> votes.firstOrNull { it.userId == v.userId } ?: v }
        return copy(
            canDecide = if (closed) false else previous.canDecide,
            myVote = mine,
            reason = if (closed) REASON_CLOSED else previous.reason,
            myGroup = previous.myGroup,
            myAuthorityLevel = previous.myAuthorityLevel,
        )
    }

    /**
     * This summary, returned by [NotificClient.reply] after the person voted,
     * as their own view: the vote that is new since [previous] is theirs, and
     * they may not vote again.
     */
    public fun afterMyVote(previous: DecisionView?): DecisionView {
        val before = previous?.votes.orEmpty().mapTo(HashSet()) { it.userId }
        val mine = votes.lastOrNull { it.userId !in before } ?: previous?.myVote ?: votes.maxByOrNull { it.at }
        return copy(
            canDecide = false,
            myVote = mine,
            reason = if (closed) REASON_CLOSED else REASON_ALREADY_VOTED,
            myGroup = previous?.myGroup,
            myAuthorityLevel = previous?.myAuthorityLevel,
        )
    }

    public companion object {
        /** The person is not among who may decide (they only see it). */
        public const val REASON_NOT_ELIGIBLE: String = "not_eligible"
        public const val REASON_ALREADY_VOTED: String = "already_voted"

        /** The person made the request: they never approve it themselves. */
        public const val REASON_REQUESTER: String = "requester_cannot_approve"
        public const val REASON_CLOSED: String = "closed"
    }
}

/** How the decision rule closed a question, inside the signed [Reply]. */
@Serializable
public data class ReplyDecision(
    val rule: DecisionRule = DecisionRule.Unknown,
    val required: Int = 1,
    val eligible: Int = 0,
    val approvals: Int = 0,
    val rejections: Int = 0,
    val outcome: DecisionOutcome? = null,
    @Serializable(with = OneOrMany::class) val by: List<String> = emptyList(),
    @SerialName("escalation_step") val escalationStep: Int = 0,
)

/** A vote inside the signed [Reply]. */
@Serializable
public data class ReplyVote(
    val identity: String = "",
    val handle: String? = null,
    val approve: Boolean = false,
    @SerialName("answered_at") val answeredAt: String = "",
    @SerialName("level_used") val levelUsed: Int? = null,
    @SerialName("auth_method") val authMethod: AuthMethod? = null,
    val group: String? = null,
    @SerialName("authority_level") val authorityLevel: String? = null,
)

/** `"#board"` or `["#board", "#legal"]`: the protocol accepts one entry or a list. */
internal object OneOrMany : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = element as? JsonArray ?: JsonArray(listOf(element))
}
