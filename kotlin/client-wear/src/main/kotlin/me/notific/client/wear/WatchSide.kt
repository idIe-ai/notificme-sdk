package me.notific.client.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/** The watch side: asks the phone app to link it, and tells it what was handled here. */
public class WatchLinkClient(context: Context) {
    private val app = context.applicationContext

    /** Phones with the phone app ([WatchLink.CAPABILITY_PHONE]) reachable now, nearest first. */
    public suspend fun phones(): List<Node> = try {
        Wearable.getCapabilityClient(app).getCapability(WatchLink.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.sortedByDescending { it.isNearby }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * This watch's name as the person sees it ("Galaxy Watch4 (ECQA)"), from
     * the Data Layer: no permission needed, unlike the Bluetooth name. Null
     * without Google Play services.
     */
    public suspend fun localName(): String? = try {
        Wearable.getNodeClient(app).localNode.await().displayName.trim().takeIf { it.isNotEmpty() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Asks the phone for one-time codes for the accounts not [linked] here
     * yet; they arrive as a message ([PhoneEventsService.onLinkCodes], empty
     * when there's nothing new). Returns how many phones were asked (0: no
     * phone with the app is reachable; use `Auth.startDeviceLink`).
     */
    public suspend fun requestLink(linked: Collection<String> = emptyList()): Int =
        sendToPhones(WatchLink.PATH_LINK_REQUEST, WatchLink.encodeLinkRequest(linked))

    /**
     * Asks the phone for the senders' images this watch is missing (at most
     * [WatchLink.ICON_REQUEST_MAX]); they arrive as Data Layer assets
     * ([PhoneEventsService.onIcon]). Returns how many phones were asked.
     */
    public suspend fun requestIcons(wants: List<IconWant>): Int =
        if (wants.isEmpty()) 0 else sendToPhones(WatchLink.PATH_ICON_REQUEST, WatchLink.encodeIconRequest(wants))

    /** Handled on the watch ("got it", answered, being read, snoozed): the phones stop ringing for it. */
    public suspend fun handled(handled: Handled): Int = sendToPhones(WatchLink.PATH_HANDLED, WatchLink.encodeHandled(handled))

    /**
     * Asks the phone to open [screenUri] ([WatchLink.screenUri]) on this
     * watch, over whatever is on it (an app can't take the screen from the
     * background by itself). Returns how many phones were asked.
     */
    public suspend fun openViaPhone(screenUri: String): Int =
        sendToPhones(WatchLink.PATH_OPEN_ON_WATCH, screenUri.toByteArray(Charsets.UTF_8))

    private suspend fun sendToPhones(path: String, data: ByteArray): Int {
        val client = Wearable.getMessageClient(app)
        var sent = 0
        for (node in phones()) {
            try {
                client.sendMessage(node.id, path, data).await()
                sent++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Out of reach: the phone learns it from the server's feed.
            }
        }
        return sent
    }
}

/**
 * Receives the phone's messages on the watch: the only thing that wakes the
 * watch app in the background. Extend it in the watch app and declare it
 * with a path filter:
 *
 * ```
 * <service android:name=".PhoneService" android:exported="true">
 *     <intent-filter>
 *         <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
 *         <data android:scheme="wear" android:host="*" android:pathPrefix="/notificme" />
 *     </intent-filter>
 * </service>
 * ```
 */
public abstract class PhoneEventsService : WearableListenerService() {
    /**
     * One-time codes from the phone: exchange each with `Auth.linkDevice`.
     * None: the phone couldn't get any (signed out, offline): offer plan B.
     * Runs off the main thread.
     */
    protected abstract fun onLinkCodes(codes: List<LinkCode>)

    /** What arrived: show it from these fields, without the network. Runs off the main thread. */
    protected abstract fun onRelay(items: List<RelayItem>)

    /** Handled on the phone: stop ringing for it here. */
    protected open fun onHandled(handled: Handled) {}

    /**
     * A sender's image from the phone ([WatchLink.iconKey], a few KB): keep it
     * to show next to what that sender sends. Runs off the main thread.
     * Declare `DATA_CHANGED` with the path prefix [WatchLink.PATH_ICON_PREFIX] to get it.
     */
    protected open fun onIcon(key: String, bytes: ByteArray) {}

    override fun onDataChanged(events: DataEventBuffer) {
        val client = Wearable.getDataClient(this)
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val key = WatchLink.iconKeyOf(event.dataItem.uri.path ?: continue) ?: continue
            val asset = runCatching { DataMapItem.fromDataItem(event.dataItem).dataMap.getAsset(WatchLink.ICON_ASSET) }.getOrNull() ?: continue
            val bytes = runBlocking {
                try {
                    client.getFdForAsset(asset).await().inputStream.use { it.readBytes() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            } ?: continue
            if (bytes.size <= MAX_ICON_BYTES) onIcon(key, bytes)
        }
    }

    private companion object {
        /** An image bigger than this is not a sender's icon. */
        const val MAX_ICON_BYTES = 256 * 1024
    }

    override fun onMessageReceived(event: MessageEvent) {
        val data = event.data ?: return
        when (event.path) {
            WatchLink.PATH_LINK_CODES -> onLinkCodes(WatchLink.decodeLinkCodes(data))
            WatchLink.PATH_RELAY -> WatchLink.decodeRelay(data).takeIf { it.isNotEmpty() }?.let(::onRelay)
            WatchLink.PATH_HANDLED -> WatchLink.decodeHandled(data)?.let(::onHandled)
        }
    }
}
