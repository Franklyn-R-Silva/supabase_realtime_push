package dev.devfrs.supabase_realtime_push

import okio.Buffer
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class ReplyTest {
    private val config = Config(
        supabaseUrl = "https://xyz.supabase.co/",
        publishableKey = "sb_publishable_abc",
        snapshotFunction = "my_snapshot",
        channelName = "Messages",
        connectedTitle = "Connected",
        connectedText = "Waiting",
        smallIcon = null,
    )
    private val credentials = Credentials("t".repeat(43), "s".repeat(43))

    @Test
    fun theReplyGoesToItsOwnFunctionWithThePairAndNoBearer() {
        val request = replyRequest(config, credentials, "chat:42", "See you at 3 \"ok\"")

        assertEquals("POST", request.method)
        // Not the configurable snapshot function: replies always go to realtime_push_reply.
        assertEquals("https://xyz.supabase.co/rest/v1/rpc/realtime_push_reply", request.url.toString())
        assertEquals("sb_publishable_abc", request.header("apikey"))
        assertNull(request.header("Authorization"))
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())

        val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals(setOf("p_topic", "p_secret", "p_id", "p_text"), body.keys().asSequence().toSet())
        assertEquals(credentials.topic, body.getString("p_topic"))
        assertEquals(credentials.secret, body.getString("p_secret"))
        assertEquals("chat:42", body.getString("p_id"))
        assertEquals("See you at 3 \"ok\"", body.getString("p_text"))
    }
}
