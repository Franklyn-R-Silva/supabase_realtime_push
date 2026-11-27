package dev.devfrs.supabase_realtime_push

internal data class Post(val notification: SnapshotNotification, val alert: Boolean)

/** What one snapshot does to the shade, and the revisions to remember afterwards. */
internal data class Plan(
    val cancel: Set<String>,
    val post: List<Post>,
    val alerted: Map<String, Long>,
    val dismissed: Map<String, Long>,
)

/**
 * Contract §4 as a pure function.
 *
 * @param shown ids this package has in the shade now.
 * @param alerted id → last revision already alerted (or shown silently).
 * @param dismissed id → revision the user swiped away or tapped.
 */
internal fun reconcile(
    snapshot: Snapshot,
    shown: Set<String>,
    alerted: Map<String, Long>,
    dismissed: Map<String, Long>,
): Plan {
    val post = snapshot.notifications
        .filter { n -> dismissed[n.id]?.let { n.revision > it } ?: true }
        .map { n -> Post(n, alert = !n.silent && (alerted[n.id]?.let { n.revision > it } ?: true)) }
    val posting = post.map { it.notification.id }.toSet()
    val listed = snapshot.notifications.associateBy { it.id }
    return Plan(
        cancel = shown - posting,
        post = post,
        // Every listed id remembers its revision, alerted or not: un-silencing or
        // reposting after a reboot must not ring for something already seen.
        alerted = listed.mapValues { (id, n) -> maxOf(n.revision, alerted[id] ?: Long.MIN_VALUE) },
        // A dismissal is kept only while it still hides something.
        dismissed = dismissed.filter { (id, r) -> listed[id]?.let { it.revision <= r } ?: false },
    )
}
