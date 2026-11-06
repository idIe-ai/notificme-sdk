package me.notific.sample

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun WelcomeScreen(ui: Ui, vm: SampleViewModel, modifier: Modifier = Modifier) {
    var server by rememberSaveable(ui.baseUrl) { mutableStateOf(ui.baseUrl) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // The symbol has a white bell: always on the navy tile, like the app icon.
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0B1F44))) {
            Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.welcome_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Choice(R.string.demo, R.string.demo_hint, "choice-demo") { vm.openDemo() }
        Choice(R.string.sign_in, R.string.sign_in_hint, "choice-login") { vm.setBaseUrl(server); vm.go(Screen.Login) }
        Choice(R.string.person_token, R.string.person_token_hint, "choice-token") { vm.setBaseUrl(server); vm.go(Screen.Token) }
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text(stringResource(R.string.server)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().testTag("server"),
        )
        ui.error?.let { ErrorText(it) }
    }
}

@Composable
private fun Choice(title: Int, hint: Int, tag: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LoginScreen(ui: Ui, vm: SampleViewModel, modifier: Modifier = Modifier) {
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable(ui.login?.signup?.suggestedHandle) { mutableStateOf(ui.login?.signup?.suggestedHandle.orEmpty()) }
    var name by rememberSaveable { mutableStateOf("") }
    val step = ui.login
    Form(modifier, ui.baseUrl) {
        when {
            step == null -> {
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, singleLine = true,
                    label = { Text(stringResource(R.string.email)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth().testTag("email"),
                )
                Button(onClick = { vm.sendCode(email) }, enabled = !ui.busy && email.contains('@'), modifier = Modifier.fillMaxWidth().testTag("send-code")) {
                    Text(stringResource(R.string.send_code))
                }
            }
            step.signup == null -> {
                Text(stringResource(R.string.code_sent, step.codeLength, step.email), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = code, onValueChange = { code = it.filter(Char::isLetterOrDigit) }, singleLine = true,
                    label = { Text(stringResource(R.string.code)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("code"),
                )
                Button(onClick = { vm.verify(code) }, enabled = !ui.busy && code.length >= step.codeLength, modifier = Modifier.fillMaxWidth().testTag("verify")) {
                    Text(stringResource(R.string.verify))
                }
            }
            else -> {
                Text(stringResource(R.string.signup_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = handle, onValueChange = { handle = it.lowercase() }, singleLine = true,
                    label = { Text(stringResource(R.string.handle)) }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.display_name)) }, modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = { vm.signUp(handle, name) }, enabled = !ui.busy && handle.length >= 2 && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.create_account))
                }
            }
        }
        ui.error?.let { ErrorText(it) }
    }
}

@Composable
fun TokenScreen(ui: Ui, vm: SampleViewModel, modifier: Modifier = Modifier) {
    var token by rememberSaveable { mutableStateOf("") }
    Form(modifier, ui.baseUrl) {
        Text(stringResource(R.string.token_label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.token_body), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = token, onValueChange = { token = it.trim() },
            label = { Text("nm_dlg_…") }, minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("token"),
        )
        Button(onClick = { vm.usePersonToken(token) }, enabled = token.length > 10, modifier = Modifier.fillMaxWidth().testTag("connect")) {
            Text(stringResource(R.string.connect))
        }
        ui.error?.let { ErrorText(it) }
    }
}

@Composable
private fun Form(modifier: Modifier, server: String, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("${stringResource(R.string.server)}: $server", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
fun ErrorText(msg: Msg) {
    Text(
        if (msg.arg != null) stringResource(msg.res, msg.arg) else stringResource(msg.res),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("error"),
    )
}
