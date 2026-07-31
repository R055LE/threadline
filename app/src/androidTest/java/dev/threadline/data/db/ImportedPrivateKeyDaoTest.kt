package dev.threadline.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportedPrivateKeyDaoTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: ThreadlineDatabase

    @Before
    fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            context,
            ThreadlineDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun renameChangesOnlyLabelAndDeleteRemovesOnlyTargetRecord() = runBlocking {
        val dao = database.importedPrivateKeys()
        val first = entity("first", "First", byteArrayOf(1, 2, 3))
        val second = entity("second", "Second", byteArrayOf(4, 5, 6))
        dao.insert(first)
        dao.insert(second)

        assertEquals(1, dao.rename(first.id, "Renamed"))
        val renamed = requireNotNull(dao.find(first.id))
        assertEquals("Renamed", renamed.displayName)
        assertArrayEquals(first.ciphertext, renamed.ciphertext)
        assertArrayEquals(first.initializationVector, renamed.initializationVector)

        assertEquals(1, dao.delete(first.id))
        assertNull(dao.find(first.id))
        val untouched = requireNotNull(dao.find(second.id))
        assertEquals(second.displayName, untouched.displayName)
        assertArrayEquals(second.ciphertext, untouched.ciphertext)
    }

    private fun entity(
        id: String,
        displayName: String,
        ciphertext: ByteArray,
    ) = ImportedPrivateKeyEntity(
        id = id,
        displayName = displayName,
        format = "OpenSSH",
        keyType = "ssh-ed25519",
        publicKeyFingerprint = "SHA256:$id",
        ciphertext = ciphertext,
        initializationVector = ByteArray(12) { id.length.toByte() },
        createdAtMillis = id.length.toLong(),
        cryptoVersion = 1,
    )
}
