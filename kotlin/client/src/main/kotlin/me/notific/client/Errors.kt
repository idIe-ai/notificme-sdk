package me.notific.client

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Base of every error thrown by the client. [code] is stable: the server's
 * `error.code` for API errors (`validation_failed`, `rate_limited`, …), or
 * one of `unauthorized`, `timeout`, `network_error`, `invalid_response` for
 * failures on this side. [ErrorCodes] lists the ones worth handling.
 */
public open class NotificMeException(
    public val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** The API answered with a non-2xx status. */
public class NotificMeApiException(
    public val status: Int,
    code: String,
    /** Developer-facing message from the server. Don't show it to people: translate [code]. */
    public val serverMessage: String?,
    public val details: JsonElement?,
    public val requestId: String?,
    /** Seconds from the `Retry-After` header. */
    public val retryAfterSeconds: Long?,
) : NotificMeException(
    code,
    "notific.me API error $status ($code)" + (serverMessage?.let { ": $it" } ?: ""),
) {
    /** A text field of [details] (`name` of `authority_level_in_use`, `plan` of `plan_required`…). */
    public fun detail(name: String): String? = ((details as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull

    /** A number field of [details] (`count` and `limit` of `too_many_recipients`, `members`…). */
    public fun detailInt(name: String): Int? = ((details as? JsonObject)?.get(name) as? JsonPrimitive)?.intOrNull

    /** The fields `validation_failed` (and `member_not_found`) point at, from `details.errors`. */
    public val fieldErrors: List<FieldError>
        get() = ((details as? JsonObject)?.get("errors") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            FieldError(
                path = (o["path"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                message = (o["message"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            )
        }
}

/** A field the server refused: [path] is a JSON pointer (`/to/2`, `/handle`); [message] is for developers. */
public data class FieldError(val path: String, val message: String)

/** Error codes worth a specific message or flow. Compare with [NotificMeException.code]. */
public object ErrorCodes {
    public const val UNAUTHORIZED: String = "unauthorized"
    public const val FORBIDDEN: String = "forbidden"
    public const val NETWORK_ERROR: String = "network_error"
    public const val TIMEOUT: String = "timeout"
    public const val VALIDATION_FAILED: String = "validation_failed"
    public const val RATE_LIMITED: String = "rate_limited"

    // Answers and decision rules.
    public const val ALREADY_ANSWERED: String = "already_answered"

    /** The person only sees the question: someone else decides it (403). */
    public const val NOT_ELIGIBLE: String = "not_eligible"

    /** The person already voted on this question (409). */
    public const val ALREADY_VOTED: String = "already_voted"

    /** Whoever made the request never approves it (403). */
    public const val REQUESTER_CANNOT_APPROVE: String = "requester_cannot_approve"

    // Sending.
    /** More people than the plan allows in one send (422, details `count`, `limit`, `plan`). */
    public const val TOO_MANY_RECIPIENTS: String = "too_many_recipients"

    // Groups and authority levels.
    /** The workspace's plan has no groups (403, details `plan`, `plans`, `feature`). */
    public const val PLAN_REQUIRED: String = "plan_required"

    /** Groups only exist in organizations (409). */
    public const val PERSONAL_WORKSPACE: String = "personal_workspace"
    public const val GROUP_NOT_FOUND: String = "group_not_found"
    public const val GROUP_HANDLE_TAKEN: String = "group_handle_taken"
    public const val GROUP_LIMIT: String = "group_limit"
    public const val MEMBER_NOT_FOUND: String = "member_not_found"
    public const val UNKNOWN_AUTHORITY_LEVEL: String = "unknown_authority_level"

    /** An authority level left out of the ladder still has people (409, details `key`, `name`, `members`). */
    public const val AUTHORITY_LEVEL_IN_USE: String = "authority_level_in_use"

    /**
     * Client side: a magic link or a handoff points to a cell (server) that is
     * not in `GET /v0/auth/cells` of the entry server, or whose URL there is
     * not `https://`. Nothing was called; ask the person for a new link.
     */
    public const val UNKNOWN_CELL: String = "unknown_cell"

    /** The one-time pass of a handoff expired, was used or is wrong (401 at the home cell). */
    public const val INVALID_HANDOFF: String = "invalid_handoff"

    // Envelopes (server/docs/api-envelopes.md, "Erros").
    public const val ENVELOPE_NOT_FOUND: String = "envelope_not_found"
    public const val ENVELOPE_NOT_OPEN: String = "envelope_not_open"
    public const val ENVELOPE_EXPIRED: String = "envelope_expired"
    public const val ENVELOPE_ALREADY_SIGNED: String = "envelope_already_signed"

    /** The document or the field layout shown is not the envelope's (409): open it again. */
    public const val DOCUMENT_CHANGED: String = "document_changed"
    public const val STRONG_AUTH_REQUIRED: String = "strong_auth_required"

    /** The app's device has no signing key on this server yet (409): register it and open again. */
    public const val DEVICE_KEY_NOT_REGISTERED: String = "device_key_not_registered"
    public const val DEVICE_SIGNATURE_INVALID: String = "device_signature_invalid"

    /** The device-key challenge expired or was used (409): open the envelope again for a new one. */
    public const val CHALLENGE_INVALID: String = "challenge_invalid"
    public const val SIGNATURE_NOT_ADOPTED: String = "signature_not_adopted"
    public const val SIGNATURE_CHANGED: String = "signature_changed"

    /** The final PDF is still being generated (409). */
    public const val FILE_NOT_READY: String = "file_not_ready"

    /** The signed PDF left the vault (410); the verification page still answers. */
    public const val VAULT_EXPIRED: String = "vault_expired"
    public const val STEP_UP_REQUIRED: String = "step_up_required"

    /** The @handle could not be confirmed with the global registry now (503); try again in a few minutes. */
    public const val HANDLE_REGISTRY_UNAVAILABLE: String = "handle_registry_unavailable"
}
