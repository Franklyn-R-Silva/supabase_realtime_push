package dev.devfrs.supabase_realtime_push

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class FetchFunnelTest {
    /** Runs the funnel the way the service does and counts the fetches. */
    private class Harness {
        val funnel = FetchFunnel()
        var fetches = 0
        var fetching = false

        fun signal() {
            if (funnel.request()) {
                fetches++
                fetching = true
            }
        }

        fun finishFetch() {
            if (funnel.done()) fetches++ else fetching = false
        }
    }

    @Test
    fun threeSignalsDuringAFetchMakeExactlyOneMore() {
        val h = Harness()
        h.signal()
        repeat(3) { h.signal() }
        assertEquals(1, h.fetches)

        h.finishFetch()
        assertEquals(2, h.fetches)
        h.finishFetch()
        assertEquals(2, h.fetches)
        assertFalse(h.fetching)
    }

    @Test
    fun aSignalAfterEverythingEndedMakesOneFetch() {
        val h = Harness()
        h.signal()
        h.finishFetch()
        assertFalse(h.fetching)

        h.signal()
        assertEquals(2, h.fetches)
        assertTrue(h.fetching)
        h.finishFetch()
        assertEquals(2, h.fetches)
    }

    @Test
    fun aBurstOf500SignalsIsTwoFetches() {
        val h = Harness()
        repeat(500) { h.signal() }
        while (h.fetching) h.finishFetch()
        assertEquals(2, h.fetches)
    }

    @Test
    fun aSignalDuringTheQueuedFetchQueuesAnotherOne() {
        val h = Harness()
        h.signal()
        h.signal()
        h.finishFetch()
        h.signal()
        h.finishFetch()
        h.finishFetch()
        assertEquals(3, h.fetches)
        assertFalse(h.fetching)
    }
}
