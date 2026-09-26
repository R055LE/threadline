package dev.threadline.data.key

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import javax.crypto.Cipher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSshPasswordCipherTest {
    private lateinit var identityId: String
    private lateinit var cipher: AndroidKeystoreSshPasswordCipher

    @Before
    fun createCipher() {
        identityId = "test-${UUID.randomUUID()}"
        cipher = AndroidKeystoreSshPasswordCipher()
    }

    @After
    fun removeKey() = runBlocking {
        cipher.deleteKey(identityId)
    }

    @Test
    fun keystoreRejectsCipherThatHasNotBeenAuthorized() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        assumeTrue(
            supportsSavedSshPasswords(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
        )

        assertThrows(SavedSshPasswordProtectionException::class.java) {
            runBlocking {
                cipher.encrypt(identityId, "fixture-password".toCharArray()) { operation ->
                    operation
                }
            }
        }
        Unit
    }

    @Test
    fun deletingOneIdentityKeyDoesNotRemoveAnotherAndMissingKeysFailClosed() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        assumeTrue(
            supportsSavedSshPasswords(
                InstrumentationRegistry.getInstrumentation().targetContext,
            ),
        )
        val otherIdentity = "other-${UUID.randomUUID()}"
        try {
            listOf(identityId, otherIdentity).forEach { id ->
                assertThrows(SavedSshPasswordProtectionException::class.java) {
                    runBlocking {
                        cipher.encrypt(id, "fixture-password".toCharArray()) { operation ->
                            operation
                        }
                    }
                }
            }

            cipher.deleteKey(identityId)
            val encrypted = EncryptedSshPassword(
                ciphertext = ByteArray(16),
                initializationVector = ByteArray(12),
            )
            assertThrows(SavedSshPasswordUnavailableException::class.java) {
                runBlocking { cipher.decrypt(identityId, encrypted) { it } }
            }
            assertThrows(SavedSshPasswordProtectionException::class.java) {
                runBlocking { cipher.decrypt(otherIdentity, encrypted) { it } }
            }
        } finally {
            cipher.deleteKey(otherIdentity)
        }
        Unit
    }
}
