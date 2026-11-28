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
import kotlin.concurrent.thread

/**
 * Holds the Realtime connection with the app closed and keeps the shade equal to the
 * snapshot (contract §5). No Flutter engine runs here.
 *
 * Foreground, with the "connected" notification, only while the app is not exempt from
 * battery optimization: an exempt app may run a background service, anything else is
 * stopped about a minute after the app leaves the screen. The choice is re-made on every
 * start command, the periodic watch included. With `foregroundService: false` it never goes
 * foreground: not exempt, it runs while the app is open and Android stops it soon after.
 *
 * The watch is an alarm broadcast to [WatchReceiver], which hands it to this service when
 * Android lets it start, and otherwise reads the snapshot itself ([readWithoutService]): the
 * system killed the service, or `foregroundService` is off and the app is closed.
 *
 * With the screen off it does not reconnect. The CPU sleeps, heartbeats stop, and the
 * server closes the idle socket within a minute; reconnecting then meant one TLS
 * handshake, join and snapshot per minute all night. The watch reads the snapshot instead,
 * and turning the screen on reconnects.
 */
internal class RealtimePushService : Service(), PhoenixSocket.Listener {
    companion object {
        const val ACTION_WATCH = "dev.devfrs.supabase_realtime_push.WATCH"

        /** Started with startForegroundService: Android kills the app unless startForeground follows, exempt or not. */
        private const val EXTRA_FOREGROUND_REQUIRED = "dev.devfrs.supabase_realtime_push.FOREGROUND_REQUIRED"
        private const val WATCH_INTERVAL_MS = 5 * 60_000L
        private const val WAKE_MS = 20_000L

        /** Throws when Android refuses (Android 12+ from the background, for instance). */
        fun start(context: Context) {
            val intent = Intent(context, RealtimePushService::class.java)
            if (isIgnoringBatteryOptimizations(context) || Store(context).config?.foregroundService == false) {
                context.startService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent.putExtra(EXTRA_FOREGROUND_REQUIRED, true))
            }
        }

        fun stop(context: Context) {
            // The service may be gone already, its watch still set: the alarm outlives the process.
            cancelWatch(context)
            context.stopService(Intent(context, RealtimePushService::class.java))
        }

        /** Without SCHEDULE_EXACT_ALARM: Android may defer it, more so in Doze and for an app seldom opened. */
        fun scheduleWatch(context: Context) {
            val at = SystemClock.elapsedRealtime() + WATCH_INTERVAL_MS
            try {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, watchIntent(context))
            } catch (e: Exception) {
                Log.w(TAG, "watch not scheduled: ${e.javaClass.simpleName}")
            }
        }

        fun cancelWatch(context: Context) {
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(watchIntent(context))
        }

        private fun watchIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, WatchReceiver::class.java).setAction(ACTION_WATCH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** For [readWithoutService], which has no service to own a client. */
        private val alone by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build() }

        /**
         * The watch without the service, on a background thread: schedules the next one first, so a
         * failure here never ends the watch, then reads and applies the snapshot under a wake lock.
         */
        fun readWithoutService(context: Context, http: OkHttpClient = alone) {
            val store = Store(context)
            if (store.status != Store.RUNNING) return
            val config = store.config ?: return
            val credentials = store.credentials() ?: return
            scheduleWatch(context)
            val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "supabase_realtime_push:watch")
            wakeLock.acquire(WAKE_MS)
            try {
                if (!readSnapshot(context, http, config, credentials, Shade(context), store)) cancelWatch(context)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
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
    private val funnel = FetchFunnel()

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
        if (!decideForeground(current, required)) {
            // Not exempt, and Android 12+ refused the foreground from the background (a sticky
            // restart, the watch). A background service would be stopped within a minute anyway.
            // The status stays running: the next app launch starts it again.
            Log.w(TAG, "foreground not allowed from the background: waiting for the app to open")
            // Until then the watch reads the snapshot without the service.
            thread(name = "supabase-realtime-push-watch") { readWithoutService(applicationContext) }
            stopSelf()
            return START_NOT_STICKY
        }
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
        scheduleWatch(this)
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        if (store.status != Store.RUNNING) cancelWatch(this)
        unregisterReceiver(screenOn)
        handler.post { socket.disconnect() }
        thread.quitSafely()
        fetcher.shutdownNow()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    /** `false` when the service cannot keep running: it needs the foreground and Android refused it. */
    private fun decideForeground(config: Config, required: Boolean): Boolean {
        val wanted = config.foregroundService && !isIgnoringBatteryOptimizations(this)
        // Started with startForegroundService, it must call startForeground even to leave it at once.
        if ((wanted || required) && !foreground) enterForeground(config)
        if (!wanted && foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        return !wanted || foreground
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
            // ForegroundServiceStartNotAllowedException: Android 12+ outside its exemptions.
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

    // Snapshot: signals and joins go through the funnel, one fetch running and one waiting.

    private fun requestSnapshot() {
        if (!funnel.request()) return
        try {
            fetcher.execute {
                wakeLock.acquire(WAKE_MS)
                try {
                    do {
                        // An escaped exception would crash the app and leave the funnel shut for good.
                        try {
                            fetchOnce()
                        } catch (e: Exception) {
                            Log.w(TAG, "snapshot not applied: ${e.javaClass.simpleName}")
                        }
                    } while (funnel.done())
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                }
            }
        } catch (e: Exception) {
            // The executor is shut down: the service is going away.
        }
    }

    private fun fetchOnce() {
        val c = config ?: return
        val creds = credentials ?: return
        if (stopped) return
        if (!readSnapshot(this, http, c, creds, shade, store)) {
            stopped = true
            stopSelf()
        }
    }
}

/**
 * Reads the snapshot and makes the shade match it. `false` when the server does not know the
 * pair: the status is then `rejected` and the shade empty. Blocking: off the main thread.
 */
internal fun readSnapshot(context: Context, http: OkHttpClient, config: Config, credentials: Credentials, shade: Shade, store: Store): Boolean {
    when (val result = fetchSnapshot(http, config, credentials)) {
        is SnapshotResult.Ok -> {
            val iconSize = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
            // An https icon never redirects to http.
            LargeIcons.prefetch(http.newBuilder().followSslRedirects(false).build(), result.snapshot.notifications, iconSize)
            shade.apply(result.snapshot, config)
        }
        is SnapshotResult.Rejected -> {
            Log.w(TAG, "the server does not know this device: stopping")
            store.status = Store.REJECTED
            shade.clear()
            return false
        }
        is SnapshotResult.Failed -> Log.w(TAG, "snapshot failed: ${result.reason}")
    }
    return true
}
