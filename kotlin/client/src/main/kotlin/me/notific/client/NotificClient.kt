package me.notific.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import java.io.Closeable
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Client for the consumption side of notific.me: the person's inbox,
 * question sets, replies and realtime feed.
 *
 * It uses a **person token**, never a service token. Build one per signed-in
 * person and [close] it when they sign out.
 *
 * ```
 * val tokens = SessionTokens()
 * val client = NotificClient(tokens = tokens)
 * val inbox = Inbox(client, scope)
 * inbox.start()
 * ```
 */
public class NotificClient(
    /** Token of the person; `null` only for [Auth] calls. */
    tokens: TokenProvider? = null,
    public val config: Config = Config(),
) : Closeable {

    public data class Config(
        /** API origin, without a trailing slash. */
        val baseUrl: String = DEFAULT_BASE_URL,
        /** Sent as `User-Agent`, e.g. `my-intranet-android/2.3`. */
        val userAgent: String = "notificme-kotlin/$VERSION",
        val requestTimeoutMillis: Long = 30_000,
        /** Retries of safe requests on network errors, 429 and 5xx. */
        val maxRetries: Int = 2,
        /** Platform sent with reactions (`android`, `ios`, `web`). */
        val platform: String? = null,
        /** Your own [HttpClient] (tests, proxies). It must have the [WebSockets] plugin for [realtime]. */
        val httpClient: HttpClient? = null,
    )

    internal val tokens: TokenProvider? = tokens
    internal val baseUrl: String = config.baseUrl.trimEnd('/')
    private val ownsHttp = config.httpClient == null
    internal val http: HttpClient = config.httpClient ?: HttpClient(OkHttp) {
        expectSuccess = false
        install(WebSockets)
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                // Requests are bounded by requestTimeoutMillis; the socket must
                // stay open for the realtime feed, kept alive by pings.
                readTimeout(0, TimeUnit.SECONDS)
                pingInterval(20, TimeUnit.SECONDS)
            }
        }
    }

    /** Sign in by e-mail (code or magic link). Works without a token. */
    public val auth: Auth = Auth(this)

    /** Contacts, organization members and sending messages and questionnaires to them. */
    public val people: People = People(this)

    /** Groups (`#finance`) and the authority ladder of the person's organizations. */
    public val groups: Groups = Groups(this)
    /** The person's own account: the electronic signature they adopt and apply to answers. */
    public val account: Account = Account(this)

    /** Face verification: enrolling the person's face in an organization and proving it is them. */
    public val face: Face = Face(this)

    /** The signed-in person, their workspaces and the current sync cursor. */
    public suspend fun me(): Me = call(HttpMethod.Get, "/v0/me", Me.serializer())

    /**
     * Full URL of a path the API returns ([User.avatarUrl], [Workspace.logoUrl]);
     * null when there is none. Photos and logos are public, so the URL works
     * without the token.
     */
    public fun urlOf(path: String?): String? = when {
        path.isNullOrBlank() -> null
        path.startsWith("/") -> baseUrl + path
        else -> path
    }

    /**
     * One page of the person's feed after [cursor] (0 = from the start).
     * Apply the changes in order and continue from [SyncPage.nextCursor]
     * while [SyncPage.hasMore].
     */
    public suspend fun sync(cursor: Long = 0, limit: Int = 500): SyncPage =
        call(HttpMethod.Get, "/v0/sync", SyncPage.serializer(), query = mapOf("cursor" to cursor, "limit" to limit))

    /** Question sets addressed to the person; [status] `null` = every status. */
    public suspend fun questionSets(status: QuestionSetStatus? = QuestionSetStatus.Open): List<QuestionSet> {
        val query = status?.let { mapOf("status" to Wire.json.encodeToJsonElement(QuestionSetStatus.serializer(), it).jsonPrimitive.content) }
        return call(HttpMethod.Get, "/v0/question-sets", QuestionSetList.serializer(), query = query.orEmpty()).questionSets
    }

    public suspend fun questionSet(id: String): QuestionSet =
        call(HttpMethod.Get, "/v0/question-sets/${id.encodePath()}", QuestionSet.serializer())

    /**
     * Answers a question set. Send every answer at once; questions left out
     * stay unanswered (only `optional` ones may be).
     *
     * With a signed-in session the server accepts level 0 ([AuthMethod.Tap])
     * and level 1 ([AuthMethod.Unlock]); levels 2 and 3 accept an
     * authenticator code ([AuthMethod.Totp] with [totpCode]) when
     * [QuestionSet.answerableWithTotp]. Device-key signatures belong to the
     * official app.
     *
     * When [QuestionSet.requireSignature], pass the hash of the signature
     * shown to the person ([ESignature.signatureSha256], from
     * [Account.signature]) as [eSignatureSha256]: the server checks it is the
     * account's current signature and copies it into the reply. Errors:
     * `signature_not_adopted` (adopt one with [Account.adoptSignature]),
     * `signature_required`, `signature_changed` (409: it was changed on
     * another device; show the current one again).
     *
     * When [QuestionSet.requireFace], pass in [face] the verification the
     * person just passed for this question set ([FaceProof.Verified], from
     * [Face.verify]), or [FaceProof.Fallback] with [AuthMethod.Totp] and a
     * fresh [totpCode] when the organization accepts the code instead.
     * Errors: `face_required` (`details.enrolled`, `details.on_fail`,
     * `details.unenrolled` say which path the person has),
     * `face_verification_invalid` (409: expired or already used; verify
     * again), `face_fallback_unavailable` (`details.reason`), `face_disabled`.
     *
     * Safe to retry: the same [idempotencyKey] never creates two replies.
     */
    public suspend fun reply(
        questionSet: QuestionSet,
        answers: List<Answer>,
        authMethod: AuthMethod = if (questionSet.level <= 0) AuthMethod.Tap else AuthMethod.Unlock,
        totpCode: String? = null,
        idempotencyKey: String = UUID.randomUUID().toString(),
        eSignatureSha256: String? = null,
        face: FaceProof? = null,
    ): ReplyAccepted {
        val body = ReplyRequest(
            questionSetId = questionSet.id,
            version = questionSet.version,
            answers = answers,
            idempotencyKey = idempotencyKey,
            authMethod = authMethod,
            totpCode = totpCode,
            contentHash = questionSet.contentHash,
            answeredAt = Instant.now().toString(),
            eSignature = eSignatureSha256?.let(::ESignatureRef),
            face = face?.let(FaceRef::of),
        )
        return call(
            HttpMethod.Post, "/v0/replies", ReplyAccepted.serializer(),
            body = Wire.json.encodeToJsonElement(ReplyRequest.serializer(), body),
            retry = true,
        )
    }

    /** Records reactions (opened, dismissed, button actions…). */
    public suspend fun react(reactions: List<Reaction>) {
        if (reactions.isEmpty()) return
        val body = ReactionsRequest(reactions.map { it.copy(platform = it.platform ?: config.platform) })
        call(HttpMethod.Post, "/v0/reactions", JsonElement.serializer(), body = Wire.json.encodeToJsonElement(ReactionsRequest.serializer(), body))
    }

    /** Marks notifications as read. */
    public suspend fun markOpened(notificationIds: List<String>): Unit = react(now(ReactionKind.Opened, notificationIds))

    /** The person dismissed these notifications. */
    public suspend fun dismiss(notificationIds: List<String>): Unit = react(now(ReactionKind.Dismissed, notificationIds))

    /**
     * "Got it" on critical alerts or messages ([Notification.asksForAck]):
     * read, plus an action reaction [ACTION_ACKNOWLEDGED]. The server records
     * who, the device, the session and both clocks in the evidence trail and
     * sets [Notification.acknowledgedAt] on every device; the sender of a
     * message sees it in Sent.
     */
    public suspend fun acknowledge(notificationIds: List<String>) {
        if (notificationIds.isEmpty()) return
        val read = now(ReactionKind.Opened, notificationIds)
        react(read + now(ReactionKind.Action, notificationIds).map { it.copy(action = ACTION_ACKNOWLEDGED) })
    }

    // ------------------------------------------------------------- devices

    /** The person's devices; revoked ones only with [includeRevoked]. */
    public suspend fun devices(includeRevoked: Boolean = false): List<Device> =
        call(
            HttpMethod.Get, "/v0/devices", DeviceList.serializer(),
            query = if (includeRevoked) mapOf("include_revoked" to true) else emptyMap(),
        ).devices

    /**
     * Registers the device of this session, or updates it when the session
     * already has one (a new push token, a new app version). Send every
     * field: a missing push token is cleared.
     */
    public suspend fun registerDevice(device: DeviceInfo): Device =
        call(HttpMethod.Post, "/v0/devices", Device.serializer(), body = Wire.json.encodeToJsonElement(DeviceInfo.serializer(), device))

    /** Changes only the given fields; `pushToken = ""` stops push to it. */
    public suspend fun updateDevice(id: String, update: DeviceUpdate): Device =
        call(HttpMethod.Patch, "/v0/devices/${id.encodePath()}", Device.serializer(), body = Wire.json.encodeToJsonElement(DeviceUpdate.serializer(), update))

    /** Revokes a device and ends its sessions (signing out, when it is this device). */
    public suspend fun revokeDevice(id: String) {
        call(HttpMethod.Delete, "/v0/devices/${id.encodePath()}", JsonElement.serializer())
    }

    /**
     * A one-time code (2 minutes, once) that links another device of the
     * person, such as their watch: hand it over (the Wear OS Data Layer) and
     * the other device calls [Auth.linkDevice]. Not for a company's
     * delegated session (403).
     */
    public suspend fun createDeviceLinkCode(): DeviceLinkCode =
        call(HttpMethod.Post, "/v0/devices/link-codes", DeviceLinkCode.serializer())

    /** Approves the code a new device shows ([Auth.startDeviceLink]); `invalid_code` (404) when wrong or expired. */
    public suspend fun approveDeviceLink(userCode: String): LinkedDevice =
        call(
            HttpMethod.Post, "/v0/devices/link-codes/approve", LinkedDeviceResponse.serializer(),
            body = buildJsonObject { put("user_code", userCode.trim()) },
        ).device

    /** Sends a test push to the person's devices that have a push token. */
    public suspend fun pushTest(): PushTestResult = call(HttpMethod.Post, "/v0/push/test", PushTestResult.serializer())

    // ------------------------------------------------------------- content

    /** Metadata and a short-lived signed URL of stored content (`content:<id>`). */
    public suspend fun content(id: String): ContentInfo =
        call(HttpMethod.Get, "/v0/content/${id.encodePath()}", ContentInfo.serializer())

    /** Downloads stored text content (the HTML of `content.rich`), up to [maxBytes]. */
    public suspend fun contentText(id: String, maxBytes: Int = 2 shl 20): String {
        val info = content(id)
        if (info.sizeBytes > maxBytes) throw NotificMeException("content_too_large", "Content has ${info.sizeBytes} bytes")
        val url = urlOf(info.url) ?: throw NotificMeException("content_unavailable", "Content has no URL")
        val (status, text) = try {
            withTimeout(config.requestTimeoutMillis) {
                val r = http.request(url) {
                    this.method = HttpMethod.Get
                    header(HttpHeaders.UserAgent, config.userAgent)
                }
                r.status.value to r.bodyAsText()
            }
        } catch (e: TimeoutCancellationException) {
            throw NotificMeException("timeout", "Content download timed out", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw NotificMeException("network_error", e.message ?: e.toString(), e)
        }
        if (status !in 200..299) throw apiError(status, text, null, null)
        return text
    }

    // ------------------------------------------------------------- account

    /** Authenticator app (TOTP) state, and how long this session stays verified. */
    public suspend fun twoFactor(): TwoFactor = call(HttpMethod.Get, "/v0/me/2fa", TwoFactor.serializer())

    /**
     * Stops notifications from [source] in a workspace the person manages
     * (owner or admin; see [Workspace.canManage]): a `suppress` rule for
     * everyone in it.
     */
    public suspend fun muteSource(workspaceHandle: String, source: String, ruleName: String = "Mute $source") {
        val body = buildJsonObject {
            put("name", ruleName)
            put("action", "suppress")
            putJsonObject("match") { putJsonArray("source") { add(source) } }
        }
        call(HttpMethod.Post, "/v0/workspaces/${workspaceHandle.encodePath()}/rules", JsonElement.serializer(), body = body)
    }

    /**
     * Realtime feed over WebSocket, reconnecting with backoff while
     * collected. [cursor] is read on every (re)connection so the gateway
     * resends what was missed. Ends with [NotificMeException] (`unauthorized`)
     * when the token is gone.
     */
    public fun realtime(cursor: () -> Long): Flow<RealtimeEvent> = Realtime(this).connect(cursor)

    override fun close() {
        if (ownsHttp) http.close()
    }

    private fun now(kind: ReactionKind, ids: List<String>): List<Reaction> {
        val at = Instant.now().toString()
        val tz = ZoneId.systemDefault().id
        return ids.map { Reaction(kind = kind, notificationId = it, at = at, tz = tz) }
    }

    // ------------------------------------------------------------------ HTTP

    internal suspend fun <T> call(
        method: HttpMethod,
        path: String,
        serializer: KSerializer<T>,
        query: Map<String, Any?> = emptyMap(),
        body: JsonElement? = null,
        auth: Boolean = true,
        retry: Boolean = method == HttpMethod.Get,
    ): T {
        var attempt = 0
        var refreshed = false
        while (true) {
            val token = if (auth) {
                tokens?.token(refresh = false) ?: throw NotificMeException("unauthorized", "No signed-in person")
            } else {
                null
            }
            val result: Pair<Int, String>
            val headers: io.ktor.http.Headers
            try {
                val response = withTimeout(config.requestTimeoutMillis) {
                    val r = http.request(baseUrl + path) {
                        this.method = method
                        for ((k, v) in query) if (v != null) parameter(k, v)
                        header(HttpHeaders.Accept, "application/json")
                        header(HttpHeaders.UserAgent, config.userAgent)
                        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                        if (body != null) setBody(TextContent(Wire.json.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json))
                    }
                    r to r.bodyAsText()
                }
                result = response.first.status.value to response.second
                headers = response.first.headers
            } catch (e: TimeoutCancellationException) {
                if (retry && attempt < config.maxRetries) {
                    delay(backoff(attempt++, null))
                    continue
                }
                throw NotificMeException("timeout", "Request timed out after ${config.requestTimeoutMillis} ms", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (retry && attempt < config.maxRetries) {
                    delay(backoff(attempt++, null))
                    continue
                }
                throw NotificMeException("network_error", e.message ?: e.toString(), e)
            }

            val (status, text) = result
            if (status in 200..299) {
                return decode(serializer, text, status)
            }
            val error = apiError(status, text, headers[HttpHeaders.RetryAfter], headers["X-Request-Id"])
            if (status == 401 && token != null && !refreshed) {
                refreshed = true
                if (tokens?.token(refresh = true) != null) continue
            }
            if (retry && (status == 429 || status >= 500) && attempt < config.maxRetries) {
                delay(backoff(attempt++, error.retryAfterSeconds))
                continue
            }
            throw error
        }
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String, status: Int): T {
        val source = text.ifBlank { "null" }
        return try {
            Wire.json.decodeFromString(serializer, source)
        } catch (e: Exception) {
            throw NotificMeException("invalid_response", "Unexpected response ($status): ${e.message}", e)
        }
    }

    private fun backoff(attempt: Int, retryAfterSeconds: Long?): Long {
        if (retryAfterSeconds != null) return (retryAfterSeconds * 1000).coerceAtMost(60_000)
        val exp = (500L shl attempt).coerceAtMost(8_000)
        return exp / 2 + Random.nextLong(exp / 2 + 1)
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.notific.me"
        public const val VERSION: String = "0.1.0"

        /** The action of a "got it" reaction ([acknowledge]). */
        public const val ACTION_ACKNOWLEDGED: String = "acknowledged"
    }
}

/** Sign-in by e-mail: [start] sends a code, [verify] exchanges it for a [Session]. */
public class Auth internal constructor(private val client: NotificClient) {

    /** Sends a sign-in code to [email]. The answer is the same whether or not the e-mail has an account. */
    public suspend fun start(email: String, locale: String? = null): LoginCodeSent = client.call(
        HttpMethod.Post, "/v0/auth/start", LoginCodeSent.serializer(),
        body = obj("email" to email, "locale" to locale), auth = false,
    )

    public suspend fun verify(email: String, code: String, device: DeviceInfo? = null, locale: String? = null): SignInResult =
        signIn("/v0/auth/verify", obj("email" to email, "code" to code, "locale" to locale, "device" to device?.let(::encodeDevice)))

    /** Exchanges the token of a magic link (`nm_ml_…`) or invite link (`nm_inv_…`). */
    public suspend fun magicLink(token: String, device: DeviceInfo? = null, locale: String? = null): SignInResult =
        signIn("/v0/auth/magic", obj("token" to token, "locale" to locale, "device" to device?.let(::encodeDevice)))

    /** Creates the account after [SignInResult.SignupRequired]. */
    public suspend fun signUp(
        signupToken: String,
        handle: String,
        displayName: String,
        device: DeviceInfo? = null,
        locale: String? = null,
    ): Session {
        val result = signIn(
            "/v0/auth/signup",
            obj(
                "signup_token" to signupToken, "handle" to handle.removePrefix("@"), "display_name" to displayName,
                "locale" to locale, "device" to device?.let(::encodeDevice),
            ),
        )
        return (result as? SignInResult.SignedIn)?.session
            ?: throw NotificMeException("invalid_response", "Sign-up did not return a session")
    }

    /**
     * Exchanges a code from the person's signed-in device
     * ([NotificClient.createDeviceLinkCode]) for a session of this device,
     * registered as [device] (a watch: platform `wearos`). `invalid_link`
     * (401) when it expired or was used.
     */
    public suspend fun linkDevice(code: String, device: DeviceInfo, locale: String? = null): Session {
        val result = signIn("/v0/auth/device-link", obj("code" to code.trim(), "locale" to locale, "device" to encodeDevice(device)))
        return (result as? SignInResult.SignedIn)?.session
            ?: throw NotificMeException("invalid_response", "Device link did not return a session")
    }

    /** Plan B: a code for this device to show; the person approves it elsewhere ([pollDeviceLink]). */
    public suspend fun startDeviceLink(device: DeviceInfo, locale: String? = null): DeviceLinkStart = client.call(
        HttpMethod.Post, "/v0/auth/device-link/start", DeviceLinkStart.serializer(),
        body = obj("locale" to locale, "device" to encodeDevice(device)), auth = false,
    )

    /** Whether the code of [startDeviceLink] was approved; `invalid_link` (401) once it expired. */
    public suspend fun pollDeviceLink(pollToken: String): DeviceLinkPoll {
        val raw = client.call(
            HttpMethod.Post, "/v0/auth/device-link/poll", JsonElement.serializer(),
            body = obj("poll_token" to pollToken), auth = false, retry = true,
        )
        val o = raw as? JsonObject ?: throw NotificMeException("invalid_response", "Expected an object")
        return if (o["status"]?.jsonPrimitive?.contentOrNull == "pending") {
            val p = Wire.json.decodeFromJsonElement(PendingLink.serializer(), o)
            DeviceLinkPoll.Pending(p.expiresAt, p.interval)
        } else {
            DeviceLinkPoll.Linked(Wire.json.decodeFromJsonElement(Session.serializer(), o))
        }
    }

    /** Ends the session on the server. Clear it locally too. */
    public suspend fun logout() {
        client.call(HttpMethod.Post, "/v0/auth/logout", JsonElement.serializer())
    }

    private suspend fun signIn(path: String, body: JsonElement): SignInResult {
        val raw = client.call(HttpMethod.Post, path, JsonElement.serializer(), body = body, auth = false)
        val o = raw as? JsonObject ?: throw NotificMeException("invalid_response", "Expected an object")
        return try {
            if (o["status"]?.jsonPrimitive?.contentOrNull == "signup_required") {
                Wire.json.decodeFromJsonElement(SignInResult.SignupRequired.serializer(), o)
            } else {
                SignInResult.SignedIn(Wire.json.decodeFromJsonElement(Session.serializer(), o))
            }
        } catch (e: Exception) {
            throw NotificMeException("invalid_response", "Unexpected sign-in response: ${e.message}", e)
        }
    }

    private fun encodeDevice(d: DeviceInfo): JsonElement = Wire.json.encodeToJsonElement(DeviceInfo.serializer(), d)

    private fun obj(vararg pairs: Pair<String, Any?>): JsonElement = JsonObject(
        pairs.mapNotNull { (k, v) ->
            when (v) {
                null -> null
                is JsonElement -> k to v
                is String -> k to JsonPrimitive(v)
                else -> error("unsupported $v")
            }
        }.toMap(),
    )
}

/** JSON settings shared by the client: tolerant to fields and values added by newer servers. */
public object Wire {
    public val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }
}

@Serializable
internal data class DeviceList(val devices: List<Device> = emptyList())

/** `POST /v0/push/test`: how many devices got it. */
@Serializable
public data class PushTestResult(
    val devices: Int = 0,
    val sent: Int = 0,
    val invalid: Int = 0,
    val retryable: Int = 0,
    val failed: Int = 0,
)

@Serializable
internal data class QuestionSetList(@SerialName("question_sets") val questionSets: List<QuestionSet> = emptyList())

@Serializable
internal data class ReplyRequest(
    @SerialName("question_set_id") val questionSetId: String,
    val version: Int,
    val answers: List<Answer>,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("auth_method") val authMethod: AuthMethod,
    @SerialName("totp_code") val totpCode: String? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    @SerialName("answered_at") val answeredAt: String? = null,
    @SerialName("e_signature") val eSignature: ESignatureRef? = null,
    val face: FaceRef? = null,
)

@Serializable
internal data class ReactionsRequest(val reactions: List<Reaction>)

internal fun apiError(status: Int, text: String, retryAfter: String?, requestIdHeader: String?): NotificMeApiException {
    val body = runCatching { Wire.json.parseToJsonElement(text).jsonObject }.getOrNull()
    val err = body?.get("error") as? JsonObject
    val code = err?.get("code")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: statusCode(status)
    return NotificMeApiException(
        status = status,
        code = code,
        serverMessage = err?.get("message")?.jsonPrimitive?.contentOrNull,
        details = err?.get("details"),
        requestId = body?.get("request_id")?.jsonPrimitive?.contentOrNull ?: requestIdHeader,
        retryAfterSeconds = retryAfter?.trim()?.toLongOrNull(),
    )
}

private fun statusCode(status: Int): String = when {
    status == 401 -> "unauthorized"
    status == 403 -> "forbidden"
    status == 404 -> "not_found"
    status == 409 -> "conflict"
    status == 429 -> "rate_limited"
    status == 503 -> "unavailable"
    status >= 500 -> "internal_error"
    else -> "bad_request"
}

private fun String.encodePath(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
