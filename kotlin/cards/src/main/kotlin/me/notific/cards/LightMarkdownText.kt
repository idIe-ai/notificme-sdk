package me.notific.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.notific.client.InlineMarkdown
import me.notific.client.InlineSpan
import me.notific.client.MdBlock

/**
 * Light Markdown ([InlineMarkdown]) with its blocks: paragraphs, bullet and
 * numbered lists, and quotes. For a card `text` block and a question's
 * `context`.
 *
 * Bullets are drawn in [accent]; numbers as written, in [secondary], aligned
 * to the widest one; a wrapped item lines up with its text, not under the
 * marker. A quote has a thin bar at its left and its text in [secondary].
 * Bold, italic, code and links look as in [lightMarkdown] (links in [accent],
 * with the domain when the label hides it). With [markdown] false (a
 * notification summary) the text is drawn as [lightMarkdown] does it, with no
 * blocks.
 */
@Composable
public fun LightMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
    markdown: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    secondary: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val parts = remember(text, markdown, accent, secondary, codeBackground) {
        InlineMarkdown.blocks(text, markdown).map { it.toPart(accent, secondary, codeBackground) }
    }
    val single = parts.singleOrNull()
    if (single is Part.Para) {
        Text(single.text, modifier, color = color, style = style)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (part in parts) {
            when (part) {
                is Part.Para -> Text(part.text, color = color, style = style)
                is Part.Items -> ItemsPart(part, style, color, accent, secondary)
                is Part.Quote -> Text(
                    part.text,
                    Modifier
                        .drawBehind {
                            drawRoundRect(
                                secondary.copy(alpha = 0.35f),
                                size = Size(QUOTE_BAR.toPx(), size.height),
                                cornerRadius = CornerRadius(QUOTE_BAR.toPx() / 2),
                            )
                        }
                        .padding(start = QUOTE_BAR + 10.dp),
                    color = secondary,
                    style = style,
                )
            }
        }
    }
}

@Composable
private fun ItemsPart(part: Part.Items, style: TextStyle, color: Color, accent: Color, secondary: Color) {
    val markerStyle = style.copy(fontFeatureSettings = "tnum")
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // Every marker as wide as the widest ("9." under "10."), so the texts start in one column.
    val markerWidth = remember(part.markers, markerStyle, density) {
        with(density) { part.markers.maxOf { measurer.measure(it, markerStyle).size.width }.toDp() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        part.texts.forEachIndexed { i, item ->
            Row {
                Text(
                    part.markers[i],
                    Modifier.width(markerWidth).alignByBaseline(),
                    color = if (part.ordered) secondary else accent,
                    style = markerStyle,
                    textAlign = TextAlign.End,
                )
                Spacer(Modifier.width(8.dp))
                Text(item, Modifier.weight(1f).alignByBaseline(), color = color, style = style)
            }
        }
    }
}

/** A block ready to draw. */
@Immutable
private sealed interface Part {
    class Para(val text: AnnotatedString) : Part
    class Items(val ordered: Boolean, val markers: List<String>, val texts: List<AnnotatedString>) : Part
    class Quote(val text: AnnotatedString) : Part
}

private fun MdBlock.toPart(accent: Color, secondary: Color, codeBackground: Color): Part {
    fun styled(list: List<InlineSpan>) = buildAnnotatedString { appendSpans(list, accent, secondary, codeBackground) }
    return when (this) {
        is MdBlock.Text -> Part.Para(styled(spans))
        is MdBlock.ListBlock -> Part.Items(
            ordered = ordered,
            markers = items.map { item -> item.number?.let { "$it." } ?: "•" },
            texts = items.map { styled(it.spans) },
        )
        is MdBlock.Quote -> Part.Quote(
            buildAnnotatedString {
                lines.forEachIndexed { i, line ->
                    if (i > 0) append('\n')
                    appendSpans(line, accent, secondary, codeBackground)
                }
            },
        )
    }
}

private val QUOTE_BAR = 3.dp
