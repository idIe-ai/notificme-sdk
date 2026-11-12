package me.notific.cards.wear

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.RadioButtonDefaults
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import me.notific.client.Question
import me.notific.client.QuestionType

/**
 * The items of one question in a `TransformingLazyColumn`: its text, then a
 * radio button per answer ("Yes"/"No", or each option of a `single`). A tap
 * picks the answer in [state]; nothing is sent until the app replies with
 * [WearAnswerState.toAnswers].
 *
 * With [onSpeak] (the app has a speech recognizer and [state] was made with
 * `dictation`), typed answers are spoken instead: `text`, `voice` and `number`
 * get a microphone button and the transcription, and a `single` with
 * `allow_other` gets an "Other…" choice. The app runs the recognizer and
 * hands the text back with [WearAnswerState.setSpoken]. Questions the watch
 * can't answer add no items.
 *
 * [yesColor] and [noColor] tint "Yes" and "No" gently (a soft container, the
 * icon in the color, a stronger container once picked); null keeps the theme.
 *
 * ```
 * TransformingLazyColumn(state = listState) {
 *     for (q in answers.questions) wearQuestionItems(q, answers, spec, onSpeak = ::listen)
 * }
 * ```
 */
public fun TransformingLazyColumnScope.wearQuestionItems(
    question: Question,
    state: WearAnswerState,
    transformationSpec: TransformationSpec,
    enabled: Boolean = true,
    showText: Boolean = true,
    onSpeak: ((Question) -> Unit)? = null,
    yesColor: Color? = null,
    noColor: Color? = null,
) {
    val speech = onSpeak != null && state.dictation
    if (!question.answerableOnWatch(speech)) return
    if (showText && question.text.isNotBlank()) {
        item(key = "q:${question.id}") {
            Text(
                remember(question.text) { wearMarkdown(question.text) },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp, start = 6.dp, end = 6.dp)
                    .transformedHeight(this, transformationSpec),
            )
        }
    }
    if (question.optional) {
        item(key = "q:${question.id}:optional") {
            Text(
                stringResource(R.string.nmw_optional),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
            )
        }
    }
    when (question.type) {
        QuestionType.YesNo -> for (value in listOf(true, false)) {
            item(key = "q:${question.id}:$value") {
                val tint = if (value) yesColor else noColor
                RadioButton(
                    selected = state.yesNo(question.id) == value,
                    onSelect = { state.setYesNo(question.id, value) },
                    enabled = enabled,
                    colors = tint?.let { tintedRadioColors(it) } ?: RadioButtonDefaults.radioButtonColors(),
                    transformation = SurfaceTransformation(transformationSpec),
                    icon = {
                        Icon(
                            if (value) Icons.Rounded.Check else Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) {
                    Text(stringResource(if (value) R.string.nmw_yes else R.string.nmw_no))
                }
            }
        }
        QuestionType.Single -> {
            question.options.forEachIndexed { i, option ->
                item(key = "q:${question.id}:o$i") {
                    RadioButton(
                        selected = state.choice(question.id) == option,
                        onSelect = { state.setChoice(question.id, option) },
                        enabled = enabled,
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    ) {
                        Text(option, maxLines = 3)
                    }
                }
            }
            if (speech && question.allowOther) {
                item(key = "q:${question.id}:other") {
                    val said = state.spoken(question.id)
                    RadioButton(
                        selected = said != null,
                        onSelect = { onSpeak?.invoke(question) },
                        enabled = enabled,
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = { Icon(MicIcon, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        secondaryLabel = said?.let { { Text(it, maxLines = 3) } },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    ) {
                        Text(stringResource(R.string.nmw_other_spoken))
                    }
                }
            }
        }
        QuestionType.Text, QuestionType.Voice, QuestionType.Number -> {
            val said = state.spoken(question.id)
            if (said != null) {
                item(key = "q:${question.id}:said") {
                    val isNumber = question.type == QuestionType.Number
                    val number = state.spokenNumber(question.id)
                    Card(
                        onClick = { onSpeak?.invoke(question) },
                        enabled = enabled,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    ) {
                        Text("“$said”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        when {
                            // What will be sent, so "twelve and a half" read as 12 is seen before sending.
                            isNumber && number != null -> Text(
                                "= " + formatCardValue(kotlinx.serialization.json.JsonPrimitive(number)),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            isNumber -> Text(stringResource(R.string.nmw_say_a_number), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item(key = "q:${question.id}:speak") {
                Button(
                    onClick = { onSpeak?.invoke(question) },
                    enabled = enabled,
                    colors = if (said == null) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    icon = { Icon(MicIcon, contentDescription = null, modifier = Modifier.size(22.dp)) },
                    transformation = SurfaceTransformation(transformationSpec),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                ) {
                    Text(stringResource(if (said == null) R.string.nmw_speak else R.string.nmw_speak_again))
                }
            }
        }
        else -> Unit
    }
}

/** A soft container in [tint], the icon and the control in it; the text stays readable on the dark theme. */
@Composable
private fun tintedRadioColors(tint: Color) = RadioButtonDefaults.radioButtonColors(
    selectedContainerColor = tint.copy(alpha = 0.34f),
    selectedContentColor = MaterialTheme.colorScheme.onSurface,
    selectedSecondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    selectedIconColor = tint,
    selectedControlColor = tint,
    unselectedContainerColor = tint.copy(alpha = 0.14f),
    unselectedContentColor = MaterialTheme.colorScheme.onSurface,
    unselectedSecondaryContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedIconColor = tint,
)

/** The Material "mic" symbol (Apache 2.0), not in the core icon set. */
public val MicIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Mic", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            addPathNodes(
                "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                    "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11L5,11c0,3.41 2.72,6.23 6,6.72L11,21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z",
            ),
            fill = SolidColor(Color.Black),
        )
        .build()
}
