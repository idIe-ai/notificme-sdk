package me.notific.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * One answer of a reply. Build it with the helpers, which follow the rules
 * the server checks for each question type:
 *
 * ```
 * Answer.yesNo("backup", true)
 * Answer.choice("action", "Approve")
 * Answer.other("action", "Only after 10 pm")      // single with allow_other
 * Answer.choices("regions", listOf("eu", "us"))
 * Answer.text("note", "Ship it")
 * Answer.number("amount", 120.5)
 * Answer.date("when", "2026-11-30")
 * ```
 */
@Serializable
public data class Answer(
    @SerialName("question_id") val questionId: String,
    val value: JsonElement? = null,
    @SerialName("free_text") val freeText: String? = null,
    @SerialName("audio_ref") val audioRef: String? = null,
) {
    public companion object {
        public fun yesNo(questionId: String, value: Boolean): Answer = Answer(questionId, JsonPrimitive(value))

        /** One option of a `single` question. */
        public fun choice(questionId: String, option: String): Answer = Answer(questionId, JsonPrimitive(option))

        /** Free text instead of an option, for a `single` question with `allow_other`. */
        public fun other(questionId: String, text: String): Answer = Answer(questionId, freeText = text.trim())

        /** Options of a `multi` question, plus free text when it has `allow_other`. */
        public fun choices(questionId: String, options: List<String>, other: String? = null): Answer = Answer(
            questionId,
            JsonArray(options.distinct().map(::JsonPrimitive)),
            freeText = other?.trim()?.takeIf { it.isNotEmpty() },
        )

        /** A `text` (or `voice`, typed) answer. */
        public fun text(questionId: String, text: String): Answer = Answer(questionId, freeText = text.trim())

        public fun number(questionId: String, value: Number): Answer = Answer(questionId, JsonPrimitive(value))

        /** ISO date, `YYYY-MM-DD`. */
        public fun date(questionId: String, isoDate: String): Answer = Answer(questionId, JsonPrimitive(isoDate.trim()))
    }
}
