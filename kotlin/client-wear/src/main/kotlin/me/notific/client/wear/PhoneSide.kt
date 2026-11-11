package me.notific.client.wear

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * The phone side: links the watch, forwards what arrives and tells it what
 * was handled here. Every call is best effort and never throws (no watch,
 * Bluetooth off, no Google Play services).
 */
public class PhoneLink(context: Context) {
    private val app = context.applicationContext

    /** Watches with the watch app ([WatchLink.CAPABILITY_WATCH]) reachable now. */
    public suspend fun watches(): Set<Node> = try {
        Wearable.getCapabilityClient(app).getCapability(WatchLink.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptySet()
    }

    /** One-time codes for the watch [nodeId] (null: every watch) to link the accounts. */
    public suspend fun sendLinkCodes(codes: List<LinkCode>, nodeId: String? = null): Int =
        send(WatchLink.PATH_LINK_CODES, WatchLink.encodeLinkCodes(codes), nodeId)

    /** Forwards what arrived. Returns how many watches got it. */
    public suspend fun relay(items: List<RelayItem>): Int =
        if (items.isEmpty()) 0 else send(WatchLink.PATH_RELAY, WatchLink.encodeRelay(items), null)

    /** Handled here ("got it", answered, read, being read, snoozed): the watches stop ringing for it. */
    public suspend fun handled(handled: Handled): Int = send(WatchLink.PATH_HANDLED, WatchLink.encodeHandled(handled), null)

    /**
     * Opens a watch app screen ([WatchLink.screenUri]) on the watch [nodeId]
     * (null: every connected watch), over whatever is on it. Google Play
     * services starts it, so it works with the watch app in the background.
     * Returns whether the request went out.
     */
    public suspend fun openOnWatch(screenUri: String, nodeId: String? = null): Boolean {
        if (WatchLink.parseScreenUri(screenUri) == null) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(screenUri)).addCategory(Intent.CATEGORY_BROWSABLE)
        return withContext(Dispatchers.IO) {
            try {
                RemoteActivityHelper(app).startRemoteActivity(intent, nodeId).get(OPEN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
    }

    private suspend fun send(path: String, data: ByteArray, nodeId: String?): Int {
        val client = Wearable.getMessageClient(app)
        val targets = nodeId?.let(::listOf) ?: watches().map { it.id }
        var sent = 0
        for (node in targets) {
            try {
                client.sendMessage(node, path, data).await()
                sent++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // That watch went out of reach: it catches up when the person opens the app.
            }
        }
        return sent
    }

    private companion object {
        const val OPEN_TIMEOUT_SECONDS = 15L
    }
}

/**
 * The phone app's listener for its watch app. Extend it, and declare it in
 * the manifest with the `MESSAGE_RECEIVED` action and a path filter, so the
 * phone wakes only for these messages:
 *
 * ```
 * <service android:name=".WatchService" android:exported="true">
 *     <intent-filter>
 *         <action android:name="com.google.android.gms.wearable.MESSAGE_RECEIVED" />
 *         <data android:scheme="wear" android:host="*" android:pathPrefix="/notificme" />
 *     </intent-filter>
 * </service>
 * ```
 */
public abstract class PhoneBridgeService : WearableListenerService() {
    /**
     * The watch [nodeId] asks to be linked: return a code for each account to
     * link (`NotificClient.createDeviceLinkCode`) except those it already has
     * ([linked], by account id), or none. Runs off the main thread.
     */
    protected abstract suspend fun linkCodes(nodeId: String, linked: Set<String>): List<LinkCode>

    /** The watch [nodeId] handled an item ("got it", answered, being read, snoozed): stop ringing for it here. */
    protected open fun onHandled(nodeId: String, handled: Handled) {}

    override fun onMessageReceived(event: MessageEvent) {
        val node = event.sourceNodeId
        when (event.path) {
            // Always answers, even with no code (signed out, server unreachable): the watch then offers plan B.
            WatchLink.PATH_LINK_REQUEST -> runBlocking {
                val linked = WatchLink.decodeLinkRequest(event.data ?: ByteArray(0))
                PhoneLink(this@PhoneBridgeService).sendLinkCodes(linkCodes(node, linked), node)
            }
            WatchLink.PATH_HANDLED -> WatchLink.decodeHandled(event.data ?: return)?.let { onHandled(node, it) }
            // The watch asks to show one of its screens over everything: only on itself, only its own screens.
            WatchLink.PATH_OPEN_ON_WATCH -> {
                val uri = event.data?.toString(Charsets.UTF_8) ?: return
                if (WatchLink.parseScreenUri(uri) == null) return
                runBlocking { PhoneLink(this@PhoneBridgeService).openOnWatch(uri, node) }
            }
        }
    }
}
