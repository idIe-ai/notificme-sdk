package me.notific.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A one-time code that links another device of the person (a watch) to their
 * account: valid for 2 minutes, once. The signed-in device asks for it with
 * [NotificClient.createDeviceLinkCode] and hands it to the new device, which
 * exchanges it with [Auth.linkDevice] for a session of its own.
 */
@Serializable
public data class DeviceLinkCode(
    val code: String,
    @SerialName("expires_at") val expiresAt: String,
)

/**
 * Plan B, like signing in on a TV: the new device shows [userCode], the
 * person approves it on a signed-in device ([NotificClient.approveDeviceLink]),
 * and the new device polls with [pollToken] every [interval] seconds.
 */
@Serializable
public data class DeviceLinkStart(
    @SerialName("user_code") val userCode: String,
    @SerialName("poll_token") val pollToken: String,
    @SerialName("expires_at") val expiresAt: String,
    val interval: Int = 5,
)

/** What [Auth.pollDeviceLink] found. */
public sealed interface DeviceLinkPoll {
    /** Not approved yet: ask again after [interval] seconds. */
    public data class Pending(val expiresAt: String, val interval: Int) : DeviceLinkPoll

    /** Approved: the session of the new device. */
    public data class Linked(val session: Session) : DeviceLinkPoll
}

/** The device the person approved ([NotificClient.approveDeviceLink]). */
@Serializable
public data class LinkedDevice(val name: String = "", val platform: String = "")

@Serializable
internal data class LinkedDeviceResponse(val device: LinkedDevice = LinkedDevice())

@Serializable
internal data class PendingLink(
    val status: String = "",
    @SerialName("expires_at") val expiresAt: String = "",
    val interval: Int = 5,
)
