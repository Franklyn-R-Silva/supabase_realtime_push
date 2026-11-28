package dev.devfrs.supabase_realtime_push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlin.concurrent.thread

/**
 * Brings the service back after a reboot and after an app update. Installing an update
 * kills the process, service included, and START_STICKY does not survive a new APK.
 * Both broadcasts may start a foreground service from the background, and remoteMessaging
 * is not among the types Android 15 forbids from BOOT_COMPLETED.
 */
internal class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (Store(context).status != Store.RUNNING) return
        try {
            RealtimePushService.start(context)
        } catch (e: Exception) {
            Log.w(TAG, "service not restarted after ${intent.action}: ${e.javaClass.simpleName}")
        }
    }
}

/** The user swiped a notification away: it stays away until its revision grows. */
internal class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(Shade.EXTRA_ID) ?: return
        val revision = intent.getLongExtra(Shade.EXTRA_REVISION, Long.MIN_VALUE)
        // Off the main thread: it waits for a snapshot being posted, which can take seconds.
        val pending = goAsync()
        thread {
            try {
                Shade(context).dismissed(id, revision)
            } finally {
                pending.finish()
            }
        }
    }
}
