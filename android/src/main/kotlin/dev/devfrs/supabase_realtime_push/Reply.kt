package dev.devfrs.supabase_realtime_push

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** The SQL function that takes a reply typed in the shade (contract §9). */
internal const val REPLY_FUNCTION = "realtime_push_reply"

/** A reply is sent from a receiver, where the service's client may not exist: the snapshot's timeout. */
private val replyHttp = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()

internal fun replyRequest(config: Config, credentials: Credentials, id: String, text: String): Request = rpcRequest(
    config,
    REPLY_FUNCTION,
    JSONObject().put("p_topic", credentials.topic).put("p_secret", credentials.secret).put("p_id", id).put("p_text", text),
)

/**
 * Blocking: call it off the main thread. `true` only when the server answered `true`; a wrong
 * pair (`null`), an HTTP error or a timeout is `false`. Never log the text or the response body.
 */
internal fun sendReply(request: Request): Boolean = try {
    replyHttp.newCall(request).execute().use { response ->
        val sent = response.isSuccessful && response.body?.string()?.trim() == "true"
        if (!sent) Log.w(TAG, "reply not sent: HTTP ${response.code}")
        sent
    }
} catch (e: Exception) {
    Log.w(TAG, "reply not sent: ${e.javaClass.simpleName}")
    false
}
