package dev.devfrs.supabase_realtime_push

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

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
        const val EXTRA_ID = "dev.devfrs.supabase_realtime_push.ID"
        const val EXTRA_REVISION = "dev.devfrs.supabase_realtime_push.REVISION"
        const val EXTRA_DATA = "dev.devfrs.supabase_realtime_push.DATA"
        const val EXTRA_KEY = "dev.devfrs.supabase_realtime_push.KEY"

        /**
         * One owner of the shade at a time: the snapshot thread posts while a swipe or a tap
         * records a dismissal on the main thread. Without it, a swipe landing mid-apply was
         * reposted at once.
         */
        private val LOCK = Any()
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
        val plan = reconcile(snapshot, shown(), store.alerted(), store.dismissed())
        for (id in plan.cancel) manager.cancel(id, NOTIFICATION_ID)
        val now = System.currentTimeMillis()
        plan.post.forEachIndexed { i, post ->
            // Launchers that show a number add up every notification's, so only one carries the badge.
            val badge = if (i == 0) snapshot.badge else null
            // A decreasing `when` keeps the snapshot's order in the shade, first on top.
            notify(post, config, badge, now - i)
        }
        store.saveRevisions(plan.alerted, plan.dismissed)
        Log.d(TAG, "shade: ${plan.post.size} shown, ${plan.cancel.size} removed")
    }

    /** The user swiped [id] away, or tapped it, while it showed [revision]. */
    fun dismissed(id: String, revision: Long) = synchronized(LOCK) {
        val dismissed = store.dismissed()
        // Never backwards: a late swipe of an old revision must not undo a newer one.
        store.saveRevisions(store.alerted(), dismissed + (id to maxOf(revision, dismissed[id] ?: Long.MIN_VALUE)))
        // A snapshot applied between the gesture and this call may have reposted it.
        manager.cancel(id, NOTIFICATION_ID)
    }

    fun clear() = synchronized(LOCK) {
        for (id in shown()) manager.cancel(id, NOTIFICATION_ID)
    }

    private fun shown(): Set<String> =
        manager.activeNotifications.filter { it.id == NOTIFICATION_ID && it.tag != null }.map { it.tag }.toSet()

    private fun notify(post: Post, config: Config, badge: Int?, `when`: Long) {
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
        if (badge != null) builder.setNumber(badge)
        try {
            manager.notify(n.id, NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "notification not posted: no permission")
        }
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
