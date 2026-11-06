package me.notific.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import me.notific.cards.NotificCard
import me.notific.cards.rememberAnswerState
import me.notific.client.Notification
import me.notific.client.QuestionSet
import me.notific.client.Severity

@Composable
fun InboxScreen(ui: Ui, vm: SampleViewModel, modifier: Modifier = Modifier) {
    val inbox = ui.inbox
    LazyColumn(modifier.fillMaxSize().testTag("inbox"), contentPadding = PaddingValues(bottom = 24.dp)) {
        ui.who?.let { who ->
            item { Text(who, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        }
        ui.error?.let { item { Box(Modifier.padding(16.dp)) { ErrorText(it) } } }
        if (inbox.questions.isNotEmpty()) {
            item { Section(stringResource(R.string.decisions)) }
            items(inbox.questions, key = { "q-" + it.id }) { qs ->
                QuestionRow(qs, inbox.notificationFor(qs.id)?.severity) { vm.go(Screen.Question(qs.id)) }
            }
        }
        if (inbox.notifications.isNotEmpty()) {
            item { Section(stringResource(R.string.notifications)) }
            items(inbox.notifications.filter { n -> n.questionSetId == null || inbox.questions.none { it.id == n.questionSetId } }, key = { "n-" + it.id }) { n ->
                NotificationRow(n) { vm.openNotification(n.id) }
                HorizontalDivider(Modifier.padding(start = 36.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (!inbox.loading && inbox.questions.isEmpty() && inbox.notifications.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp).testTag("empty"),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun QuestionRow(qs: QuestionSet, severity: Severity?, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).testTag("question-row-${qs.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SeverityDot(severity ?: if (qs.level >= 2) Severity.High else Severity.Medium)
                Spacer(Modifier.width(8.dp))
                Text(qs.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row {
                Text(stringResource(R.string.level, qs.level), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                qs.timeoutAt?.let {
                    Text("  ·  " + stringResource(R.string.expires, formatTime(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: Notification, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("notification-row-${n.id}"),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 6.dp)) { SeverityDot(n.severity) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                n.headline,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (n.unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (n.subtitle.isNotBlank()) {
                Text(n.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("${n.source} · ${formatTime(n.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SeverityDot(severity: Severity) {
    val color = when (severity) {
        Severity.Critical -> Color(0xFFDC2626)
        Severity.High -> Color(0xFFFF6A00)
        Severity.Medium -> Color(0xFFCA8A04)
        Severity.Low -> Color(0xFF2563EB)
        Severity.Info -> Color(0xFF9CA3AF)
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun NotificationScreen(ui: Ui, vm: SampleViewModel, id: String, openUrl: (String) -> Unit) {
    val n = ui.inbox.notifications.firstOrNull { it.id == id } ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("notification-detail"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(n.headline, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (n.subtitle.isNotBlank()) Text(n.subtitle, style = MaterialTheme.typography.bodyMedium)
        Text("${n.source} · ${n.type} · ${formatTime(n.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        n.card?.let { card ->
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            NotificCard(card.copy(title = card.title.takeUnless { it == n.headline })) { button ->
                if (button.url != null) openUrl(button.url!!) else vm.button(n.id, button)
            }
        }
    }
}

@Composable
fun QuestionScreen(ui: Ui, vm: SampleViewModel, id: String) {
    val qs = ui.inbox.questions.firstOrNull { it.id == id }
    if (qs == null) {
        Text(stringResource(R.string.error_answered), modifier = Modifier.padding(16.dp))
        return
    }
    val answers = rememberAnswerState(qs)
    var totp by rememberSaveable(qs.id) { mutableStateOf("") }
    var triedToSend by rememberSaveable(qs.id) { mutableStateOf(false) }
    val canAnswer = qs.answerable || qs.answerableWithTotp
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).testTag("question-detail"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(qs.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        qs.context?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            stringResource(R.string.level, qs.level) + " · " + when {
                qs.answerable -> stringResource(R.string.answer_here)
                qs.answerableWithTotp -> stringResource(R.string.needs_totp, qs.level)
                else -> stringResource(R.string.needs_app)
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (canAnswer) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("level-note"),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        NotificCard(qs.cardWithQuestions(), answers = answers, enabled = canAnswer && !ui.busy)
        if (canAnswer) {
            if (!qs.answerable) {
                OutlinedTextField(
                    value = totp,
                    onValueChange = { v -> totp = v.filter(Char::isDigit).take(8) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.totp_code)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth().testTag("totp"),
                )
            }
            if (triedToSend && !answers.isComplete) ErrorText(Msg(R.string.missing_answers))
            ui.error?.let { ErrorText(it) }
            Button(
                onClick = {
                    triedToSend = true
                    if (answers.isComplete) vm.reply(qs, answers.toAnswers(), totp.takeIf { !qs.answerable })
                },
                enabled = !ui.busy && (qs.answerable || totp.length >= 6),
                modifier = Modifier.fillMaxWidth().testTag("send-answer"),
            ) { Text(stringResource(R.string.send_answer)) }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)

fun formatTime(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(timeFormat)
}.getOrDefault(iso)
