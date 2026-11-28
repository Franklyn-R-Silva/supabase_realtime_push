package dev.devfrs.supabase_realtime_push

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
internal class ServiceTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = Store(context)
    private val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    private val manager = context.getSystemService(NotificationManager::class.java)

    // A closed port: the socket fails at once, nothing leaves the machine.
    private fun running(foregroundService: Boolean) {
        store.credentialsOrCreate()
        store.config = Config("http://127.0.0.1:9", "key", "snap", "Messages", "Connected", "Waiting", null, foregroundService = foregroundService)
        store.status = Store.RUNNING
    }

    private fun exempt() = shadowOf(context.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)

    /** Starts the service and returns whether it went foreground, destroying it after. */
    private fun startsForeground(): Boolean {
        val controller = Robolectric.buildService(RealtimePushService::class.java, Intent()).create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        try {
            assertFalse(shadow.isStoppedBySelf)
            return shadow.lastForegroundNotification != null
        } finally {
            controller.destroy()
        }
    }

    /** Answers the snapshot call with [body], without a network. */
    private fun snapshot(body: String) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("")
            .body(body.toResponseBody()).build()
    }.build()

    @Test
    fun notExemptItRunsInTheForegroundWithTheConnectedNotification() {
        running(foregroundService = true)
        assertTrue(startsForeground())
    }

    @Test
    fun withForegroundServiceOffItNeverGoesForeground() {
        running(foregroundService = false)
        assertFalse(startsForeground())
    }

    @Test
    fun exemptItNeverGoesForeground() {
        running(foregroundService = true)
        exempt()
        assertFalse(startsForeground())
    }

    @Test
    fun theWatchWithoutTheServiceShowsTheSnapshotAndSetsTheNextOne() {
        running(foregroundService = false)

        RealtimePushService.readWithoutService(context, snapshot("""{"notifications":[{"id":"a","revision":1,"title":"Hi"}]}"""))

        assertEquals(listOf("a"), manager.activeNotifications.map { it.tag })
        assertNotNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun theWatchWithoutTheServiceStopsForARejectedPair() {
        running(foregroundService = false)

        RealtimePushService.readWithoutService(context, snapshot("null"))

        assertEquals(Store.REJECTED, store.status)
        assertNull(alarms.peekNextScheduledAlarm())
    }

    @Test
    fun theWatchDoesNothingOnceStopped() {
        running(foregroundService = false)
        store.status = Store.STOPPED

        RealtimePushService.readWithoutService(context, snapshot("""{"notifications":[{"id":"a","revision":1,"title":"Hi"}]}"""))

        assertTrue(manager.activeNotifications.isEmpty())
        assertNull(alarms.peekNextScheduledAlarm())
    }
}
