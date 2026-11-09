package me.notific.client

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Every example in sdk/protocol/examples must be readable by this client. */
class ProtocolExamplesTest {
    private val dir = File(System.getProperty("notificme.protocolExamples") ?: "../../protocol/examples")

    private fun examples(kind: String): List<File> =
        dir.listFiles { f -> f.name.endsWith(".$kind.json") }.orEmpty().sortedBy { it.name }

    private fun read(f: File): JsonObject = Wire.json.parseToJsonElement(f.readText()).jsonObject

    @Test
    fun examplesExist() {
        assertTrue(examples("event").isNotEmpty(), "no event examples in $dir")
        assertTrue(examples("question-set").isNotEmpty())
        assertTrue(examples("reply").isNotEmpty())
    }

    @Test
    fun eventCardsHaveOnlyKnownBlocks() {
        for (f in examples("event")) {
            val content = read(f)["content"]?.jsonObject ?: continue
            val card = Wire.json.decodeFromJsonElement(NotificationContent.serializer(), content).card ?: continue
            val unknown = card.blocks.filterIsInstance<Block.Unknown>()
            assertTrue(unknown.isEmpty(), "${f.name}: unknown blocks $unknown")
        }
    }

    @Test
    fun weeklyReportCoversEveryDisplayBlock() {
        val f = File(dir, "weekly-report.event.json")
        val card = Wire.json.decodeFromJsonElement(NotificationContent.serializer(), read(f)["content"]!!.jsonObject).card!!
        val types = card.blocks.map { it.type }.toSet()
        assertEquals(setOf("text", "metric", "chart", "table", "list", "image", "progress", "buttons"), types)

        val table = card.blocks.filterIsInstance<Block.Table>().single()
        assertEquals("Acme", table.rows[0][0].content)
        assertTrue(table.rows[1][2] is kotlinx.serialization.json.JsonNull)
        val bar = card.blocks.filterIsInstance<Block.Chart>().first { it.kind == ChartKind.Bar }
        assertEquals(listOf(120.0, 95.0, 140.0), bar.series.single().values)
        val steps = card.blocks.filterIsInstance<Block.Progress>().first { it.steps.isNotEmpty() }
        assertEquals(1, steps.current)
        assertEquals("182400", card.blocks.filterIsInstance<Block.Metric>().first().value.content)
    }

    @Test
    fun questionSetsDecodeAndShowEveryQuestion() {
        for (f in examples("question-set")) {
            val qs = Wire.json.decodeFromJsonElement(QuestionSet.serializer(), read(f))
            assertTrue(qs.questions.none { it.type == QuestionType.Unknown }, "${f.name}: unknown question type")
            val card = qs.cardWithQuestions()
            val shown = card.blocks.mapNotNull {
                when (it) {
                    is Block.Options -> it.questionId
                    is Block.Input -> it.questionId
                    else -> null
                }
            }
            assertEquals(qs.questions.map { it.id }.toSet(), shown.toSet(), f.name)
            assertEquals(shown.size, shown.toSet().size, "${f.name}: a question is shown twice")
        }
    }

    @Test
    fun purchaseRequestCoversEveryQuestionType() {
        val qs = Wire.json.decodeFromJsonElement(QuestionSet.serializer(), read(File(dir, "purchase-request.question-set.json")))
        assertEquals(QuestionType.entries.toSet() - QuestionType.Unknown, qs.questions.map { it.type }.toSet())
        val supplier = qs.question("supplier")!!
        assertTrue(supplier.allowOther)
        assertEquals(listOf("Dell", "Lenovo", "Apple"), supplier.options)
    }

    @Test
    fun decisionRuleOfAnAskDecodes() {
        val f = File(dir, "contract-board-quorum.question-set.json")
        if (!f.exists()) return
        val d = Wire.json.decodeFromJsonElement(QuestionSet.serializer(), read(f)).decision!!
        assertEquals(DecisionRule.Quorum, d.rule)
        assertEquals(2, d.required)
        // One entry or a list.
        assertEquals(listOf("#board>=director"), d.by)
    }

    @Test
    fun repliesDecode() {
        for (f in examples("reply")) {
            val reply = Wire.json.decodeFromJsonElement(Reply.serializer(), read(f))
            assertEquals(ReplyStatus.Answered, reply.status, f.name)
            assertTrue(reply.answers.isNotEmpty())
            assertTrue(reply.signature != null)
        }
    }

    @Test
    fun signedRepliesCarryImagesThatMatchTheirHashes() {
        for (f in examples("reply")) {
            val raw = read(f)
            if ("e_signature" !in raw) continue
            val e = Wire.json.decodeFromJsonElement(Reply.serializer(), raw).eSignature
            assertTrue(e != null, f.name)
            assertTrue(e.method != SignatureMethod.Unknown, "${f.name}: unknown signature method")
            assertTrue(e.hashesMatch(), "${f.name}: e_signature hashes do not match the images")
        }
        for (f in examples("question-set")) {
            val raw = read(f)
            val expected = raw["require_signature"]?.jsonPrimitive?.booleanOrNull ?: false
            assertEquals(expected, Wire.json.decodeFromJsonElement(QuestionSet.serializer(), raw).requireSignature, f.name)
        }
    }
}
