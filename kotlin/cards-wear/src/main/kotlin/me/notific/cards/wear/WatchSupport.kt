package me.notific.cards.wear

import me.notific.client.Question
import me.notific.client.QuestionSet
import me.notific.client.QuestionSetStatus
import me.notific.client.QuestionType

/** Why a question set has to be answered somewhere else (the phone, the web). */
public enum class WatchBlocker {
    /** It is not open any more (answered, ran out, cancelled). */
    Closed,

    /** A decision rule only lets the person watch it (already voted, not eligible, the requester). */
    NotEligible,

    /** A question the watch can't show (`multi`, `date`; typed answers without dictation). */
    QuestionType,

    /** A required question needs a typed answer and the watch has no dictation. */
    FreeText,

    /** It asks for more proof than the watch gives (see [watchSupport]'s `maxLevel`). */
    Level,

    /** The sender asks for the person's electronic signature. */
    Signature,
}

/** Whether a question set can be answered on the watch, and if not, why. */
public data class WatchSupport(val blockers: Set<WatchBlocker>) {
    public val answerable: Boolean get() = blockers.isEmpty()

    /** The reason to show first: the one the person can do least about. */
    public val main: WatchBlocker? get() = WatchBlocker.entries.firstOrNull { it in blockers }
}

/** The highest level asked by the set or by any of its questions. */
public val QuestionSet.requiredLevel: Int
    get() = maxOf(level, questions.maxOfOrNull { it.level ?: 0 } ?: 0)

/** A question the watch answers with a tap: yes/no, or one of the options. */
public val Question.tappable: Boolean
    get() = when (type) {
        QuestionType.YesNo -> true
        QuestionType.Single -> options.isNotEmpty()
        else -> false
    }

/**
 * A question the watch answers by speaking: the watch's speech recognizer
 * transcribes it (no keyboard). `text` and `voice` take the transcription as
 * `free_text`, `number` takes the number said, and a `single` with
 * `allow_other` takes a spoken "other" answer.
 */
public val Question.speakable: Boolean
    get() = when (type) {
        QuestionType.Text, QuestionType.Voice, QuestionType.Number -> true
        QuestionType.Single -> allowOther
        else -> false
    }

/**
 * Can the watch show and answer [this]? Speaking only counts with
 * [dictation]; a `single` with more than [maxOptions] options goes to the
 * phone ("too many alternatives for a wrist"; 0: no `single` at all).
 */
public fun Question.answerableOnWatch(dictation: Boolean, maxOptions: Int = Int.MAX_VALUE): Boolean = when {
    type == QuestionType.Single && options.size > maxOptions -> dictation && speakable && options.isEmpty()
    tappable -> true
    else -> dictation && speakable
}

/**
 * Can this question set be answered on a watch? A watch answers with a tap
 * (level 0) or with its own signed-in session (level 1, [maxLevel]); never
 * more. With [dictation] (the watch has a speech recognizer) typed answers
 * are spoken; a `single` with more than [maxOptions] options goes to the
 * phone. Optional questions the watch can't show are left unanswered; a
 * required one sends the person to the phone.
 */
public fun QuestionSet.watchSupport(maxLevel: Int = 0, dictation: Boolean = false, maxOptions: Int = Int.MAX_VALUE): WatchSupport {
    val blockers = mutableSetOf<WatchBlocker>()
    if (status != QuestionSetStatus.Open) blockers += WatchBlocker.Closed
    if (!canDecide) blockers += WatchBlocker.NotEligible
    for (q in questions) {
        if (q.optional || q.answerableOnWatch(dictation, maxOptions)) continue
        blockers += if (q.speakable && !dictation) WatchBlocker.FreeText else WatchBlocker.QuestionType
    }
    if (questions.none { it.answerableOnWatch(dictation, maxOptions) }) blockers += WatchBlocker.QuestionType
    if (requiredLevel > maxLevel.coerceIn(0, 1)) blockers += WatchBlocker.Level
    if (requireSignature) blockers += WatchBlocker.Signature
    return WatchSupport(blockers)
}
