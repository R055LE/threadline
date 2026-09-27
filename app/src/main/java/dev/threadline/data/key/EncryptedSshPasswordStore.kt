package dev.threadline.data.key

import dev.threadline.core.model.SessionCredential
import dev.threadline.data.db.SavedSshPasswordDao
import dev.threadline.data.db.SavedSshPasswordEntity
import java.util.concurrent.CancellationException
import javax.crypto.Cipher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class EncryptedSshPasswordStore(
    private val dao: SavedSshPasswordDao,
    private val cipher: SshPasswordCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun save(
        identityId: String,
        password: CharArray,
        authorize: suspend (Cipher) -> Cipher,
    ) {
        try {
            require(identityId.isNotBlank())
            require(password.isNotEmpty()) { "Enter a password to save." }
            val encrypted = cipher.encrypt(identityId, password, authorize)
            try {
                protectSavedPasswordStorage("The SSH password could not be saved securely.") {
                    dao.upsert(
                        SavedSshPasswordEntity(
                            identityId = identityId,
                            ciphertext = encrypted.ciphertext,
                            initializationVector = encrypted.initializationVector,
                            cryptoVersion = AndroidKeystoreSshPasswordCipher.CRYPTO_VERSION,
                        ),
                    )
                }
            } finally {
                encrypted.ciphertext.fill(0)
                encrypted.initializationVector.fill(0)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun credential(
        identityId: String,
        authorize: suspend (Cipher) -> Cipher,
    ): SessionCredential.Password {
        require(identityId.isNotBlank())
        val stored = protectSavedPasswordStorage("The saved SSH password could not be read.") {
            withContext(ioDispatcher) { dao.find(identityId) }
        } ?: throw SavedSshPasswordUnavailableException()
        require(stored.cryptoVersion == AndroidKeystoreSshPasswordCipher.CRYPTO_VERSION) {
            "This saved SSH password uses an unsupported encryption version."
        }
        val plaintext = cipher.decrypt(
            identityId = identityId,
            encrypted = EncryptedSshPassword(
                ciphertext = stored.ciphertext,
                initializationVector = stored.initializationVector,
            ),
            authorize = authorize,
        )
        return try {
            SessionCredential.Password.from(plaintext)
        } finally {
            plaintext.fill('\u0000')
        }
    }

    suspend fun delete(identityId: String) {
        require(identityId.isNotBlank())
        // If Room cleanup fails, the ciphertext is left without a usable key.
        protectSavedPasswordStorage("The saved SSH password key could not be removed.") {
            cipher.deleteKey(identityId)
        }
        protectSavedPasswordStorage("The saved SSH password could not be removed.") {
            withContext(ioDispatcher) { dao.delete(identityId) }
        }
    }
}

internal class SavedSshPasswordStorageException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

private suspend inline fun <T> protectSavedPasswordStorage(
    message: String,
    crossinline operation: suspend () -> T,
): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: SavedSshPasswordException) {
    throw failure
} catch (failure: Exception) {
    throw SavedSshPasswordStorageException(message, failure)
}
