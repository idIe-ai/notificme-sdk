package me.notific.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.svg.SvgDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import me.notific.client.Block
import me.notific.client.Card
import me.notific.client.CardButton
import me.notific.client.ChartKind

/**
 * Draws a notific.me card. With [answers], `options` and `input` blocks are
 * editable; without it they are not shown (a notification card).
 *
 * The questions inside a card are drawn with native components on purpose:
 * decisions are never rendered as HTML.
 */
@Composable
public fun NotificCard(
    card: Card,
    modifier: Modifier = Modifier,
    answers: AnswerState? = null,
    enabled: Boolean = true,
    // Before onButton: a trailing lambda keeps meaning onButton.
    questionExtra: (@Composable (me.notific.client.Question) -> Unit)? = null,
    // Inside the text fields of the questions (the app's dictation mic).
    questionFieldAction: (@Composable (me.notific.client.Question) -> Unit)? = null,
    onButton: (CardButton) -> Unit = {},
) {
    val accent = card.theme?.accent?.let(::parseColor)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        card.title?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        for (block in card.blocks) {
            when (block) {
                is Block.Text -> TextBlock(block)
                is Block.Metric -> MetricBlock(block)
                is Block.Chart -> ChartBlock(block, accent)
                is Block.Table -> TableBlock(block)
                is Block.BulletList -> ListBlock(block)
                is Block.Image -> ImageBlock(block)
                is Block.Progress -> ProgressBlock(block, accent)
                is Block.Buttons -> ButtonsBlock(block, enabled, onButton)
                is Block.Options -> answers?.let { s -> s.questions.firstOrNull { it.id == block.questionId }?.let { QuestionView(it, s, enabled = enabled, extra = questionExtra, fieldAction = questionFieldAction) } }
                is Block.Input -> answers?.let { s -> s.questions.firstOrNull { it.id == block.questionId }?.let { QuestionView(it, s, enabled = enabled, extra = questionExtra, fieldAction = questionFieldAction) } }
                is Block.Unknown -> Text(
                    stringResource(R.string.nm_unsupported, block.type),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** `**bold**` → bold spans. Nothing else of Markdown is interpreted. */
public fun lightMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var bold = false
    for (part in text.split("**")) {
        if (bold) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(part)
        if (bold) pop()
        bold = !bold
    }
}

@Composable
private fun TextBlock(block: Block.Text) {
    Text(lightMarkdown(block.text), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MetricBlock(block: Block.Metric) {
    Column {
        Text(block.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatValue(block.value), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            block.unit?.let {
                Spacer(Modifier.width(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            block.delta?.let {
                Spacer(Modifier.width(10.dp))
                // Green when it moved the good way: up, or down when lower is better.
                val up = it.startsWith("+")
                val down = it.startsWith("-") || it.startsWith("\u2212")
                val good = if (block.better == "lower") down else up
                val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                val color = when {
                    !up && !down -> MaterialTheme.colorScheme.onSurfaceVariant
                    good -> if (dark) Color(0xFF4ADE80) else Color(0xFF15803D)
                    else -> MaterialTheme.colorScheme.error
                }
                Text(it, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

private val SeriesColors = listOf(
    Color(0xFFFF6A00), Color(0xFF1E3A8A), Color(0xFF0F9D8A), Color(0xFF7C3AED), Color(0xFFDB2777), Color(0xFFCA8A04),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartBlock(block: Block.Chart, accent: Color?) {
    val colors = if (accent != null) listOf(accent) + SeriesColors.drop(1) else SeriesColors
    val grid = MaterialTheme.colorScheme.outlineVariant
    val all = block.series.flatMap { it.values }
    val dataMax = all.maxOrNull() ?: 0.0
    val dataMin = all.minOrNull() ?: 0.0
    // Bars start at zero; lines use the data range with some air above and below.
    val pad = if (block.kind == ChartKind.Line) (dataMax - dataMin).coerceAtLeast(1.0) * 0.12 else 0.0
    val max = if (block.kind == ChartKind.Bar) dataMax.coerceAtLeast(0.0) else dataMax + pad
    val min = if (block.kind == ChartKind.Bar) dataMin.coerceAtMost(0.0) else dataMin - pad
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    val points = block.series.maxOfOrNull { it.values.size } ?: 0
    val description = block.series.joinToString("; ") { s -> s.name + ": " + s.values.joinToString(", ") { formatNumber(it) } }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .semantics { contentDescription = description },
        ) {
            val h = size.height
            val w = size.width
            for (i in 0..3) {
                val y = h * i / 3f
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            fun y(v: Double) = (h - ((v - min) / span * h)).toFloat()
            if (points == 0) return@Canvas
            when (block.kind) {
                ChartKind.Line -> block.series.forEachIndexed { si, s ->
                    val step = if (points > 1) w / (points - 1) else 0f
                    val path = Path()
                    s.values.forEachIndexed { i, v ->
                        val p = Offset(step * i, y(v))
                        if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    drawPath(path, colors[si % colors.size], style = Stroke(width = 5f))
                    s.values.forEachIndexed { i, v -> drawCircle(colors[si % colors.size], 6f, Offset(step * i, y(v))) }
                }
                ChartKind.Bar -> {
                    val groupW = w / points
                    val n = block.series.size.coerceAtLeast(1)
                    val barW = groupW * 0.7f / n
                    block.series.forEachIndexed { si, s ->
                        s.values.forEachIndexed { i, v ->
                            val left = groupW * i + groupW * 0.15f + barW * si
                            val top = y(maxOf(v, 0.0))
                            val bottom = y(minOf(v, 0.0))
                            drawRect(colors[si % colors.size], Offset(left, top), Size(barW * 0.9f, (bottom - top).coerceAtLeast(2f)))
                        }
                    }
                }
            }
        }
        if (block.x.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (label in block.x) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        if (block.series.size > 1 || block.series.firstOrNull()?.name?.isNotBlank() == true) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                block.series.forEachIndexed { i, s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i % colors.size]))
                        Spacer(Modifier.width(4.dp))
                        Text(s.name, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableBlock(block: Block.Table) {
    val border = MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .horizontalScroll(rememberScrollState()),
    ) {
        Row(Modifier.padding(horizontal = 4.dp)) {
            block.columns.forEachIndexed { c, name ->
                Column(Modifier.widthIn(min = 72.dp, max = 220.dp).padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    for (row in block.rows) {
                        val cell = row.getOrNull(c)
                        Text(
                            if (cell == null || cell is JsonNull) "—" else formatValue(cell),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListBlock(block: Block.BulletList) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in block.items) {
            Row {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(lightMarkdown(item.text), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ImageBlock(block: Block.Image) {
    SubcomposeAsyncImage(
        model = block.src,
        imageLoader = cardImageLoader(),
        contentDescription = block.alt,
        contentScale = ContentScale.FillWidth,
        error = {
            Text(
                block.alt ?: block.src,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp, max = 260.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

private var sharedLoader: ImageLoader? = null

/** One image loader for every card, with SVG support (icons and diagrams are often SVG). */
@Composable
private fun cardImageLoader(): ImageLoader {
    val context = LocalContext.current.applicationContext
    return remember {
        sharedLoader ?: ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
            .also { sharedLoader = it }
    }
}

@Composable
private fun ProgressBlock(block: Block.Progress, accent: Color?) {
    val color = accent ?: MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.value?.let { v ->
            val pct = (v.coerceIn(0.0, 1.0) * 100).toInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { v.toFloat().coerceIn(0f, 1f) },
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                    modifier = Modifier.weight(1f).height(8.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.nm_progress_percent, pct), style = MaterialTheme.typography.labelMedium)
            }
        }
        if (block.steps.isNotEmpty()) {
            val current = (block.current ?: 0).coerceIn(0, block.steps.lastIndex)
            Text(stringResource(R.string.nm_step_of, current + 1, block.steps.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                block.steps.forEachIndexed { i, step ->
                    val done = i < current
                    val now = i == current
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (done || now) color else MaterialTheme.colorScheme.outlineVariant),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            step,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (done || now) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonsBlock(block: Block.Buttons, enabled: Boolean, onButton: (CardButton) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        block.buttons.forEachIndexed { i, b ->
            if (i == 0) {
                Button(onClick = { onButton(b) }, enabled = enabled) { Text(b.label) }
            } else {
                OutlinedButton(onClick = { onButton(b) }, enabled = enabled) { Text(b.label) }
            }
        }
    }
}

internal fun formatValue(p: JsonPrimitive): String {
    if (p.isString) return p.content
    p.longOrNull?.let { return "%,d".format(it) }
    p.doubleOrNull?.let { return formatNumber(it) }
    return p.content
}

internal fun formatNumber(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) "%,d".format(v.toLong()) else "%,.2f".format(v)

internal fun parseColor(hex: String): Color? = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrNull()
