package me.notific.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Decision rules: the partial vote state, voting and the feed. */
class DecisionTest {
    private val requests = CopyOnWriteArrayList<HttpRequestData>()

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): NotificClient {
        val engine = MockEngine { req ->
            requests += req
            handler(req)
        }
        return NotificClient(TokenProvider { "nm_ses_test" }, NotificClient.Config(baseUrl = "https://api.test", httpClient = HttpClient(engine)))
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private val anaVote = """{"user_id":"u1","handle":"ana","display_name":"Ana","approve":true,"at":"2026-11-09T14:32:00Z","group":"#finance","authority_level":"manager","authority_level_name":"Manager"}"""
    private val myVote = """{"user_id":"u2","handle":"bruno","display_name":"Bruno","approve":true,"at":"2026-11-09T14:40:00Z","group":"#finance","authority_level":"director","authority_level_name":"Director"}"""

    /** Bruno's view of an open quorum of 2 that Ana already approved. */
    private val openQs = """{"id":"q1","workspace_id":"w1","status":"open","version":1,"level":1,"title":"Pay invoice 42?",
        "questions":[{"id":"ok","type":"yesno","text":"Approve?"}],"answerable":true,
        "decision":{"rule":"quorum","required":2,"eligible":3,"approvals":1,"rejections":0,"pending":2,"by":["#finance>=manager"],
          "votes":[$anaVote],"escalation_step":0,"escalation_steps":1,"next_escalation_at":"2026-11-09T15:00:00Z",
          "can_decide":true,"my_group":"#finance","my_authority_level":"Director"}}"""

    @Test
    fun partialStateDecodes() {
        val qs = Wire.json.decodeFromString(QuestionSet.serializer(), openQs)
        val d = assertNotNull(qs.decision)
        assertEquals(DecisionRule.Quorum, d.rule)
        assertEquals(1, d.missing)
        assertFalse(d.closed)
        assertEquals("@ana", d.lastApproval?.mention)
        assertEquals("Manager", d.votes.single().authorityLevelName)
        assertEquals(listOf("#finance>=manager"), d.by)
        assertEquals("2026-11-09T15:00:00Z", d.nextEscalationAt)
        assertEquals("#finance", d.myGroup)
        assertTrue(qs.canDecide)
    }

    @Test
    fun watchersAndClosedDecisions() {
        val watching = Wire.json.decodeFromString(
            QuestionSet.serializer(),
            """{"id":"q1","status":"open","answerable":false,"decision":{"rule":"each_group","required":2,"by":["#finance","#legal"],"votes":[],"can_decide":false,"reason":"not_eligible"}}""",
        )
        assertFalse(watching.canDecide)
        assertEquals(DecisionView.REASON_NOT_ELIGIBLE, watching.decision?.reason)
        assertEquals(DecisionRule.EachGroup, watching.decision?.rule)

        val closed = Wire.json.decodeFromString(
            DecisionView.serializer(),
            """{"rule":"majority","required":2,"eligible":3,"approvals":2,"rejections":1,"pending":0,"outcome":"approved","votes":[$anaVote,$myVote],"decided_by":$myVote}""",
        )
        assertTrue(closed.closed)
        assertEquals(DecisionOutcome.Approved, closed.outcome)
        assertEquals("Bruno", closed.decidedBy?.name)

        // A rule from a newer server does not break the question.
        val future = Wire.json.decodeFromString(DecisionView.serializer(), """{"rule":"weighted","required":3,"outcome":"postponed"}""")
        assertEquals(DecisionRule.Unknown, future.rule)
        assertNull(future.outcome)
        // A question without a rule has no decision and anyone in "to" answers.
        assertTrue(Wire.json.decodeFromString(QuestionSet.serializer(), """{"id":"q2"}""").canDecide)
    }

    @Test
    fun signedReplyCarriesTheRuleAndVotes() {
        val reply = Wire.json.decodeFromString(
            Reply.serializer(),
            """{"question_set_id":"q1","status":"rejected","answers":[],"answered_by":{"identity":"ana@acme.com","level_used":1,"auth_method":"unlock","group":"#finance","authority_level":"manager"},
               "decision":{"rule":"quorum","required":2,"eligible":2,"approvals":0,"rejections":1,"outcome":"rejected","by":["#finance"],"escalation_step":1},
               "votes":[{"identity":"ana@acme.com","handle":"ana","approve":false,"answered_at":"2026-11-09T14:32:00Z","level_used":1,"auth_method":"unlock","group":"#finance","authority_level":"manager"}]}""",
        )
        assertEquals(ReplyStatus.Rejected, reply.status)
        assertEquals("#finance", reply.answeredBy?.group)
        assertEquals(DecisionOutcome.Rejected, reply.decision?.outcome)
        assertEquals(1, reply.decision?.escalationStep)
        assertFalse(reply.votes.single().approve)
    }

    @Test
    fun aVoteThatDoesNotCloseKeepsTheQuestionOpen() = runBlocking<Unit> {
        var voted = false
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/replies" -> {
                    voted = true
                    json(
                        """{"status":"accepted","question_set_id":"q1","answer_set_id":"a2","closed":false,
                        "decision":{"rule":"quorum","required":2,"eligible":3,"approvals":2,"rejections":0,"pending":1,"votes":[$anaVote,$myVote],"escalation_step":0,"escalation_steps":1}}""",
                    )
                }
                "/v0/sync" -> json("""{"changes":[],"next_cursor":1,"has_more":false,"reset":false}""")
                // Before the vote, the open list has it answerable; the reload after it is not asked here.
                "/v0/question-sets" -> json("""{"question_sets":[$openQs]}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.refresh()
        val qs = inbox.state.value.questions.single()
        val accepted = inbox.reply(qs, listOf(Answer.yesNo("ok", true)))
        assertTrue(voted)
        assertFalse(accepted.isClosed)
        assertNull(accepted.reply)
        val after = inbox.state.value.questions.single()
        assertFalse(after.answerable)
        assertFalse(after.canDecide)
        val d = assertNotNull(after.decision)
        assertEquals(2, d.approvals)
        assertEquals(DecisionView.REASON_ALREADY_VOTED, d.reason)
        // The vote that is new is the person's own.
        assertEquals("u2", d.myVote?.userId)
        assertEquals("Director", d.myAuthorityLevel)
    }

    @Test
    fun theVoteThatClosesDropsTheQuestion() = runBlocking<Unit> {
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/replies" -> json(
                    """{"status":"accepted","question_set_id":"q1","answer_set_id":"a2","closed":true,
                    "reply":{"question_set_id":"q1","status":"answered","answers":[]},
                    "decision":{"rule":"quorum","required":2,"approvals":2,"outcome":"approved","votes":[$anaVote,$myVote],"decided_by":$myVote}}""",
                )
                "/v0/sync" -> json("""{"changes":[],"next_cursor":1,"has_more":false,"reset":false}""")
                "/v0/question-sets" -> json("""{"question_sets":[$openQs]}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.refresh()
        val accepted = inbox.reply(inbox.state.value.questions.single(), listOf(Answer.yesNo("ok", true)))
        assertTrue(accepted.isClosed)
        assertEquals(ReplyStatus.Answered, accepted.reply?.status)
        assertTrue(inbox.state.value.questions.isEmpty())
    }

    @Test
    fun aReplyWithoutARuleIsClosed() {
        val r = Wire.json.decodeFromString(
            ReplyAccepted.serializer(),
            """{"status":"accepted","question_set_id":"q1","answer_set_id":"a1","reply":{"question_set_id":"q1","status":"answered"}}""",
        )
        assertTrue(r.isClosed)
        assertNull(r.decision)
    }

    @Test
    fun votesFromTheFeedShowAtOnceKeepingTheViewersOwnFields() = runBlocking<Unit> {
        var listed = 0
        val c = client { req ->
            when (req.url.encodedPath) {
                "/v0/sync" -> when (req.url.parameters["cursor"]) {
                    "0" -> json("""{"changes":[],"next_cursor":1,"has_more":false,"reset":false}""")
                    else -> json(
                        """{"changes":[{"seq":2,"entity":"question_set","id":"q1","op":"upsert","workspace_id":"w1",
                        "data":{"id":"q1","status":"open","decision":{"rule":"quorum","required":2,"eligible":3,"approvals":1,"rejections":1,"pending":1,
                          "votes":[$anaVote,{"user_id":"u3","display_name":"Caio","approve":false,"at":"2026-11-09T14:35:00Z"}],"escalation_step":0,"escalation_steps":1}}}],
                        "next_cursor":2,"has_more":false,"reset":false}""",
                    )
                }
                "/v0/question-sets" -> {
                    listed++
                    // The reload fails (offline): what the feed said stays.
                    if (listed == 1) json("""{"question_sets":[$openQs]}""") else json("""{"error":{"code":"unavailable"}}""", HttpStatusCode.BadRequest)
                }
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val inbox = Inbox(c, CoroutineScope(Dispatchers.Default))
        inbox.refresh()
        inbox.refresh()
        val d = assertNotNull(inbox.state.value.questions.single().decision)
        assertEquals(1, d.rejections)
        assertEquals(2, d.votes.size)
        // The feed's summary has no viewer fields: Bruno still may decide, as Director.
        assertEquals(true, d.canDecide)
        assertEquals("Director", d.myAuthorityLevel)
    }

    @Test
    fun voteErrorsHaveStableCodes() = runBlocking<Unit> {
        val c = client { json("""{"error":{"code":"already_voted"}}""", HttpStatusCode.Conflict) }
        val e = assertFailsWith<NotificMeApiException> { c.reply(QuestionSet(id = "q1"), listOf(Answer.yesNo("ok", true))) }
        assertEquals(ErrorCodes.ALREADY_VOTED, e.code)
    }

    @Test
    fun summaryAfterAVoteAndFromTheFeed() {
        val before = DecisionView(rule = DecisionRule.Quorum, required = 2, approvals = 1, votes = listOf(Vote("u1", approve = true)), canDecide = true, myGroup = "#board")
        val after = DecisionView(rule = DecisionRule.Quorum, required = 2, approvals = 2, votes = listOf(Vote("u1", approve = true), Vote("u2", approve = true)))
        val mine = after.afterMyVote(before)
        assertEquals("u2", mine.myVote?.userId)
        assertEquals(false, mine.canDecide)
        assertEquals("#board", mine.myGroup)

        val closed = after.copy(outcome = DecisionOutcome.Approved).withViewerOf(mine)
        assertEquals(DecisionView.REASON_CLOSED, closed.reason)
        assertEquals("u2", closed.myVote?.userId)
        // A full view (with can_decide) is never overwritten.
        val full = after.copy(canDecide = true)
        assertEquals(full, full.withViewerOf(mine))
    }

    @Test
    fun groupTags() {
        assertEquals(GroupTag("finance"), GroupTag.parse("#Finance"))
        assertEquals(GroupTag("finance", "manager"), GroupTag.parse(" #finance >= Manager "))
        assertEquals("#finance>=manager", GroupTag.parse("#finance>=manager").toString())
        assertEquals("#all", GroupTag.parse("#all").toString())
        assertTrue(GroupTag.parse("#all")!!.builtin)
        // Built-ins have no level filter; bad handles and keys are not tags.
        assertNull(GroupTag.parse("#all>=manager"))
        assertNull(GroupTag.parse("#f"))
        assertNull(GroupTag.parse("#finance>="))
        assertNull(GroupTag.parse("#finance>=Gerente Sênior"))
        assertNull(GroupTag.parse("finance"))
        assertTrue(GroupTag.isValidHandle("plantao-infra"))
        assertFalse(GroupTag.isValidHandle("everyone"))
        assertTrue(GroupTag.isValidLevelKey("l1"))
        assertFalse(GroupTag.isValidLevelKey("-x"))
    }
}
