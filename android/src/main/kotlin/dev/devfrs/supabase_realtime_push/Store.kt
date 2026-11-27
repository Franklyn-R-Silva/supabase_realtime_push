package dev.devfrs.supabase_realtime_push

import android.content.Context
import android.util.Base64
import org.json.JSONObject
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
)

internal data class Credentials(val topic: String, val secret: String)

/** The package's private `SharedPreferences`. Never log what comes out of here. */
internal class Store(context: Context) {
    companion object {
        const val STOPPED = "stopped"
        const val RUNNING = "running"
        const val REJECTED = "rejected"

        private const val FILE = "dev.devfrs.supabase_realtime_push"
        private const val TOPIC = "topic"
        private const val SECRET = "secret"
        private const val TAP_KEY = "tapKey"
        private const val STATUS = "status"
        private const val ALERTED = "alerted"
        private const val DISMISSED = "dismissed"
        private val LOCK = Any()

        private fun randomToken(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun credentials(): Credentials? {
        val topic = prefs.getString(TOPIC, null) ?: return null
        val secret = prefs.getString(SECRET, null) ?: return null
        return Credentials(topic, secret)
    }

    fun credentialsOrCreate(): Credentials = synchronized(LOCK) {
        credentials() ?: Credentials(randomToken(), randomToken()).also {
            // commit, not apply: Dart registers the pair on the server right away, and a
            // process death before an async write would leave the server with a pair this
            // device no longer has.
            prefs.edit().putString(TOPIC, it.topic).putString(SECRET, it.secret).commit()
        }
    }

    fun forgetDevice() {
        prefs.edit().remove(TOPIC).remove(SECRET).commit()
    }

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
                .commit()
        }

    var status: String
        get() = prefs.getString(STATUS, null) ?: STOPPED
        set(value) {
            prefs.edit().putString(STATUS, value).commit()
        }

    fun alerted(): Map<String, Long> = readMap(ALERTED)

    fun dismissed(): Map<String, Long> = readMap(DISMISSED)

    fun saveRevisions(alerted: Map<String, Long>, dismissed: Map<String, Long>) {
        prefs.edit().putString(ALERTED, JSONObject(alerted).toString()).putString(DISMISSED, JSONObject(dismissed).toString()).apply()
    }

    fun clearRevisions() {
        prefs.edit().remove(ALERTED).remove(DISMISSED).apply()
    }

    private fun readMap(key: String): Map<String, Long> {
        val json = try {
            JSONObject(prefs.getString(key, null) ?: return emptyMap())
        } catch (e: Exception) {
            return emptyMap()
        }
        return json.keys().asSequence().associateWith { json.optLong(it) }
    }
}
