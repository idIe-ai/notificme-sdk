package me.notific.cards

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import me.notific.client.Block
import me.notific.client.Question
import me.notific.client.QuestionSet
import me.notific.client.QuestionType
import me.notific.client.ReplyAnswer
import me.notific.client.Wire

class AnsweredDecisionTest {
    private val qs: QuestionSet = Wire.json.decodeFromString(
        QuestionSet.serializer(),
        File("../../protocol/examples/purchase-request.question-set.json").readText(),
    )

    private val answers = listOf(
        ReplyAnswer("approve", "Approve this purchase?", JsonPrimitive(true)),
        ReplyAnswer("supplier", "Which supplier?", freeText = "HP"),
        ReplyAnswer("extras", "Include extras", JsonArray(listOf(JsonPrimitive("Headset"))), freeText = "Mouse"),
        ReplyAnswer("quantity", "How many units?", JsonPrimitive(12)),
        ReplyAnswer("deliver_by", "Deliver by", JsonPrimitive("2026-12-05")),
    )

    @Test
    fun replyAnswersBecomeWhatTheFormHad() {
        val d = AnsweredDecision(qs.card, qs.questions, answers)
        assertEquals(Draft(bool = true), d.drafts["approve"])
        // A single answered only with free text is "Other".
        assertEquals(Draft(other = true, text = "HP"), d.drafts["supplier"])
        assertEquals(Draft(choices = setOf("Headset"), other = true, text = "Mouse"), d.drafts["extras"])
        assertEquals(Draft(text = "12"), d.drafts["quantity"])
        assertEquals(Draft(text = "2026-12-05"), d.drafts["deliver_by"])
        assertEquals(Draft(), d.drafts["note"])
        assertTrue(d.isAnswered("supplier"))
        assertFalse(d.isAnswered("note"))
        assertTrue(d.anyAnswer)
    }

    @Test
    fun answersOnlyKeepsOnlyAnsweredQuestions() {
        val d = AnsweredDecision(qs.card, qs.questions, answers)
        assertTrue(d.hides)
        // The card keeps its own blocks and gets every question that was missing from it.
        assertEquals(qs.questions.map { it.id }.toSet(), d.card.blocks.mapNotNull { d.question(it)?.id }.toSet())
        val metric = d.card.blocks.first { it is Block.Metric }
        assertFalse(d.shows(metric, onlyAnswers = true))
        assertTrue(d.shows(metric, onlyAnswers = false))
        val note = d.card.blocks.first { d.question(it)?.id == "note" }
        assertFalse(d.shows(note, onlyAnswers = true))
        val approve = d.card.blocks.first { d.question(it)?.id == "approve" }
        assertTrue(d.shows(approve, onlyAnswers = true))
    }

    @Test
    fun nothingAnsweredMeansNothingToFilter() {
        val d = AnsweredDecision(qs.card, qs.questions, emptyList())
        assertFalse(d.anyAnswer)
        assertFalse(d.hides)
        assertTrue(d.card.blocks.all { d.shows(it, onlyAnswers = true) })
    }

    @Test
    fun onlyOpenAnswersHaveNothingToHide() {
        val q = listOf(Question("risk", QuestionType.Text, "Any risk?"))
        val d = AnsweredDecision(null, q, listOf(ReplyAnswer("risk", "Any risk?", freeText = "None.")))
        assertFalse(d.hides)
        assertEquals(Draft(text = "None."), d.drafts["risk"])
        // No card from the sender: no title to show or hide.
        assertEquals(null, d.card.title)
    }

    @Test
    fun anAnswerToAQuestionThatLeftTheListIsKept() {
        val q = listOf(Question("ok", QuestionType.YesNo, "Approve?"))
        val d = AnsweredDecision(
            null,
            q,
            listOf(ReplyAnswer("ok", "Approve?", JsonPrimitive(true)), ReplyAnswer("old", "Old question?", JsonPrimitive("Blue"))),
        )
        assertEquals(listOf("ok", "old"), d.questions.map { it.id })
        assertEquals("Old question?", d.questions.last().text)
        assertEquals(Draft(text = "Blue"), d.drafts["old"])
        assertTrue(d.isAnswered("old"))
    }

    @Test
    fun aScaleAnswerIsTheChosenNumber() {
        val q = listOf(Question("trust", QuestionType.Single, "How confident?", options = listOf("1", "2", "3", "4", "5")))
        val d = AnsweredDecision(null, q, listOf(ReplyAnswer("trust", "How confident?", JsonPrimitive("4"))))
        assertEquals(Draft(choice = "4"), d.drafts["trust"])
        assertTrue(d.hides)
    }
}
