package dev.threadline.data.key

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystorePrivateKeyCipherTest {
    private lateinit var alias: String
    private lateinit var cipher: AndroidKeystorePrivateKeyCipher

    @Before
    fun createCipher() {
        alias = "threadline.test.${UUID.randomUUID()}"
        deleteAlias()
        cipher = AndroidKeystorePrivateKeyCipher(alias)
    }

    @After
    fun removeKey() {
        deleteAlias()
    }

    @Test
    fun encryptsWithRandomIvAndAuthenticatesCiphertextAndMetadata() {
        val plaintext = "private fixture bytes".encodeToByteArray()
        val aad = "record-1 metadata".encodeToByteArray()

        val first = cipher.encrypt(plaintext, aad)
        val second = cipher.encrypt(plaintext, aad)

        assertFalse(first.ciphertext.contentEquals(plaintext))
        assertNotEquals(first.initializationVector.toList(), second.initializationVector.toList())
        assertNotEquals(first.ciphertext.toList(), second.ciphertext.toList())
        assertArrayEquals(plaintext, cipher.decrypt(first, aad))

        assertThrows(PrivateKeyProtectionException::class.java) {
            cipher.decrypt(first, "record-2 metadata".encodeToByteArray())
        }
        val tampered = first.ciphertext.copyOf().also { bytes ->
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        }
        assertThrows(PrivateKeyProtectionException::class.java) {
            cipher.decrypt(first.copy(ciphertext = tampered), aad)
        }
    }

    @Test
    fun missingDeviceKeyFailsClosed() {
        val aad = "metadata".encodeToByteArray()
        val encrypted = cipher.encrypt("secret".encodeToByteArray(), aad)
        deleteAlias()

        assertThrows(PrivateKeyProtectionKeyMissingException::class.java) {
            cipher.decrypt(encrypted, aad)
        }
    }

    private fun deleteAlias() {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
            deleteEntry(alias)
        }
    }
}
