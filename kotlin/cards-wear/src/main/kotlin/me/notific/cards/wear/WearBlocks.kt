package me.notific.cards.wear

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.text.NumberFormat
import java.util.Locale
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import me.notific.client.Block
import me.notific.client.Card
import me.notific.client.InlineMarkdown
import me.notific.client.InlineSpan
import me.notific.client.MdBlock
import me.notific.client.Severity

/** Colors of the severities on a dark watch face: red, orange, amber, blue, gray. */
public object WearSeverity {
    public val Critical: Color = Color(0xFFFF5A52)
    public val High: Color = Color(0xFFFF8A3D)
    public val Medium: Color = Color(0xFFFBBF24)
    public val Low: Color = Color(0xFF60A5FA)
    public val Info: Color = Color(0xFF8E8E98)

    public fun color(severity: Severity): Color = when (severity) {
        Severity.Critical -> Critical
        Severity.High -> High
        Severity.Medium -> Medium
        Severity.Low -> Low
        Severity.Info -> Info
    }
}

/**
 * Light Markdown for the watch: bold, italic and code. Links can't be opened
 * on a watch, so they are plain text (with the domain when the label hides it).
 *
 * Inline marks only: lists and quotes stay as written. For a card `text`
 * block or a question's `context`, use [WearMarkdownText], which draws them.
 */
public fun wearMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    appendWearSpans(InlineMarkdown.parse(text))
}

/**
 * Light Markdown ([InlineMarkdown]) with its blocks, compact for a round
 * screen: a list item is a line that starts with "•" (in [accent]) or its
 * number as written, wrapped lines aligned with the text; a quote has a thin
 * bar at its left and its text in [secondary]. Paragraphs follow [textAlign];
 * lists and quotes start at the left. Inline marks as in [wearMarkdown].
 */
@Composable
public fun WearMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    secondary: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val blocks = remember(text) { InlineMarkdown.blocks(text) }
    val single = blocks.singleOrNull()
    if (single is MdBlock.Text) {
        Text(remember(single) { buildAnnotatedString { appendWearSpans(single.spans) } }, modifier, color = color, textAlign = textAlign, style = style)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Text -> Text(
                    remember(block) { buildAnnotatedString { appendWearSpans(block.spans) } },
                    Modifier.fillMaxWidth(),
                    color = color,
                    textAlign = textAlign,
                    style = style,
                )
                is MdBlock.ListBlock -> WearItems(block, style, color, accent, secondary)
                is MdBlock.Quote -> Text(
                    remember(block) {
                        buildAnnotatedString {
                            block.lines.forEachIndexed { i, line ->
                                if (i > 0) append('\n')
                                appendWearSpans(line)
                            }
                        }
                    },
                    Modifier
                        .fillMaxWidth()
                        .drawBehind {
                            drawRoundRect(
                                secondary.copy(alpha = 0.45f),
                                size = Size(2.dp.toPx(), size.height),
                                cornerRadius = CornerRadius(1.dp.toPx()),
                            )
                        }
                        .padding(start = 8.dp),
                    color = secondary,
                    style = style,
                )
            }
        }
    }
}

@Composable
private fun WearItems(block: MdBlock.ListBlock, style: TextStyle, color: Color, accent: Color, secondary: Color) {
    val markers = remember(block) { block.items.map { item -> item.number?.let { "$it." } ?: "•" } }
    val markerStyle = style.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val markerWidth = remember(markers, markerStyle, density) {
        with(density) { markers.maxOf { measurer.measure(it, markerStyle).size.width }.toDp() }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        block.items.forEachIndexed { i, item ->
            Row {
                Text(
                    markers[i],
                    Modifier.width(markerWidth).alignByBaseline(),
                    color = if (block.ordered) secondary else accent,
                    textAlign = TextAlign.End,
                    style = markerStyle,
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    remember(item) { buildAnnotatedString { appendWearSpans(item.spans) } },
                    Modifier.weight(1f).alignByBaseline(),
                    color = color,
                    style = style,
                )
            }
        }
    }
}

/**
 * [text] in one plain string for a two-line inbox row: list items as
 * `• item` or `3. item`, quote lines as their text, links as in [wearMarkdown]
 * (with the domain when the label hides it).
 */
internal fun wearPlain(text: String): String = InlineMarkdown.blocks(text).joinToString("\n") { block ->
    fun line(spans: List<InlineSpan>) = buildAnnotatedString { appendWearSpans(spans) }.text
    when (block) {
        is MdBlock.Text -> line(block.spans)
        is MdBlock.ListBlock -> block.items.joinToString("\n") { (if (it.number != null) "${it.number}. " else "• ") + line(it.spans) }
        is MdBlock.Quote -> block.lines.joinToString("\n") { line(it) }
    }
}

private fun AnnotatedString.Builder.appendWearSpans(spans: List<InlineSpan>) {
    for (span in spans) {
        when (span) {
            is InlineSpan.Text -> withStyle(
                SpanStyle(
                    fontWeight = if (span.bold) FontWeight.SemiBold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                ),
            ) { append(span.text) }
            is InlineSpan.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(span.text) }
            is InlineSpan.Link -> {
                withStyle(SpanStyle(fontWeight = if (span.bold) FontWeight.SemiBold else null)) { append(span.text) }
                if (span.showHost) append(" (${span.host})")
            }
        }
    }
}

/**
 * The blocks of [card] a watch draws, in order. Questions (`options`,
 * `input`) are drawn by [WearQuestion]; link buttons and images belong to
 * the phone (an image with a description shows the description).
 */
public fun Card.watchBlocks(): List<Block> = blocks.filter { it.shownOnWatch }

/** See [watchBlocks]. */
public val Block.shownOnWatch: Boolean
    get() = when (this) {
        is Block.Options, is Block.Input, is Block.Buttons, is Block.Unknown -> false
        is Block.Image -> !alt.isNullOrBlank()
        is Block.Chart -> series.any { it.values.size >= 2 }
        is Block.Table -> columns.isNotEmpty() && rows.isNotEmpty()
        is Block.BulletList -> items.isNotEmpty()
        is Block.Progress -> value != null || steps.isNotEmpty()
        else -> true
    }

/**
 * A value from a card the way people read it in [locale]: `1,234`, `12.5`
 * (`1.234`, `12,5` in Portuguese); text stays as sent.
 */
public fun formatCardValue(value: JsonPrimitive, locale: Locale = Locale.getDefault()): String {
    if (value is JsonNull) return "—"
    if (value.isString) return value.content
    val number: Number = value.longOrNull ?: value.doubleOrNull?.takeIf { it.isFinite() } ?: return value.content
    return NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(number)
}

/** One block of a card, sized for a watch (one item of a scrolling list). */
@Composable
public fun WearBlock(block: Block, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        when (block) {
            is Block.Text -> WearMarkdownText(
                block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                accent = accent,
            )
            is Block.Metric -> MetricBlock(block)
            is Block.Chart -> SparkBlock(block, accent)
            is Block.Table -> TableBlock(block)
            is Block.BulletList -> ListBlock(block, accent)
            is Block.Progress -> ProgressBlock(block, accent)
            is Block.Image -> Text(
                block.alt.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Unit
        }
    }
}

@Composable
private fun MetricBlock(block: Block.Metric) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(block.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatCardValue(block.value), style = MaterialTheme.typography.numeralSmall, color = MaterialTheme.colorScheme.onSurface)
            block.unit?.let {
                Spacer(Modifier.width(4.dp))
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        block.delta?.let {
            val up = it.startsWith("+")
            val down = it.startsWith("-") || it.startsWith("−")
            val good = if (block.better == "lower") down else up
            val color = when {
                !up && !down -> MaterialTheme.colorScheme.onSurfaceVariant
                good -> Color(0xFF4ADE80)
                else -> MaterialTheme.colorScheme.error
            }
            Text(it, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** A chart becomes a sparkline of its first series (the watch has no room for axes). */
@Composable
private fun SparkBlock(block: Block.Chart, accent: Color) {
    val series = block.series.firstOrNull { it.values.size >= 2 } ?: return
    Column(Modifier.fillMaxWidth()) {
        Text(series.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(36.dp)) {
            val values = series.values
            val min = values.min()
            val max = values.max()
            val span = (max - min).takeIf { it > 0 } ?: 1.0
            val step = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val p = Offset(i * step, (size.height * (1 - (v - min) / span)).toFloat())
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, accent, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val last = Offset(size.width, (size.height * (1 - (values.last() - min) / span)).toFloat())
            drawCircle(accent, radius = 3.5.dp.toPx(), center = last)
        }
        Text(
            formatCardValue(JsonPrimitive(series.values.last())),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

/** A table becomes "column: value" lines of its first rows. */
@Composable
private fun TableBlock(block: Block.Table) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in block.rows.take(MAX_TABLE_ROWS)) {
            Column {
                block.columns.forEachIndexed { c, name ->
                    val cell = row.getOrNull(c) ?: return@forEachIndexed
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("$name  ") }
                            append(formatCardValue(cell))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (block.rows.size > MAX_TABLE_ROWS) {
            Text(pluralStringResource(R.plurals.nmw_more_rows, block.rows.size - MAX_TABLE_ROWS, block.rows.size - MAX_TABLE_ROWS), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ListBlock(block: Block.BulletList, accent: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in block.items) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Text(remember(item.text) { wearMarkdown(item.text) }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ProgressBlock(block: Block.Progress, accent: Color) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        block.value?.let { v ->
            val fraction = v.toFloat().coerceIn(0f, 1f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(CircleShape).background(accent))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nmw_percent, (fraction * 100).toInt()), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (block.steps.isNotEmpty()) {
            val current = (block.current ?: 0).coerceIn(0, block.steps.lastIndex)
            Text(
                stringResource(R.string.nmw_step_of, current + 1, block.steps.size) + " · " + block.steps[current],
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val MAX_TABLE_ROWS = 3
