# notific.me for Kotlin and Android

Libraries to show a person's notific.me inbox and answer decisions **inside your own app**, plus a reference app that uses them.

| Module | What it is | Coordinates |
| --- | --- | --- |
| [`client/`](client/) | Plain Kotlin client (JVM and Android): sign-in, inbox sync, realtime feed, question sets, replies, reactions | `me.notific:client` *(not published yet)* |
| [`client-android/`](client-android/) | Android helpers: session encrypted with the Android Keystore, device info, realtime only while the app is in the foreground | `me.notific:client-android` *(not published yet)* |
| [`cards/`](cards/) | Jetpack Compose renderer for the 10 card blocks and the 7 question types | used from this build |
| [`sample/`](sample/) | Reference app (Compose): demo inbox offline, e-mail sign-in, person tokens | never published to a store |

The client uses a **person token**, never a service token. There are two ways to get one:

- **Inside your company's app** (the embedded channel): your backend, holding the service token, issues a short-lived token for the person signed in through your SSO with `POST /v0/member-tokens` (`notific.members.issueToken()` in the TypeScript SDK, `notific.members.issue_token()` in Python). The token only sees your workspace. Your app answers decisions up to **level 1**. For a higher level the person confirms with an authenticator code or in the notific.me app.
- **In an app where the person signs in to notific.me**: e-mail code or magic link with `client.auth`.

## Quick start

```kotlin
// 1. A token provider. Inside a company app, ask your backend:
val tokens = TokenProvider { refresh -> myBackend.notificmeToken(forceNew = refresh) }

// 2. The client and a live inbox.
val client = NotificAndroid.client(context, tokens)
val inbox = Inbox(client, viewModelScope)
inbox.bindToForeground()          // realtime while visible; push wakes the app otherwise

// 3. Observe it.
inbox.state.collect { state ->
    render(state.questions, state.notifications, state.connection)
}

// 4. Answer a decision with every answer at once.
inbox.reply(questionSet, listOf(
    Answer.yesNo("approve", true),
    Answer.choice("supplier", "Dell"),
    Answer.text("note", "Deliver to the 3rd floor"),
))
```

Sign-in by e-mail (an app where the person has a notific.me account):

```kotlin
val tokens = SessionTokens(KeystoreSessionStorage(context))
val auth = NotificAndroid.client(context, tokens = null).auth
auth.start("ana@acme.com")
when (val r = auth.verify("ana@acme.com", code, NotificAndroid.device(context))) {
    is SignInResult.SignedIn -> tokens.set(r.session)
    is SignInResult.SignupRequired -> auth.signUp(r.signupToken, r.suggestedHandle, "Ana")
}
```

Draw cards with your own components, or with the Compose renderer in [`cards/`](cards/):

```kotlin
val answers = rememberAnswerState(questionSet)
NotificCard(questionSet.cardWithQuestions(), answers = answers)
Button(enabled = answers.isComplete, onClick = { scope.launch { inbox.reply(questionSet, answers.toAnswers()) } }) {
    Text("Send")
}
```

Keep the inbox between launches (and offline) with an encrypted cache, and manage the person's devices:

```kotlin
val inbox = Inbox(client, viewModelScope, cache = KeystoreInboxCache(context))
inbox.restore()                    // the last inbox at once, before the network answers

client.registerDevice(NotificAndroid.device(context, pushToken = fcmToken))  // or updateDevice(id, DeviceUpdate(pushToken = token))
client.devices()                   // this phone is `current`; revokeDevice(id) signs one out
client.contentText(notification.content?.rich?.contentId ?: return)      // sender's HTML (`content.rich`), signed URL
```

### What you get

- **Tolerant to newer servers.** Unknown card blocks arrive as `Block.Unknown`, unknown question types as `QuestionType.Unknown`, unknown fields are ignored.
- **Realtime with catch-up.** `Inbox` syncs by cursor, then listens on the WebSocket gateway. It reconnects with backoff and the gateway resends what was missed. Deliveries are acknowledged as delivered.
- **Reads on every device.** A notification read on the web or another phone comes back as a `delivery` change and is read here too.
- **Offline first.** With an `InboxCache` the inbox starts from the last saved state and only asks for what changed (`KeystoreInboxCache` encrypts it with a Keystore key, outside backups).
- **Safe retries.** Replies carry an idempotency key. Reads are retried on network errors, 429 and 5xx, honouring `Retry-After`.
- **Stable errors.** `NotificMeException.code` is the server's error code (`step_up_required`, `already_answered`, `validation_failed`…) or `unauthorized`, `timeout`, `network_error`, `invalid_response`. Show your own text for each code, never the server's message.

## Reference app

The sample app has three modes:

- **Demo:** an offline inbox built from the protocol examples in [`../protocol/examples`](../protocol/examples). It covers every card block and question type.
- **Sign in with e-mail:** your notific.me account.
- **Person token:** paste a token issued by your backend.

Debug builds also accept the server and a token from adb:

```sh
adb shell am start -n me.notific.sample/.MainActivity --es nm.baseUrl http://10.0.2.2:8080 --es nm.token nm_dlg_...
```

The emulator reaches your machine at `10.0.2.2`. If a server running in Docker doesn't answer there, forward the port and use `127.0.0.1`:

```sh
adb reverse tcp:8080 tcp:8080
adb shell am start -n me.notific.sample/.MainActivity --es nm.baseUrl http://127.0.0.1:8080 --es nm.token nm_dlg_...
```

## Development

Open this folder in Android Studio, or use the wrapper (JDK 17+; Android SDK with API 37):

```sh
./gradlew :client:test :cards:testDebugUnitTest :sample:assembleDebug
```

The client tests read the shared fixtures in `../protocol/examples`.

To run the live test against a server (it issues a person token, asks a question, answers it in realtime and checks the signed reply):

```sh
NOTIFICME_E2E_URL=http://localhost:8080 NOTIFICME_E2E_SERVICE_TOKEN=nm_svc_... NOTIFICME_E2E_EMAIL=ana@example.com ./gradlew :client:test --tests '*LiveServerTest*'
```

## License

[Apache 2.0](../LICENSE).
