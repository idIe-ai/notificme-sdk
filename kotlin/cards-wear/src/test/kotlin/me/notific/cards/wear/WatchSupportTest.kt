package me.notific.cards.wear

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import me.notific.client.Answer
import me.notific.client.DecisionView
import me.notific.client.Question
import me.notific.client.QuestionSet
import me.notific.client.QuestionSetStatus
import me.notific.client.QuestionType

class WatchSupportTest {
    private val yes = Question("ok", QuestionType.YesNo, "Deploy now?")
    private val pick = Question("env", QuestionType.Single, "Where?", options = listOf("staging", "prod"))
    private val note = Question("note", QuestionType.Text, "Why?")

    private fun set(vararg q: Question, level: Int = 0) = QuestionSet(id = "qs", level = level, questions = q.toList(), answerable = true)

    @Test
    fun `yes-no and single at level 0 are answered on the watch`() {
        val s = set(yes, pick).watchSupport()
        assertTrue(s.answerable)
        assertNull(s.main)
    }

    @Test
    fun `level 1 goes to the phone unless the watch may answer it`() {
        assertEquals(setOf(WatchBlocker.Level), set(yes, level = 1).watchSupport().blockers)
        assertTrue(set(yes, level = 1).watchSupport(maxLevel = 1).answerable)
        // A watch never gives more than level 1.
        assertFalse(set(yes, level = 2).watchSupport(maxLevel = 4).answerable)
    }

    @Test
    fun `a question with a higher level raises the set`() {
        val strict = yes.copy(level = 2)
        assertEquals(2, set(strict).requiredLevel)
        assertEquals(WatchBlocker.Level, set(strict).watchSupport().main)
    }

    @Test
    fun `a required question the watch can't show blocks it, an optional one doesn't`() {
        val many = Question("regions", QuestionType.Multi, "Regions?", options = listOf("eu", "us"))
        assertEquals(WatchBlocker.QuestionType, set(yes, many).watchSupport().main)
        assertTrue(set(yes, many.copy(optional = true)).watchSupport().answerable)
        // Only questions the watch can't show: nothing to tap.
        assertFalse(set(many.copy(optional = true)).watchSupport().answerable)
        // A typed answer needs dictation.
        assertEquals(setOf(WatchBlocker.FreeText), set(yes, note).watchSupport().blockers)
    }

    @Test
    fun `too many alternatives go to the phone`() {
        val many = Question("x", QuestionType.Single, "Which?", options = listOf("a", "b", "c", "d", "e"))
        assertTrue(set(many).watchSupport(maxOptions = 5).answerable)
        assertEquals(WatchBlocker.QuestionType, set(many).watchSupport(maxOptions = 4).main)
        // Only yes/no on the wrist: no single at all.
        assertEquals(WatchBlocker.QuestionType, set(yes, pick).watchSupport(maxOptions = 0).main)
        assertTrue(set(yes).watchSupport(maxOptions = 0).answerable)
        assertEquals(listOf("ok"), WearAnswerState(listOf(yes, pick), maxOptions = 0).questions.map { it.id })
    }

    @Test
    fun `with dictation typed answers are spoken`() {
        val amount = Question("amount", QuestionType.Number, "How much?")
        val voice = Question("why", QuestionType.Voice, "Tell us why")
        assertTrue(set(note).watchSupport(dictation = true).answerable)
        assertTrue(set(yes, amount, voice).watchSupport(dictation = true).answerable)
        val date = Question("when", QuestionType.Date, "When?")
        assertEquals(WatchBlocker.QuestionType, set(note, date).watchSupport(dictation = true).main)
    }

    @Test
    fun `spoken answers`() {
        val amount = Question("amount", QuestionType.Number, "How much?")
        val other = pick.copy(allowOther = true)
        val noDictation = WearAnswerState(listOf(note, other))
        assertEquals(listOf("env"), noDictation.questions.map { it.id })
        noDictation.setSpoken("env", "mars")
        assertNull(noDictation.spoken("env"))

        val state = WearAnswerState(listOf(note, amount, other), dictation = true, locale = Locale.forLanguageTag("pt-BR"))
        state.setSpoken("note", "  Pode subir depois das 22h ")
        state.setSpoken("amount", "não sei")
        state.setChoice("env", "prod")
        assertEquals(listOf("amount"), state.missing().map { it.id })
        assertNull(state.spokenNumber("amount"))
        state.setSpoken("amount", "R$ 1.200,50")
        assertEquals(1200.5, state.spokenNumber("amount"))
        // A spoken "other" replaces the option.
        state.setSpoken("env", "só homologação")
        assertNull(state.choice("env"))
        assertTrue(state.isComplete)
        assertEquals(
            listOf(Answer.text("note", "Pode subir depois das 22h"), Answer.number("amount", 1200.5), Answer.other("env", "só homologação")),
            state.toAnswers(),
        )
    }

    @Test
    fun `spoken numbers follow the language`() {
        val pt = Locale.forLanguageTag("pt-BR")
        assertEquals(1200L, WearAnswerState.parseSpokenNumber("R$ 1.200", pt))
        assertEquals(12.5, WearAnswerState.parseSpokenNumber("12,5", pt))
        assertEquals(1200L, WearAnswerState.parseSpokenNumber("1200.", pt))
        assertEquals(1200.5, WearAnswerState.parseSpokenNumber("1,200.5", Locale.US))
        assertEquals(-3L, WearAnswerState.parseSpokenNumber("-3", Locale.US))
        assertNull(WearAnswerState.parseSpokenNumber("between 2 and 3", Locale.US))
        assertNull(WearAnswerState.parseSpokenNumber("a lot", Locale.US))
    }

    @Test
    fun `single with no options needs typing`() {
        val free = Question("x", QuestionType.Single, "Other?", allowOther = true)
        assertEquals(setOf(WatchBlocker.FreeText, WatchBlocker.QuestionType), set(free).watchSupport().blockers)
    }

    @Test
    fun `signature, closed and not eligible`() {
        assertEquals(WatchBlocker.Signature, set(yes).copy(requireSignature = true).watchSupport().main)
        assertEquals(WatchBlocker.Closed, set(yes).copy(status = QuestionSetStatus.Answered).watchSupport().main)
        val watching = set(yes).copy(decision = DecisionView(canDecide = false, reason = "already_voted"))
        assertEquals(WatchBlocker.NotEligible, watching.watchSupport().main)
    }

    @Test
    fun `answer state keeps only taps the question accepts`() {
        val state = WearAnswerState(listOf(yes, pick, note))
        assertEquals(listOf("ok", "env"), state.questions.map { it.id })
        assertFalse(state.isComplete)
        state.setChoice("env", "mars")
        assertNull(state.choice("env"))
        state.setChoice("env", "prod")
        state.setYesNo("env", true) // not a yes/no question
        assertEquals("prod", state.choice("env"))
        assertEquals(listOf("ok"), state.missing().map { it.id })
        state.setYesNo("ok", false)
        assertTrue(state.isComplete)
        assertEquals(listOf(Answer("ok", JsonPrimitive(false)), Answer("env", JsonPrimitive("prod"))), state.toAnswers())
    }

    @Test
    fun `answer state with only optional questions is complete but empty`() {
        val state = WearAnswerState(listOf(yes.copy(optional = true)))
        assertTrue(state.isComplete)
        assertEquals(emptyList(), state.toAnswers())
        assertFalse(WearAnswerState(listOf(note)).isComplete)
    }

    @Test
    fun `short age`() {
        val now = Instant.parse("2026-11-09T12:00:00Z")
        val utc = ZoneOffset.UTC
        assertEquals(ShortAge.Now, ShortAge.of("2026-11-09T11:59:30Z", now, utc))
        assertEquals(ShortAge.Minutes(5), ShortAge.of("2026-11-09T11:55:00Z", now, utc))
        assertEquals(ShortAge.Hours(3), ShortAge.of("2026-11-09T08:30:00-00:00", now, utc))
        assertEquals(ShortAge.Weekday(LocalDate.of(2026, 11, 7)), ShortAge.of("2026-11-07T10:00:00Z", now, utc))
        assertEquals(ShortAge.Date(LocalDate.of(2026, 10, 1)), ShortAge.of("2026-10-01T10:00:00Z", now, utc))
        assertNull(ShortAge.of("yesterday", now, utc))
    }

    @Test
    fun `card values and markdown`() {
        assertEquals("12", formatCardValue(JsonPrimitive(12.0), Locale.US))
        assertEquals("12.5", formatCardValue(JsonPrimitive(12.5), Locale.US))
        assertEquals("1,234.57", formatCardValue(JsonPrimitive(1234.567), Locale.US))
        assertEquals("12,5", formatCardValue(JsonPrimitive(12.5), Locale.forLanguageTag("pt-BR")))
        assertEquals("99.9%", formatCardValue(JsonPrimitive("99.9%")))
        assertEquals("Deploy prod now (example.com)", wearMarkdown("Deploy **prod** [now](https://example.com/x)").text)
    }
}
