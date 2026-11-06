package me.notific.client.android

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import me.notific.client.Session
import me.notific.client.SessionStorage
import me.notific.client.Wire

/**
 * Keeps the session in the app's private preferences, encrypted with an
 * AES-GCM key that never leaves the Android Keystore. A key that can no
 * longer decrypt (app data restored on another phone, key invalidated)
 * simply means "signed out".
 */
public class KeystoreSessionStorage(
    context: Context,
    private val name: String = "notificme.session",
) : SessionStorage {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("$name.key")

    override fun load(): Session? {
        val stored = prefs.getString(KEY, null) ?: return null
        return try {
            val json = cipher.open(Base64.decode(stored, Base64.NO_WRAP)).decodeToString()
            Wire.json.decodeFromString(Session.serializer(), json)
        } catch (e: Exception) {
            prefs.edit { remove(KEY) }
            null
        }
    }

    override fun save(session: Session?) {
        if (session == null) {
            prefs.edit { remove(KEY) }
            return
        }
        val sealed = cipher.seal(Wire.json.encodeToString(Session.serializer(), session).encodeToByteArray())
        prefs.edit { putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
    }

    private companion object {
        const val KEY = "session"
    }
}
