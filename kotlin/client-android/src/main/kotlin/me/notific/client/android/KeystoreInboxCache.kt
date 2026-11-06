package me.notific.client.android

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.notific.client.InboxCache
import me.notific.client.InboxSnapshot
import me.notific.client.Wire

/**
 * Keeps the inbox in a file outside backups, encrypted with an AES-GCM key
 * that never leaves the Android Keystore. A file that no longer decrypts is
 * dropped and the next sync starts from scratch.
 */
public class KeystoreInboxCache(
    context: Context,
    name: String = "notificme.inbox",
) : InboxCache {
    private val file = File(context.applicationContext.noBackupFilesDir, "$name.bin")
    private val cipher = KeystoreCipher("$name.key")

    override suspend fun load(): InboxSnapshot? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        try {
            Wire.json.decodeFromString(InboxSnapshot.serializer(), cipher.open(file.readBytes()).decodeToString())
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    override suspend fun save(snapshot: InboxSnapshot): Unit = withContext(Dispatchers.IO) {
        val sealed = cipher.seal(Wire.json.encodeToString(InboxSnapshot.serializer(), snapshot).encodeToByteArray())
        // Write then rename, so a crash mid-write never leaves half a file.
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) { file.delete() }
    }
}
