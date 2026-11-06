package app.receiptscanner.drive

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class StableFileIdTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val idFile: File get() = File(folder.root, "drive-file-id")

    @Test
    fun `retries reuse the id from the first attempt`() = runBlocking {
        var generated = 0
        val first = stableFileId(idFile) { "id-${++generated}" }
        val second = stableFileId(idFile) { "id-${++generated}" }

        assertEquals("id-1", first)
        assertEquals("id-1", second)
        assertEquals(1, generated)
    }

    @Test
    fun `an empty id file gets a new id`() = runBlocking {
        idFile.writeText("")

        assertEquals("fresh", stableFileId(idFile) { "fresh" })
        assertEquals("fresh", idFile.readText())
    }

    @Test
    fun `a failed id request saves nothing`() {
        assertThrows(IOException::class.java) {
            runBlocking { stableFileId(idFile) { throw IOException("offline") } }
        }
        assertFalse(idFile.exists())
    }
}
