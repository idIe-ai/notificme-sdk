package me.notific.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public data class User(
    val id: String,
    val email: String = "",
    val handle: String = "",
    @SerialName("display_name") val displayName: String = "",
    val locale: String = "en",
    /** Path on the API (`/v0/avatars/…`); empty when there is no photo. Full URL: [NotificClient.urlOf]. */
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("created_at") val createdAt: String = "",
)

/** A signed-in session of the person (`nm_ses_…`). */
@Serializable
public data class Session(
    val token: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("expires_at") val expiresAt: String,
    val user: User,
    /** This device as the server registered it (sent when signing in with a [DeviceInfo]). */
    val device: Device? = null,
)

/** The device being signed in, so the person can see and revoke it. */
@Serializable
public data class DeviceInfo(
    val name: String? = null,
    /** `android`, `ios`, `web`, `wearos` or `other`. */
    val platform: String = "android",
    /** `fcm` or `apns`. */
    @SerialName("push_provider") val pushProvider: String? = null,
    @SerialName("push_token") val pushToken: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
)

/** `POST /v0/auth/start`: a code went to the e-mail. */
@Serializable
public data class LoginCodeSent(
    /** Seconds until the code expires. */
    @SerialName("expires_in") val expiresIn: Int = 0,
    @SerialName("code_length") val codeLength: Int = 8,
)

/** Result of proving an e-mail (code or magic link). */
public sealed interface SignInResult {
    public data class SignedIn(val session: Session) : SignInResult

    /** The e-mail has no account yet; finish with [Auth.signUp]. */
    @Serializable
    public data class SignupRequired(
        @SerialName("signup_token") val signupToken: String,
        val email: String,
        @SerialName("expires_at") val expiresAt: String = "",
        @SerialName("suggested_handle") val suggestedHandle: String = "",
    ) : SignInResult
}

@Serializable
public data class Workspace(
    val id: String,
    val handle: String = "",
    val name: String = "",
    /** `personal` or `org`. */
    val kind: String = "",
    /** `owner`, `admin`, `member` or `guest`. */
    val role: String = "",
    val plan: String = "",
    /** Path of the logo on the API (`/v0/avatars/…`); empty when there is none. Full URL: [NotificClient.urlOf]. */
    @SerialName("logo_url") val logoUrl: String? = null,
) {
    /** Owners and admins may change the workspace's rules (e.g. mute a source). */
    public val canManage: Boolean get() = role == "owner" || role == "admin"
}

/** `GET /v0/me`. */
@Serializable
public data class Me(
    val user: User,
    val workspaces: List<Workspace> = emptyList(),
    @SerialName("sync_cursor") val syncCursor: Long = 0,
    /** The device of this session, if it was registered. */
    val device: Device? = null,
    /** Invitations waiting for an answer. */
    @SerialName("pending_invites") val pendingInvites: Int = 0,
)

/** A device of the person (`GET /v0/devices`). The push token is never returned. */
@Serializable
public data class Device(
    val id: String,
    val name: String = "",
    val platform: String = "",
    @SerialName("push_provider") val pushProvider: String? = null,
    @SerialName("has_push_token") val hasPushToken: Boolean = false,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("revoked_at") val revokedAt: String? = null,
    /** The device of the session making the request. */
    val current: Boolean = false,
)

/** Fields to change on a device (`PATCH /v0/devices/{id}`); `null` keeps, `""` clears the push token. */
@Serializable
public data class DeviceUpdate(
    val name: String? = null,
    @SerialName("push_provider") val pushProvider: String? = null,
    @SerialName("push_token") val pushToken: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
)

/** Authenticator app (TOTP) of the person (`GET /v0/me/2fa`). */
@Serializable
public data class TwoFactor(
    val enabled: Boolean = false,
    @SerialName("enabled_at") val enabledAt: String? = null,
    /** Minutes a code keeps level 2 answers unlocked for this session. */
    @SerialName("window_minutes") val windowMinutes: Int = 0,
    @SerialName("session_verified_until") val sessionVerifiedUntil: String? = null,
)

/** A stored file (`GET /v0/content/{id}`): [url] is signed and short-lived. */
@Serializable
public data class ContentInfo(
    val id: String,
    val kind: String = "",
    @SerialName("content_type") val contentType: String = "",
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    val sha256: String? = null,
    val url: String,
    @SerialName("url_expires_at") val urlExpiresAt: String = "",
)
