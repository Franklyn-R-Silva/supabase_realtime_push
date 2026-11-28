package dev.devfrs.supabase_realtime_push

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** One entry of the snapshot (contract §3). [data] stays a JSON string: Dart decodes it. */
internal data class SnapshotNotification(
    val id: String,
    val revision: Long,
    val title: String,
    val body: String?,
    val silent: Boolean,
    val data: String?,
    /** Offer a Reply action; the text goes to `realtime_push_reply`. */
    val reply: Boolean = false,
)

internal data class Snapshot(val badge: Int?, val notifications: List<SnapshotNotification>)

internal sealed class SnapshotResult {
    data class Ok(val snapshot: Snapshot) : SnapshotResult()

    /** The server answered `null`: this topic/secret pair is not registered. */
    object Rejected : SnapshotResult()

    data class Failed(val reason: String) : SnapshotResult()
}

/**
 * `null` for a JSON `null` body. Entries missing a required field are skipped, unknown
 * fields are ignored, and anything that is not a JSON object throws.
 */
internal fun parseSnapshot(text: String): Snapshot? {
    val trimmed = text.trim()
    if (trimmed == "null") return null
    val json = JSONObject(trimmed)
    val list = json.optJSONArray("notifications")
    val notifications = (0 until (list?.length() ?: 0))
        .mapNotNull { list?.optJSONObject(it)?.let(::parseNotification) }
        .distinctBy { it.id }
    return Snapshot((json.opt("badge") as? Number)?.toInt()?.coerceAtLeast(0), notifications)
}

private fun parseNotification(json: JSONObject): SnapshotNotification? {
    val id = json.opt("id") as? String
    val revision = json.opt("revision") as? Number
    val title = json.opt("title") as? String
    if (id.isNullOrEmpty() || revision == null || title == null) return null
    return SnapshotNotification(
        id = id,
        revision = revision.toLong(),
        title = title,
        body = json.opt("body") as? String,
        silent = json.opt("silent") as? Boolean ?: false,
        data = json.optJSONObject("data")?.toString(),
        reply = json.opt("reply") as? Boolean ?: false,
    )
}

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

/** A call to a SQL function as the anon role; the device's pair in [body] is the only credential. */
internal fun rpcRequest(config: Config, function: String, body: JSONObject): Request = Request.Builder()
    .url("${config.supabaseUrl.trimEnd('/')}/rest/v1/rpc/$function")
    // No Authorization header: the new keys are not JWTs, and the gateway applies the anon role without one.
    .header("apikey", config.publishableKey)
    .header("Accept", "application/json")
    .post(body.toString().toRequestBody(JSON_TYPE))
    .build()

/** Blocking: call it off the main thread. Never put the response body in a log. */
internal fun fetchSnapshot(client: OkHttpClient, config: Config, credentials: Credentials): SnapshotResult {
    val body = JSONObject().put("p_topic", credentials.topic).put("p_secret", credentials.secret)
    val request = rpcRequest(config, config.snapshotFunction, body)
    return try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return SnapshotResult.Failed("HTTP ${response.code}")
            val snapshot = parseSnapshot(response.body?.string().orEmpty())
            if (snapshot == null) SnapshotResult.Rejected else SnapshotResult.Ok(snapshot)
        }
    } catch (e: Exception) {
        SnapshotResult.Failed(e.javaClass.simpleName)
    }
}
