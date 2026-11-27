package dev.devfrs.supabase_realtime_push

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class PhoenixTest {
    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJyb2xlIjoiYW5vbiJ9.c2lnbmF0dXJl"

    @Test
    fun joinFrameMatchesTheContract() {
        val frame = JSONObject(Phoenix.join("realtime:push:abc", "7", jwt))

        assertEquals("realtime:push:abc", frame.getString("topic"))
        assertEquals("phx_join", frame.getString("event"))
        assertEquals("7", frame.getString("ref"))
        assertEquals("7", frame.getString("join_ref"))
        val payload = frame.getJSONObject("payload")
        assertEquals(jwt, payload.getString("access_token"))
        val config = payload.getJSONObject("config")
        assertFalse(config.getJSONObject("broadcast").getBoolean("self"))
        assertEquals("", config.getJSONObject("presence").getString("key"))
        assertFalse(config.getBoolean("private"))
    }

    @Test
    fun joinLeavesAccessTokenOutForAPublishableKey() {
        val payload = JSONObject(Phoenix.join("realtime:push:abc", "1", "sb_publishable_abc123")).getJSONObject("payload")

        assertFalse(payload.has("access_token"))
        assertTrue(payload.has("config"))
    }

    @Test
    fun onlyThreePartEyJKeysAreJwts() {
        assertTrue(Phoenix.isJwt(jwt))
        assertFalse(Phoenix.isJwt("sb_publishable_abc123"))
        assertFalse(Phoenix.isJwt("eyJonly.two"))
    }

    @Test
    fun heartbeatFrameGoesToThePhoenixTopic() {
        val frame = JSONObject(Phoenix.heartbeat("3"))

        assertEquals("phoenix", frame.getString("topic"))
        assertEquals("heartbeat", frame.getString("event"))
        assertEquals("3", frame.getString("ref"))
        assertEquals(0, frame.getJSONObject("payload").length())
    }

    @Test
    fun parsesAReply() {
        val frame = Phoenix.parse("""{"topic":"realtime:push:abc","event":"phx_reply","payload":{"status":"ok","response":{}},"ref":"1"}""")

        assertEquals(Phoenix.Frame("realtime:push:abc", "phx_reply", "1", "ok"), frame)
    }

    @Test
    fun parsesABroadcastWithANullRef() {
        val frame = Phoenix.parse("""{"topic":"realtime:push:abc","event":"broadcast","payload":{"event":"changed","payload":{}},"ref":null}""")

        assertEquals("broadcast", frame?.event)
        assertNull(frame?.ref)
        assertNull(frame?.status)
    }

    @Test
    fun ignoresWhatIsNotAJsonObject() {
        assertNull(Phoenix.parse("not json"))
        assertNull(Phoenix.parse("[1,2]"))
    }

    @Test
    fun buildsTheWebsocketUrl() {
        assertEquals(
            "wss://xyz.supabase.co/realtime/v1/websocket?apikey=sb_publishable_k&vsn=1.0.0",
            Phoenix.websocketUrl("https://xyz.supabase.co/", "sb_publishable_k"),
        )
        assertEquals(
            "ws://10.0.2.2:54321/realtime/v1/websocket?apikey=a%2Bb%3D&vsn=1.0.0",
            Phoenix.websocketUrl("HTTP://10.0.2.2:54321", "a+b="),
        )
    }

    @Test
    fun backoffFollowsTheContractThenStaysAt30Seconds() {
        assertEquals(listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L, 30_000L), (0..6).map(Phoenix::backoffMs))
    }

    @Test
    fun backoffIsSpreadBetweenHalfAndOneAndAHalfTimes() {
        val almostOne = Math.nextDown(1.0)
        for (attempt in 0..6) {
            val base = Phoenix.backoffMs(attempt)
            assertEquals(base / 2, Phoenix.reconnectDelayMs(attempt, joinedForMs = 0L, unit = 0.0))
            assertEquals(base, Phoenix.reconnectDelayMs(attempt, joinedForMs = 5_000L, unit = 0.5))
            assertTrue(Phoenix.reconnectDelayMs(attempt, joinedForMs = 0L, unit = almostOne) <= base * 3 / 2)
        }
    }

    @Test
    fun aDropAfterALongSessionReconnectsWithinFiveSeconds() {
        val day = 24 * 3_600_000L
        assertEquals(0L, Phoenix.reconnectDelayMs(attempt = 4, joinedForMs = day, unit = 0.0))
        assertTrue(Phoenix.reconnectDelayMs(attempt = 4, joinedForMs = day, unit = Math.nextDown(1.0)) <= 5_000L)
    }

    @Test
    fun theDefaultRandomStaysInBounds() {
        repeat(1_000) {
            assertTrue(Phoenix.reconnectDelayMs(attempt = 4, joinedForMs = 0L) in 15_000L..45_000L)
            assertTrue(Phoenix.reconnectDelayMs(attempt = 0, joinedForMs = Phoenix.LONG_SESSION_MS) in 0L..5_000L)
        }
    }
}
