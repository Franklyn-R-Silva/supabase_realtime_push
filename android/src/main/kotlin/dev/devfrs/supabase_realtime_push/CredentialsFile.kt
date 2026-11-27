package dev.devfrs.supabase_realtime_push

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The device's topic and secret in one file, meant for `noBackupFilesDir`: Auto Backup and
 * device-to-device transfer copy `SharedPreferences`, and a new phone holding the old
 * phone's pair would read its notifications. Plain `java.io`, so the JVM tests reach it.
 */
internal class CredentialsFile(private val file: File) {
    private companion object {
        val TOKEN = Regex("^[A-Za-z0-9_-]{43}$")
    }

    /** `null` when missing or not two tokens. */
    fun read(): Credentials? {
        val lines = try {
            file.readLines()
        } catch (e: IOException) {
            return null
        }
        val topic = lines.getOrNull(0)?.takeIf { TOKEN.matches(it) } ?: return null
        val secret = lines.getOrNull(1)?.takeIf { TOKEN.matches(it) } ?: return null
        return Credentials(topic, secret)
    }

    /**
     * Synced to disk before it returns: Dart registers the pair on the server right away,
     * and a process death before the write lands would leave the server with a pair this
     * device no longer has.
     */
    fun write(credentials: Credentials) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use {
            it.write("${credentials.topic}\n${credentials.secret}\n".toByteArray())
            it.fd.sync()
        }
        if (!tmp.renameTo(file)) throw IOException("device credentials not saved")
    }

    fun delete() {
        file.delete()
    }
}
