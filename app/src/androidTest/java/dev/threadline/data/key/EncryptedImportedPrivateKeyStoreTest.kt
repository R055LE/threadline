package dev.threadline.data.key

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.threadline.data.db.ImportedPrivateKeyDao
import dev.threadline.data.db.ImportedPrivateKeyEntity
import dev.threadline.data.db.ImportedPrivateKeyMetadataRow
import java.security.KeyPairGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.connectbot.sshlib.SshKeys
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EncryptedImportedPrivateKeyStoreTest {
    @Test
    fun savesOnlyEncryptedBytesAndLoadsSelfClearingCredential() = runBlocking {
        val dao = FakeImportedPrivateKeyDao()
        val cipher = RecordingPrivateKeyCipher()
        val store = EncryptedImportedPrivateKeyStore(
            dao = dao,
            cipher = cipher,
            ioDispatcher = Dispatchers.Unconfined,
            currentTimeMillis = { 123L },
            newId = { "key-id" },
        )
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair()
        val keyBytes = SshKeys.encodePemPrivateKey(keyPair, null).encodeToByteArray()
        val original = keyBytes.copyOf()

        val metadata = store.save(" Fixture key ", keyBytes, null)

        assertArrayEquals(original, keyBytes)
        assertEquals("key-id", metadata.id)
        assertEquals("Fixture key", metadata.displayName)
        assertEquals("ssh-rsa", metadata.keyType)
        assertTrue(metadata.publicKeyFingerprint.startsWith("SHA256:"))
        assertEquals(123L, metadata.createdAtMillis)
        val stored = requireNotNull(dao.entity)
        assertFalse(stored.ciphertext.contentEquals(keyBytes))
        assertFalse(stored.ciphertext.decodeToString().contains("PRIVATE KEY"))

        val credential = store.credential(metadata.id, null)
        assertArrayEquals(original, credential.keyBytes)
        assertArrayEquals(cipher.encryptionAad, cipher.decryptionAad)

        credential.clear()
        assertTrue(credential.keyBytes.all { it == 0.toByte() })
        keyBytes.fill(0)
        original.fill(0)
    }

    private class FakeImportedPrivateKeyDao : ImportedPrivateKeyDao {
        private val entities = MutableStateFlow<List<ImportedPrivateKeyMetadataRow>>(emptyList())
        var entity: ImportedPrivateKeyEntity? = null

        override fun observeAll(): Flow<List<ImportedPrivateKeyMetadataRow>> = entities

        override suspend fun find(id: String): ImportedPrivateKeyEntity? =
            entity?.takeIf { it.id == id }

        override suspend fun insert(entity: ImportedPrivateKeyEntity) {
            check(this.entity == null)
            this.entity = entity
            entities.value = listOf(
                ImportedPrivateKeyMetadataRow(
                    id = entity.id,
                    displayName = entity.displayName,
                    format = entity.format,
                    keyType = entity.keyType,
                    publicKeyFingerprint = entity.publicKeyFingerprint,
                    createdAtMillis = entity.createdAtMillis,
                ),
            )
        }
    }

    private class RecordingPrivateKeyCipher : PrivateKeyCipher {
        lateinit var encryptionAad: ByteArray
        lateinit var decryptionAad: ByteArray

        override fun encrypt(
            plaintext: ByteArray,
            associatedData: ByteArray,
        ): EncryptedPrivateKey {
            encryptionAad = associatedData.copyOf()
            return EncryptedPrivateKey(
                ciphertext = plaintext.reversedArray(),
                initializationVector = ByteArray(12) { 7 },
            )
        }

        override fun decrypt(
            encrypted: EncryptedPrivateKey,
            associatedData: ByteArray,
        ): ByteArray {
            decryptionAad = associatedData.copyOf()
            check(encryptionAad.contentEquals(associatedData))
            return encrypted.ciphertext.reversedArray()
        }
    }
}
