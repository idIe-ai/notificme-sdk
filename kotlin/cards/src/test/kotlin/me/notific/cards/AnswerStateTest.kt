package me.notific.cards

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.notific.client.Answer
import me.notific.client.QuestionSet
import me.notific.client.Wire

class AnswerStateTest {
    private val qs: QuestionSet = Wire.json.decodeFromString(
        QuestionSet.serializer(),
        File("../../protocol/examples/purchase-request.question-set.json").readText(),
    )

    @Test
    fun requiredQuestionsBlockSending() {
        val s = AnswerState(qs.questions)
        assertEquals(listOf("approve", "supplier", "quantity", "deliver_by"), s.missing().map { it.id })
        assertFalse(s.isComplete)
    }

    @Test
    fun filledAnswersBecomeTheReplyBody() {
        val s = AnswerState(qs.questions)
        s["approve"] = Draft(bool = true)
        s["supplier"] = Draft(choice = "Dell", other = true, text = "HP")
        s["extras"] = Draft(choices = setOf("Headset", "Docking station", "Not an option"))
        s["quantity"] = Draft(text = "12,5")
        s["deliver_by"] = Draft(text = "2026-11-30")
        s["note"] = Draft(text = "  ")
        assertTrue(s.isComplete)
        assertEquals(
            listOf(
                Answer.yesNo("approve", true),
                Answer.other("supplier", "HP"),
                Answer.choices("extras", listOf("Docking station", "Headset")),
                Answer.number("quantity", 12.5),
                Answer.date("deliver_by", "2026-11-30"),
            ),
            s.toAnswers(),
        )
    }

    @Test
    fun invalidValuesAreNotFilled() {
        val s = AnswerState(qs.questions)
        s["supplier"] = Draft(choice = "Unknown brand")
        s["quantity"] = Draft(text = "twelve")
        s["deliver_by"] = Draft(text = "30/11/2026")
        val missing = s.missing().map { it.id }
        assertTrue("supplier" in missing && "quantity" in missing && "deliver_by" in missing)
        assertEquals(12L, AnswerState.parseNumber(" 12 "))
    }

    @Test
    fun markdownBold() {
        val text = lightMarkdown("**api-v2** on **vps-01** exited")
        assertEquals("api-v2 on vps-01 exited", text.text)
        assertEquals(2, text.spanStyles.size)
    }

    @Test
    fun markdownLinkShowsTheDomain() {
        val text = lightMarkdown("Leia [o estudo](https://claude.ai/artifact/X) e https://notific.me.")
        assertEquals("Leia o estudo (claude.ai) e https://notific.me.", text.text)
        val links = text.getLinkAnnotations(0, text.length).map { (it.item as androidx.compose.ui.text.LinkAnnotation.Url).url }
        assertEquals(listOf("https://claude.ai/artifact/X", "https://notific.me"), links)
    }

    @Test
    fun summaryLinksOnlyBareAddresses() {
        val text = lightMarkdown("**x** https://a.com", linkColor = androidx.compose.ui.graphics.Color.Unspecified, markdown = false)
        assertEquals("**x** https://a.com", text.text)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
        assertEquals(0, text.spanStyles.size)
    }
}
