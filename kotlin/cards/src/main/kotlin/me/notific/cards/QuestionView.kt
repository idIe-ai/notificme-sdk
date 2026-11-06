package me.notific.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import me.notific.client.Question
import me.notific.client.QuestionType

/**
 * One question with native controls, bound to [state]. [extra] is drawn
 * under text and voice answers (the official app puts its dictation button
 * there); with it, the "voice is not recorded here" hint is left out.
 */
@Composable
public fun QuestionView(
    question: Question,
    state: AnswerState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    extra: (@Composable (Question) -> Unit)? = null,
) {
    val draft = state[question.id]
    Column(modifier.fillMaxWidth().testTag("question-${question.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(question.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
            if (question.optional) {
                Text(
                    "  " + stringResource(R.string.nm_optional),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (question.type) {
            QuestionType.YesNo -> YesNo(question, state, enabled)
            QuestionType.Single -> Single(question, state, enabled)
            QuestionType.Multi -> Multi(question, state, enabled)
            QuestionType.Text -> OutlinedTextField(
                value = draft.text,
                onValueChange = { v -> state.update(question.id) { it.copy(text = v) } },
                enabled = enabled,
                placeholder = { Text(stringResource(R.string.nm_answer_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("input-${question.id}"),
            )
            QuestionType.Voice -> {
                if (extra == null) {
                    Text(stringResource(R.string.nm_voice_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedTextField(
                    value = draft.text,
                    onValueChange = { v -> state.update(question.id) { it.copy(text = v) } },
                    enabled = enabled,
                    placeholder = { Text(stringResource(R.string.nm_answer_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("input-${question.id}"),
                )
            }
            QuestionType.Number -> OutlinedTextField(
                value = draft.text,
                onValueChange = { v -> state.update(question.id) { it.copy(text = v) } },
                enabled = enabled,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.nm_number_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = draft.text.isNotBlank() && AnswerState.parseNumber(draft.text) == null,
                modifier = Modifier.fillMaxWidth().testTag("input-${question.id}"),
            )
            QuestionType.Date -> DateField(question, state, enabled)
            QuestionType.Unknown -> Text(
                stringResource(R.string.nm_unknown_question),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (extra != null && enabled && (question.type == QuestionType.Text || question.type == QuestionType.Voice)) extra(question)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun YesNo(q: Question, state: AnswerState, enabled: Boolean) {
    val value = state[q.id].bool
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = value == true,
            onClick = { state.update(q.id) { it.copy(bool = true) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.nm_yes), modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.testTag("yes-${q.id}"),
        )
        FilterChip(
            selected = value == false,
            onClick = { state.update(q.id) { it.copy(bool = false) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.nm_no), modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primary,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier.testTag("no-${q.id}"),
        )
    }
}

@Composable
private fun Single(q: Question, state: AnswerState, enabled: Boolean) {
    val d = state[q.id]
    Column {
        for (option in q.options) {
            val selected = !d.other && d.choice == option
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .selectable(selected, enabled = enabled, role = Role.RadioButton) {
                        state.update(q.id) { it.copy(choice = option, other = false) }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected, onClick = null, enabled = enabled)
                Spacer(Modifier.width(10.dp))
                Text(option, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (q.allowOther) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .selectable(d.other, enabled = enabled, role = Role.RadioButton) {
                        state.update(q.id) { it.copy(other = true) }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = d.other, onClick = null, enabled = enabled)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.nm_other), style = MaterialTheme.typography.bodyLarge)
            }
            if (d.other) OtherField(q, state, enabled)
        }
    }
}

@Composable
private fun Multi(q: Question, state: AnswerState, enabled: Boolean) {
    val d = state[q.id]
    Column {
        for (option in q.options) {
            val checked = option in d.choices
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .toggleable(checked, enabled = enabled, role = Role.Checkbox) { on ->
                        state.update(q.id) { it.copy(choices = if (on) it.choices + option else it.choices - option) }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                Spacer(Modifier.width(10.dp))
                Text(option, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (q.allowOther) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .toggleable(d.other, enabled = enabled, role = Role.Checkbox) { on -> state.update(q.id) { it.copy(other = on) } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = d.other, onCheckedChange = null, enabled = enabled)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.nm_other), style = MaterialTheme.typography.bodyLarge)
            }
            if (d.other) OtherField(q, state, enabled)
        }
    }
}

@Composable
private fun OtherField(q: Question, state: AnswerState, enabled: Boolean) {
    OutlinedTextField(
        value = state[q.id].text,
        onValueChange = { v -> state.update(q.id) { it.copy(text = v) } },
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.nm_other_hint)) },
        modifier = Modifier.fillMaxWidth().testTag("other-${q.id}"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(q: Question, state: AnswerState, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    val text = state[q.id].text
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.testTag("date-${q.id}")) {
        val shown = runCatching { LocalDate.parse(text).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) }.getOrNull()
        Text(shown ?: stringResource(R.string.nm_date_pick))
    }
    if (open) {
        val initial = runCatching { LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        val picker = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { ms ->
                        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                        state.update(q.id) { it.copy(text = date) }
                    }
                    open = false
                }) { Text(stringResource(R.string.nm_date_ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.nm_date_cancel)) } },
        ) {
            DatePicker(state = picker)
        }
    }
}
