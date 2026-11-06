package me.notific.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import java.time.LocalDate
import me.notific.client.Answer
import me.notific.client.Question
import me.notific.client.QuestionSet
import me.notific.client.QuestionType

/** What the person has filled in for one question (not sent yet). */
public data class Draft(
    /** `yesno`. */
    val bool: Boolean? = null,
    /** `single`. */
    val choice: String? = null,
    /** `multi`. */
    val choices: Set<String> = emptySet(),
    /** "Other" picked (`single`/`multi` with `allow_other`). */
    val other: Boolean = false,
    /** Free text, "other" text, number or date as typed. */
    val text: String = "",
)

/**
 * Answers being edited for a question set. The rules match the server's
 * (and the web app's): [isFilled] says when an answer can be sent and
 * [toAnswers] builds the reply body.
 */
@Stable
public class AnswerState(public val questions: List<Question>) {
    private val drafts = mutableStateMapOf<String, Draft>()

    public operator fun get(questionId: String): Draft = drafts[questionId] ?: Draft()

    public operator fun set(questionId: String, draft: Draft) {
        drafts[questionId] = draft
    }

    public fun update(questionId: String, change: (Draft) -> Draft) {
        set(questionId, change(get(questionId)))
    }

    /** Everything filled in so far, to keep as a draft until it is sent. */
    public fun snapshot(): Map<String, Draft> = drafts.toMap()

    /** Puts a [snapshot] back (only questions of this set). */
    public fun restore(snapshot: Map<String, Draft>) {
        val ids = questions.mapTo(HashSet()) { it.id }
        drafts.putAll(snapshot.filterKeys { it in ids })
    }

    /** Required questions still without an answer. */
    public fun missing(): List<Question> = questions.filter { !it.optional && !isFilled(it, get(it.id)) }

    public val isComplete: Boolean get() = missing().isEmpty()

    /** The reply body; questions without a filled answer are left out. */
    public fun toAnswers(): List<Answer> = questions.mapNotNull { q ->
        val d = get(q.id)
        if (!isFilled(q, d)) return@mapNotNull null
        val otherText = if (q.allowOther && d.other && d.text.isNotBlank()) d.text.trim() else null
        when (q.type) {
            QuestionType.YesNo -> Answer.yesNo(q.id, d.bool!!)
            QuestionType.Single -> if (otherText != null) Answer.other(q.id, otherText) else Answer.choice(q.id, d.choice!!)
            QuestionType.Multi -> Answer.choices(q.id, q.options.filter { it in d.choices }, otherText)
            QuestionType.Text, QuestionType.Voice -> Answer.text(q.id, d.text)
            QuestionType.Number -> Answer.number(q.id, parseNumber(d.text)!!)
            QuestionType.Date -> Answer.date(q.id, d.text)
            QuestionType.Unknown -> null
        }
    }

    public companion object {
        public fun isFilled(q: Question, d: Draft): Boolean {
            val other = q.allowOther && d.other && d.text.isNotBlank()
            return when (q.type) {
                QuestionType.YesNo -> d.bool != null
                QuestionType.Single -> (!d.other && d.choice != null && d.choice in q.options) || other
                QuestionType.Multi -> d.choices.any { it in q.options } || other
                QuestionType.Text, QuestionType.Voice -> d.text.isNotBlank()
                QuestionType.Number -> parseNumber(d.text) != null
                QuestionType.Date -> runCatching { LocalDate.parse(d.text.trim()) }.isSuccess
                QuestionType.Unknown -> false
            }
        }

        /** Accepts `12`, `12.5` and `12,5`. */
        public fun parseNumber(text: String): Number? {
            val t = text.trim().replace(',', '.')
            if (t.isEmpty()) return null
            t.toLongOrNull()?.let { return it }
            return t.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
    }
}

@Composable
public fun rememberAnswerState(questionSet: QuestionSet): AnswerState =
    remember(questionSet.id, questionSet.version) { AnswerState(questionSet.questions) }
