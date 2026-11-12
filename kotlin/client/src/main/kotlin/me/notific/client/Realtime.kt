package me.notific.client

import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What the realtime feed reports. */
public sealed interface RealtimeEvent {
    /** The gateway accepted the connection. */
    public data class Connected(val userId: String, val sessionId: String, val deviceId: String?) : RealtimeEvent

    /** Changes missed while offline (same format as [NotificClient.sync]). */
    public data class Changes(val page: SyncPage) : RealtimeEvent

    /** Caught up: from here on only live events. */
    public data class Ready(val cursor: Long) : RealtimeEvent

    /** A new delivery (or an update of a live card). Already acknowledged as delivered. */
    public data class Delivered(val delivery: Delivery) : RealtimeEvent

    /**
     * The person's inbox marks or rules changed on another device (or by a
     * rule): sync from the cursor you have (docs/27).
     */
    public data object InboxChanged : RealtimeEvent

    /** The gateway rejected a message. */
    public data class ServerError(val code: String, val message: String) : RealtimeEvent

    /** Connection lost; it reconnects by itself after [retryInMillis]. */
    public data class Disconnected(val reason: String, val retryInMillis: Long) : RealtimeEvent
}

/** WebSocket close codes of the gateway. */
internal object CloseCodes {
    const val UNAUTHORIZED: Short = 4001
    const val SLOW_CONSUMER: Short = 4008
    const val TOO_MANY: Short = 4029
}

internal class Realtime(private val client: NotificClient) {

    fun connect(cursor: () -> Long): Flow<RealtimeEvent> = channelFlow {
        var attempt = 0
        var refreshedToken = false
        while (isActive) {
            val token = client.tokens?.token(refresh = false)
                ?: throw NotificMeException("unauthorized", "No signed-in person")
            var connected = false
            var close: CloseReason? = null
            var failure: Throwable? = null
            try {
                val session = client.http.webSocketSession(wsUrl(cursor())) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.UserAgent, client.config.userAgent)
                }
                try {
                    for (frame in session.incoming) {
                        if (frame !is Frame.Text) continue
                        val event = parse(frame.readText()) ?: continue
                        if (event is RealtimeEvent.Connected) {
                            connected = true
                            attempt = 0
                            refreshedToken = false
                        }
                        if (event is RealtimeEvent.Delivered) ack(session, event.delivery)
                        send(event)
                    }
                    close = session.closeReason.await()
                } finally {
                    runCatching { session.close() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }

            if (close?.code == CloseCodes.UNAUTHORIZED || (!connected && failure != null && unauthorized())) {
                // Token revoked or expired: one fresh token, then give up.
                if (refreshedToken || client.tokens?.token(refresh = true) == null) {
                    throw NotificMeException("unauthorized", "The session ended")
                }
                refreshedToken = true
                continue
            }
            val wait = backoff(attempt++, close?.code == CloseCodes.TOO_MANY)
            send(RealtimeEvent.Disconnected(close?.message?.ifBlank { null } ?: failure?.message ?: "closed", wait))
            delay(wait)
        }
    }

    /** After a failed handshake: is the token still accepted? */
    private suspend fun unauthorized(): Boolean = try {
        client.call(HttpMethod.Get, "/v0/me", JsonElement.serializer())
        false
    } catch (e: NotificMeApiException) {
        e.status == 401
    } catch (e: NotificMeException) {
        e.code == "unauthorized"
    }

    private suspend fun ack(session: io.ktor.websocket.WebSocketSession, d: Delivery) {
        val msg = buildJsonObject {
            put("type", JsonPrimitive("ack"))
            put("id", JsonPrimitive(d.id))
            put("created_at", JsonPrimitive(d.createdAt))
            put("state", JsonPrimitive("delivered"))
        }
        runCatching { session.send(Frame.Text(msg.toString())) }
    }

    private fun wsUrl(cursor: Long): String {
        val base = client.baseUrl
        val ws = when {
            base.startsWith("https://") -> "wss://" + base.removePrefix("https://")
            base.startsWith("http://") -> "ws://" + base.removePrefix("http://")
            else -> base
        }
        return "$ws/v0/ws?cursor=$cursor"
    }

    private fun backoff(attempt: Int, tooMany: Boolean): Long {
        val base = if (tooMany) 30_000L else 1_000L
        val exp = (base shl attempt.coerceAtMost(6)).coerceAtMost(60_000)
        return exp / 2 + Random.nextLong(exp / 2 + 1)
    }

    companion object {
        fun parse(text: String): RealtimeEvent? {
            val o = runCatching { Wire.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
            return when (o.str("type")) {
                "hello" -> RealtimeEvent.Connected(o.str("user_id").orEmpty(), o.str("session_id").orEmpty(), o.str("device_id"))
                "sync" -> RealtimeEvent.Changes(Wire.json.decodeFromJsonElement(SyncPage.serializer(), o))
                "ready" -> RealtimeEvent.Ready(o["cursor"]?.jsonPrimitive?.longOrNull ?: 0)
                "delivery" -> {
                    val d = o["delivery"] as? JsonObject ?: return null
                    RealtimeEvent.Delivered(Wire.json.decodeFromJsonElement(Delivery.serializer(), d))
                }
                "inbox_changed" -> RealtimeEvent.InboxChanged
                "error" -> RealtimeEvent.ServerError(o.str("code").orEmpty(), o.str("message").orEmpty())
                else -> null
            }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
