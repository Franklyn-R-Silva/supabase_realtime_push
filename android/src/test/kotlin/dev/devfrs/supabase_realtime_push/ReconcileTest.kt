package dev.devfrs.supabase_realtime_push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ReconcileTest {
    private fun n(id: String, revision: Long, silent: Boolean = false) = SnapshotNotification(id, revision, "title $id", null, silent, null)

    private fun snapshot(vararg notifications: SnapshotNotification) = Snapshot(null, notifications.toList())

    private fun Plan.alerts() = post.filter { it.alert }.map { it.notification.id }

    private fun Plan.posts() = post.map { it.notification.id }

    @Test
    fun cancelsWhatLeftTheSnapshot() {
        val plan = reconcile(snapshot(n("a", 1)), shown = setOf("a", "b", "c"), alerted = mapOf("a" to 1), dismissed = emptyMap())

        assertEquals(setOf("b", "c"), plan.cancel)
        assertEquals(listOf("a"), plan.posts())
    }

    @Test
    fun anEmptySnapshotClearsTheShade() {
        val plan = reconcile(snapshot(), shown = setOf("a"), alerted = mapOf("a" to 1), dismissed = mapOf("a" to 1))

        assertEquals(setOf("a"), plan.cancel)
        assertTrue(plan.post.isEmpty())
        assertTrue(plan.alerted.isEmpty())
        assertTrue(plan.dismissed.isEmpty())
    }

    @Test
    fun alertsOnlyForANewIdOrAHigherRevision() {
        val plan = reconcile(
            snapshot(n("new", 1), n("same", 5), n("grew", 7), n("older", 2)),
            shown = setOf("same", "grew", "older"),
            alerted = mapOf("same" to 5, "grew" to 6, "older" to 3),
            dismissed = emptyMap(),
        )

        assertEquals(listOf("new", "same", "grew", "older"), plan.posts())
        assertEquals(listOf("new", "grew"), plan.alerts())
        assertTrue(plan.cancel.isEmpty())
    }

    @Test
    fun silentPostsWithoutAlertingAndStillRemembersTheRevision() {
        val plan = reconcile(snapshot(n("a", 3, silent = true)), shown = emptySet(), alerted = emptyMap(), dismissed = emptyMap())

        assertEquals(listOf("a"), plan.posts())
        assertTrue(plan.alerts().isEmpty())
        assertEquals(mapOf("a" to 3L), plan.alerted)

        val unsilenced = reconcile(snapshot(n("a", 3)), shown = setOf("a"), alerted = plan.alerted, dismissed = emptyMap())
        assertTrue(unsilenced.alerts().isEmpty())
    }

    @Test
    fun aRepostAfterARebootDoesNotAlertAgain() {
        val plan = reconcile(snapshot(n("a", 4)), shown = emptySet(), alerted = mapOf("a" to 4), dismissed = emptyMap())

        assertEquals(listOf("a"), plan.posts())
        assertTrue(plan.alerts().isEmpty())
    }

    @Test
    fun respectsTheDismissalUntilTheRevisionGrows() {
        val dismissed = mapOf("a" to 5L)

        val same = reconcile(snapshot(n("a", 5)), shown = emptySet(), alerted = mapOf("a" to 5), dismissed = dismissed)
        assertTrue(same.post.isEmpty())
        assertEquals(dismissed, same.dismissed)

        val grew = reconcile(snapshot(n("a", 6)), shown = emptySet(), alerted = mapOf("a" to 5), dismissed = dismissed)
        assertEquals(listOf("a"), grew.alerts())
        assertTrue(grew.dismissed.isEmpty())
    }

    @Test
    fun aDismissedNotificationStillShownIsCancelled() {
        val plan = reconcile(snapshot(n("a", 5)), shown = setOf("a"), alerted = mapOf("a" to 5), dismissed = mapOf("a" to 5))

        assertEquals(setOf("a"), plan.cancel)
        assertTrue(plan.post.isEmpty())
    }

    @Test
    fun prunesRevisionsToTheSnapshot() {
        val plan = reconcile(
            snapshot(n("a", 2), n("b", 9)),
            shown = emptySet(),
            alerted = mapOf("a" to 1, "gone" to 4),
            dismissed = mapOf("b" to 9, "gone" to 4),
        )

        assertEquals(mapOf("a" to 2L, "b" to 9L), plan.alerted)
        assertEquals(mapOf("b" to 9L), plan.dismissed)
    }

    @Test
    fun aRevisionThatWentBackKeepsTheHigherAlertedOne() {
        val plan = reconcile(snapshot(n("a", 3)), shown = setOf("a"), alerted = mapOf("a" to 8), dismissed = emptyMap())

        assertEquals(mapOf("a" to 8L), plan.alerted)
        assertTrue(plan.alerts().isEmpty())
    }
}
