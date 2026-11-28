package dev.devfrs.supabase_realtime_push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ReconcileTest {
    private fun n(
        id: String,
        revision: Long,
        silent: Boolean = false,
        title: String = "title $id",
        body: String? = null,
        data: String? = null,
        reply: Boolean = false,
    ) = SnapshotNotification(id, revision, title, body, silent, data, reply)

    private fun snapshot(vararg notifications: SnapshotNotification, badge: Int? = null) = Snapshot(badge, notifications.toList())

    private fun planFor(
        snapshot: Snapshot,
        shown: Set<String>,
        alerted: Map<String, Long> = emptyMap(),
        dismissed: Map<String, Long> = emptyMap(),
    ) = reconcile(snapshot, shown, Memory(alerted, dismissed))

    private fun Plan.alerts() = post.filter { it.alert }.map { it.notification.id }

    private fun Plan.posts() = post.map { it.notification.id }

    /** Applies [snapshot] after [before], with the shade holding everything [before] wanted shown. */
    private fun next(before: Plan, snapshot: Snapshot) = reconcile(snapshot, before.memory.posted.keys, before.memory)

    @Test
    fun cancelsWhatLeftTheSnapshot() {
        val plan = planFor(snapshot(n("a", 1)), shown = setOf("a", "b", "c"), alerted = mapOf("a" to 1))

        assertEquals(setOf("b", "c"), plan.cancel)
        assertEquals(listOf("a"), plan.posts())
    }

    @Test
    fun anEmptySnapshotClearsTheShade() {
        val plan = planFor(snapshot(), shown = setOf("a"), alerted = mapOf("a" to 1), dismissed = mapOf("a" to 1))

        assertEquals(setOf("a"), plan.cancel)
        assertTrue(plan.post.isEmpty())
        assertEquals(Memory(), plan.memory)
    }

    @Test
    fun alertsOnlyForANewIdOrAHigherRevision() {
        val plan = planFor(
            snapshot(n("new", 1), n("same", 5), n("grew", 7), n("older", 2)),
            shown = setOf("same", "grew", "older"),
            alerted = mapOf("same" to 5, "grew" to 6, "older" to 3),
        )

        assertEquals(listOf("new", "grew"), plan.alerts())
        assertTrue(plan.cancel.isEmpty())
    }

    @Test
    fun silentPostsWithoutAlertingAndStillRemembersTheRevision() {
        val plan = planFor(snapshot(n("a", 3, silent = true)), shown = emptySet())

        assertEquals(listOf("a"), plan.posts())
        assertTrue(plan.alerts().isEmpty())
        assertEquals(mapOf("a" to 3L), plan.memory.alerted)

        val unsilenced = next(plan, snapshot(n("a", 3)))
        assertEquals(listOf("a"), unsilenced.posts())
        assertTrue(unsilenced.alerts().isEmpty())
    }

    @Test
    fun aRepostAfterARebootDoesNotAlertAgain() {
        val before = planFor(snapshot(n("a", 4)), shown = emptySet())
        // The reboot emptied the shade; the memory survived on disk.
        val plan = reconcile(snapshot(n("a", 4)), emptySet(), before.memory)

        assertEquals(listOf("a"), plan.posts())
        assertTrue(plan.alerts().isEmpty())
    }

    @Test
    fun respectsTheDismissalUntilTheRevisionGrows() {
        val dismissed = mapOf("a" to 5L)

        val same = planFor(snapshot(n("a", 5)), shown = emptySet(), alerted = mapOf("a" to 5), dismissed = dismissed)
        assertTrue(same.post.isEmpty())
        assertEquals(dismissed, same.memory.dismissed)

        val grew = planFor(snapshot(n("a", 6)), shown = emptySet(), alerted = mapOf("a" to 5), dismissed = dismissed)
        assertEquals(listOf("a"), grew.alerts())
        assertTrue(grew.memory.dismissed.isEmpty())
    }

    @Test
    fun aDismissedNotificationStillShownIsCancelled() {
        val plan = planFor(snapshot(n("a", 5)), shown = setOf("a"), alerted = mapOf("a" to 5), dismissed = mapOf("a" to 5))

        assertEquals(setOf("a"), plan.cancel)
        assertTrue(plan.post.isEmpty())
        assertTrue(plan.memory.posted.isEmpty())
    }

    @Test
    fun prunesTheMemoryToTheSnapshot() {
        val plan = planFor(
            snapshot(n("a", 2), n("b", 9)),
            shown = emptySet(),
            alerted = mapOf("a" to 1, "gone" to 4),
            dismissed = mapOf("b" to 9, "gone" to 4),
        )

        assertEquals(mapOf("a" to 2L, "b" to 9L), plan.memory.alerted)
        assertEquals(mapOf("b" to 9L), plan.memory.dismissed)
        assertEquals(setOf("a"), plan.memory.posted.keys)
    }

    @Test
    fun aRevisionThatWentBackKeepsTheHigherAlertedOne() {
        val plan = planFor(snapshot(n("a", 3)), shown = setOf("a"), alerted = mapOf("a" to 8))

        assertEquals(mapOf("a" to 8L), plan.memory.alerted)
        assertTrue(plan.alerts().isEmpty())
    }

    @Test
    fun anUnchangedSnapshotPostsNothing() {
        val first = planFor(snapshot(n("a", 1), n("b", 2, body = "x", data = """{"k":1}"""), badge = 3), shown = emptySet())
        assertEquals(listOf("a", "b"), first.posts())

        val again = next(first, snapshot(n("a", 1), n("b", 2, body = "x", data = """{"k":1}"""), badge = 3))
        assertTrue(again.post.isEmpty())
        assertTrue(again.cancel.isEmpty())
        assertEquals(first.memory, again.memory)
    }

    @Test
    fun onlyTheNotificationThatChangedIsReposted() {
        val first = planFor(snapshot(n("a", 1), n("b", 1), n("c", 1), n("d", 1), n("e", 1)), shown = emptySet())

        assertEquals(listOf("b"), next(first, snapshot(n("a", 1), n("b", 1, title = "new"), n("c", 1), n("d", 1), n("e", 1))).posts())
        assertEquals(listOf("c"), next(first, snapshot(n("a", 1), n("b", 1), n("c", 1, body = "now"), n("d", 1), n("e", 1))).posts())
        assertEquals(listOf("d"), next(first, snapshot(n("a", 1), n("b", 1), n("c", 1), n("d", 1, data = "{}"), n("e", 1))).posts())
        assertEquals(listOf("e"), next(first, snapshot(n("a", 1), n("b", 1), n("c", 1), n("d", 1), n("e", 1, silent = true))).posts())
        val replyOn = next(first, snapshot(n("a", 1, reply = true), n("b", 1), n("c", 1), n("d", 1), n("e", 1)))
        assertEquals(listOf("a"), replyOn.posts())
        assertTrue(replyOn.alerts().isEmpty())

        val grew = next(first, snapshot(n("a", 1), n("b", 2), n("c", 1), n("d", 1), n("e", 1)))
        assertEquals(listOf("b"), grew.posts())
        assertEquals(listOf("b"), grew.alerts())
    }

    @Test
    fun aChangeOfTitleAtTheSameRevisionIsAQuietUpdate() {
        val first = planFor(snapshot(n("a", 1)), shown = emptySet())
        val plan = next(first, snapshot(n("a", 1, title = "edited")))

        assertEquals(listOf("a"), plan.posts())
        assertTrue(plan.alerts().isEmpty())
    }

    @Test
    fun theBadgeRidesOnTheFirstAndMovesWithIt() {
        val first = planFor(snapshot(n("a", 1), n("b", 1), badge = 2), shown = emptySet())
        assertEquals(listOf(2, null), first.post.map { it.badge })

        assertEquals(listOf("a"), next(first, snapshot(n("a", 1), n("b", 1), badge = 5)).posts())

        val reordered = next(first, snapshot(n("b", 1), n("a", 1), badge = 2))
        assertEquals(listOf("b", "a"), reordered.posts())
        assertEquals(listOf(2, null), reordered.post.map { it.badge })
    }

    @Test
    fun anIdMissingFromTheShadeIsPostedAgainEvenIfUnchanged() {
        val first = planFor(snapshot(n("a", 1), n("b", 1)), shown = emptySet())
        val plan = reconcile(snapshot(n("a", 1), n("b", 1)), setOf("a"), first.memory)

        assertEquals(listOf("b"), plan.posts())
        assertTrue(plan.alerts().isEmpty())
    }
}
