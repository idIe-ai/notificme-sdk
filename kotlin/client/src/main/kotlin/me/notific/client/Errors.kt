package me.notific.client

import kotlinx.serialization.json.JsonElement

/**
 * Base of every error thrown by the client. [code] is stable: the server's
 * `error.code` for API errors (`validation_failed`, `rate_limited`, …), or
 * one of `unauthorized`, `timeout`, `network_error`, `invalid_response` for
 * failures on this side.
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
)
