package me.notific.client

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gives the client the person's token.
 *
 * - Inside your company app, fetch a short token from **your backend**,
 *   which issues it for the person signed in through your SSO:
 *   `TokenProvider { refresh -> myApi.notificmeToken(force = refresh) }`.
 * - In an app where the person signs in to notific.me itself, use
 *   [SessionTokens] with [Auth].
 */
public fun interface TokenProvider {
    /**
     * Returns the token, or `null` when there is no signed-in person.
     * [refresh] is `true` after the server rejected the last token: fetch a
     * new one or return `null`.
     */
    public suspend fun token(refresh: Boolean): String?
}

/** Where [SessionTokens] keeps the session between app launches. */
public interface SessionStorage {
    public fun load(): Session?

    /** `null` clears it. */
    public fun save(session: Session?)

    public object InMemory : SessionStorage {
        @Volatile private var session: Session? = null

        override fun load(): Session? = session

        override fun save(session: Session?) {
            this.session = session
        }
    }
}

/**
 * [TokenProvider] for a notific.me login session. A session can't be
 * refreshed: when it expires or the server rejects it, it is cleared and
 * [session] turns `null` (show the sign-in screen).
 */
public class SessionTokens(private val storage: SessionStorage = SessionStorage.InMemory) : TokenProvider {
    private val current = MutableStateFlow(storage.load()?.takeUnless { it.isExpired() })

    public val session: StateFlow<Session?> = current.asStateFlow()

    public fun set(session: Session?) {
        storage.save(session)
        current.value = session
    }

    override suspend fun token(refresh: Boolean): String? {
        val s = current.value ?: return null
        if (refresh || s.isExpired()) {
            set(null)
            return null
        }
        return s.token
    }
}

/** True when [Session.expiresAt] is in the past (an unreadable date counts as valid). */
public fun Session.isExpired(now: Instant = Instant.now()): Boolean = try {
    OffsetDateTime.parse(expiresAt).toInstant() <= now
} catch (e: Exception) {
    false
}
