package me.notific.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import me.notific.client.Card
import me.notific.client.Question
import me.notific.client.QuestionType

/** Colors of [AnsweredCard]. The official app passes its own theme's; the defaults follow Material. */
@Immutable
public data class AnsweredColors(
    /** The chosen option: radio, check box, the 1–5 box. */
    val accent: Color,
    val onAccent: Color,
    val yes: Color,
    val no: Color,
    /** Chosen answers and questions. */
    val text: Color,
    /** Options left out, hints. */
    val textSecondary: Color,
    val textTertiary: Color,
    /** Border of the options left out. */
    val outline: Color,
    /** Background of written answers (text, number, date). */
    val fill: Color,
)

public object AnsweredDefaults {
    @Composable
    public fun colors(): AnsweredColors {
        val c = MaterialTheme.colorScheme
        return AnsweredColors(
            accent = c.primary,
            onAccent = c.onPrimary,
            yes = Color(0xFF34C759),
            no = Color(0xFFFF3B30),
            text = c.onSurface,
            textSecondary = c.onSurfaceVariant,
            textTertiary = c.onSurfaceVariant.copy(alpha = 0.7f),
            outline = c.outlineVariant,
            fill = c.surfaceVariant,
        )
    }
}

/** The Apple curve (the web's `--ease-apple`): fast out, long soft landing. */
private val Ease = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private const val MOVE = 420

/** Shows or hides in place, top to bottom: the height closes while the content fades. */
@Composable
private fun RevealV(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(MOVE, easing = Ease), expandFrom = Alignment.Top) + fadeIn(tween(280, delayMillis = 90)),
        exit = shrinkVertically(tween(MOVE, easing = Ease), shrinkTowards = Alignment.Top) + fadeOut(tween(180)),
    ) { content() }
}

/** Shows or hides in place, side to side: what stays keeps its width and slides to the start. */
@Composable
private fun RevealH(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(tween(MOVE, easing = Ease), expandFrom = Alignment.Start) + fadeIn(tween(280, delayMillis = 90)),
        exit = shrinkHorizontally(tween(MOVE, easing = Ease), shrinkTowards = Alignment.Start) + fadeOut(tween(180)),
    ) { content() }
}

/** Space above an item that only exists when something visible comes before it (animated, so nothing jumps). */
@Composable
private fun gapAbove(hasBefore: Boolean, gap: Dp): Dp {
    val g by animateDpAsState(if (hasBefore) gap else 0.dp, tween(MOVE, easing = Ease), label = "gap")
    return g
}

/**
 * A decision that is over, drawn the way it was presented, with the answers
 * marked. With [onlyAnswers], what was not chosen goes away in place (choices
 * left out, hints, the card's title and supporting blocks, questions nobody
 * answered); switching back brings it in again, with nothing jumping.
 */
@Composable
public fun AnsweredCard(
    decision: AnsweredDecision,
    onlyAnswers: Boolean,
    modifier: Modifier = Modifier,
    colors: AnsweredColors = AnsweredDefaults.colors(),
) {
    val only = onlyAnswers && decision.hides
    val card = decision.card
    val title = card.title?.takeIf { it.isNotBlank() }
    val shown = card.blocks.map { decision.shows(it, only) }
    Column(modifier.fillMaxWidth().testTag("answered-card")) {
        if (title != null) {
            RevealV(!only) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.text)
            }
        }
        card.blocks.forEachIndexed { i, block ->
            val before = (title != null && !only) || shown.take(i).any { it }
            RevealV(shown[i]) {
                Box(Modifier.padding(top = gapAbove(before, 22.dp))) {
                    val q = decision.question(block)
                    if (q != null) {
                        AnsweredQuestion(q, decision.drafts[q.id] ?: Draft(), only, colors)
                    } else {
                        // Supporting block (text, metric, table…): the card renderer itself, read-only.
                        NotificCard(Card(blocks = listOf(block), theme = card.theme), enabled = false)
                    }
                }
            }
        }
    }
}

private val scaleOptions = listOf("1", "2", "3", "4", "5")

@Composable
private fun AnsweredQuestion(q: Question, d: Draft, only: Boolean, colors: AnsweredColors) {
    val scale = q.type == QuestionType.Single && !q.allowOther && q.options == scaleOptions
    val hint = when {
        scale -> null
        q.type == QuestionType.Single -> stringResource(R.string.nm_choose_one)
        q.type == QuestionType.Multi -> stringResource(R.string.nm_choose_many)
        else -> null
    }
    Column(Modifier.fillMaxWidth().testTag("answered-${q.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(q.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = colors.text, modifier = Modifier.weight(1f, fill = false))
            if (q.optional) {
                Text("  " + stringResource(R.string.nm_optional), style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
            }
        }
        if (hint != null) {
            RevealV(!only) { Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.textTertiary, modifier = Modifier.padding(top = 4.dp)) }
        }
        Spacer(Modifier.height(10.dp))
        when {
            q.type == QuestionType.YesNo -> YesNo(q, d, only, colors)
            scale -> Scale(q, d, only, colors)
            q.type == QuestionType.Single || q.type == QuestionType.Multi -> Choices(q, d, only, colors)
            else -> Written(q, d, colors)
        }
    }
}

@Composable
private fun YesNo(q: Question, d: Draft, only: Boolean, colors: AnsweredColors) {
    Row {
        for (value in listOf(true, false)) {
            val chosen = d.bool == value
            val tone = if (value) colors.yes else colors.no
            RevealH(!only || chosen) {
                Row(
                    Modifier
                        .padding(end = 10.dp)
                        .clip(CircleShape)
                        .background(if (chosen) tone.copy(alpha = 0.14f) else Color.Transparent)
                        .border(1.dp, if (chosen) tone.copy(alpha = 0.55f) else colors.outline, CircleShape)
                        .heightIn(min = 46.dp)
                        .padding(start = 8.dp, end = 18.dp)
                        .semantics { role = Role.RadioButton; selected = chosen }
                        .testTag(if (value) "answered-yes-${q.id}" else "answered-no-${q.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(if (chosen) tone else colors.fill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (value) Icons.Rounded.Check else Icons.Rounded.Close,
                            contentDescription = null,
                            tint = if (chosen) Color.White else colors.textTertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(if (value) R.string.nm_yes else R.string.nm_no),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (chosen) colors.text else colors.textSecondary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Scale(q: Question, d: Draft, only: Boolean, colors: AnsweredColors) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 8.dp
        val box = (maxWidth - gap * (scaleOptions.size - 1)) / scaleOptions.size
        Row {
            scaleOptions.forEachIndexed { i, o ->
                val chosen = !d.other && d.choice == o
                RevealH(!only || chosen) {
                    Box(
                        Modifier
                            .padding(end = if (i < scaleOptions.lastIndex) gap else 0.dp)
                            .width(box)
                            .height(52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (chosen) colors.accent else colors.fill)
                            .semantics { role = Role.RadioButton; selected = chosen }
                            .testTag("answered-scale-${q.id}-$o"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(o, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = if (chosen) colors.onAccent else colors.textSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun Choices(q: Question, d: Draft, only: Boolean, colors: AnsweredColors) {
    val multi = q.type == QuestionType.Multi
    val otherChosen = q.allowOther && d.other && d.text.isNotBlank()
    val rows = q.options.map { o -> o to (if (multi) o in d.choices else !d.other && d.choice == o) } +
        if (q.allowOther) listOf(null to otherChosen) else emptyList()
    Column {
        rows.forEachIndexed { i, (option, chosen) ->
            val before = rows.take(i).any { (_, c) -> !only || c }
            RevealV(!only || chosen) {
                Row(
                    Modifier
                        .padding(top = gapAbove(before, 8.dp))
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (chosen) colors.accent.copy(alpha = 0.09f) else Color.Transparent)
                        .border(1.dp, if (chosen) colors.accent.copy(alpha = 0.6f) else colors.outline, RoundedCornerShape(14.dp))
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .semantics { role = if (multi) Role.Checkbox else Role.RadioButton; selected = chosen }
                        .testTag(if (option == null) "answered-other-${q.id}" else "answered-option-${q.id}-$option"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Mark(multi, chosen, colors)
                    Spacer(Modifier.width(12.dp))
                    when {
                        option != null -> Text(
                            option,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                            color = if (chosen) colors.text else colors.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        chosen -> Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.nm_other), style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                            Text(d.text.trim(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = colors.text)
                        }
                        else -> {
                            Text(stringResource(R.string.nm_other), style = MaterialTheme.typography.bodyLarge, color = colors.textSecondary, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.Edit, null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Radio dot or check box, drawn (not a disabled Material control: an answer is not greyed out). */
@Composable
private fun Mark(multi: Boolean, chosen: Boolean, colors: AnsweredColors) {
    val shape = if (multi) RoundedCornerShape(6.dp) else CircleShape
    Box(
        Modifier
            .size(20.dp)
            .clip(shape)
            .background(if (chosen) colors.accent else Color.Transparent)
            .border(1.5.dp, if (chosen) colors.accent else colors.outline, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (chosen && multi) Icon(Icons.Rounded.Check, null, tint = colors.onAccent, modifier = Modifier.size(15.dp))
        if (chosen && !multi) Box(Modifier.size(7.dp).clip(CircleShape).background(colors.onAccent))
    }
}

/** Text, voice, number and date: what was written, in a filled field. */
@Composable
private fun Written(q: Question, d: Draft, colors: AnsweredColors) {
    val shape = RoundedCornerShape(14.dp)
    val text = when (q.type) {
        QuestionType.Number -> AnswerState.parseNumber(d.text)?.let { NumberFormat.getInstance().format(it) } ?: d.text
        QuestionType.Date -> runCatching { LocalDate.parse(d.text.trim().take(10)).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) }.getOrDefault(d.text)
        else -> d.text
    }.trim()
    val short = q.type == QuestionType.Number || q.type == QuestionType.Date
    if (text.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .border(1.dp, colors.outline, shape)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.nm_no_answer), style = MaterialTheme.typography.bodyLarge, color = colors.textTertiary)
        }
        return
    }
    Box(
        Modifier
            .then(if (short) Modifier.widthIn(min = 140.dp) else Modifier.fillMaxWidth())
            .clip(shape)
            .background(colors.fill)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("answered-text-${q.id}"),
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (short) FontWeight.Medium else FontWeight.Normal, color = colors.text)
    }
}
