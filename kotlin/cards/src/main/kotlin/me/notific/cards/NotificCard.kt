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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import me.notific.client.InlineMarkdown
import me.notific.client.InlineSpan

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

/** Light Markdown ([InlineMarkdown]) with links in the default text color. */
public fun lightMarkdown(text: String): AnnotatedString = lightMarkdown(text, linkColor = Color.Unspecified)

/**
 * Light Markdown ([InlineMarkdown]): bold, italic, code and links. Links are
 * [LinkAnnotation.Url]s, so a `Text` opens them with the `LocalUriHandler`
 * (the system browser). When the label hides the destination, its domain
 * follows in [hostColor], so no one can hide an address behind a name.
 * With [markdown] false (a notification summary) only bare addresses become
 * links.
 */
public fun lightMarkdown(
    text: String,
    linkColor: Color,
    hostColor: Color = Color.Unspecified,
    codeBackground: Color = Color.Unspecified,
    markdown: Boolean = true,
): AnnotatedString = buildAnnotatedString {
    for (span in InlineMarkdown.parse(text, markdown)) {
        when (span) {
            is InlineSpan.Text -> emphasized(span.bold, span.italic) { append(span.text) }
            is InlineSpan.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(span.text) }
            is InlineSpan.Link -> {
                emphasized(span.bold, span.italic) {
                    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                    withLink(LinkAnnotation.Url(span.url, style)) { append(span.text) }
                }
                if (span.showHost && span.host.isNotEmpty()) {
                    withStyle(SpanStyle(color = hostColor, fontSize = 0.85.em)) { append(" (${span.host})") }
                }
            }
        }
    }
}

private inline fun AnnotatedString.Builder.emphasized(bold: Boolean, italic: Boolean, block: AnnotatedString.Builder.() -> Unit) {
    if (!bold && !italic) {
        block()
        return
    }
    withStyle(
        SpanStyle(
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
        ),
    ) { block() }
}

/** [lightMarkdown] in the theme's colors, for a `Text` in Material 3. */
@Composable
public fun rememberLightMarkdown(text: String, markdown: Boolean = true): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return remember(text, markdown, colors.primary, colors.onSurfaceVariant, colors.surfaceVariant) {
        lightMarkdown(text, colors.primary, colors.onSurfaceVariant, colors.surfaceVariant, markdown)
    }
}

@Composable
private fun TextBlock(block: Block.Text) {
    Text(rememberLightMarkdown(block.text), style = MaterialTheme.typography.bodyMedium)
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

private val TableCellPadding = 8.dp
private val TableEdgePadding = 4.dp
private val TableRowGap = 8.dp

/** A text column never gets narrower than this while the table still fits. */
private val TableMinTextColumn = 72.dp

/** In a table that scrolls, a text column wraps past this width. */
private val TableMaxTextColumn = 220.dp

private val TableFade = 28.dp

/**
 * The `table` block. Columns of numbers are right-aligned and never wrap;
 * text columns wrap. When the columns cannot fit even with the text wrapped,
 * the table scrolls sideways and fades at the edge that has more to show.
 *
 * Column widths come from the drawn cells themselves (their intrinsic
 * widths: same style, weight, font and padding), so a width is never a few
 * pixels short of its text.
 */
@Composable
private fun TableBlock(block: Block.Table) {
    val border = MaterialTheme.colorScheme.outlineVariant
    val headerStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
    val cellStyle = MaterialTheme.typography.bodySmall
    val columns = block.columns.size
    if (columns == 0) return
    val texts = remember(block) {
        block.rows.map { row -> List(columns) { c -> row.getOrNull(c)?.takeIf { it !is JsonNull }?.let(::formatValue) ?: "—" } }
    }
    val numeric = remember(block) { numericColumns(columns, block.rows) }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, border, RoundedCornerShape(8.dp)),
    ) {
        val available = constraints.maxWidth - with(LocalDensity.current) { TableEdgePadding.roundToPx() * 2 }
        val scroll = rememberScrollState()
        Box(
            Modifier
                .fadingEdges(scroll, TableFade)
                .horizontalScroll(scroll)
                .padding(horizontal = TableEdgePadding, vertical = 8.dp),
        ) {
            TableGrid(columns, numeric, available) {
                block.columns.forEachIndexed { c, name -> TableCell(name, headerStyle, numeric[c], header = true) }
                for (row in texts) row.forEachIndexed { c, text -> TableCell(text, cellStyle, numeric[c], header = false) }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, style: TextStyle, numeric: Boolean, header: Boolean) {
    Text(
        text,
        style = style,
        textAlign = if (numeric) TextAlign.End else TextAlign.Start,
        // A number keeps its natural width, so one line always holds it.
        maxLines = if (numeric) 1 else if (header) 3 else 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = TableCellPadding),
    )
}

/**
 * Lays the cells (header row first, row by row, [columns] per row) out in a
 * grid: each column as wide as [planTable] says for [available] pixels,
 * each row as tall as its tallest cell. Inside a horizontal scroll the
 * incoming width is unbounded, hence [available] from outside.
 */
@Composable
private fun TableGrid(columns: Int, numeric: List<Boolean>, available: Int, content: @Composable () -> Unit) {
    Layout(content) { measurables, _ ->
        val rows = measurables.size / columns
        val natural = IntArray(columns)
        val minWord = IntArray(columns)
        measurables.forEachIndexed { i, m ->
            val c = i % columns
            natural[c] = maxOf(natural[c], m.maxIntrinsicWidth(Constraints.Infinity))
            if (!numeric[c]) minWord[c] = maxOf(minWord[c], m.minIntrinsicWidth(Constraints.Infinity))
        }
        val pad = TableCellPadding.roundToPx() * 2
        val plan = planTable(
            natural = natural.toList(),
            flexible = numeric.map { !it },
            available = available,
            minFlex = TableMinTextColumn.roundToPx() + pad,
            maxFlex = TableMaxTextColumn.roundToPx() + pad,
            minWord = minWord.toList(),
        )
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(plan.widths[i % columns])) }
        val gap = TableRowGap.roundToPx()
        val heights = List(rows) { r -> (0 until columns).maxOf { c -> placeables[r * columns + c].height } }
        val height = heights.sum() + gap * (rows - 1).coerceAtLeast(0)
        layout(plan.widths.sum(), height) {
            var y = 0
            for (r in 0 until rows) {
                var x = 0
                for (c in 0 until columns) {
                    placeables[r * columns + c].placeRelative(x, y)
                    x += plan.widths[c]
                }
                y += heights[r] + gap
            }
        }
    }
}


/**
 * Fades the content out at a side that can still scroll, so a cut-off
 * column reads as "more this way" instead of as missing.
 */
private fun Modifier.fadingEdges(scroll: ScrollState, width: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = width.toPx().coerceAtMost(size.width / 3)
        if (scroll.canScrollForward) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.canScrollBackward) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

@Composable
private fun ListBlock(block: Block.BulletList) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (item in block.items) {
            Row {
                Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(rememberLightMarkdown(item.text), style = MaterialTheme.typography.bodyMedium)
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
