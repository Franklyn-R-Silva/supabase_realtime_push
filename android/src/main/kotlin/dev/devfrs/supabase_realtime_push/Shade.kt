package dev.devfrs.supabase_realtime_push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.app.RemoteInput

/** The package's notifications in the system shade: the snapshot ones and the "connected" one. */
internal class Shade(private val context: Context) {
    companion object {
        const val MESSAGES_CHANNEL = "supabase_realtime_push_messages"
        const val CONNECTION_CHANNEL = "supabase_realtime_push_connection"

        /** Every snapshot notification uses this id; its snapshot id is the tag. */
        const val NOTIFICATION_ID = 0x52_50_01
        const val CONNECTED_ID = 0x52_50_02

        const val ACTION_TAP = "dev.devfrs.supabase_realtime_push.TAP"
        const val ACTION_DISMISS = "dev.devfrs.supabase_realtime_push.DISMISS"
        const val ACTION_REPLY = "dev.devfrs.supabase_realtime_push.REPLY"
        const val EXTRA_ID = "dev.devfrs.supabase_realtime_push.ID"
        const val EXTRA_REVISION = "dev.devfrs.supabase_realtime_push.REVISION"
        const val EXTRA_DATA = "dev.devfrs.supabase_realtime_push.DATA"
        const val EXTRA_KEY = "dev.devfrs.supabase_realtime_push.KEY"
        const val EXTRA_TITLE = "dev.devfrs.supabase_realtime_push.TITLE"
        const val EXTRA_BODY = "dev.devfrs.supabase_realtime_push.BODY"

        /** The RemoteInput result key: what the user typed. */
        const val KEY_REPLY_TEXT = "dev.devfrs.supabase_realtime_push.REPLY_TEXT"

        /**
         * One owner of the shade at a time: the snapshot thread posts while a swipe or a tap
         * records a dismissal on the main thread. Without it, a swipe landing mid-apply was
         * reposted at once.
         */
        private val LOCK = Any()

        /**
         * Android silently drops updates of a shown notification past 5 per second per app,
         * and the memory would then call them posted. Posts 250 ms apart stay under it.
         */
        private const val POST_GAP_MS = 250L
        private var lastPostAt = 0L // guarded by LOCK
    }

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val store = Store(context)

    /** Updating an existing channel only changes its name; sound and importance stay the user's. */
    fun createChannels(config: Config?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val messages = NotificationChannel(MESSAGES_CHANNEL, config?.channelName ?: appLabel(), NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(true) }
        val connection = NotificationChannel(CONNECTION_CHANNEL, config?.connectedTitle ?: appLabel(), NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) }
        manager.createNotificationChannel(messages)
        manager.createNotificationChannel(connection)
    }

    fun connectedNotification(config: Config?): Notification =
        NotificationCompat.Builder(context, CONNECTION_CHANNEL)
            .setSmallIcon(smallIcon(config))
            .setContentTitle(config?.connectedTitle ?: appLabel())
            .setContentText(config?.connectedText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setSilent(true)
            .setContentIntent(openApp(null))
            .build()

    fun apply(snapshot: Snapshot, config: Config): Unit = synchronized(LOCK) {
        // A stop() that ran while this snapshot was in flight wins.
        if (store.status != Store.RUNNING) return
        createChannels(config)
        val plan = reconcile(snapshot, shown(), store.memory())
        for (id in plan.cancel) manager.cancel(id, NOTIFICATION_ID)
        val now = System.currentTimeMillis()
        // A decreasing `when` keeps the snapshot's order among what is posted together, first on top.
        plan.post.forEachIndexed { i, post ->
            // A stop() waiting on the lock gets in within one gap.
            if (i > 0 && store.status != Store.RUNNING) return
            notify(post, config, now - i)
        }
        store.saveMemory(plan.memory)
        Log.d(TAG, "shade: ${plan.post.size} posted, ${plan.cancel.size} removed")
    }

    /** The user swiped [id] away, or tapped it, while it showed [revision]. */
    fun dismissed(id: String, revision: Long) = synchronized(LOCK) {
        val memory = store.memory()
        // Never backwards: a late swipe of an old revision must not undo a newer one.
        store.saveMemory(memory.copy(dismissed = memory.dismissed + (id to maxOf(revision, memory.dismissed[id] ?: Long.MIN_VALUE))))
        // A snapshot applied between the gesture and this call may have reposted it.
        manager.cancel(id, NOTIFICATION_ID)
    }

    /**
     * The reply typed under [n] did not reach the server. Posted again, alerting, with "Not sent"
     * in the header, the text under it and the Reply action to try again. Posted even if a
     * snapshot removed [n] meanwhile: the next snapshot takes it away, the text is not lost silently.
     */
    fun replyFailed(n: SnapshotNotification, text: CharSequence, config: Config): Unit = synchronized(LOCK) {
        // After stop(), nothing of the previous user's goes back in the shade.
        if (store.status != Store.RUNNING) return
        // shortcut: the repost drops the launcher badge number until the snapshot changes; carry it in the intent if that matters.
        notify(Post(n, alert = true, badge = null), config, System.currentTimeMillis(), unsent = text)
    }

    fun clear() = synchronized(LOCK) {
        for (id in shown()) manager.cancel(id, NOTIFICATION_ID)
    }

    private fun shown(): Set<String> =
        manager.activeNotifications.filter { it.id == NOTIFICATION_ID && it.tag != null }.map { it.tag }.toSet()

    private fun notify(post: Post, config: Config, `when`: Long, unsent: CharSequence? = null) {
        val n = post.notification
        val builder = NotificationCompat.Builder(context, MESSAGES_CHANNEL)
            .setSmallIcon(smallIcon(config))
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(n.body?.let { NotificationCompat.BigTextStyle().bigText(it) })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(`when`)
            .setShowWhen(false)
            .setAutoCancel(true)
            .setContentIntent(openApp(n))
            .setDeleteIntent(onDismiss(n))
            // Alerting re-alerts an already shown notification; anything else is a quiet update,
            // also when it is new to the shade (after a reboot, say).
            .setOnlyAlertOnce(!post.alert)
            .setSilent(!post.alert)
        // Before Android 8 the notification, not the channel, carries sound and vibration.
        if (post.alert) builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        post.badge?.let { builder.setNumber(it) }
        if (n.reply) builder.addAction(replyAction(n))
        if (unsent != null) {
            builder.setSubText(context.getString(R.string.supabase_realtime_push_reply_failed)).setRemoteInputHistory(arrayOf(unsent))
        }
        // Only background threads post (the snapshot's and a reply's), so the wait never blocks the main thread.
        val wait = lastPostAt + POST_GAP_MS - SystemClock.elapsedRealtime()
        if (wait > 0) Thread.sleep(wait)
        try {
            manager.notify(n.id, NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notification not posted: no permission")
        }
        lastPostAt = SystemClock.elapsedRealtime()
    }

    /**
     * Opens the launch activity; with [n], as a tap the plugin hands to Dart. No intent data:
     * Flutter's deep linking would read it as a route.
     */
    private fun openApp(n: SnapshotNotification?): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return null
        val intent = Intent().setComponent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (n != null) {
            intent.setAction(ACTION_TAP)
                .putExtra(EXTRA_KEY, store.tapKey())
                .putExtra(EXTRA_ID, n.id)
                .putExtra(EXTRA_REVISION, n.revision)
                .putExtra(EXTRA_DATA, n.data)
            // The identifier makes each id its own PendingIntent.
            // shortcut: before Android 10 two ids with the same hashCode share one tap intent; rare, so left as is.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) intent.identifier = n.id
        }
        return PendingIntent.getActivity(
            context,
            n?.id?.hashCode() ?: CONNECTED_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun onDismiss(n: SnapshotNotification): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, DismissReceiver::class.java)
            .setAction(ACTION_DISMISS)
            // The data makes each id its own PendingIntent; extras do not count.
            .setData(Uri.fromParts("supabase-realtime-push", n.id, null))
            .putExtra(EXTRA_ID, n.id)
            .putExtra(EXTRA_REVISION, n.revision),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Carries what [replyFailed] needs to post [n] again, in case a snapshot removed it meanwhile. */
    private fun replyAction(n: SnapshotNotification): NotificationCompat.Action {
        val label = context.getString(R.string.supabase_realtime_push_reply)
        val intent = Intent(context, ReplyReceiver::class.java)
            .setAction(ACTION_REPLY)
            .setData(Uri.fromParts("supabase-realtime-push", n.id, null))
            .putExtra(EXTRA_ID, n.id)
            .putExtra(EXTRA_REVISION, n.revision)
            .putExtra(EXTRA_TITLE, n.title)
            .putExtra(EXTRA_BODY, n.body)
            .putExtra(EXTRA_DATA, n.data)
        // Mutable, or the system cannot add the typed text; the explicit component keeps that safe.
        val reply = PendingIntentCompat.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, true)
        return NotificationCompat.Action.Builder(0, label, reply)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY_TEXT).setLabel(label).build())
            // Someone holding the locked phone must not reply as its owner.
            .setAuthenticationRequired(true)
            .build()
    }

    private fun smallIcon(config: Config?): Int {
        val name = config?.smallIcon ?: return context.applicationInfo.icon
        val res = context.resources
        val id = res.getIdentifier(name, "drawable", context.packageName)
            .takeIf { it != 0 } ?: res.getIdentifier(name, "mipmap", context.packageName)
        if (id == 0) Log.w(TAG, "small icon '$name' not found, using the app icon")
        return if (id != 0) id else context.applicationInfo.icon
    }

    private fun appLabel(): String = context.applicationInfo.loadLabel(context.packageManager).toString()
}
