package dev.threadline.data.key

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.threadline.data.db.HostProfileEntity
import dev.threadline.data.db.KnownHostEntity
import dev.threadline.data.db.SshIdentityEntity
import dev.threadline.data.db.ThreadlineDatabase
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedSshPasswordStoreTest {
    private lateinit var database: ThreadlineDatabase
    private lateinit var cipher: TestSshPasswordCipher
    private lateinit var store: EncryptedSshPasswordStore

    @Before
    fun createStore() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            ThreadlineDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            database.sshIdentities().insert(
                SshIdentityEntity(
                    id = IDENTITY_ID,
                    label = "Fixture",
                    username = "operator",
                    authenticationMethod = "PASSWORD",
                    importedPrivateKeyId = null,
                    createdAtMillis = 1,
                    updatedAtMillis = 1,
                ),
            )
        }
        cipher = TestSshPasswordCipher()
        store = EncryptedSshPasswordStore(
            dao = database.savedSshPasswords(),
            cipher = cipher,
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun saveReplaceAndDeleteKeepOnlyCiphertextAndLeaveIdentity() = runBlocking {
        database.hostProfiles().insert(
            HostProfileEntity(
                id = "profile-id",
                displayName = "Fixture",
                hostname = "example.test",
                port = 22,
                username = "operator",
                preferredIdentityId = IDENTITY_ID,
                createdAtMillis = 1,
                updatedAtMillis = 1,
            ),
        )
        database.knownHosts().insert(
            KnownHostEntity(
                endpointKey = "example.test:22",
                hostname = "example.test",
                port = 22,
                algorithm = "ssh-ed25519",
                encodedKey = byteArrayOf(1, 2, 3),
                firstSeenAtMillis = 1,
                lastSeenAtMillis = 1,
            ),
        )
        val first = "old-password".toCharArray()
        store.save(IDENTITY_ID, first) { it }
        assertArrayEquals(CharArray(first.size), first)
        val stored = requireNotNull(database.savedSshPasswords().find(IDENTITY_ID))
        assertFalse(stored.ciphertext.contentEquals("old-password".encodeToByteArray()))

        val replacement = "new-password".toCharArray()
        store.save(IDENTITY_ID, replacement) { it }
        assertArrayEquals(CharArray(replacement.size), replacement)
        val credential = store.credential(IDENTITY_ID) { it }
        assertArrayEquals("new-password".toCharArray(), credential.characters)
        credential.clear()
        assertArrayEquals(CharArray("new-password".length), credential.characters)

        store.delete(IDENTITY_ID)

        assertNull(database.savedSshPasswords().find(IDENTITY_ID))
        assertEquals(IDENTITY_ID, database.sshIdentities().find(IDENTITY_ID)?.id)
        assertEquals("profile-id", database.hostProfiles().find("profile-id")?.id)
        assertEquals("example.test", database.knownHosts().find("example.test:22")?.hostname)
        assertEquals(listOf(IDENTITY_ID), cipher.deletedKeys)
    }

    @Test
    fun cancelledApprovalDoesNotPersistAndClearsInput() = runBlocking {
        val input = "fixture-password".toCharArray()

        assertThrows(SavedSshPasswordAuthenticationCancelledException::class.java) {
            runBlocking {
                store.save(IDENTITY_ID, input) {
                    throw SavedSshPasswordAuthenticationCancelledException()
                }
            }
        }

        assertArrayEquals(CharArray(input.size), input)
        assertNull(database.savedSshPasswords().find(IDENTITY_ID))
    }

    private class TestSshPasswordCipher : SshPasswordCipher {
        val deletedKeys = mutableListOf<String>()

        override suspend fun encrypt(
            identityId: String,
            password: CharArray,
            authorize: suspend (Cipher) -> Cipher,
        ): EncryptedSshPassword {
            authorize(Cipher.getInstance("AES/GCM/NoPadding"))
            val bytes = ByteArray(password.size) { password[it].code.toByte() }
            val ciphertext = bytes.map { (it.toInt() xor MASK).toByte() }.toByteArray()
            bytes.fill(0)
            return EncryptedSshPassword(
                ciphertext = ciphertext,
                initializationVector = ByteArray(12) { 7 },
            )
        }

        override suspend fun decrypt(
            identityId: String,
            encrypted: EncryptedSshPassword,
            authorize: suspend (Cipher) -> Cipher,
        ): CharArray {
            authorize(Cipher.getInstance("AES/GCM/NoPadding"))
            return CharArray(encrypted.ciphertext.size) { index ->
                (encrypted.ciphertext[index].toInt() xor MASK).toChar()
            }
        }

        override suspend fun deleteKey(identityId: String) {
            deletedKeys += identityId
        }

        private companion object {
            const val MASK = 0x5a
        }
    }

    private companion object {
        const val IDENTITY_ID = "identity-id"
    }
}
