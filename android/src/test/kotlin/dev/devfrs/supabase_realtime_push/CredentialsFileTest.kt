package dev.devfrs.supabase_realtime_push

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

internal class CredentialsFileTest {
    private val dir = createTempDirectory("srp").toFile()
    private val file = File(dir, "device")
    private val pair = Credentials("A".repeat(42) + "_", "b-".repeat(21) + "c")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun writesAndReadsThePair() {
        CredentialsFile(file).write(pair)

        assertEquals(pair, CredentialsFile(file).read())
        assertFalse(File(dir, "device.tmp").exists())
    }

    @Test
    fun aMissingFileIsNoDevice() {
        assertNull(CredentialsFile(file).read())
    }

    @Test
    fun aDamagedFileIsNoDevice() {
        file.writeText("too-short\n")
        assertNull(CredentialsFile(file).read())

        file.writeText("${pair.topic}\n")
        assertNull(CredentialsFile(file).read())

        file.writeText("${pair.topic}\n${pair.secret}=\n")
        assertNull(CredentialsFile(file).read())
    }

    @Test
    fun aNewWriteReplacesTheOldPair() {
        CredentialsFile(file).write(pair)
        val other = Credentials("Z".repeat(43), "9".repeat(43))
        CredentialsFile(file).write(other)

        assertEquals(other, CredentialsFile(file).read())
    }

    @Test
    fun deleteForgetsTheDevice() {
        CredentialsFile(file).write(pair)
        CredentialsFile(file).delete()

        assertNull(CredentialsFile(file).read())
    }
}
