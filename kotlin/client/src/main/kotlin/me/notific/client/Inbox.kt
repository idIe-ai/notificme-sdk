package me.notific.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Realtime connection of an [Inbox]. */
public enum class Connection { Offline, Connecting, Live }

public data class InboxState(
    /** Newest first. */
    val notifications: List<Notification> = emptyList(),
    /** Open question sets, the one that times out first on top. */
    val questions: List<QuestionSet> = emptyList(),
    val loading: Boolean = true,
    val connection: Connection = Connection.Offline,
    /** Last error; `unauthorized` means the person must sign in again. */
    val error: NotificMeException? = null,
    /**
     * How the decisions that are no longer open ended (question set id →
     * answered, timeout, cancelled…), from the feed or asked once.
     */
    val ended: Map<String, QuestionSetStatus> = emptyMap(),
) {
    /**
     * How the decision carried by [n] ended: null for a plain notification or
     * an open decision; [QuestionSetStatus.Unknown] while it is not known yet.
     * A decision that ran out ends as [QuestionSetStatus.Timeout].
     */
    public fun decisionEnd(n: Notification): QuestionSetStatus? {
        val id = n.questionSetId ?: return null
        if (questions.any { it.id == id }) return null
        ended[id]?.let { return it }
        return when (n.status) {
            "expired" -> QuestionSetStatus.Timeout
            "cancelled" -> QuestionSetStatus.Cancelled
            "resolved" -> QuestionSetStatus.Answered
            else -> QuestionSetStatus.Unknown
        }
    }

    /** Unread notifications that are not an open decision (those count once, as a question). */
    val unreadCount: Int
        get() {
            val open = questions.mapTo(HashSet()) { it.id }
            return notifications.count { it.unread && (it.questionSetId == null || it.questionSetId !in open) }
        }

    /** The notification that carries [questionSetId], if any. */
    public fun notificationFor(questionSetId: String): Notification? =
        notifications.firstOrNull { it.questionSetId == questionSetId }
}

/** A `delivery` entity of the sync feed. */
@Serializable
internal data class DeliveryChange(
    val id: String,
    @SerialName("notification_id") val notificationId: String,
    val channel: String? = null,
    val status: String? = null,
    val seq: Long = 0,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("delivered_at") val deliveredAt: String? = null,
    @SerialName("read_at") val readAt: String? = null,
)

/** What an [InboxCache] keeps between launches. */
@Serializable
public data class InboxSnapshot(
    val cursor: Long = 0,
    val notifications: List<Notification> = emptyList(),
    val questions: List<QuestionSet> = emptyList(),
    @SerialName("user_id") val userId: String? = null,
    /** See [InboxState.ended]. */
    val ended: Map<String, QuestionSetStatus> = emptyMap(),
    /**
     * Version of what a cached notification carries. A snapshot saved by an
     * older SDK (missing = 0) is shown at once but synced again from the start
     * once, since the feed only sends what changed (fields added later, like
     * [Notification.sourceName], would never reach the items kept here).
     */
    val schema: Int = 0,
) {
    public companion object {
        /** Bump when [Notification] gains a field the feed sends: caches older than it resync once. */
        public const val SCHEMA: Int = 1
    }
}

/**
 * The part of a `question_set` change of the feed that says whether it is
 * still open and, with a decision rule, the votes so far.
 */
@Serializable
internal data class QuestionSetChange(
    val id: String = "",
    val status: QuestionSetStatus = QuestionSetStatus.Unknown,
    val decision: DecisionView? = null,
)

/**
 * Where an [Inbox] keeps its last state, so the app opens with it at once
 * (and offline) and the next sync only asks for what changed.
 */
public interface InboxCache {
    public suspend fun load(): InboxSnapshot?
    public suspend fun save(snapshot: InboxSnapshot)
    public suspend fun clear()
}

/**
 * The person's inbox kept up to date: a full sync, then the realtime feed
 * (with catch-up on reconnection). Observe [state]; call [start] when the
 * screen is visible and [stop] when it is not (push wakes the app otherwise).
 *
 * [workspaceId] limits it to one workspace (a token issued by a company is
 * already limited to the company's workspace). With a [cache] it starts from
 * the last saved state and saves after every change.
 */
public class Inbox(
    private val client: NotificClient,
    private val scope: CoroutineScope,
    public val workspaceId: String? = null,
    private val cache: InboxCache? = null,
) {
    private val mutable = MutableStateFlow(InboxState())
    public val state: StateFlow<InboxState> = mutable.asStateFlow()

    private val lock = Mutex()
    private val byId = LinkedHashMap<String, Notification>()
    private val ended = HashMap<String, QuestionSetStatus>()
    private var cursor = 0L
    private var job: Job? = null
    private var restored = false
    private var saveJob: Job? = null

    /** The cache was from an older [InboxSnapshot.SCHEMA]: the first page from cursor 0 replaces it. */
    private var resync = false

    /**
     * Loads the cached state, if any (once; [start] and [refresh] call it).
     * Call it early to show the last inbox before the network answers.
     */
    public suspend fun restore() {
        val c = cache ?: return
        lock.withLock {
            if (restored) return
            restored = true
            val snap = runCatching { c.load() }.getOrNull() ?: return
            snap.notifications.forEach { byId[it.id] = it }
            snap.ended.forEach { (id, status) -> ended.putIfAbsent(id, status) }
            // An older cache: shown now, but the next pull starts over (cursor 0)
            // and replaces it, as a first sync on a new install would.
            if (snap.schema < InboxSnapshot.SCHEMA) resync = true
            else if (snap.cursor > cursor) cursor = snap.cursor
            mutable.update { it.copy(questions = snap.questions, loading = snap.notifications.isEmpty() && snap.questions.isEmpty()) }
            publish()
        }
    }

    /** Starts (or keeps) syncing and listening. */
    public fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    public fun stop() {
        job?.cancel()
        job = null
        mutable.update { it.copy(connection = Connection.Offline) }
    }

    /** Pulls what changed since the last sync and reloads the open questions. */
    public suspend fun refresh() {
        restore()
        guard {
            pull()
            loadQuestions()
        }
    }

    /**
     * Answers a question set and drops it from the open list. A vote that
     * does not close a question with a decision rule keeps it open, with the
     * person's vote in [QuestionSet.decision] (they may not vote again). See
     * [NotificClient.reply].
     */
    public suspend fun reply(
        questionSet: QuestionSet,
        answers: List<Answer>,
        authMethod: AuthMethod = if (questionSet.level <= 0) AuthMethod.Tap else AuthMethod.Unlock,
        totpCode: String? = null,
        idempotencyKey: String = java.util.UUID.randomUUID().toString(),
        eSignatureSha256: String? = null,
    ): ReplyAccepted {
        val accepted = client.reply(questionSet, answers, authMethod, totpCode, idempotencyKey, eSignatureSha256)
        val decision = accepted.decision
        mutable.update { s ->
            if (!accepted.isClosed && decision != null) {
                s.copy(
                    questions = s.questions.map { q ->
                        if (q.id != questionSet.id) q
                        else q.copy(decision = decision.afterMyVote(q.decision ?: questionSet.decision), answerable = false, answerableWithTotp = false)
                    },
                )
            } else {
                s.copy(questions = s.questions.filterNot { it.id == questionSet.id })
            }
        }
        guard { pull() }
        return accepted
    }

    /** Marks notifications as read, here and on the server. */
    public suspend fun markOpened(notificationIds: List<String>) {
        if (notificationIds.isEmpty()) return
        val now = java.time.Instant.now().toString()
        lock.withLock {
            for (id in notificationIds) {
                val n = byId[id] ?: continue
                byId[id] = n.copy(deliveries = n.deliveries.map { it.copy(readAt = it.readAt ?: now) })
            }
            publish()
        }
        guard { client.markOpened(notificationIds) }
    }

    /**
     * "Got it" on critical alerts or messages ([Notification.asksForAck]). The
     * server records it first (it is evidence, so a failure throws
     * [NotificMeException]); then they are read and acknowledged here too.
     */
    public suspend fun acknowledge(notificationIds: List<String>) {
        if (notificationIds.isEmpty()) return
        client.acknowledge(notificationIds)
        val now = java.time.Instant.now().toString()
        lock.withLock {
            for (id in notificationIds) {
                val n = byId[id] ?: continue
                byId[id] = n.copy(
                    acknowledgedAt = n.acknowledgedAt ?: now,
                    deliveries = n.deliveries.map { it.copy(readAt = it.readAt ?: now) },
                )
            }
            publish()
        }
    }

    private suspend fun run() {
        restore()
        mutable.update { it.copy(loading = it.notifications.isEmpty() && it.questions.isEmpty(), connection = Connection.Connecting) }
        val ok = guard {
            pull()
            loadQuestions()
        }
        mutable.update { it.copy(loading = false) }
        if (!ok && state.value.error?.code == "unauthorized") return
        try {
            client.realtime { cursor }.collect { event ->
                when (event) {
                    is RealtimeEvent.Connected -> mutable.update { it.copy(connection = Connection.Connecting) }
                    is RealtimeEvent.Changes -> apply(event.page)
                    is RealtimeEvent.Ready -> {
                        lock.withLock { if (event.cursor > cursor) cursor = event.cursor }
                        mutable.update { it.copy(connection = Connection.Live, error = null) }
                        guard { loadQuestions() }
                    }
                    is RealtimeEvent.Delivered -> guard {
                        pull()
                        if (event.delivery.questionSetId != null) loadQuestions()
                    }
                    is RealtimeEvent.Disconnected -> mutable.update { it.copy(connection = Connection.Connecting) }
                    is RealtimeEvent.ServerError -> Unit
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotificMeException) {
            mutable.update { it.copy(connection = Connection.Offline, error = e) }
        }
    }

    /** Reads the feed from the current cursor until it is caught up. */
    private suspend fun pull() {
        var pages = 0
        do {
            val page = client.sync(lock.withLock { cursor })
            apply(page)
        } while (page.hasMore && ++pages < MAX_PAGES)
    }

    private suspend fun apply(page: SyncPage) {
        var questionsChanged = false
        // Votes on open questions (decision rules): shown at once, before the list reloads.
        val votes = HashMap<String, DecisionView>()
        lock.withLock {
            if (page.reset || resync) {
                byId.clear()
                resync = false
            }
            for (c in page.changes) {
                if (workspaceId != null && c.workspaceId.isNotEmpty() && c.workspaceId != workspaceId) continue
                when (c.entity) {
                    SyncEntity.Notification -> {
                        val data = c.data
                        if (c.op == SyncOp.Delete || data == null) {
                            byId.remove(c.id)
                        } else {
                            runCatching { Wire.json.decodeFromJsonElement(Notification.serializer(), data) }
                                .onSuccess { byId[c.id] = it }
                        }
                    }
                    // A delivery changed (read on another device, delivered…): update it in its notification.
                    SyncEntity.Delivery -> c.data?.let { applyDelivery(it) }
                    SyncEntity.QuestionSet -> {
                        questionsChanged = true
                        val change = c.data?.let { runCatching { Wire.json.decodeFromJsonElement(QuestionSetChange.serializer(), it) }.getOrNull() }
                        val id = change?.id?.ifEmpty { c.id } ?: c.id
                        // Answered, ran out or cancelled: keep how it ended for its notification.
                        change?.takeIf { it.status != QuestionSetStatus.Open && it.status != QuestionSetStatus.Unknown }
                            ?.let { ended[id] = it.status }
                        change?.decision?.takeIf { change.status == QuestionSetStatus.Open }?.let { votes[id] = it }
                    }
                    SyncEntity.AnswerSet -> questionsChanged = true
                    else -> Unit
                }
            }
            if (page.nextCursor > cursor) cursor = page.nextCursor
            publish()
        }
        if (votes.isNotEmpty()) {
            mutable.update { s ->
                s.copy(questions = s.questions.map { q -> votes[q.id]?.let { d -> q.copy(decision = d.withViewerOf(q.decision)) } ?: q })
            }
        }
        if (questionsChanged) guard { loadQuestions() }
    }

    private fun applyDelivery(data: kotlinx.serialization.json.JsonElement) {
        val d = runCatching { Wire.json.decodeFromJsonElement(DeliveryChange.serializer(), data) }.getOrNull() ?: return
        val n = byId[d.notificationId] ?: return
        val state = DeliveryState(id = d.id, channel = d.channel, readAt = d.readAt, status = d.status, seq = d.seq, sentAt = d.sentAt, deliveredAt = d.deliveredAt)
        val i = n.deliveries.indexOfFirst { it.id == d.id }
        val list = if (i >= 0) n.deliveries.toMutableList().also { it[i] = state } else n.deliveries + state
        byId[d.notificationId] = n.copy(deliveries = list)
    }

    private suspend fun loadQuestions() {
        val open = client.questionSets(QuestionSetStatus.Open)
            .filter { workspaceId == null || it.workspaceId == workspaceId }
            .sortedWith(compareBy<QuestionSet> { it.timeoutAt ?: "9999" }.thenByDescending { it.createdAt })
        mutable.update { it.copy(questions = open) }
        scheduleSave()
        learnEnded(open)
    }

    /**
     * Decisions that left the open list before this inbox kept how they ended
     * (an older cache, or a change missed): asks the server once each, newest first.
     */
    private suspend fun learnEnded(open: List<QuestionSet>) {
        val openIds = open.mapTo(HashSet()) { it.id }
        val unknown = lock.withLock {
            byId.values.asSequence()
                .filter { n -> n.questionSetId.let { it != null && it !in openIds && it !in ended } && n.status !in CLOSED_STATUSES }
                .sortedByDescending { it.createdAt }
                .mapNotNull { it.questionSetId }
                .distinct()
                .take(MAX_LOOKUPS)
                .toList()
        }
        if (unknown.isEmpty()) return
        for (id in unknown) {
            val status = try {
                client.questionSet(id).status
            } catch (e: NotificMeApiException) {
                // Gone or not visible any more: don't ask again. Anything else: next time.
                if (e.status in GONE) QuestionSetStatus.Unknown else return
            } catch (e: NotificMeException) {
                return // offline: next time
            }
            if (status == QuestionSetStatus.Open) continue
            lock.withLock {
                ended[id] = status
                publish()
            }
        }
    }

    private fun publish() {
        val list = byId.values.sortedByDescending { it.createdAt }
        // Only what still has a notification here is worth keeping.
        val referenced = list.mapNotNullTo(HashSet()) { it.questionSetId }
        ended.keys.retainAll(referenced)
        val endedNow = HashMap(ended)
        mutable.update { it.copy(notifications = list, ended = endedNow) }
        scheduleSave()
    }

    /** Saves shortly after the last change (a burst of changes is saved once). */
    private fun scheduleSave() {
        val c = cache ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MILLIS)
            save(c)
        }
    }

    /** Saves the current state now (call before the process may die, e.g. at the end of a background sync). */
    public suspend fun flush() {
        val c = cache ?: return
        saveJob?.cancel()
        save(c)
    }

    private suspend fun save(c: InboxCache) {
        val snap = lock.withLock {
            InboxSnapshot(
                cursor = cursor,
                notifications = byId.values.toList(),
                questions = state.value.questions,
                ended = HashMap(ended),
                // Still owed a full sync (offline since the update): saved as old, so it stays owed.
                schema = if (resync) 0 else InboxSnapshot.SCHEMA,
            )
        }
        runCatching { c.save(snap) }
    }

    /** Runs [block]; a [NotificMeException] goes to [InboxState.error] instead of crashing. */
    private suspend fun guard(block: suspend () -> Unit): Boolean = try {
        block()
        mutable.update { if (it.error != null) it.copy(error = null) else it }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: NotificMeException) {
        mutable.update { it.copy(error = e) }
        false
    }

    private companion object {
        const val MAX_PAGES = 50
        const val SAVE_DELAY_MILLIS = 400L

        /** Lookups of how older decisions ended, per reload of the open list. */
        const val MAX_LOOKUPS = 20

        /** Notification statuses that already say how its decision ended (see [InboxState.decisionEnd]). */
        val CLOSED_STATUSES = setOf("expired", "cancelled", "resolved")

        /** HTTP statuses that mean the question set is not there for this person any more. */
        val GONE = setOf(403, 404, 410)
    }
}
