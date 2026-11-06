package me.notific.sample

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.notific.client.Connection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SampleApp(vm: SampleViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    val message = ui.message?.let { if (it.arg != null) stringResource(it.res, it.arg) else stringResource(it.res) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            vm.consumeMessage()
        }
    }
    BackHandler(enabled = ui.screen != Screen.Welcome && ui.screen != Screen.Inbox) { vm.back() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (ui.screen) {
                            Screen.Welcome -> stringResource(R.string.app_name)
                            Screen.Login -> stringResource(R.string.sign_in)
                            Screen.Token -> stringResource(R.string.person_token)
                            else -> stringResource(R.string.inbox)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (ui.screen !in listOf(Screen.Welcome, Screen.Inbox)) {
                        IconButton(onClick = vm::back, modifier = Modifier.testTag("back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    if (ui.screen == Screen.Inbox) {
                        StatusBadge(ui)
                        if (ui.mode != Mode.Demo) {
                            IconButton(onClick = vm::refresh, modifier = Modifier.testTag("refresh")) {
                                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                            }
                        }
                        TextButton(onClick = vm::signOut, modifier = Modifier.testTag("sign-out")) { Text(stringResource(R.string.sign_out)) }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = ui.screen) {
                Screen.Welcome -> WelcomeScreen(ui, vm)
                Screen.Login -> LoginScreen(ui, vm)
                Screen.Token -> TokenScreen(ui, vm)
                Screen.Inbox -> InboxScreen(ui, vm)
                is Screen.Notification -> NotificationScreen(ui, vm, s.id) { url -> openUrl(context, url) }
                is Screen.Question -> QuestionScreen(ui, vm, s.id)
            }
            if (ui.busy || (ui.inbox.loading && ui.mode != Mode.None)) {
                LinearProgressIndicator(Modifier.align(Alignment.TopCenter).testTag("busy"))
            }
        }
    }

    ui.demoReply?.let { body ->
        AlertDialog(
            onDismissRequest = vm::closeDemoReply,
            confirmButton = { TextButton(onClick = vm::closeDemoReply) { Text(stringResource(R.string.close)) } },
            title = { Text(stringResource(R.string.demo_sent_title)) },
            text = { Text(body, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("demo-reply")) },
        )
    }
}

@Composable
private fun StatusBadge(ui: Ui) {
    val (label, color) = when {
        ui.mode == Mode.Demo -> stringResource(R.string.demo_badge) to MaterialTheme.colorScheme.tertiary
        ui.inbox.connection == Connection.Live -> stringResource(R.string.live) to Color(0xFF16A34A)
        ui.inbox.connection == Connection.Connecting -> stringResource(R.string.connecting) to Color(0xFFCA8A04)
        else -> stringResource(R.string.offline) to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp).testTag("status")) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private val Int.dp get() = androidx.compose.ui.unit.Dp(this.toFloat())

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
    }
}
