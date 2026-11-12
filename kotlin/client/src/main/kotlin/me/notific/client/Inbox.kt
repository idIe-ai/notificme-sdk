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
    /** The person's marks (seen, archived, pinned) by notification id, the same on every device. */
    val marks: Map<String, InboxMark> = emptyMap(),
    /**
     * The person's inbox rules, as the feed sent them. After a full resync
     * older rules may be missing: call [Inbox.reloadRules] (own session only).
     */
    val rules: List<InboxRule> = emptyList(),
    /** Changes of marks made offline, already in [marks], still to be sent (they go when the network is back). */
    val queuedMarks: Int = 0,
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

    /**
     * Unread notifications in the inbox (not archived) that are not an open
     * decision (those count once, as a question). Unread = not opened and not
     * marked seen ([isUnread]).
     */
    val unreadCount: Int
        get() {
            val open = questions.mapTo(HashSet()) { it.id }
            return notifications.count { isUnread(it) && !isArchived(it) && (it.questionSetId == null || it.questionSetId !in open) }
        }

    /** The person's marks on [n], if any. */
    public fun markOf(n: Notification): InboxMark? = marks[n.id]

    /** Not opened on any device and not marked seen (docs/27, D2). */
    public fun isUnread(n: Notification): Boolean = n.unread && marks[n.id]?.seen != true

    /** Archived (by the person or a rule); a waiting item is never archived. */
    public fun isArchived(n: Notification): Boolean = marks[n.id]?.archived == true && !isWaiting(n)

    public fun isPinned(n: Notification): Boolean = marks[n.id]?.pinned == true

    /**
     * Still waiting for the person (the "Waiting for you" band): what the
     * server said ([Notification.waiting]), unless the question already ended
     * or the "got it" was given since.
     */
    public fun isWaiting(n: Notification): Boolean = when (n.waiting) {
        null -> false
        InboxWaiting.Decision, InboxWaiting.Questionnaire -> {
            val end = decisionEnd(n)
            end == null || (end == QuestionSetStatus.Unknown && n.status == "active")
        }
        InboxWaiting.Ack, InboxWaiting.Alert -> n.acknowledgedAt == null && n.status == "active"
    }

    /** Waiting items, newest first. */
    val waitingItems: List<Notification> get() = notifications.filter { isWaiting(it) }

    /** The rule that applies to [n]: its type's rule, else its service's; null = the defaults. */
    public fun ruleFor(n: Notification): InboxRule? =
        rules.firstOrNull { it.workspaceId == n.workspaceId && it.source == n.source && it.type == n.type }
            ?: rules.firstOrNull { it.workspaceId == n.workspaceId && it.source == n.source && it.type == null }

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
    /** See [InboxState.marks]. */
    val marks: List<InboxMark> = emptyList(),
    /** See [InboxState.rules]. */
    val rules: List<InboxRule> = emptyList(),
    /** Changes of marks made offline, still to be sent, in order. */
    @SerialName("pending_marks") val pendingMarks: List<PendingInboxMark> = emptyList(),
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
        // 2: [Notification.sourceIconUrl] (and [Notification.sourceName] for people).
        // 3: the icon comes from the service that sent it, not from the source text.
        // 4: [Notification.waiting] and the inbox marks and rules (docs/27).
        public const val SCHEMA: Int = 4
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
    private val marks = HashMap<String, InboxMark>()
    private val rules = LinkedHashMap<String, InboxRule>()

    /** Marks asked for and not yet taken by the server, oldest first; shown over [marks]. */
    private val pending = ArrayList<PendingInboxMark>()

    /**
     * "All" marks the server took and the next pull has not confirmed yet:
     * still shown over [marks], but never written into it (nor the cache),
     * because this device's guess of what "all" reached is only an estimate
     * (the server's real marks come with the pull).
     */
    private val settled = ArrayList<PendingInboxMark>()

    /** One sender of [pending] at a time, so changes reach the server in the order they were made. */
    private val sending = Mutex()
    private var cursor = 0L
    private var job: Job? = null
    private var restored = false
    private var saveJob: Job? = null

    /** The cache was from an older [InboxSnapshot.SCHEMA]: the first page from cursor 0 replaces it. */
    private var resync = false

    /** A reset or resync cleared [rules]: the ones older than the feed's horizon only come back from the server. */
    private var rulesCleared = false

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
            snap.marks.forEach { marks.putIfAbsent(it.notificationId, it) }
            snap.rules.forEach { rules.putIfAbsent(it.id, it) }
            if (pending.isEmpty()) pending.addAll(snap.pendingMarks)
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
        sendMarks()
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
        face: FaceProof? = null,
    ): ReplyAccepted {
        val accepted = client.reply(questionSet, answers, authMethod, totpCode, idempotencyKey, eSignatureSha256, face)
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

    /**
     * The decision ended on another device (answered on the phone or the
     * web, told by a companion link): it leaves the open list now, before
     * the next sync confirms it.
     */
    public suspend fun questionEnded(questionSetId: String, status: QuestionSetStatus = QuestionSetStatus.Answered) {
        // Restored first, so a later restore does not bring it back from the cache.
        restore()
        lock.withLock {
            ended[questionSetId] = status
            mutable.update { s -> s.copy(questions = s.questions.filterNot { it.id == questionSetId }) }
            publish()
        }
    }

    /**
     * Marks notifications as read, here and on the server: only when the
     * person opened them (it is evidence, and the sender sees "read"). To
     * tidy the inbox use [mark] and [markAll].
     */
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

    /**
     * Sets or clears the person's marks (seen, archived, pinned) on these
     * notifications, on the server; the change comes back through the feed,
     * on every device. Waiting items are never archived nor marked seen
     * ([InboxMarksResult.waiting]). No evidence, no read receipt.
     *
     * It shows here at once. Offline it waits in a queue (kept with the
     * cache) and goes with the next [refresh] or connection, in order; the
     * result is then this inbox's estimate, with [InboxMarksResult.queued].
     * A refusal of the server throws [NotificMeException] and the change is
     * undone here.
     */
    public suspend fun mark(notificationIds: List<String>, change: InboxMarkChange): InboxMarksResult {
        val ids = notificationIds.distinct()
        if (ids.isEmpty()) return InboxMarksResult()
        return submit(PendingInboxMark.of(ids, null, null, null, change))
    }

    /**
     * Sets or clears the marks on everything this inbox already has ("mark
     * all as seen", "archive all"), optionally only one service or type:
     * up to the current cursor, so what arrives meanwhile stays. "Archive
     * all" keeps pinned items; waiting items stay. Offline, as in [mark].
     */
    public suspend fun markAll(change: InboxMarkChange, source: String? = null, type: String? = null): InboxMarksResult {
        val until = lock.withLock { cursor }
        if (until <= 0) return InboxMarksResult()
        return submit(PendingInboxMark.of(emptyList(), until, source, type, change))
    }

    private suspend fun submit(p: PendingInboxMark): InboxMarksResult {
        val estimate = lock.withLock {
            val r = MarkOverlay.apply(p, HashMap(mutable.value.marks), overlayState(), workspaceId)
            pending += p
            publish()
            r
        }
        val sent = sendMarks()
        sent.failed[p.id]?.let { throw it }
        val r = sent.done[p.id] ?: return estimate.copy(queued = true)
        guard { pull() }
        return r
    }

    private class Sent(val done: Map<String, InboxMarksResult>, val failed: Map<String, NotificMeException>)

    /**
     * Sends the queued marks in order. Offline (or the server failing) it
     * stops and keeps them for later; a refusal (4xx) drops that change.
     */
    private suspend fun sendMarks(): Sent = sending.withLock {
        val done = HashMap<String, InboxMarksResult>()
        val failed = HashMap<String, NotificMeException>()
        while (true) {
            val p = lock.withLock { pending.firstOrNull() } ?: break
            val r = try {
                sendMark(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotificMeException) {
                if (laterAgain(e)) break
                lock.withLock {
                    pending.remove(p)
                    publish()
                }
                failed[p.id] = e
                continue
            }
            lock.withLock {
                pending.remove(p)
                // Kept as the server will send it back, so nothing blinks before the next pull.
                // "All" stays only an overlay: what the server really reached comes with the pull.
                if (p.ids.isEmpty() && p.untilCursor != null) settled += p
                else MarkOverlay.apply(p, marks, overlayState(), workspaceId)
                publish()
            }
            done[p.id] = r
        }
        Sent(done, failed)
    }

    private suspend fun sendMark(p: PendingInboxMark): InboxMarksResult {
        var total = InboxMarksResult()
        val until = p.untilCursor
        if (p.ids.isEmpty() && until != null) {
            var rounds = 0
            var after: String? = null
            do {
                val r = client.markInboxAll(until, p.change, workspaceId, p.source, p.type, after)
                // With next_after each round goes on from where the last one stopped and
                // the counts add up; without it (an older server) the same items come back,
                // so waiting, unchanged and kept_pinned keep the largest count.
                total = if (rounds == 0 || after != null || r.nextAfter != null) total + r else total.repeated(r)
                after = r.nextAfter ?: after
            } while (!r.complete && ++rounds < MAX_PAGES)
        } else {
            for (chunk in p.ids.chunked(MAX_MARK_IDS)) total = total + client.markInbox(chunk, p.change)
        }
        return total
    }

    /** Worth trying again later: no network, a timeout, the server failing or busy, the session to renew. */
    private fun laterAgain(e: NotificMeException): Boolean =
        e.code == ErrorCodes.NETWORK_ERROR || e.code == ErrorCodes.TIMEOUT || e.code == ErrorCodes.UNAUTHORIZED ||
            e.code == ErrorCodes.RATE_LIMITED || (e is NotificMeApiException && e.status >= 500)

    /** What [MarkOverlay] needs to tell waiting items (call with [lock] held). */
    private fun overlayState(): InboxState = InboxState(
        notifications = byId.values.toList(),
        questions = mutable.value.questions,
        ended = HashMap(ended),
    )

    /**
     * Creates or adjusts the person's rule for a service (and optionally a
     * type), the same on every device; see [NotificClient.saveInboxRule].
     * Online only: it throws [NotificMeException] offline.
     */
    public suspend fun saveRule(
        workspaceId: String,
        source: String,
        type: String? = null,
        group: Boolean? = null,
        mute: Boolean? = null,
        autoArchiveAfter: Int? = null,
        clearAutoArchive: Boolean = false,
    ): InboxRule {
        val r = client.saveInboxRule(workspaceId, source, type, group, mute, autoArchiveAfter, clearAutoArchive)
        lock.withLock {
            rules[r.id] = r
            publish()
        }
        return r
    }

    /** Deletes a rule (the service goes back to the defaults). Online only. */
    public suspend fun deleteRule(id: String) {
        try {
            client.deleteInboxRule(id)
        } catch (e: NotificMeApiException) {
            if (e.status != 404) throw e
        }
        lock.withLock {
            rules.remove(id)
            publish()
        }
    }

    /** Loads every rule of the person again (after a full resync). Own session only. */
    public suspend fun reloadRules() {
        val list = client.inboxRules(workspaceId)
        lock.withLock {
            rules.clear()
            list.forEach { rules[it.id] = it }
            publish()
        }
    }

    private suspend fun run() {
        restore()
        mutable.update { it.copy(loading = it.notifications.isEmpty() && it.questions.isEmpty(), connection = Connection.Connecting) }
        sendMarks()
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
                        // Back online: what was marked offline goes now.
                        if (lock.withLock { pending.isNotEmpty() } && sendMarks().done.isNotEmpty()) guard { pull() }
                        guard { loadQuestions() }
                    }
                    // Marks or rules changed elsewhere (another device, a rule): read what changed.
                    is RealtimeEvent.InboxChanged -> guard { pull() }
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
        // The "all" marks the server had taken before this pull began are in what it reads.
        val confirmed = lock.withLock { settled.toList() }
        var pages = 0
        do {
            val page = client.sync(lock.withLock { cursor })
            apply(page)
            // A reset already cleared the list and moved the cursor to 0: read again from there.
        } while ((page.hasMore || page.reset) && ++pages < MAX_PAGES)
        if (confirmed.isNotEmpty()) {
            lock.withLock {
                settled.removeAll(confirmed.toSet())
                publish()
            }
        }
        // Rules older than the feed's horizon left with the reset: load them all again,
        // or a muted service would ring again until the next start.
        if (lock.withLock { rulesCleared.also { rulesCleared = false } }) {
            try {
                reloadRules()
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotificMeException) {
                // A delegated session has no personal rules; offline, the next pull tries again.
                if (laterAgain(e)) lock.withLock { rulesCleared = true }
            }
        }
    }

    private suspend fun apply(page: SyncPage) {
        var questionsChanged = false
        // Votes on open questions (decision rules): shown at once, before the list reloads.
        val votes = HashMap<String, DecisionView>()
        lock.withLock {
            if (page.reset || resync) {
                byId.clear()
                marks.clear()
                rules.clear()
                settled.clear()
                resync = false
                rulesCleared = true
            }
            // The server no longer knows this cursor: start over from 0 (a cursor only
            // ever comes from next_cursor or "ready", never from a live delivery's seq).
            if (page.reset) cursor = 0
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
                    SyncEntity.InboxMark -> {
                        val data = c.data
                        if (c.op == SyncOp.Delete || data == null) {
                            marks.remove(c.id)
                        } else {
                            runCatching { Wire.json.decodeFromJsonElement(InboxMark.serializer(), data) }
                                .onSuccess { marks[it.notificationId.ifEmpty { c.id }] = it }
                        }
                    }
                    SyncEntity.InboxRule -> {
                        val data = c.data
                        if (c.op == SyncOp.Delete || data == null) {
                            rules.remove(c.id)
                        } else {
                            runCatching { Wire.json.decodeFromJsonElement(InboxRule.serializer(), data) }
                                .onSuccess { rules[it.id] = it }
                        }
                    }
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
        // Marks of notifications no longer here go too (a mark never comes before its notification).
        marks.keys.retainAll(byId.keys)
        val endedNow = HashMap(ended)
        val marksNow = HashMap(marks)
        // What was asked and the server did not take yet shows already (offline too).
        if (pending.isNotEmpty() || settled.isNotEmpty()) {
            val view = InboxState(notifications = list, questions = mutable.value.questions, ended = endedNow)
            settled.forEach { MarkOverlay.apply(it, marksNow, view, workspaceId) }
            pending.forEach { MarkOverlay.apply(it, marksNow, view, workspaceId) }
        }
        val rulesNow = rules.values.toList()
        val queued = pending.size
        mutable.update { it.copy(notifications = list, ended = endedNow, marks = marksNow, rules = rulesNow, queuedMarks = queued) }
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
                marks = marks.values.toList(),
                rules = rules.values.toList(),
                pendingMarks = pending.toList(),
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

    private operator fun InboxMarksResult.plus(o: InboxMarksResult): InboxMarksResult = InboxMarksResult(
        applied = applied + o.applied,
        unchanged = unchanged + o.unchanged,
        waiting = waiting + o.waiting,
        waitingByReason = (waitingByReason.keys + o.waitingByReason.keys).associateWith { (waitingByReason[it] ?: 0) + (o.waitingByReason[it] ?: 0) },
        keptPinned = keptPinned + o.keptPinned,
        notFound = notFound + o.notFound,
        complete = o.complete,
        queued = queued || o.queued,
    )

    /** [o] counted the same items again: only the applied and the not found add up. */
    private fun InboxMarksResult.repeated(o: InboxMarksResult): InboxMarksResult = InboxMarksResult(
        applied = applied + o.applied,
        unchanged = maxOf(unchanged, o.unchanged),
        waiting = maxOf(waiting, o.waiting),
        waitingByReason = (waitingByReason.keys + o.waitingByReason.keys).associateWith { maxOf(waitingByReason[it] ?: 0, o.waitingByReason[it] ?: 0) },
        keptPinned = maxOf(keptPinned, o.keptPinned),
        notFound = notFound + o.notFound,
        complete = o.complete,
        queued = queued || o.queued,
    )

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
