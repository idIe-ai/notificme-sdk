package me.notific.client

import java.net.URI
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Login between cells (servers) of notific.me.
 *
 * With the global identity turned on, a person may type their e-mail on the
 * server they know (api.notific.me) and live on another one (their home
 * cell). Two things then reach the client:
 *
 *  - `POST /v0/auth/verify` answers `{ status: "handoff", handoff: { cell_id, token } }`
 *    ([SignInResult.Handoff]); the client exchanges the pass at the home cell
 *    (`POST /v0/auth/handoff`) for a normal session of that cell;
 *  - a magic link carries `c=<cell_id>`; the client signs in at that cell.
 *
 * In both cases the cell's URL comes ONLY from `GET /v0/auth/cells` of the
 * server the person signed in on (the entry), and only when it is `https://`.
 * A URL inside a link or a response is never used. Debug and QA builds may
 * also accept `http://` on a loopback host ([NotificClient.Config.allowLoopbackHttpCells]).
 *
 * Dormant code: servers send neither the handoff nor `c=` while the global
 * identity is off (the default, and the case of every server in production
 * today). Proved only with unit tests against fake servers; no installed app
 * has received a handoff yet.
 */

/** `GET /v0/auth/cells` of the entry server: the cells it knows (no keys). */
@Serializable
public data class CellDirectory(
    /** The id of the server that answered (the entry). */
    val self: String = "",
    val cells: List<CellInfo> = emptyList(),
) {
    /**
     * The base URL of [cellId] (without a trailing slash), or null when it is
     * not in the list or its URL is not a plain `https://` origin.
     * [allowLoopbackHttp] also accepts `http://` on a lab loopback host
     * ([LOOPBACK_HOSTS]); only debug and QA builds turn it on.
     */
    public fun urlOf(cellId: String, allowLoopbackHttp: Boolean = false): String? {
        if (!isValidCellId(cellId)) return null
        val cell = cells.firstOrNull { it.cellId == cellId } ?: return null
        return safeCellBase(cell.url, allowLoopbackHttp)
    }

    public companion object {
        private val CELL_ID = Regex("^[a-z]{2,12}(-[0-9]{1,3})?$")

        /**
         * Hosts where a lab cell may answer over plain `http://` in debug and
         * QA builds: this machine and the Android emulator's alias of the
         * host (`10.0.2.2`, used with `adb reverse` or a local cell).
         */
        public val LOOPBACK_HOSTS: Set<String> = setOf("127.0.0.1", "localhost", "10.0.2.2")

        /** Same shape as the server's cell ids (`br-1`, `us-12`). */
        public fun isValidCellId(id: String?): Boolean = id != null && CELL_ID.matches(id)

        /**
         * [url] as a base URL when it is `https://host[:port][/path]`, without
         * user info, query or fragment; null otherwise.
         */
        public fun safeHttpsBase(url: String): String? = safeCellBase(url, allowLoopbackHttp = false)

        /**
         * Like [safeHttpsBase]; with [allowLoopbackHttp] (debug and QA builds
         * only), `http://` is also accepted when the host is one of
         * [LOOPBACK_HOSTS]. Any other `http://` host is always refused.
         */
        public fun safeCellBase(url: String, allowLoopbackHttp: Boolean): String? {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
            val https = uri.scheme.equals("https", ignoreCase = true)
            val loopbackHttp = allowLoopbackHttp && uri.scheme.equals("http", ignoreCase = true) &&
                uri.host?.lowercase() in LOOPBACK_HOSTS
            if (!https && !loopbackHttp) return null
            if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            return url.trim().trimEnd('/')
        }
    }
}

@Serializable
public data class CellInfo(
    @SerialName("cell_id") val cellId: String,
    val url: String,
    val region: String = "",
)

/** Where a sign-in through [Auth.completeHandoff] or [Auth.magicLinkAt] ended up. */
public data class CellSignIn(
    /** Base URL of the home cell: keep the account (and every later call) on it. */
    val baseUrl: String,
    /** [SignInResult.SignedIn] or [SignInResult.SignupRequired] (finish the sign-up on [baseUrl]). */
    val result: SignInResult,
)
