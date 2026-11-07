package me.notific.client

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Declarative card (`card.schema.json`). Draw it with your own components.
 * Decisions (`options`, `input`) point to questions of the question set.
 */
@Serializable
public data class Card(
    val title: String? = null,
    val theme: CardTheme? = null,
    val blocks: List<Block> = emptyList(),
) {
    /**
     * This card plus an `options` or `input` block for every question that
     * no block shows yet, so no question is left out of the screen.
     */
    public fun withQuestions(questions: List<Question>): Card {
        val shown = blocks.mapNotNull {
            when (it) {
                is Block.Options -> it.questionId
                is Block.Input -> it.questionId
                else -> null
            }
        }.toSet()
        val extra = questions.filter { it.id !in shown }.map { it.block() }
        return if (extra.isEmpty()) this else copy(blocks = blocks + extra)
    }
}

@Serializable
public data class CardTheme(
    /** `#RRGGBB`. */
    val accent: String? = null,
    /** Icon URL or name. */
    val icon: String? = null,
)

/**
 * One block of a card. Block types this version does not know arrive as
 * [Unknown], so a newer server never breaks an older app.
 */
@Serializable(with = BlockSerializer::class)
public sealed interface Block {
    public val type: String

    /** Text with light Markdown (`**bold**`). */
    @Serializable
    public data class Text(val text: String) : Block {
        override val type: String get() = "text"
    }

    @Serializable
    public data class Metric(
        val label: String,
        /** Number or text, as sent. */
        val value: JsonPrimitive,
        val unit: String? = null,
        /** Change, e.g. `+18%`. */
        val delta: String? = null,
        /** Which way is good: `higher` (default) or `lower` (a default rate, a response time). */
        val better: String? = null,
    ) : Block {
        override val type: String get() = "metric"
    }

    @Serializable
    public data class Chart(
        val kind: ChartKind = ChartKind.Line,
        val x: List<String> = emptyList(),
        val series: List<ChartSeries> = emptyList(),
    ) : Block {
        override val type: String get() = "chart"
    }

    @Serializable
    public data class Table(
        val columns: List<String> = emptyList(),
        /** Cells are strings, numbers or `null` ([kotlinx.serialization.json.JsonNull]). */
        val rows: List<List<JsonPrimitive>> = emptyList(),
    ) : Block {
        override val type: String get() = "table"
    }

    /** The protocol's `list` block. */
    @Serializable
    public data class BulletList(val items: List<ListItem> = emptyList()) : Block {
        override val type: String get() = "list"
    }

    @Serializable
    public data class Image(val src: String, val alt: String? = null) : Block {
        override val type: String get() = "image"
    }

    /** A bar ([value] from 0 to 1) and/or named [steps] with the [current] one. */
    @Serializable
    public data class Progress(
        val value: Double? = null,
        val steps: List<String> = emptyList(),
        val current: Int? = null,
    ) : Block {
        override val type: String get() = "progress"
    }

    @Serializable
    public data class Buttons(val buttons: List<CardButton> = emptyList()) : Block {
        override val type: String get() = "buttons"
    }

    /** Shows a `yesno`, `single` or `multi` question of the question set. */
    @Serializable
    public data class Options(@SerialName("question_id") val questionId: String) : Block {
        override val type: String get() = "options"
    }

    /** Shows a `text`, `voice`, `number` or `date` question of the question set. */
    @Serializable
    public data class Input(@SerialName("question_id") val questionId: String) : Block {
        override val type: String get() = "input"
    }

    /** A block type this version does not know, kept as received. */
    public data class Unknown(override val type: String, val raw: JsonObject) : Block
}

@Serializable
public enum class ChartKind {
    @SerialName("line") Line,
    @SerialName("bar") Bar,
}

@Serializable
public data class ChartSeries(val name: String, val values: List<Double> = emptyList())

@Serializable
public data class ListItem(val text: String, val icon: String? = null)

/** A link ([url]) or an action registered by a plugin ([action]). */
@Serializable
public data class CardButton(
    val label: String,
    val url: String? = null,
    val action: String? = null,
)

internal fun Question.block(): Block = when (type) {
    QuestionType.YesNo, QuestionType.Single, QuestionType.Multi -> Block.Options(id)
    else -> Block.Input(id)
}

internal object BlockSerializer : KSerializer<Block> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("me.notific.client.Block") {
        element<String>("type")
    }

    override fun deserialize(decoder: Decoder): Block {
        val input = decoder as? JsonDecoder ?: throw SerializationException("Block can only be read from JSON")
        val obj = input.decodeJsonElement().jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val fields = JsonObject(obj - "type")
        val json = input.json
        return try {
            when (type) {
                "text" -> json.decodeFromJsonElement(Block.Text.serializer(), fields)
                "metric" -> json.decodeFromJsonElement(Block.Metric.serializer(), fields)
                "chart" -> json.decodeFromJsonElement(Block.Chart.serializer(), fields)
                "table" -> json.decodeFromJsonElement(Block.Table.serializer(), fields)
                "list" -> json.decodeFromJsonElement(Block.BulletList.serializer(), fields)
                "image" -> json.decodeFromJsonElement(Block.Image.serializer(), fields)
                "progress" -> json.decodeFromJsonElement(Block.Progress.serializer(), fields)
                "buttons" -> json.decodeFromJsonElement(Block.Buttons.serializer(), fields)
                "options" -> json.decodeFromJsonElement(Block.Options.serializer(), fields)
                "input" -> json.decodeFromJsonElement(Block.Input.serializer(), fields)
                else -> Block.Unknown(type, obj)
            }
        } catch (e: SerializationException) {
            // A known type with fields this version cannot read: keep it, don't fail the card.
            Block.Unknown(type, obj)
        } catch (e: IllegalArgumentException) {
            Block.Unknown(type, obj)
        }
    }

    override fun serialize(encoder: Encoder, value: Block) {
        val output = encoder as? JsonEncoder ?: throw SerializationException("Block can only be written as JSON")
        val json = output.json
        val fields = when (value) {
            is Block.Text -> json.encodeToJsonElement(Block.Text.serializer(), value)
            is Block.Metric -> json.encodeToJsonElement(Block.Metric.serializer(), value)
            is Block.Chart -> json.encodeToJsonElement(Block.Chart.serializer(), value)
            is Block.Table -> json.encodeToJsonElement(Block.Table.serializer(), value)
            is Block.BulletList -> json.encodeToJsonElement(Block.BulletList.serializer(), value)
            is Block.Image -> json.encodeToJsonElement(Block.Image.serializer(), value)
            is Block.Progress -> json.encodeToJsonElement(Block.Progress.serializer(), value)
            is Block.Buttons -> json.encodeToJsonElement(Block.Buttons.serializer(), value)
            is Block.Options -> json.encodeToJsonElement(Block.Options.serializer(), value)
            is Block.Input -> json.encodeToJsonElement(Block.Input.serializer(), value)
            is Block.Unknown -> {
                output.encodeJsonElement(value.raw)
                return
            }
        }.jsonObject
        output.encodeJsonElement(JsonObject(mapOf("type" to JsonPrimitive(value.type)) + fields))
    }
}
