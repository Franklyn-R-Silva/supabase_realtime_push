package dev.devfrs.supabase_realtime_push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real bitmaps: the large icon tests decode and scale PNGs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class ShadeTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val store = Store(context)
    private val config = Config(
        "https://x.supabase.co", "key", "snap", "Messages", "Connected", "Waiting", null,
        channels = mapOf("offers" to "Offers"),
    )

    @Before
    fun running() {
        store.credentialsOrCreate()
        store.config = config
        store.status = Store.RUNNING
    }

    private fun n(id: String, revision: Long = 1, channel: String? = null, largeIcon: String? = null) =
        SnapshotNotification(id, revision, "title $id", null, false, null, channel = channel, largeIcon = largeIcon)

    private fun Shade.apply(vararg notifications: SnapshotNotification) = apply(Snapshot(null, notifications.toList()), config)

    private fun shown(): Map<String, Notification> = manager.activeNotifications.associate { it.tag to it.notification }

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /** Answers every request with [code] and [body], without a network, after [delay]. */
    private fun client(code: Int, body: ByteArray, delay: Duration = Duration.ZERO) = OkHttpClient.Builder().addInterceptor { chain ->
        ShadowSystemClock.advanceBy(delay)
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("")
            .body(body.toResponseBody()).build()
    }.build()

    @Test
    fun postsEachUnderItsIdInItsDeclaredChannel() {
        Shade(context).apply(n("a"), n("b", channel = "offers"), n("c", channel = "undeclared"))

        val shown = shown()
        assertEquals(setOf("a", "b", "c"), shown.keys)
        assertEquals(Shade.MESSAGES_CHANNEL, shown.getValue("a").channelId)
        assertEquals(Shade.channelId("offers"), shown.getValue("b").channelId)
        assertEquals(Shade.MESSAGES_CHANNEL, shown.getValue("c").channelId)
        assertEquals("Offers", manager.getNotificationChannel(Shade.channelId("offers")).name)
    }

    @Test
    fun postsNothingOnceStopped() {
        store.status = Store.STOPPED
        Shade(context).apply(n("a"))

        assertTrue(shown().isEmpty())
    }

    @Test
    fun aSwipedNotificationStaysAwayUntilItsRevisionGrows() {
        val shade = Shade(context)
        shade.apply(n("a"))
        shade.dismissed("a", 1)
        shade.apply(n("a"))
        assertTrue(shown().isEmpty())

        shade.apply(n("a", revision = 2))
        assertEquals(setOf("a"), shown().keys)
    }

    @Test
    fun clearRemovesWhatItPosted() {
        val shade = Shade(context)
        shade.apply(n("a"), n("b"))
        shade.clear()

        assertTrue(shown().isEmpty())
    }

    @Test
    fun showsALargeIconOnceItIsDownloaded() {
        val url = "https://x/shade.png"
        LargeIcons.prefetch(client(200, png(64, 64)), listOf(n("a", largeIcon = url)), 256)
        Shade(context).apply(n("a", largeIcon = url), n("b", largeIcon = "https://x/never-fetched.png"))

        assertNotNull(shown().getValue("a").getLargeIcon())
        assertNull(shown().getValue("b").getLargeIcon())
    }

    @Test
    fun skipsAFailedOrOversizedIcon() {
        LargeIcons.prefetch(client(404, png(64, 64)), listOf(n("a", largeIcon = "https://x/404.png")), 256)
        LargeIcons.prefetch(client(200, ByteArray((1 shl 20) + 1)), listOf(n("a", largeIcon = "https://x/huge.png")), 256)
        LargeIcons.prefetch(client(200, "not a picture".toByteArray()), listOf(n("a", largeIcon = "https://x/text.png")), 256)

        assertNull(LargeIcons.get("https://x/404.png"))
        assertNull(LargeIcons.get("https://x/huge.png"))
        assertNull(LargeIcons.get("https://x/text.png"))
    }

    @Test
    fun aSlowImageHostUsesUpTheSnapshotsBudget() {
        val slow = client(200, png(64, 64), delay = Duration.ofSeconds(6))
        LargeIcons.prefetch(slow, listOf(n("a", largeIcon = "https://x/first.png"), n("b", largeIcon = "https://x/second.png")), 256)

        assertNotNull(LargeIcons.get("https://x/first.png"))
        assertNull(LargeIcons.get("https://x/second.png"))
    }

    @Test
    fun decodesABigPictureToFitTheSizeAndKeepsASmallOne() {
        val big = decodeIcon(png(1000, 500), 256)!!
        assertEquals(256 to 128, big.width to big.height)

        val small = decodeIcon(png(100, 40), 256)!!
        assertEquals(100 to 40, small.width to small.height)
    }
}
