package dev.devfrs.supabase_realtime_push

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

internal const val TAG = "SupabaseRealtimePush"

/** What `start` received, kept so the service can come back after a reboot without Dart. */
internal data class Config(
    val supabaseUrl: String,
    val publishableKey: String,
    val snapshotFunction: String,
    val channelName: String,
    val connectedTitle: String,
    val connectedText: String,
    val smallIcon: String?,
    /** Extra message channels, id to the name users see in settings. */
    val channels: Map<String, String> = emptyMap(),
    /** False: never the "connected" notification; without the exemption the service then runs only while the app is open. */
    val foregroundService: Boolean = true,
)

internal data class Credentials(val topic: String, val secret: String)

/**
 * What reconciliation remembers between snapshots, by id: the last revision alerted, the
 * revision the user dismissed, and the [signature] of what is in the shade.
 */
internal data class Memory(
    val alerted: Map<String, Long> = emptyMap(),
    val dismissed: Map<String, Long> = emptyMap(),
    val posted: Map<String, String> = emptyMap(),
)

/** The package's private storage. Never log what comes out of here. */
internal class Store(context: Context) {
    companion object {
        const val STOPPED = "stopped"
        const val RUNNING = "running"
        const val REJECTED = "rejected"

        private const val FILE = "dev.devfrs.supabase_realtime_push"
        private const val TAP_KEY = "tapKey"
        private const val STATUS = "status"
        private const val ALERTED = "alerted"
        private const val DISMISSED = "dismissed"
        private const val POSTED = "posted"
        private val LOCK = Any()

        private fun randomToken(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val device = CredentialsFile(File(context.applicationContext.noBackupFilesDir, "$FILE.device"))

    fun credentials(): Credentials? = synchronized(LOCK) { device.read() }

    fun credentialsOrCreate(): Credentials = synchronized(LOCK) {
        device.read() ?: Credentials(randomToken(), randomToken()).also { device.write(it) }
    }

    fun forgetDevice() = synchronized(LOCK) { device.delete() }

    /** A per-install key on every tap intent: the launch activity is exported, so anyone can send it extras. */
    fun tapKey(): String = synchronized(LOCK) {
        prefs.getString(TAP_KEY, null) ?: randomToken().also { prefs.edit().putString(TAP_KEY, it).commit() }
    }

    var config: Config?
        get() {
            return Config(
                supabaseUrl = prefs.getString("supabaseUrl", null) ?: return null,
                publishableKey = prefs.getString("publishableKey", null) ?: return null,
                snapshotFunction = prefs.getString("snapshotFunction", null) ?: return null,
                channelName = prefs.getString("channelName", null) ?: return null,
                connectedTitle = prefs.getString("connectedTitle", null) ?: return null,
                connectedText = prefs.getString("connectedText", null) ?: return null,
                smallIcon = prefs.getString("smallIcon", null),
                channels = readMap("channels") { json, id -> json.optString(id) },
                foregroundService = prefs.getBoolean("foregroundService", true),
            )
        }
        set(value) {
            // putString(key, null) removes the key.
            prefs.edit()
                .putString("supabaseUrl", value?.supabaseUrl)
                .putString("publishableKey", value?.publishableKey)
                .putString("snapshotFunction", value?.snapshotFunction)
                .putString("channelName", value?.channelName)
                .putString("connectedTitle", value?.connectedTitle)
                .putString("connectedText", value?.connectedText)
                .putString("smallIcon", value?.smallIcon)
                .putString("channels", value?.channels?.let { JSONObject(it).toString() })
                .putBoolean("foregroundService", value?.foregroundService ?: true)
                .commit()
        }

    // Auto Backup restores these prefs on a reinstall or a new phone, but not the device
    // file: without a pair nothing can run, whatever the restored status says.
    var status: String
        get() = if (credentials() == null) STOPPED else prefs.getString(STATUS, null) ?: STOPPED
        set(value) {
            prefs.edit().putString(STATUS, value).commit()
        }

    fun memory() = Memory(
        alerted = readMap(ALERTED) { json, id -> json.optLong(id) },
        dismissed = readMap(DISMISSED) { json, id -> json.optLong(id) },
        posted = readMap(POSTED) { json, id -> json.optString(id) },
    )

    fun saveMemory(memory: Memory) {
        prefs.edit()
            .putString(ALERTED, JSONObject(memory.alerted).toString())
            .putString(DISMISSED, JSONObject(memory.dismissed).toString())
            .putString(POSTED, JSONObject(memory.posted).toString())
            .apply()
    }

    fun clearMemory() {
        prefs.edit().remove(ALERTED).remove(DISMISSED).remove(POSTED).apply()
    }

    private fun <T> readMap(key: String, value: (JSONObject, String) -> T): Map<String, T> {
        val json = try {
            JSONObject(prefs.getString(key, null) ?: return emptyMap())
        } catch (e: Exception) {
            return emptyMap()
        }
        return json.keys().asSequence().associateWith { value(json, it) }
    }
}
