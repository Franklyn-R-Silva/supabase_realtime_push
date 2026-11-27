package dev.devfrs.supabase_realtime_push

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the Realtime connection with the app closed and keeps the shade equal to the
 * snapshot (contract §5). No Flutter engine runs here.
 *
 * Foreground, with the "connected" notification, only while the app is not exempt from
 * battery optimization: an exempt app may run a background service, anything else is
 * stopped about a minute after the app leaves the screen. The choice is re-made on every
 * start command, the periodic watch included.
 *
 * With the screen off it does not reconnect. The CPU sleeps, heartbeats stop, and the
 * server closes the idle socket within a minute; reconnecting then meant one TLS
 * handshake, join and snapshot per minute all night. The watch reads the snapshot instead,
 * and turning the screen on reconnects.
 */
internal class RealtimePushService : Service(), PhoenixSocket.Listener {
    companion object {
        private const val ACTION_WATCH = "dev.devfrs.supabase_realtime_push.WATCH"

        /** Started with startForegroundService: Android kills the app unless startForeground follows, exempt or not. */
        private const val EXTRA_FOREGROUND_REQUIRED = "dev.devfrs.supabase_realtime_push.FOREGROUND_REQUIRED"
        private const val WATCH_INTERVAL_MS = 5 * 60_000L
        private const val WAKE_MS = 20_000L

        /** Throws when Android refuses (Android 12+ from the background, for instance). */
        fun start(context: Context) {
            val intent = Intent(context, RealtimePushService::class.java)
            if (isIgnoringBatteryOptimizations(context)) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent.putExtra(EXTRA_FOREGROUND_REQUIRED, true))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RealtimePushService::class.java))
        }

        fun isIgnoringBatteryOptimizations(context: Context): Boolean =
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
    }

    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var socket: PhoenixSocket
    private lateinit var store: Store
    private lateinit var shade: Shade
    private lateinit var http: OkHttpClient
    private lateinit var wakeLock: PowerManager.WakeLock
    private val fetcher = Executors.newSingleThreadExecutor()
    private val fetchQueued = AtomicBoolean(false)

    @Volatile private var config: Config? = null

    @Volatile private var credentials: Credentials? = null

    @Volatile private var stopped = false
    private var attempt = 0
    private var foreground = false
    private val reconnect = Runnable { connect() }

    // SCREEN_ON reaches only receivers registered in code, never the manifest.
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.post {
                if (config == null || stopped) return@post
                if (socket.active) socket.check() else reconnectNow()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("supabase-realtime-push").also { it.start() }
        handler = Handler(thread.looper)
        store = Store(this)
        shade = Shade(this)
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "supabase_realtime_push:fetch")
            .apply { setReferenceCounted(false) }
        // No read timeout and no OkHttp ping on the socket: the Phoenix heartbeat already
        // finds dead sockets, and a second clock out of phase would wake the radio twice as often.
        val socketClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
        http = socketClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
        socket = PhoenixSocket(socketClient, handler, this)
        ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val required = intent?.getBooleanExtra(EXTRA_FOREGROUND_REQUIRED, false) == true
        val current = store.config.takeIf { store.status == Store.RUNNING }
        val creds = store.credentials()
        if (current == null || creds == null) {
            if (required) enterForeground(current)
            stopSelf()
            return START_NOT_STICKY
        }
        // First thing: Android allows only a few seconds between startForegroundService and startForeground.
        decideForeground(current, required)
        stopped = false
        if (intent?.action == ACTION_WATCH) wakeLock.acquire(WAKE_MS)
        handler.post {
            val changed = current != config || creds != credentials
            config = current
            credentials = creds
            when {
                intent?.action == ACTION_WATCH -> watch()
                changed || !socket.active -> reconnectNow()
            }
        }
        scheduleWatch()
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        if (store.status != Store.RUNNING) cancelWatch()
        unregisterReceiver(screenOn)
        handler.post { socket.disconnect() }
        thread.quitSafely()
        fetcher.shutdownNow()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun decideForeground(config: Config, required: Boolean) {
        val exempt = isIgnoringBatteryOptimizations(this)
        if ((!exempt || required) && !foreground) enterForeground(config)
        if (exempt && foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
    }

    private fun enterForeground(config: Config?) {
        shade.createChannels(config)
        val notification = shade.connectedNotification(config)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(this, Shade.CONNECTED_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
            } else {
                // The remoteMessaging type does not exist before Android 14; the manifest's type applies.
                startForeground(Shade.CONNECTED_ID, notification)
            }
            foreground = true
        } catch (e: Exception) {
            // Android 12+ refuses from the background outside its exemptions (the app lost its
            // battery exemption while running in the background). The service runs until the
            // system stops it; the next start from the app brings it back in the foreground.
            Log.w(TAG, "foreground refused: ${e.javaClass.simpleName}")
        }
    }

    // Connection, on the handler thread.

    private fun connect() {
        val c = config ?: return
        val creds = credentials ?: return
        if (stopped) return
        socket.connect(Phoenix.websocketUrl(c.supabaseUrl, c.publishableKey), Phoenix.channelTopic(creds.topic), c.publishableKey)
    }

    private fun reconnectNow() {
        attempt = 0
        handler.removeCallbacks(reconnect)
        connect()
    }

    override fun onJoined() {
        attempt = 0
        requestSnapshot()
    }

    override fun onChanged() {
        requestSnapshot()
    }

    override fun onDropped(reason: String, joinedForMs: Long) {
        if (stopped) return
        if (!interactive()) {
            Log.d(TAG, "screen off: reconnecting when it turns on, the watch reads the snapshot meanwhile")
            return
        }
        val delay = Phoenix.reconnectDelayMs(attempt, joinedForMs)
        if (joinedForMs < Phoenix.LONG_SESSION_MS) attempt++
        Log.d(TAG, "reconnecting in $delay ms")
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, delay)
    }

    private fun interactive(): Boolean = (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    // The watch: a wake-up every few minutes, even in Doze, without SCHEDULE_EXACT_ALARM.

    private fun watch() {
        when {
            !interactive() -> requestSnapshot()
            !socket.active -> reconnectNow()
            else -> socket.check()
        }
    }

    private fun watchIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, RealtimePushService::class.java).setAction(ACTION_WATCH),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun scheduleWatch() {
        val at = SystemClock.elapsedRealtime() + WATCH_INTERVAL_MS
        try {
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, watchIntent())
        } catch (e: Exception) {
            Log.w(TAG, "watch not scheduled: ${e.javaClass.simpleName}")
        }
    }

    private fun cancelWatch() {
        (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(watchIntent())
    }

    // Snapshot: one fetch running and at most one waiting, so a burst of signals is two reads.

    private fun requestSnapshot() {
        if (fetchQueued.getAndSet(true)) return
        try {
            fetcher.execute {
                fetchQueued.set(false)
                wakeLock.acquire(WAKE_MS)
                try {
                    fetchOnce()
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        } catch (e: Exception) {
            // The executor is shut down: the service is going away.
            fetchQueued.set(false)
        }
    }

    private fun fetchOnce() {
        val c = config ?: return
        val creds = credentials ?: return
        if (stopped) return
        when (val result = fetchSnapshot(http, c, creds)) {
            is SnapshotResult.Ok -> if (!stopped) shade.apply(result.snapshot, c)
            is SnapshotResult.Rejected -> {
                Log.w(TAG, "the server does not know this device: stopping")
                store.status = Store.REJECTED
                shade.clear()
                stopped = true
                stopSelf()
            }
            is SnapshotResult.Failed -> Log.w(TAG, "snapshot failed: ${result.reason}")
        }
    }
}
