package dev.devfrs.supabase_realtime_push

/**
 * At most one snapshot fetch running and one waiting. Every signal or join during a fetch
 * only marks the waiting one, so a batch insert that fires 500 broadcasts costs two reads,
 * not 500.
 */
internal class FetchFunnel {
    private var running = false
    private var queued = false

    /** A signal or a join. `true`: start a fetch now. */
    @Synchronized
    fun request(): Boolean {
        if (running) {
            queued = true
            return false
        }
        running = true
        return true
    }

    /** A fetch ended. `true`: run one more now. */
    @Synchronized
    fun done(): Boolean {
        if (queued) {
            queued = false
            return true
        }
        running = false
        return false
    }
}
