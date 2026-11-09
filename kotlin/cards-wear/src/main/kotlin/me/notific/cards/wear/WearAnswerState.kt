package me.notific.cards.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import java.text.DecimalFormatSymbols
import java.util.Locale
import me.notific.client.Answer
import me.notific.client.Question
import me.notific.client.QuestionSet
import me.notific.client.QuestionType

/**
 * The answers of a question set on the watch, not sent yet: a tap (yes/no or
 * one option) or, with [dictation], what the person said (a transcription for
 * `text`/`voice`, a number, an "other" answer). Only questions the watch can
 * answer are kept; [toAnswers] builds the reply body for
 * [me.notific.client.NotificClient.reply].
 */
@Stable
public class WearAnswerState(
    questions: List<Question>,
    public val dictation: Boolean = false,
    /** How spoken numbers are written (the recognizer follows the watch's language). */
    private val locale: Locale = Locale.getDefault(),
    /** A `single` with more options goes to the phone ([answerableOnWatch]). */
    maxOptions: Int = Int.MAX_VALUE,
) {
    /** The questions drawn on the watch, in order. */
    public val questions: List<Question> = questions.filter { it.answerableOnWatch(dictation, maxOptions) }
    private val picked = mutableStateMapOf<String, String>()
    private val spoken = mutableStateMapOf<String, String>()

    /** `true`/`false` of a yes/no question, or null while it is unanswered. */
    public fun yesNo(questionId: String): Boolean? = picked[questionId]?.toBooleanStrictOrNull()

    /** The option picked in a `single` question (null when an "other" answer was spoken instead). */
    public fun choice(questionId: String): String? = picked[questionId]

    /** What was said for the question (a transcription, a number, an "other" answer). */
    public fun spoken(questionId: String): String? = spoken[questionId]

    /** The number read from what was said for a `number` question (shown next to the transcription). */
    public fun spokenNumber(questionId: String): Number? =
        spoken[questionId]?.takeIf { find(questionId)?.type == QuestionType.Number }?.let(::parseSpokenNumber)

    public fun setYesNo(questionId: String, value: Boolean) {
        if (find(questionId)?.type == QuestionType.YesNo) picked[questionId] = value.toString()
    }

    /** Picks an option; it replaces a spoken "other" answer. */
    public fun setChoice(questionId: String, option: String) {
        val q = find(questionId) ?: return
        if (q.type == QuestionType.Single && option in q.options) {
            picked[questionId] = option
            spoken.remove(questionId)
        }
    }

    /**
     * Keeps what the person said for a question that accepts speech (see
     * [speakable]); blank clears it. A spoken "other" replaces the option picked.
     */
    public fun setSpoken(questionId: String, text: String) {
        val q = find(questionId) ?: return
        if (!dictation || !q.speakable) return
        val clean = text.trim()
        if (clean.isEmpty()) {
            spoken.remove(questionId)
            return
        }
        spoken[questionId] = clean
        if (q.type == QuestionType.Single) picked.remove(questionId)
    }

    public fun isAnswered(questionId: String): Boolean {
        val q = find(questionId) ?: return false
        return when (q.type) {
            QuestionType.YesNo -> picked.containsKey(questionId)
            QuestionType.Single -> picked.containsKey(questionId) || spoken.containsKey(questionId)
            QuestionType.Text, QuestionType.Voice -> spoken.containsKey(questionId)
            QuestionType.Number -> spoken[questionId]?.let(::parseSpokenNumber) != null
            else -> false
        }
    }

    /** Required questions still unanswered. */
    public fun missing(): List<Question> = questions.filter { !it.optional && !isAnswered(it.id) }

    public val isComplete: Boolean get() = questions.isNotEmpty() && missing().isEmpty()

    /** The reply body; questions without an answer are left out. */
    public fun toAnswers(): List<Answer> = questions.mapNotNull { q ->
        if (!isAnswered(q.id)) return@mapNotNull null
        when (q.type) {
            QuestionType.YesNo -> Answer.yesNo(q.id, picked.getValue(q.id).toBoolean())
            QuestionType.Single -> picked[q.id]?.let { Answer.choice(q.id, it) } ?: Answer.other(q.id, spoken.getValue(q.id))
            QuestionType.Text, QuestionType.Voice -> Answer.text(q.id, spoken.getValue(q.id))
            QuestionType.Number -> Answer.number(q.id, parseSpokenNumber(spoken.getValue(q.id))!!)
            else -> null
        }
    }

    private fun find(questionId: String): Question? = questions.firstOrNull { it.id == questionId }

    private fun parseSpokenNumber(text: String): Number? = parseSpokenNumber(text, locale)

    public companion object {
        /**
         * The one number in a transcription, read the way [locale] writes it:
         * `1,200.5` in English, `1.200,5` in Portuguese and Spanish. Words
         * around it are ignored (`R$ 1.200` → 1200); two numbers → null.
         */
        public fun parseSpokenNumber(text: String, locale: Locale = Locale.getDefault()): Number? {
            val symbols = DecimalFormatSymbols.getInstance(locale)
            val group = symbols.groupingSeparator
            val decimal = symbols.decimalSeparator
            val digits = Regex("-?\\d[\\d${Regex.escape(group.toString())}]*(${Regex.escape(decimal.toString())}\\d+)?")
            val found = digits.findAll(text.replace(' ', ' ')).toList()
            if (found.size != 1) return null
            val n = found[0].value.replace(group.toString(), "").replace(decimal, '.')
            n.toLongOrNull()?.let { return it }
            return n.toDoubleOrNull()?.takeIf { it.isFinite() }
        }
    }
}

@Composable
public fun rememberWearAnswerState(questionSet: QuestionSet, dictation: Boolean = false, maxOptions: Int = Int.MAX_VALUE): WearAnswerState =
    remember(questionSet.id, questionSet.version, dictation, maxOptions) {
        WearAnswerState(questionSet.questions, dictation, maxOptions = maxOptions)
    }
