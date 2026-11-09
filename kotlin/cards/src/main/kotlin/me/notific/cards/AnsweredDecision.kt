package me.notific.cards

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import me.notific.client.Block
import me.notific.client.Card
import me.notific.client.Question
import me.notific.client.QuestionType
import me.notific.client.ReplyAnswer

/**
 * A decision that is over, ready to draw as it was presented: the card with
 * every question in it and what was chosen in each one ([drafts], in the same
 * shape the form fills). [AnsweredCard] draws it; with "answers only" it hides
 * what was not chosen.
 *
 * An answer to a question that is no longer in the list (an older version of
 * the set) comes in as a text question, so nothing answered goes missing.
 */
@Immutable
public class AnsweredDecision(card: Card?, questions: List<Question>, answers: List<ReplyAnswer>) {
    private val orphans = answers.filter { a -> questions.none { it.id == a.questionId } }

    public val questions: List<Question> =
        questions + orphans.map { Question(id = it.questionId, type = QuestionType.Text, text = it.questionText.ifBlank { it.questionId }) }

    /** The sender's card (title and supporting blocks only when there was one) with every question. */
    public val card: Card = (card ?: Card()).withQuestions(this.questions)

    public val drafts: Map<String, Draft> =
        this.questions.associate { q -> q.id to answeredDraft(q, answers.firstOrNull { it.questionId == q.id }) }

    /** Somebody answered something (a decision that expired or was cancelled may have nothing). */
    public val anyAnswer: Boolean = this.questions.any { isAnswered(it.id) }

    /**
     * "Answers only" has something to hide: supporting blocks, unanswered
     * questions, or choices left out. False with nothing answered (then the
     * whole form is shown, as it was asked) or with only open answers.
     */
    public val hides: Boolean = anyAnswer && (
        !this.card.title.isNullOrBlank() || this.card.blocks.any { b ->
            val q = question(b)
            q == null || !isAnswered(q.id) || q.type == QuestionType.YesNo || q.type == QuestionType.Single || q.type == QuestionType.Multi
        }
        )

    public fun question(block: Block): Question? = when (block) {
        is Block.Options -> questions.firstOrNull { it.id == block.questionId }
        is Block.Input -> questions.firstOrNull { it.id == block.questionId }
        else -> null
    }

    public fun isAnswered(questionId: String): Boolean {
        val q = questions.firstOrNull { it.id == questionId } ?: return false
        return AnswerState.isFilled(q, drafts[questionId] ?: Draft())
    }

    /** Whether [block] stays on screen: everything when showing all; only answered questions in "answers only". */
    public fun shows(block: Block, onlyAnswers: Boolean): Boolean {
        if (!onlyAnswers || !hides) return true
        return question(block)?.let { isAnswered(it.id) } == true
    }

    public companion object {
        /**
         * The reply's answer to [question] as a [Draft]. A `single` answered
         * only with free text is "Other"; numbers and dates keep the text
         * the server sent (formatting is the screen's job).
         */
        public fun answeredDraft(question: Question, answer: ReplyAnswer?): Draft {
            if (answer == null) return Draft()
            val v = answer.value
            val free = answer.freeText?.takeIf { it.isNotBlank() }
            val text = v.text()
            return when (question.type) {
                QuestionType.YesNo -> Draft(bool = (v as? JsonPrimitive)?.booleanOrNull)
                QuestionType.Single -> if (text != null) Draft(choice = text) else if (free != null) Draft(other = true, text = free) else Draft()
                QuestionType.Multi -> Draft(
                    choices = (v as? JsonArray)?.mapNotNull { it.text() }?.toSet().orEmpty(),
                    other = free != null,
                    text = free.orEmpty(),
                )
                QuestionType.Text, QuestionType.Voice, QuestionType.Unknown -> Draft(text = free ?: text ?: v.listText().orEmpty())
                QuestionType.Number, QuestionType.Date -> Draft(text = text.orEmpty())
            }
        }

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

        private fun JsonElement?.listText(): String? = (this as? JsonArray)?.mapNotNull { it.text() }?.joinToString(", ")
    }
}
