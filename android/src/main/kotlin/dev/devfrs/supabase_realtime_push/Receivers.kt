package dev.devfrs.supabase_realtime_push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
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

/**
 * The user replied from the shade (contract §9). Sent off the main thread; Android keeps the
 * notification's spinner until it is posted again or removed, so both outcomes do one of those.
 */
internal class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Shade.KEY_REPLY_TEXT) ?: return
        val n = SnapshotNotification(
            id = intent.getStringExtra(Shade.EXTRA_ID) ?: return,
            revision = intent.getLongExtra(Shade.EXTRA_REVISION, Long.MIN_VALUE),
            title = intent.getStringExtra(Shade.EXTRA_TITLE) ?: return,
            body = intent.getStringExtra(Shade.EXTRA_BODY),
            silent = false,
            data = intent.getStringExtra(Shade.EXTRA_DATA),
            reply = true,
        )
        val pending = goAsync()
        thread(name = "supabase-realtime-push-reply") {
            try {
                val store = Store(context)
                // Missing only after stop(forgetDevice: true), which also cleared the shade: nothing to send or show.
                val config = store.config ?: return@thread
                val credentials = store.credentials() ?: return@thread
                val shade = Shade(context)
                // Sent counts as a dismissal, like a tap: the user has read it, and the next snapshot would put it back.
                if (sendReply(replyRequest(config, credentials, n.id, text.toString()))) {
                    shade.dismissed(n.id, n.revision)
                } else {
                    shade.replyFailed(n, text, config)
                }
            } catch (e: Exception) {
                Log.w(TAG, "reply not handled: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
