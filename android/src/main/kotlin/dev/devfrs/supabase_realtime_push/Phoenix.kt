package dev.devfrs.supabase_realtime_push

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.random.Random

/** The Phoenix Channels frames the service needs (contract §2). No Android calls, so the JVM tests reach it. */
internal object Phoenix {
    const val HEARTBEAT_MS = 25_000L
    private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)

    data class Frame(val topic: String?, val event: String?, val ref: String?, val status: String?)

    /**
     * After a joined session this long, a drop reconnects within [SOON_MS] without growing
     * the backoff: Supabase Cloud closes public connections after at most 24 h.
     */
    const val LONG_SESSION_MS = 60_000L
    private const val SOON_MS = 5_000L

    /** Base delay before reconnect number [attempt] (0-based): 1, 2, 5, 10, 30 s, then 30 s forever. */
    fun backoffMs(attempt: Int): Long = BACKOFF_MS[attempt.coerceIn(0, BACKOFF_MS.size - 1)]

    /**
     * The backoff times a factor between 0.5 and 1.5, or 0 to 5 s after a long session. After a
     * Realtime restart every device drops in the same second, and the server takes only
     * `TENANT_MAX_JOINS_PER_SECOND` (100 by default) joins: without the spread the refused
     * joins come back as synchronized waves. [unit] is uniform in [0, 1).
     */
    fun reconnectDelayMs(attempt: Int, joinedForMs: Long, unit: Double = Random.nextDouble()): Long =
        if (joinedForMs >= LONG_SESSION_MS) (SOON_MS * unit).toLong() else (backoffMs(attempt) * (0.5 + unit)).toLong()

    fun websocketUrl(supabaseUrl: String, key: String): String {
        val base = supabaseUrl.trimEnd('/')
            .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
            .replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
        return "$base/realtime/v1/websocket?apikey=${URLEncoder.encode(key, "UTF-8")}&vsn=1.0.0"
    }

    fun channelTopic(deviceTopic: String) = "realtime:push:$deviceTopic"

    /** The legacy anon key is a JWT; the new `sb_publishable_…` keys are not. */
    fun isJwt(key: String): Boolean = key.startsWith("eyJ") && key.split('.').size == 3

    fun join(topic: String, ref: String, key: String): String {
        val config = JSONObject()
            .put("broadcast", JSONObject().put("self", false))
            .put("presence", JSONObject().put("key", ""))
            .put("private", false)
        val payload = JSONObject().put("config", config)
        if (isJwt(key)) payload.put("access_token", key)
        return JSONObject()
            .put("topic", topic)
            .put("event", "phx_join")
            .put("payload", payload)
            .put("ref", ref)
            .put("join_ref", ref)
            .toString()
    }

    fun heartbeat(ref: String): String = JSONObject()
        .put("topic", "phoenix")
        .put("event", "heartbeat")
        .put("payload", JSONObject())
        .put("ref", ref)
        .toString()

    /** `null` when [text] is not a JSON object. */
    fun parse(text: String): Frame? {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        return Frame(
            topic = json.string("topic"),
            event = json.string("event"),
            ref = json.string("ref"),
            status = json.optJSONObject("payload")?.string("status"),
        )
    }

    // optString turns JSON null into the text "null".
    private fun JSONObject.string(key: String): String? = if (isNull(key)) null else optString(key)
}

/**
 * One WebSocket to Realtime, joined to one public topic, kept alive with heartbeats.
 *
 * All state lives on [handler]'s thread: OkHttp callbacks are re-posted there, and the
 * [generation] counter drops whatever a replaced socket still delivers. Call it only on
 * that thread.
 */
internal class PhoenixSocket(
    private val client: OkHttpClient,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onJoined()

        fun onChanged()

        /**
         * The socket closed, the join was refused or a heartbeat went unanswered. [joinedForMs]
         * is how long the channel had been joined (0 if it never was). Not called after [disconnect].
         */
        fun onDropped(reason: String, joinedForMs: Long)
    }

    private companion object {
        const val CHECK_TIMEOUT_MS = 10_000L
        const val JOIN_TIMEOUT_MS = 15_000L
    }

    private var socket: WebSocket? = null
    private var generation = 0
    private var nextRef = 1L
    private var topic: String? = null
    private var accessToken: String = ""
    private var joinRef: String? = null
    private var pendingHeartbeat: String? = null
    private var joinedAt = 0L

    var joined = false
        private set

    /** A socket is open or opening, joined or not. */
    val active: Boolean get() = socket != null

    private val beat = object : Runnable {
        override fun run() {
            if (!joined || !sendHeartbeat()) return
            handler.postDelayed(this, Phoenix.HEARTBEAT_MS)
        }
    }

    fun connect(url: String, topic: String, accessToken: String) {
        closeSocket()
        val current = ++generation
        this.topic = topic
        this.accessToken = accessToken
        socket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    handler.post { if (current == generation) join() }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handler.post { if (current == generation) receive(text) }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    handler.post { if (current == generation) fail("socket closed ($code)") }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val http = response?.code?.let { " HTTP $it" } ?: ""
                    handler.post { if (current == generation) fail("socket failed$http: ${t.javaClass.simpleName}") }
                }
            },
        )
    }

    fun disconnect() {
        generation++
        closeSocket()
    }

    /**
     * Sends a heartbeat now and drops the socket if it is not answered in 10 s. While the
     * CPU sleeps the periodic heartbeat stalls and the server may have closed a socket the
     * device still believes is open.
     */
    fun check() {
        if (!joined) return
        // A heartbeat already in flight is the one to wait for: a second one would fail a
        // healthy socket whose reply is simply late.
        if (pendingHeartbeat == null) {
            handler.removeCallbacks(beat)
            if (!sendHeartbeat()) return
            handler.postDelayed(beat, Phoenix.HEARTBEAT_MS)
        }
        val ref = pendingHeartbeat
        val current = generation
        handler.postDelayed({
            if (current == generation && ref != null && pendingHeartbeat == ref) fail("heartbeat unanswered on check")
        }, CHECK_TIMEOUT_MS)
    }

    private fun join() {
        val ref = newRef()
        joinRef = ref
        send(Phoenix.join(topic ?: return, ref, accessToken), "phx_join")
        // An unanswered join would leave the socket open and the channel silent forever.
        val current = generation
        handler.postDelayed({ if (current == generation && joinRef == ref) fail("join unanswered") }, JOIN_TIMEOUT_MS)
    }

    private fun receive(text: String) {
        val frame = Phoenix.parse(text) ?: return
        val ours = frame.topic == topic
        when (frame.event) {
            "phx_reply" -> when (frame.ref ?: return) {
                pendingHeartbeat -> pendingHeartbeat = null
                joinRef -> {
                    joinRef = null
                    if (frame.status != "ok") return fail("join refused (${frame.status})")
                    joined = true
                    joinedAt = SystemClock.elapsedRealtime()
                    handler.removeCallbacks(beat)
                    handler.postDelayed(beat, Phoenix.HEARTBEAT_MS)
                    listener.onJoined()
                }
            }
            // Any event name counts, so the server can rename the signal without silencing old clients.
            "broadcast" -> if (ours) listener.onChanged()
            "phx_error", "phx_close" -> if (ours) fail("channel ended (${frame.event})")
        }
    }

    /** `false` when the previous heartbeat was never answered (the socket is then dropped). */
    private fun sendHeartbeat(): Boolean {
        if (pendingHeartbeat != null) {
            fail("heartbeat unanswered")
            return false
        }
        val ref = newRef()
        pendingHeartbeat = ref
        send(Phoenix.heartbeat(ref), "heartbeat")
        return true
    }

    private fun send(frame: String, event: String) {
        if (socket?.send(frame) != true) Log.w(TAG, "socket refused to send $event")
    }

    private fun fail(reason: String) {
        if (socket == null) return
        Log.w(TAG, "realtime dropped: $reason")
        val joinedFor = if (joined) SystemClock.elapsedRealtime() - joinedAt else 0L
        generation++
        closeSocket()
        listener.onDropped(reason, joinedFor)
    }

    private fun closeSocket() {
        handler.removeCallbacks(beat)
        joined = false
        joinRef = null
        pendingHeartbeat = null
        socket?.cancel()
        socket = null
    }

    private fun newRef(): String = (nextRef++).toString()
}
