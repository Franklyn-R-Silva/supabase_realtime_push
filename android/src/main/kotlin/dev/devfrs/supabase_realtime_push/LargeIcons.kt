package dev.devfrs.supabase_realtime_push

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The pictures beside notification text (`largeIcon`), downloaded before the shade takes its
 * lock: a download under it would hold up a swipe on the main thread.
 */
internal object LargeIcons {
    /** Bigger downloads are skipped: an avatar is a few kilobytes. */
    private const val MAX_DOWNLOAD = 1L shl 20

    /** For all of a snapshot's icons together: a down image host delays the shade by this much, not per icon. */
    private const val BUDGET_MS = 5_000L

    /** By bitmap bytes: about sixteen icons at the xxxhdpi size. */
    private val cache = object : LruCache<String, Bitmap>(4 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(url: String?): Bitmap? = url?.let { cache.get(it) }

    /** Blocking, off the main thread: fetches what [notifications] need and the cache lacks. */
    fun prefetch(client: OkHttpClient, notifications: List<SnapshotNotification>, sizePx: Int) {
        val deadline = SystemClock.elapsedRealtime() + BUDGET_MS
        for (url in notifications.mapNotNull { it.largeIcon }.distinct()) {
            if (cache.get(url) != null) continue
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            // shortcut: a URL that fails is tried again on every snapshot; remember failures if servers list broken ones.
            download(client, url, left)?.let { decodeIcon(it, sizePx) }?.let { cache.put(url, it) }
        }
    }

    private fun download(client: OkHttpClient, url: String, timeoutMs: Long): ByteArray? = try {
        val call = client.newCall(Request.Builder().url(url).build()).apply { timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS) }
        call.execute().use { response ->
            val source = response.body?.source()
            when {
                !response.isSuccessful || source == null -> null.also { Log.w(TAG, "large icon failed: HTTP ${response.code}") }
                // request() is true only when more than the limit is there.
                source.request(MAX_DOWNLOAD + 1) -> null.also { Log.w(TAG, "large icon skipped: over 1 MB") }
                else -> source.buffer.readByteArray()
            }
        }
    } catch (e: Exception) {
        // Never the URL: it can name the sender.
        Log.w(TAG, "large icon failed: ${e.javaClass.simpleName}")
        null
    }
}

/**
 * Decodes [bytes] to fit a [sizePx] square. Sampled while decoding, so a small file that claims
 * a huge picture never fills the memory, and scaled down after, so the notification stays far
 * below the binder's transaction limit.
 */
internal fun decodeIcon(bytes: ByteArray, sizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (longest / (sample * 2) >= sizePx) sample *= 2
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return null
    val scale = sizePx.toFloat() / maxOf(bitmap.width, bitmap.height)
    if (scale >= 1f) return bitmap
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}
