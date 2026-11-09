package me.notific.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
public enum class Severity {
    @SerialName("info") Info,
    @SerialName("low") Low,
    @SerialName("medium") Medium,
    @SerialName("high") High,
    @SerialName("critical") Critical,
}

/** A notification delivered to the person, as it comes from `GET /v0/sync`. */
@Serializable
public data class Notification(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    val source: String = "",
    val type: String = "",
    val severity: Severity = Severity.Info,
    val title: String? = null,
    val summary: String? = null,
    val status: String = "",
    /** Set when the notification carries a question set. */
    @SerialName("question_set_id") val questionSetId: String? = null,
    val deliveries: List<DeliveryState> = emptyList(),
    val content: NotificationContent? = null,
    /** Live card: the same id is updated in place and [version] goes up. */
    @SerialName("card_id") val cardId: String? = null,
    val version: Int = 1,
    /** `high` wakes the phone; `normal` does not. */
    val priority: String? = null,
    /** Verification level of the decision it carries (0 when none). */
    val level: Int = 0,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    /** When the person gave "got it" on this (critical) alert, on any of their devices. */
    @SerialName("acknowledged_at") val acknowledgedAt: String? = null,
) {
    /** No delivery of it was read yet. */
    public val unread: Boolean get() = deliveries.isNotEmpty() && deliveries.all { it.readAt == null }

    public val card: Card? get() = content?.card

    /**
     * Title to show. Without its own title the server uses the source
     * (`dokploy`), so the summary says more.
     */
    public val headline: String
        get() = if (weakTitle) summary?.takeIf { it.isNotBlank() } ?: title ?: type else title.orEmpty()

    /** Second line to show under [headline] (empty when the headline already is the summary). */
    public val subtitle: String
        get() = if (weakTitle) "" else summary?.takeIf { it != title }.orEmpty()

    private val weakTitle: Boolean get() = title.isNullOrBlank() || title == source || title == type

    /**
     * Waiting for the person's "got it" ([NotificClient.acknowledge]): an open
     * critical alert that is not a decision, or a message from a person, not
     * acknowledged on any of their devices yet.
     */
    public val asksForAck: Boolean
        get() = acknowledgedAt == null && questionSetId == null &&
            ((severity == Severity.Critical && closedAt == null) || type == TYPE_MESSAGE)

    public companion object {
        /** [type] of a message one person sent to another (Compose). */
        public const val TYPE_MESSAGE: String = "message"
    }
}

@Serializable
public data class DeliveryState(
    val id: String? = null,
    val channel: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    /** `pending`, `sent`, `delivered`, `read`, `failed`, `expired`… */
    val status: String? = null,
    val seq: Long = 0,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
)

@Serializable
public data class NotificationContent(
    /** Short text for push and simple channels. */
    val summary: String? = null,
    val card: Card? = null,
    /** Rich HTML stored by the sender; fetch it with [NotificClient.content]. */
    val rich: RichContent? = null,
)

/** HTML attached to a notification (`content.rich`). */
@Serializable
public data class RichContent(
    /** `content:<id>`. */
    @SerialName("html_ref") val htmlRef: String,
    /** Height the sender suggests, in CSS pixels. */
    val height: Int? = null,
) {
    /** The id for [NotificClient.content]. */
    public val contentId: String get() = htmlRef.removePrefix("content:")
}

@Serializable
public enum class SyncEntity {
    @SerialName("notification") Notification,
    @SerialName("delivery") Delivery,
    @SerialName("question_set") QuestionSet,
    @SerialName("answer_set") AnswerSet,
    @SerialName("unknown") Unknown,
}

@Serializable
public enum class SyncOp {
    @SerialName("upsert") Upsert,
    @SerialName("delete") Delete,
}

/** One change of the person's feed. */
@Serializable
public data class SyncChange(
    val seq: Long,
    val entity: SyncEntity = SyncEntity.Unknown,
    val id: String,
    val op: SyncOp = SyncOp.Upsert,
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("changed_at") val changedAt: String = "",
    val data: JsonElement? = null,
)

/** `GET /v0/sync?cursor=&limit=`. */
@Serializable
public data class SyncPage(
    val changes: List<SyncChange> = emptyList(),
    @SerialName("next_cursor") val nextCursor: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
    /** The cursor is too old: drop local state and apply this page from scratch. */
    val reset: Boolean = false,
)

/** A delivery pushed by the realtime gateway. */
@Serializable
public data class Delivery(
    val id: String,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("workspace_id") val workspaceId: String = "",
    @SerialName("notification_id") val notificationId: String = "",
    @SerialName("question_set_id") val questionSetId: String? = null,
    val seq: Long = 0,
    val kind: String = "",
    val severity: Severity = Severity.Info,
    val level: Int = 0,
    val title: String? = null,
    val summary: String = "",
    /** Update of a live card (same notification). */
    val update: Boolean = false,
)

/** How the person reacted to a notification (`POST /v0/reactions`). */
@Serializable
public enum class ReactionKind {
    @SerialName("delivered") Delivered,
    @SerialName("opened") Opened,
    @SerialName("ignored") Ignored,
    @SerialName("action") Action,
    @SerialName("muted") Muted,
    @SerialName("dismissed") Dismissed,
    @SerialName("answered") Answered,
}

@Serializable
public data class Reaction(
    val kind: ReactionKind,
    @SerialName("notification_id") val notificationId: String? = null,
    @SerialName("delivery_id") val deliveryId: String? = null,
    /** Button action id, for [ReactionKind.Action]. */
    val action: String? = null,
    /** ISO timestamp. */
    val at: String? = null,
    /** IANA time zone of the device. */
    val tz: String? = null,
    val platform: String? = null,
)
