# notific.me for Kotlin and Android

Libraries to show a person's notific.me inbox and answer decisions **inside your own app**, plus a reference app that uses them.

> **Early access.** The hosted service is opening in waves. To get a workspace and a service token, [join the list at notific.me](https://notific.me/?src=readme-kotlin).

| Module | What it is | Coordinates |
| --- | --- | --- |
| [`client/`](client/) | Plain Kotlin client (JVM and Android): sign-in, inbox sync, realtime feed, question sets, replies, reactions | `me.notific:client` *(not published yet)* |
| [`client-android/`](client-android/) | Android helpers: session encrypted with the Android Keystore, device info, realtime only while the app is in the foreground | `me.notific:client-android` *(not published yet)* |
| [`cards/`](cards/) | Jetpack Compose renderer for the 10 card blocks and the 7 question types | used from this build |
| [`client-wear/`](client-wear/) | Wear OS: the phone app links its watch app with one-time codes over the Data Layer, forwards what arrives and syncs "got it" both ways | used from this build |
| [`cards-wear/`](cards-wear/) | Compose for Wear OS renderer: inbox rows, card blocks on a small round screen, and the questions a watch answers (a tap, or the answer spoken) | used from this build |
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
    // Only from a server with the global identity on: the account lives on another cell.
    is SignInResult.Handoff -> {
        val home = auth.completeHandoff(r, NotificAndroid.device(context))
        // Keep the person on home.baseUrl from now on (a client built with it).
        (home.result as? SignInResult.SignedIn)?.let { tokens.set(it.session) }
    }
}
```

The cell's URL comes only from `GET /v0/auth/cells` of the server you asked, and only when it is `https://` (a debug or QA build may set `Config.allowLoopbackHttpCells` to also follow lab cells at `http://127.0.0.1`, `http://localhost` or `http://10.0.2.2`; never in a store build); a magic link with `c=<cell_id>` goes through `auth.magicLinkAt(cellId, token)`. Servers send neither while the global identity is off (today's default). Service tokens (`nm_svc_`) stay bound to the URL you configure.

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

Decisions with a rule (quorum, majority, unanimous, one from each group) carry the votes so far, and an organization's groups and authority ladder are one call away:

```kotlin
val d = questionSet.decision       // null: the first answer closes it
d?.approvals; d?.required          // "1 of 2 approvals"
d?.votes                           // who voted, through which #group and authority level
if (!questionSet.canDecide) d?.reason   // not_eligible, already_voted, requester_cannot_approve
val accepted = inbox.reply(questionSet, answers)
accepted.isClosed                  // false: vote recorded, the question stays open with it

client.groups.list("acme")         // #finance, #board… (groupsEnabled, canManage)
client.groups.addMembers("acme", "finance", listOf(userId), authorityLevel = "manager")
client.people.preview(listOf("#finance>=manager"), from = "acme").count   // "This goes to N people"
```

Questions that ask for an electronic signature (`questionSet.requireSignature`): the person adopts one once (the name typed in a cursive style, or drawn, plus initials; your app draws the PNGs), then each signed reply sends the hash of the signature shown:

```kotlin
val signature = client.account.signature()            // null until adopted
    ?: client.account.adoptSignature(
        name = "Ana Souza", initials = "AS", method = SignatureMethod.Typed, style = SignatureStyle.GreatVibes,
        signaturePng = signatureBytes, initialsPng = initialsBytes,   // after the person accepted your consent text
    )
inbox.reply(questionSet, answers.toAnswers(), eSignatureSha256 = signature.signatureSha256)
// signature_changed (409): it changed on another device; show client.account.signature() again.
// The signed reply carries it: reply.eSignature (images and hashes; hashesMatch() rechecks them).
```

### Wear OS

A watch is a device of the person with a session of its own (platform `wearos`), linked with a one-time code (2 minutes, once) that the phone app hands over on the Data Layer (same package name and signing key; the Data Layer connects nothing else). The watch answers up to level 1; the server refuses more from a watch.

```kotlin
// Phone: answer the watch's link request with a code per account (a MESSAGE_RECEIVED service).
class WatchService : PhoneBridgeService() {
    override suspend fun linkCodes(nodeId: String) = listOf(
        client.createDeviceLinkCode().let { LinkCode(accountId = "main", baseUrl = NotificClient.DEFAULT_BASE_URL, code = it.code) },
    )
}
PhoneLink(context).relay(listOf(item))   // what arrived: id, severity, a short summary (the watch never polls)
PhoneLink(context).handled(Handled("main", notificationId, Handled.Kind.Acknowledged))   // stop ringing there

// Watch: ask the phone, then exchange the code for a session of the watch itself.
WatchLinkClient(context).requestLink()   // codes arrive in PhoneEventsService.onLinkCodes
val session = NotificAndroid.client(context, null, code.baseUrl).auth.linkDevice(code.code, NotificAndroid.device(context))
val tokens = SessionTokens(KeystoreSessionStorage(context, "watch.${code.accountId}")).apply { set(session) }

// No phone nearby (plan B): show start.userCode; the person approves it with client.approveDeviceLink(code).
val start = client.auth.startDeviceLink(NotificAndroid.device(context))
when (val r = client.auth.pollDeviceLink(start.pollToken)) { is DeviceLinkPoll.Linked -> tokens.set(r.session); else -> Unit }
```

Each side declares its capability in its app's `res/values/wear.xml` (`notificme_phone` on the phone, `notificme_watch` on the watch); the watch extends `PhoneEventsService` (link codes, relay, handled) and the phone `PhoneBridgeService`. A critical alert takes the watch screen when the phone opens it (`PhoneLink.openOnWatch(WatchLink.screenUri("alert", account, id))`): Galaxy watches replace an app's full-screen notification with their own screen, and an app can't start its screen from the background. Draw it with `cards-wear`:

```kotlin
val support = questionSet.watchSupport(maxLevel = 1, dictation = true, maxOptions = 4)   // why not, when it can't: support.main
val answers = rememberWearAnswerState(questionSet, dictation = true, maxOptions = 4)
ScreenScaffold(scrollState = listState, edgeButton = {
    EdgeButton(onClick = { scope.launch { inbox.reply(questionSet, answers.toAnswers()) } }, enabled = answers.isComplete) { Text("Send") }
}) { padding ->
    TransformingLazyColumn(state = listState, contentPadding = padding) {
        for (block in questionSet.card?.watchBlocks().orEmpty()) item { WearBlock(block) }
        for (q in answers.questions) wearQuestionItems(q, answers, spec, onSpeak = { q -> listen(q) })
    }
}
// listen(q): start the speech recognizer (RecognizerIntent) and hand the text back with answers.setSpoken(q.id, text).
```

A watch answers `yesno` and `single` (up to `maxOptions` options) with a tap. With dictation, `text` and `voice` take the transcription, `number` the number said (read the way the watch's language writes it) and a `single` with `allow_other` a spoken "Other…". `multi`, `date`, many options, higher levels and electronic signatures go to the phone. "Got it" on critical alerts and messages: `inbox.acknowledge(ids)` (`Notification.asksForAck`).

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
./gradlew :client:test :cards:testDebugUnitTest :client-wear:testDebugUnitTest :cards-wear:testDebugUnitTest :sample:assembleDebug
```

The client tests read the shared fixtures in `../protocol/examples`.

To run the live test against a server (it issues a person token, asks a question, answers it in realtime and checks the signed reply):

```sh
NOTIFICME_E2E_URL=http://localhost:8080 NOTIFICME_E2E_SERVICE_TOKEN=nm_svc_... NOTIFICME_E2E_EMAIL=ana@example.com ./gradlew :client:test --tests '*LiveServerTest*'
```

## License

[Apache 2.0](../LICENSE).
