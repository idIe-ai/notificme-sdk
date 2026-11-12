package me.notific.sample

import android.app.Application
import android.content.Context
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.notific.client.Answer
import me.notific.client.AuthMethod
import me.notific.client.CardButton
import me.notific.client.Inbox
import me.notific.client.InboxState
import me.notific.client.NotificClient
import me.notific.client.NotificMeApiException
import me.notific.client.NotificMeException
import me.notific.client.QuestionSet
import me.notific.client.Reaction
import me.notific.client.ReactionKind
import me.notific.client.Session
import me.notific.client.SessionTokens
import me.notific.client.SignInResult
import me.notific.client.TokenProvider
import me.notific.client.android.KeystoreSessionStorage
import me.notific.client.android.NotificAndroid
import me.notific.client.android.bindToForeground

enum class Mode { None, Demo, Session, PersonToken }

sealed interface Screen {
    data object Welcome : Screen
    data object Login : Screen
    data object Token : Screen
    data object Inbox : Screen
    data class Notification(val id: String) : Screen
    data class Question(val id: String) : Screen
}

data class LoginStep(
    val email: String,
    val codeLength: Int,
    val signup: SignInResult.SignupRequired? = null,
)

/** A message for the person: a string resource and an optional argument. */
data class Msg(@StringRes val res: Int, val arg: String? = null)

data class Ui(
    val mode: Mode = Mode.None,
    val screen: Screen = Screen.Welcome,
    val baseUrl: String = NotificClient.DEFAULT_BASE_URL,
    val inbox: InboxState = InboxState(loading = false),
    /** Who is signed in (`@handle` or e-mail). */
    val who: String? = null,
    val busy: Boolean = false,
    val error: Msg? = null,
    val message: Msg? = null,
    val login: LoginStep? = null,
    /** Demo mode: the reply body that would be sent. */
    val demoReply: String? = null,
)

class SampleViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("sample", Context.MODE_PRIVATE)
    private val sessions = SessionTokens(KeystoreSessionStorage(app))

    private val mutable = MutableStateFlow(Ui(baseUrl = prefs.getString(KEY_BASE_URL, null) ?: NotificClient.DEFAULT_BASE_URL))
    val ui: StateFlow<Ui> = mutable.asStateFlow()

    private var client: NotificClient? = null
    private var inbox: Inbox? = null
    private var inboxJob: Job? = null
    private var unbind: (() -> Unit)? = null

    init {
        if (prefs.getString(KEY_MODE, null) == Mode.Session.name && sessions.session.value != null) {
            connect(sessions, Mode.Session, sessions.session.value?.user?.handle?.let { "@$it" })
        }
    }

    // ------------------------------------------------------------ navigation

    fun go(screen: Screen) = mutable.update { it.copy(screen = screen, error = null) }

    fun back() = mutable.update {
        when (it.screen) {
            is Screen.Notification, is Screen.Question -> it.copy(screen = Screen.Inbox, error = null)
            Screen.Login, Screen.Token -> it.copy(screen = Screen.Welcome, login = null, error = null)
            else -> it
        }
    }

    fun consumeMessage() = mutable.update { it.copy(message = null) }

    fun closeDemoReply() = mutable.update { it.copy(demoReply = null) }

    fun setBaseUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        prefs.edit { putString(KEY_BASE_URL, clean) }
        mutable.update { it.copy(baseUrl = clean) }
    }

    // ------------------------------------------------------------ demo

    fun openDemo() {
        disconnect()
        mutable.update { it.copy(mode = Mode.Demo, screen = Screen.Inbox, inbox = Demo.load(getApplication<Application>().assets), who = null, error = null) }
    }

    // ------------------------------------------------------------ e-mail sign-in

    fun sendCode(email: String) = work {
        val sent = authClient().auth.start(email.trim(), locale())
        mutable.update { it.copy(login = LoginStep(email.trim(), sent.codeLength)) }
    }

    fun verify(code: String) = work {
        val step = ui.value.login ?: return@work
        when (val r = authClient().auth.verify(step.email, code.trim(), NotificAndroid.device(getApplication()), locale())) {
            is SignInResult.SignedIn -> signedIn(r.session)
            is SignInResult.SignupRequired -> mutable.update { it.copy(login = step.copy(signup = r)) }
            // The account lives on another cell (only with the global identity on). The sample
            // keeps a single server, so it does not follow it; the official app does
            // (Auth.completeHandoff, then the account on CellSignIn.baseUrl).
            is SignInResult.Handoff -> throw me.notific.client.NotificMeException(
                me.notific.client.ErrorCodes.UNKNOWN_CELL, "This account lives on another notific.me server",
            )
        }
    }

    fun signUp(handle: String, name: String) = work {
        val signup = ui.value.login?.signup ?: return@work
        signedIn(authClient().auth.signUp(signup.signupToken, handle.trim(), name.trim(), NotificAndroid.device(getApplication()), locale()))
    }

    private fun signedIn(session: Session) {
        sessions.set(session)
        prefs.edit { putString(KEY_MODE, Mode.Session.name) }
        connect(sessions, Mode.Session, "@${session.user.handle}")
    }

    // ------------------------------------------------------------ person token

    /**
     * In a real app the token comes from your backend:
     * `TokenProvider { refresh -> api.notificmeToken(force = refresh) }`.
     * Here it is pasted (or passed by `adb … --es nm.token`), so it can't be renewed.
     */
    fun usePersonToken(token: String) {
        val value = token.trim()
        if (value.isEmpty()) return
        prefs.edit { remove(KEY_MODE) }
        connect(TokenProvider { refresh -> if (refresh) null else value }, Mode.PersonToken, null)
    }

    // ------------------------------------------------------------ inbox

    fun refresh() = work { inbox?.refresh() }

    fun openNotification(id: String) {
        go(Screen.Notification(id))
        if (ui.value.mode == Mode.Demo) {
            mutable.update { s ->
                s.copy(inbox = s.inbox.copy(notifications = s.inbox.notifications.map { n -> if (n.id == id) n.copy(deliveries = n.deliveries.map { it.copy(readAt = "now") }) else n }))
            }
        } else {
            viewModelScope.launch { inbox?.markOpened(listOf(id)) }
        }
    }

    fun button(notificationId: String?, button: CardButton) {
        val action = button.action ?: return
        if (ui.value.mode == Mode.Demo) {
            mutable.update { it.copy(message = Msg(R.string.action_sent, button.label)) }
            return
        }
        work {
            client?.react(listOf(Reaction(kind = ReactionKind.Action, notificationId = notificationId, action = action)))
            mutable.update { it.copy(message = Msg(R.string.action_sent, button.label)) }
        }
    }

    fun reply(qs: QuestionSet, answers: List<Answer>, totpCode: String?) {
        if (ui.value.mode == Mode.Demo) {
            val body = pretty.encodeToString(ListSerializer(Answer.serializer()), answers)
            mutable.update { s -> s.copy(demoReply = body, screen = Screen.Inbox, inbox = s.inbox.copy(questions = s.inbox.questions.filterNot { it.id == qs.id })) }
            return
        }
        work {
            val box = inbox ?: return@work
            if (totpCode != null) box.reply(qs, answers, AuthMethod.Totp, totpCode) else box.reply(qs, answers)
            mutable.update { it.copy(screen = Screen.Inbox, message = Msg(R.string.sent)) }
        }
    }

    fun signOut() {
        val wasSession = ui.value.mode == Mode.Session
        val c = client
        disconnect()
        if (wasSession && c != null) {
            // Best effort: end the session on the server too.
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { c.auth.logout() }
                c.close()
            }
        } else {
            c?.close()
        }
        client = null
        sessions.set(null)
        prefs.edit { remove(KEY_MODE) }
        mutable.update { Ui(baseUrl = it.baseUrl) }
    }

    // ------------------------------------------------------------ plumbing

    private fun connect(tokens: TokenProvider, mode: Mode, who: String?) {
        disconnect()
        client?.close()
        val c = NotificAndroid.client(getApplication(), tokens, ui.value.baseUrl)
        val box = Inbox(c, CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default))
        client = c
        inbox = box
        mutable.update { it.copy(mode = mode, screen = Screen.Inbox, who = who, login = null, error = null, inbox = InboxState()) }
        inboxJob = viewModelScope.launch {
            box.state.collect { s ->
                mutable.update { it.copy(inbox = s) }
                if (s.error?.code == "unauthorized") {
                    signOut()
                    mutable.update { it.copy(error = Msg(R.string.error_unauthorized)) }
                }
            }
        }
        unbind = box.bindToForeground()
        if (who == null) {
            viewModelScope.launch {
                runCatching { c.me() }.onSuccess { me ->
                    val ws = me.workspaces.singleOrNull()?.let { " · ${it.name.ifBlank { it.handle }}" }.orEmpty()
                    mutable.update { it.copy(who = "@${me.user.handle}$ws") }
                }
            }
        }
    }

    private fun disconnect() {
        unbind?.invoke()
        unbind = null
        inboxJob?.cancel()
        inboxJob = null
        inbox = null
    }

    private fun authClient(): NotificClient {
        val c = client
        if (c != null && c.config.baseUrl == ui.value.baseUrl) return c
        c?.close()
        return NotificAndroid.client(getApplication(), null, ui.value.baseUrl).also { client = it }
    }

    private fun work(block: suspend () -> Unit) {
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                block()
            } catch (e: NotificMeException) {
                mutable.update { it.copy(error = errorMessage(e)) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }

    private fun locale(): String = when (Locale.getDefault().language) {
        "pt" -> "pt-BR"
        "es" -> "es"
        else -> "en"
    }

    override fun onCleared() {
        disconnect()
        client?.close()
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODE = "mode"
        private val pretty = Json { prettyPrint = true; explicitNulls = false }

        /** Never shows the server's text: translates the stable error code. */
        fun errorMessage(e: NotificMeException): Msg = Msg(
            when (e.code) {
                "network_error", "timeout" -> R.string.error_network
                "unauthorized" -> R.string.error_unauthorized
                "invalid_code" -> R.string.error_code
                "step_up_required", "insufficient_level", "device_signature_required" -> R.string.error_step_up
                "already_answered" -> R.string.error_answered
                "content_changed", "version_mismatch" -> R.string.error_changed
                "question_set_expired" -> R.string.error_expired
                "validation_failed" -> R.string.error_validation
                "rate_limited" -> R.string.error_rate_limited
                "workspace_blocked" -> R.string.error_blocked
                else -> if (e is NotificMeApiException && e.status == 409) R.string.error_answered else R.string.error_generic
            },
        )
    }
}
