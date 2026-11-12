package me.notific.client

import io.ktor.http.HttpMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// The organized inbox (docs/27): the person's own state of each notification
// (seen, archived, pinned) and personal rules per service, kept on the server
// and the same on every device. None of it is evidence: "seen" is not "read",
// and whoever sent the notification never sees it. Reading is only opening the
// card ([NotificClient.markOpened]).

/**
 * Why a notification waits for the person ([Notification.waiting]). Waiting
 * items never leave the inbox by a batch action or a rule: they stay in
 * "Waiting for you" until answered or expired.
 */
@Serializable
public enum class InboxWaiting {
    /** An open decision this person can still answer. */
    @SerialName("decision") Decision,

    /** A questionnaire from a person, not answered yet. */
    @SerialName("questionnaire") Questionnaire,

    /** A message from a person without "got it" ([NotificClient.acknowledge]). */
    @SerialName("ack") Ack,

    /** A critical alert without "got it". */
    @SerialName("alert") Alert,
    ;

    /** The name on the wire (`decision`, `questionnaire`, `ack`, `alert`). */
    public val wire: String get() = when (this) {
        Decision -> "decision"
        Questionnaire -> "questionnaire"
        Ack -> "ack"
        Alert -> "alert"
    }
}

/** The person's marks on a notification (`inbox_mark` of the sync feed). */
@Serializable
public data class InboxMark(
    @SerialName("notification_id") val notificationId: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    /** Seen in the inbox (not read: reading is opening the card). */
    @SerialName("seen_at") val seenAt: String? = null,
    @SerialName("archived_at") val archivedAt: String? = null,
    @SerialName("pinned_at") val pinnedAt: String? = null,
    /** Archived by a rule ([InboxRule.autoArchiveAfter]), not by the person. */
    @SerialName("auto_archived") val autoArchived: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    public val seen: Boolean get() = seenAt != null
    public val archived: Boolean get() = archivedAt != null
    public val pinned: Boolean get() = pinnedAt != null
}

/** `POST /v0/me/inbox/marks`: what changed and what stayed. */
@Serializable
public data class InboxMarksResult(
    /** Notifications whose state changed. */
    val applied: Int = 0,
    /** Already like that. */
    val unchanged: Int = 0,
    /** Waiting items the request did not archive nor mark seen ("3 decisions waiting for you"). */
    val waiting: Int = 0,
    /** [waiting] by reason (`decision`, `questionnaire`, `ack`, `alert`). */
    @SerialName("waiting_by_reason") val waitingByReason: Map<String, Int> = emptyMap(),
    /** Pinned items "archive all" kept in the inbox. */
    @SerialName("kept_pinned") val keptPinned: Int = 0,
    /** Only with ids: not this person's notification (or no longer in the hot store). */
    @SerialName("not_found") val notFound: List<String> = emptyList(),
    /** false: "all" stopped at the server's batch limit; send it again with `after` = [nextAfter]. */
    val complete: Boolean = true,
    /** Only with [complete] false: the sync position "all" read up to (where the next call continues). */
    @SerialName("next_after") val nextAfter: String? = null,
    /**
     * Offline: not sent yet, already shown ([Inbox.mark]); the numbers are
     * this device's estimate and the server's own count comes with the sync.
     */
    @kotlinx.serialization.Transient val queued: Boolean = false,
) {
    /** [waiting] of one reason. */
    public fun waitingFor(reason: InboxWaiting): Int = waitingByReason[reason.wire] ?: 0
}

/**
 * A personal rule for a service (and optionally one type of it), the same on
 * every device (`inbox_rule` of the sync feed). Without a rule the defaults
 * apply: grouped, with sound, never archived by itself.
 */
@Serializable
public data class InboxRule(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String = "",
    /** The service: [Notification.source]. */
    val source: String = "",
    /** Only this [Notification.type]; null = every type. A type rule wins over the service rule. */
    val type: String? = null,
    /** Stack the service's notifications. Decisions and critical alerts are never grouped. */
    val group: Boolean = true,
    /** No sound nor vibration. */
    val mute: Boolean = false,
    /** Hours (1–720) to archive informational items by itself; null = off. */
    @SerialName("auto_archive_after") val autoArchiveAfter: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
internal data class InboxRuleList(val rules: List<InboxRule> = emptyList())

/** A notification's state change: true sets, false clears, null keeps. */
public data class InboxMarkChange(
    val seen: Boolean? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
) {
    init {
        require(seen != null || archived != null || pinned != null) { "Change at least one of seen, archived, pinned" }
    }
}

/**
 * Sets or clears the person's marks on these notifications (up to 1000).
 * Waiting items ([Notification.waiting]) are never archived nor marked seen:
 * see [InboxMarksResult.waiting]. Creates no evidence and no read receipt.
 */
public suspend fun NotificClient.markInbox(notificationIds: List<String>, change: InboxMarkChange): InboxMarksResult {
    require(notificationIds.size <= MAX_MARK_IDS) { "At most $MAX_MARK_IDS ids per call" }
    if (notificationIds.isEmpty()) return InboxMarksResult()
    return call(HttpMethod.Post, MARKS_PATH, InboxMarksResult.serializer(), body = buildJsonObject {
        putJsonArray("ids") { notificationIds.distinct().forEach { add(it) } }
        putChange(change)
    })
}

/**
 * Sets or clears the marks on everything that reached the person up to
 * [untilCursor] (the sync cursor the device had when the person tapped, e.g.
 * "mark all as seen"), optionally only one workspace, service or type.
 * "Archive all" keeps pinned items. Waiting items stay, as in [markInbox].
 * While [InboxMarksResult.complete] is false, call again with [after] =
 * [InboxMarksResult.nextAfter]: it goes on from where the last call stopped
 * (the rounds do not overlap, so their counts add up).
 */
public suspend fun NotificClient.markInboxAll(
    untilCursor: Long,
    change: InboxMarkChange,
    workspaceId: String? = null,
    source: String? = null,
    type: String? = null,
    after: String? = null,
): InboxMarksResult =
    call(HttpMethod.Post, MARKS_PATH, InboxMarksResult.serializer(), body = buildJsonObject {
        putJsonObject("all") {
            put("until_cursor", untilCursor.toString())
            if (after != null) put("after", after)
            if (workspaceId != null) put("workspace_id", workspaceId)
            if (source != null) put("source", source)
            if (type != null) put("type", type)
        }
        putChange(change)
    })

/** The person's inbox rules (of one workspace, or all). */
public suspend fun NotificClient.inboxRules(workspaceId: String? = null): List<InboxRule> =
    call(HttpMethod.Get, RULES_PATH, InboxRuleList.serializer(), query = mapOf("workspace_id" to workspaceId)).rules

/**
 * Creates or adjusts the rule of [workspaceId] + [source] + [type] (null =
 * every type). Null fields keep what the rule has (new rules: the defaults);
 * [clearAutoArchive] turns the auto-archive off. Errors: `workspace_not_found`,
 * `rule_limit`, `validation_failed`.
 */
public suspend fun NotificClient.saveInboxRule(
    workspaceId: String,
    source: String,
    type: String? = null,
    group: Boolean? = null,
    mute: Boolean? = null,
    autoArchiveAfter: Int? = null,
    clearAutoArchive: Boolean = false,
): InboxRule =
    call(HttpMethod.Post, RULES_PATH, InboxRule.serializer(), body = buildJsonObject {
        put("workspace_id", workspaceId)
        put("source", source)
        if (type != null) put("type", type)
        putSettings(group, mute, autoArchiveAfter, clearAutoArchive)
    })

/** Adjusts a rule; null fields keep, [clearAutoArchive] turns the auto-archive off. Error: `rule_not_found`. */
public suspend fun NotificClient.updateInboxRule(
    id: String,
    group: Boolean? = null,
    mute: Boolean? = null,
    autoArchiveAfter: Int? = null,
    clearAutoArchive: Boolean = false,
): InboxRule =
    call(HttpMethod.Patch, "$RULES_PATH/${id.segment()}", InboxRule.serializer(), body = buildJsonObject {
        putSettings(group, mute, autoArchiveAfter, clearAutoArchive)
    })

/** Deletes a rule (back to the defaults). Error: `rule_not_found`. */
public suspend fun NotificClient.deleteInboxRule(id: String) {
    call(HttpMethod.Delete, "$RULES_PATH/${id.segment()}", JsonElement.serializer())
}

private const val MARKS_PATH = "/v0/me/inbox/marks"
private const val RULES_PATH = "/v0/me/inbox/rules"

private fun String.segment(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")

/** Ids per [markInbox] call. */
public const val MAX_MARK_IDS: Int = 1000

private fun kotlinx.serialization.json.JsonObjectBuilder.putChange(change: InboxMarkChange) {
    change.seen?.let { put("seen", it) }
    change.archived?.let { put("archived", it) }
    change.pinned?.let { put("pinned", it) }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putSettings(group: Boolean?, mute: Boolean?, autoArchiveAfter: Int?, clear: Boolean) {
    if (group != null) put("group", group)
    if (mute != null) put("mute", mute)
    when {
        clear -> put("auto_archive_after", JsonNull)
        autoArchiveAfter != null -> put("auto_archive_after", autoArchiveAfter)
    }
}

/**
 * A change of marks the server did not take yet (offline): shown at once by
 * [Inbox], kept with its cache and sent in order when the network is back.
 * [ids] empty = "all up to [untilCursor]", optionally one [source] or [type].
 */
@Serializable
public data class PendingInboxMark(
    val id: String,
    val ids: List<String> = emptyList(),
    @SerialName("until_cursor") val untilCursor: Long? = null,
    val source: String? = null,
    val type: String? = null,
    val seen: Boolean? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
    /** When the person asked (ISO 8601): the time of the marks here; "all" reaches what was created until then. */
    val at: String,
) {
    val change: InboxMarkChange get() = InboxMarkChange(seen, archived, pinned)

    internal companion object {
        fun of(ids: List<String>, untilCursor: Long?, source: String?, type: String?, change: InboxMarkChange) = PendingInboxMark(
            id = java.util.UUID.randomUUID().toString(),
            ids = ids,
            untilCursor = untilCursor,
            source = source,
            type = type,
            seen = change.seen,
            archived = change.archived,
            pinned = change.pinned,
            at = java.time.Instant.now().toString(),
        )
    }
}

/**
 * The server's rules for marks, here: what a [PendingInboxMark] does to the
 * marks before the server answers (and the count it will likely give). A
 * waiting item is never archived nor marked seen; "archive all" keeps the
 * pinned ones.
 */
internal object MarkOverlay {
    fun apply(p: PendingInboxMark, marks: MutableMap<String, InboxMark>, view: InboxState, workspaceId: String?): InboxMarksResult {
        val all = p.ids.isEmpty()
        val wanted = p.ids.toHashSet()
        val until = millis(p.at)
        var applied = 0
        var unchanged = 0
        var waiting = 0
        var keptPinned = 0
        val reasons = HashMap<String, Int>()
        val found = HashSet<String>()
        for (n in view.notifications) {
            if (all) {
                if (workspaceId != null && n.workspaceId != workspaceId) continue
                if (p.source != null && n.source != p.source) continue
                if (p.type != null && n.type != p.type) continue
                val created = millis(n.createdAt)
                if (until != null && created != null && created > until) continue
            } else if (n.id !in wanted) {
                continue
            }
            found += n.id
            val cur = marks[n.id] ?: InboxMark(notificationId = n.id, workspaceId = n.workspaceId)
            var next = cur
            val isWaiting = view.isWaiting(n)
            var held = false
            p.seen?.let { on ->
                if (on && isWaiting) held = true else next = next.copy(seenAt = if (on) cur.seenAt ?: p.at else null)
            }
            p.archived?.let { on ->
                when {
                    on && isWaiting -> held = true
                    on && all && cur.pinned -> keptPinned++
                    on -> next = next.copy(archivedAt = cur.archivedAt ?: p.at)
                    else -> next = next.copy(archivedAt = null, autoArchived = false)
                }
            }
            p.pinned?.let { on -> next = next.copy(pinnedAt = if (on) cur.pinnedAt ?: p.at else null) }
            if (held) {
                waiting++
                n.waiting?.let { reasons[it.wire] = (reasons[it.wire] ?: 0) + 1 }
            }
            if (next != cur) {
                applied++
                marks[n.id] = next.copy(updatedAt = p.at)
            } else if (!held) {
                unchanged++
            }
        }
        return InboxMarksResult(
            applied = applied,
            unchanged = unchanged,
            waiting = waiting,
            waitingByReason = reasons,
            keptPinned = keptPinned,
            notFound = if (all) emptyList() else p.ids.filter { it !in found },
            complete = true,
        )
    }

    private fun millis(iso: String?): Long? = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
}
