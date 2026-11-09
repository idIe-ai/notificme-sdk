package me.notific.client.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import me.notific.client.DeviceInfo
import me.notific.client.Inbox
import me.notific.client.NotificClient
import me.notific.client.TokenProvider

/** Android helpers for the notific.me client. */
public object NotificAndroid {

    /**
     * A [NotificClient] identified as your app (`User-Agent`) and as
     * `android` in reactions (`wearos` on a watch).
     */
    public fun client(
        context: Context,
        tokens: TokenProvider?,
        baseUrl: String = NotificClient.DEFAULT_BASE_URL,
    ): NotificClient {
        val app = context.applicationContext
        val agent = "${app.packageName}/${versionName(app)} notificme-kotlin/${NotificClient.VERSION} (Android ${Build.VERSION.RELEASE})"
        return NotificClient(tokens, NotificClient.Config(baseUrl = baseUrl, userAgent = agent, platform = if (isWatch(app)) PLATFORM_WEAR_OS else "android"))
    }

    /**
     * This device, sent when signing in so the person can see and revoke it.
     * A watch is `wearos`, named as the person named it ("Galaxy Watch4 (ECQA)").
     */
    public fun device(context: Context, pushToken: String? = null): DeviceInfo {
        val app = context.applicationContext
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
        val watch = isWatch(app)
        val name = if (watch) watchName(app) ?: model else model
        return DeviceInfo(
            name = name,
            platform = if (watch) PLATFORM_WEAR_OS else "android",
            pushProvider = pushToken?.let { "fcm" },
            pushToken = pushToken,
            appVersion = versionName(app),
            osVersion = "Android ${Build.VERSION.RELEASE}",
        )
    }

    /**
     * The name the person gave this watch, or null when `DEVICE_NAME` holds
     * only the model code (a Galaxy Watch: its name is the Bluetooth one,
     * which `client-wear` reads as `WatchLinkClient.localName`).
     */
    public fun watchName(context: Context): String? =
        Settings.Global.getString(context.applicationContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.trim()?.takeIf { it.isNotEmpty() && !it.equals(Build.MODEL, ignoreCase = true) }

    /** Platform of a watch in `/v0/devices`. */
    public const val PLATFORM_WEAR_OS: String = "wearos"

    /** A Wear OS watch. */
    public fun isWatch(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)

    private fun versionName(context: Context): String = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        info.versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    } ?: "0"
}

/**
 * Keeps the realtime feed open only while the app is in the foreground
 * (push wakes it otherwise). Returns a function that undoes the binding.
 */
public fun Inbox.bindToForeground(lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle): () -> Unit {
    val observer = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = start()
        override fun onStop(owner: LifecycleOwner) = stop()
    }
    lifecycle.addObserver(observer)
    return {
        lifecycle.removeObserver(observer)
        stop()
    }
}
