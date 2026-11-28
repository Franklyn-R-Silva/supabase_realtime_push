package dev.devfrs.supabase_realtime_push

import org.json.JSONException
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

internal class SnapshotTest {
    @Test
    fun parsesTheContractExample() {
        val snapshot = parseSnapshot(
            """
            {
              "badge": 2,
              "notifications": [
                {"id": "chat:42", "revision": 1017, "title": "Ana", "body": "See you at 3", "silent": false, "reply": true, "data": {"route": "/chat/42"}}
              ]
            }
            """,
        )

        assertEquals(2, snapshot?.badge)
        val n = snapshot!!.notifications.single()
        assertEquals("chat:42", n.id)
        assertEquals(1017L, n.revision)
        assertEquals("Ana", n.title)
        assertEquals("See you at 3", n.body)
        assertEquals(false, n.silent)
        assertEquals(true, n.reply)
        assertEquals("/chat/42", JSONObject(n.data!!).getString("route"))
    }

    @Test
    fun replyIsOfferedOnlyForATrueBoolean() {
        val snapshot = parseSnapshot(
            """
            {"notifications": [
              {"id": "yes", "revision": 1, "title": "T", "reply": true},
              {"id": "missing", "revision": 1, "title": "T"},
              {"id": "no", "revision": 1, "title": "T", "reply": false},
              {"id": "text", "revision": 1, "title": "T", "reply": "true"},
              {"id": "null", "revision": 1, "title": "T", "reply": null}
            ]}
            """,
        )!!

        assertEquals(listOf("yes"), snapshot.notifications.filter { it.reply }.map { it.id })
    }

    @Test
    fun readsTheChannelAndOnlyAnHttpsLargeIcon() {
        val snapshot = parseSnapshot(
            """
            {"notifications": [
              {"id": "a", "revision": 1, "title": "T", "channel": "offers", "largeIcon": "https://x/a.png"},
              {"id": "b", "revision": 1, "title": "T", "channel": 7, "largeIcon": "http://x/a.png"},
              {"id": "c", "revision": 1, "title": "T", "largeIcon": "file:///sdcard/a.png"}
            ]}
            """,
        )!!

        assertEquals(listOf("offers", null, null), snapshot.notifications.map { it.channel })
        assertEquals(listOf("https://x/a.png", null, null), snapshot.notifications.map { it.largeIcon })
    }

    @Test
    fun optionalFieldsHaveDefaults() {
        val snapshot = parseSnapshot("""{"notifications":[{"id":"a","revision":1,"title":"T","body":null,"data":null}]}""")!!

        assertNull(snapshot.badge)
        assertEquals(SnapshotNotification("a", 1, "T", body = null, silent = false, data = null), snapshot.notifications.single())
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val snapshot = parseSnapshot("""{"v":2,"notifications":[{"id":"a","revision":1,"title":"T","color":"red"}],"extra":{}}""")!!

        assertEquals("a", snapshot.notifications.single().id)
    }

    @Test
    fun nullMeansRejected() {
        assertNull(parseSnapshot("null"))
        assertNull(parseSnapshot("  null\n"))
    }

    @Test
    fun anythingElseThatIsNotASnapshotThrows() {
        assertFailsWith<JSONException> { parseSnapshot("") }
        assertFailsWith<JSONException> { parseSnapshot("<html>") }
        assertFailsWith<JSONException> { parseSnapshot("[]") }
    }

    @Test
    fun entriesMissingARequiredFieldAreSkipped() {
        val snapshot = parseSnapshot(
            """
            {"notifications": [
              {"revision": 1, "title": "no id"},
              {"id": "", "revision": 1, "title": "empty id"},
              {"id": "b", "title": "no revision"},
              {"id": "c", "revision": "3", "title": "revision as text"},
              {"id": "d", "revision": 4},
              {"id": 5, "revision": 5, "title": "numeric id"},
              "not an object",
              {"id": "ok", "revision": 6, "title": "kept"}
            ]}
            """,
        )!!

        assertEquals(listOf("ok"), snapshot.notifications.map { it.id })
    }

    @Test
    fun noNotificationsIsAnEmptyShade() {
        assertEquals(Snapshot(badge = 0, notifications = emptyList()), parseSnapshot("""{"badge":0}"""))
    }

    @Test
    fun aRepeatedIdKeepsTheFirstEntry() {
        val snapshot = parseSnapshot("""{"notifications":[{"id":"a","revision":2,"title":"first"},{"id":"a","revision":3,"title":"second"}]}""")!!

        assertEquals("first", snapshot.notifications.single().title)
    }

    @Test
    fun keepsAnInt64RevisionAndANegativeBadgeBecomesZero() {
        val snapshot = parseSnapshot("""{"badge":-3,"notifications":[{"id":"a","revision":9007199254740993,"title":"T"}]}""")!!

        assertEquals(9007199254740993L, snapshot.notifications.single().revision)
        assertEquals(0, snapshot.badge)
    }
}
