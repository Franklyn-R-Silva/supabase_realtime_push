package dev.devfrs.supabase_realtime_push

import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
internal class StoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val store = Store(context)

    @Test
    fun keepsTheConfigWithItsChannelsUntilCleared() {
        val config = Config("https://x.supabase.co", "key", "snap", "Messages", "Connected", "Waiting", "ic_push", mapOf("offers" to "Offers"))

        store.config = config
        assertEquals(config, Store(context).config)
        store.config = null
        assertNull(store.config)
    }

    @Test
    fun aRunningStatusWithoutTheDevicePairReadsStopped() {
        // What Auto Backup restores on a new phone: the prefs, never the pair.
        store.status = Store.RUNNING
        assertEquals(Store.STOPPED, store.status)

        store.credentialsOrCreate()
        assertEquals(Store.RUNNING, store.status)
    }

    @Test
    fun forgettingTheDeviceMakesTheNextPairNew() {
        val first = store.credentialsOrCreate()
        assertEquals(first, store.credentialsOrCreate())

        store.forgetDevice()
        assertNull(store.credentials())
        assertNotEquals(first, store.credentialsOrCreate())
    }

    @Test
    fun keepsTheMemoryAndReadsACorruptOneAsEmpty() {
        val memory = Memory(alerted = mapOf("a" to 3L), dismissed = mapOf("b" to 2L), posted = mapOf("a" to "[3]"))
        store.saveMemory(memory)
        assertEquals(memory, Store(context).memory())

        context.getSharedPreferences("dev.devfrs.supabase_realtime_push", Context.MODE_PRIVATE)
            .edit().putString("alerted", "{not json").commit()
        assertEquals(memory.copy(alerted = emptyMap()), store.memory())
    }
}
