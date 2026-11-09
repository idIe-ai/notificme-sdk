package me.notific.cards.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import me.notific.client.Notification
import me.notific.client.QuestionSet
import me.notific.client.Severity

/**
 * One row of a watch inbox: a colored dot for the severity, where it came
 * from and when, a title of up to two lines and a short summary. Unread rows
 * have a brighter title; [badge] marks decisions ("Decision", "Answer on phone").
 */
@Composable
public fun WearInboxCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    origin: String = "",
    time: String = "",
    accent: Color = WearSeverity.Info,
    unread: Boolean = false,
    badge: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    transformation: SurfaceTransformation? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (unread) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 11.dp),
        transformation = transformation,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            Text(
                origin,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (time.isNotEmpty()) {
                Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Spacer(Modifier.size(3.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
            color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (badge != null) {
            Spacer(Modifier.size(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = badgeColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(badgeColor.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

/** [WearInboxCard] for a notification. [origin] defaults to its source (`dokploy`). */
@Composable
public fun WearNotificationCard(
    notification: Notification,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    origin: String = notification.source,
    badge: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    transformation: SurfaceTransformation? = null,
) {
    WearInboxCard(
        title = wearMarkdown(notification.headline).text,
        subtitle = wearMarkdown(notification.subtitle).text,
        onClick = onClick,
        modifier = modifier,
        origin = origin,
        time = shortAgeText(notification.createdAt),
        accent = WearSeverity.color(notification.severity),
        unread = notification.unread,
        badge = badge,
        badgeColor = badgeColor,
        transformation = transformation,
    )
}

/**
 * [WearInboxCard] for an open decision: its title and why it was asked, and
 * a badge that says whether it can be answered here ([support]).
 */
@Composable
public fun WearDecisionCard(
    questionSet: QuestionSet,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    support: WatchSupport = questionSet.watchSupport(),
    origin: String = "",
    severity: Severity = Severity.High,
    transformation: SurfaceTransformation? = null,
) {
    val title = questionSet.title.ifBlank { questionSet.questions.firstOrNull()?.text.orEmpty() }
    WearInboxCard(
        title = wearMarkdown(title).text,
        subtitle = wearMarkdown(questionSet.context.orEmpty()).text,
        onClick = onClick,
        modifier = modifier,
        origin = origin,
        time = shortAgeText(questionSet.createdAt),
        accent = WearSeverity.color(severity),
        unread = true,
        badge = stringResource(if (support.answerable) R.string.nmw_badge_decision else R.string.nmw_badge_on_phone),
        badgeColor = if (support.answerable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        transformation = transformation,
    )
}

/** Why [WatchSupport.main] keeps a decision off the watch, in the app's language. */
@Composable
public fun watchBlockerText(blocker: WatchBlocker): String = stringResource(
    when (blocker) {
        WatchBlocker.Closed -> R.string.nmw_blocker_closed
        WatchBlocker.NotEligible -> R.string.nmw_blocker_not_eligible
        WatchBlocker.QuestionType -> R.string.nmw_blocker_question_type
        WatchBlocker.FreeText -> R.string.nmw_blocker_free_text
        WatchBlocker.Level -> R.string.nmw_blocker_level
        WatchBlocker.Signature -> R.string.nmw_blocker_signature
    },
)
