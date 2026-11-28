package dev.devfrs.supabase_realtime_push

import org.json.JSONArray

internal data class Post(val notification: SnapshotNotification, val alert: Boolean, val badge: Int?)

/** What one snapshot does to the shade, and what to remember afterwards. */
internal data class Plan(val cancel: Set<String>, val post: List<Post>, val memory: Memory)

/** Everything a posted notification shows or carries; equal signatures need no repost. */
internal fun signature(post: Post): String = post.notification.run {
    JSONArray(listOf(revision, title, body, silent, data, post.badge, reply)).toString()
}

/**
 * Contract §4 as a pure function.
 *
 * Only what is new, changed or missing from the shade is posted: Android drops updates past
 * 5 per second per app, so reposting every notification on every snapshot lost changes.
 *
 * @param shown ids this package has in the shade now.
 */
internal fun reconcile(snapshot: Snapshot, shown: Set<String>, memory: Memory): Plan {
    val wanted = snapshot.notifications
        .filter { n -> memory.dismissed[n.id]?.let { n.revision > it } ?: true }
        .mapIndexed { i, n ->
            Post(
                notification = n,
                alert = !n.silent && (memory.alerted[n.id]?.let { n.revision > it } ?: true),
                // Launchers that show a number add up every notification's, so only one carries the badge.
                badge = if (i == 0) snapshot.badge else null,
            )
        }
    val signatures = wanted.associate { it.notification.id to signature(it) }
    val listed = snapshot.notifications.associateBy { it.id }
    return Plan(
        cancel = shown - signatures.keys,
        post = wanted.filter { it.alert || it.notification.id !in shown || memory.posted[it.notification.id] != signatures[it.notification.id] },
        memory = Memory(
            // Every listed id remembers its revision, alerted or not: un-silencing or
            // reposting after a reboot must not ring for something already seen.
            alerted = listed.mapValues { (id, n) -> maxOf(n.revision, memory.alerted[id] ?: Long.MIN_VALUE) },
            // A dismissal is kept only while it still hides something.
            dismissed = memory.dismissed.filter { (id, r) -> listed[id]?.let { it.revision <= r } ?: false },
            posted = signatures,
        ),
    )
}
