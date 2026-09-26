package dev.threadline.data.key

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class EncryptedSshPassword(
    val ciphertext: ByteArray,
    val initializationVector: ByteArray,
)

internal interface SshPasswordCipher {
    suspend fun encrypt(
        identityId: String,
        password: CharArray,
        authorize: suspend (Cipher) -> Cipher,
    ): EncryptedSshPassword

    suspend fun decrypt(
        identityId: String,
        encrypted: EncryptedSshPassword,
        authorize: suspend (Cipher) -> Cipher,
    ): CharArray

    suspend fun deleteKey(identityId: String)
}

internal class AndroidKeystoreSshPasswordCipher(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SshPasswordCipher {
    override suspend fun encrypt(
        identityId: String,
        password: CharArray,
        authorize: suspend (Cipher) -> Cipher,
    ): EncryptedSshPassword {
        require(identityId.isNotBlank())
        require(password.isNotEmpty())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw SavedSshPasswordUnavailableException()
        }
        val aad = associatedData(identityId)
        try {
            val cipher = withContext(ioDispatcher) {
                newEncryptionCipher(identityId, recoverInvalidatedKey = true).apply {
                    updateAAD(aad)
                }
            }
            val authorizedCipher = authorize(cipher)
            val plaintext = encode(password)
            return try {
                withContext(ioDispatcher) {
                    EncryptedSshPassword(
                        ciphertext = authorizedCipher.doFinal(plaintext),
                        initializationVector = authorizedCipher.iv.copyOf(),
                    )
                }
            } finally {
                plaintext.fill(0)
            }
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            throw SavedSshPasswordKeyInvalidatedException(invalidated)
        } catch (failure: InvalidKeyException) {
            throw SavedSshPasswordProtectionException(failure)
        } catch (failure: Exception) {
            if (failure is SavedSshPasswordException) throw failure
            throw SavedSshPasswordProtectionException(failure)
        } finally {
            aad.fill(0)
        }
    }

    override suspend fun decrypt(
        identityId: String,
        encrypted: EncryptedSshPassword,
        authorize: suspend (Cipher) -> Cipher,
    ): CharArray {
        require(identityId.isNotBlank())
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            throw SavedSshPasswordUnavailableException()
        }
        val aad = associatedData(identityId)
        try {
            val cipher = withContext(ioDispatcher) {
                require(encrypted.initializationVector.size == GCM_IV_BYTES)
                require(encrypted.ciphertext.size >= GCM_TAG_BYTES)
                val key = findKey(keyAlias(identityId))
                    ?: throw SavedSshPasswordUnavailableException()
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        key,
                        GCMParameterSpec(GCM_TAG_BITS, encrypted.initializationVector),
                    )
                    updateAAD(aad)
                }
            }
            val authorizedCipher = authorize(cipher)
            val plaintext = withContext(ioDispatcher) {
                authorizedCipher.doFinal(encrypted.ciphertext)
            }
            return try {
                decode(plaintext)
            } finally {
                plaintext.fill(0)
            }
        } catch (unavailable: SavedSshPasswordUnavailableException) {
            throw unavailable
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            throw SavedSshPasswordKeyInvalidatedException(invalidated)
        } catch (failure: InvalidKeyException) {
            throw SavedSshPasswordProtectionException(failure)
        } catch (failure: Exception) {
            if (failure is SavedSshPasswordException) throw failure
            throw SavedSshPasswordProtectionException(failure)
        } finally {
            aad.fill(0)
        }
    }

    override suspend fun deleteKey(identityId: String) = withContext(ioDispatcher) {
        require(identityId.isNotBlank())
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(keyAlias(identityId))
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun newEncryptionCipher(
        identityId: String,
        recoverInvalidatedKey: Boolean,
    ): Cipher {
        val alias = keyAlias(identityId)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        } catch (invalidated: KeyPermanentlyInvalidatedException) {
            if (!recoverInvalidatedKey) throw invalidated
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore.deleteEntry(alias)
            cipher.init(Cipher.ENCRYPT_MODE, createKey(alias))
        }
        return cipher
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun getOrCreateKey(alias: String): SecretKey =
        findKey(alias) ?: createKey(alias)

    private fun findKey(alias: String): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun createKey(alias: String): SecretKey = KeyGenerator.getInstance(
        KeyProperties.KEY_ALGORITHM_AES,
        ANDROID_KEYSTORE,
    ).run {
        init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or
                        KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
                .build(),
        )
        generateKey()
    }

    private fun keyAlias(identityId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identityId.encodeToByteArray())
        return try {
            ALIAS_PREFIX + digest.joinToString("") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        } finally {
            digest.fill(0)
        }
    }

    private fun associatedData(identityId: String): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF("Threadline saved SSH password")
                output.writeInt(CRYPTO_VERSION)
                output.writeUTF(identityId)
            }
            bytes.toByteArray()
        }

    private fun encode(password: CharArray): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
        return try {
            ByteArray(encoded.remaining()).also(encoded::get)
        } finally {
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    private fun decode(plaintext: ByteArray): CharArray {
        val decoded = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext))
        return try {
            CharArray(decoded.remaining()).also(decoded::get)
        } finally {
            if (decoded.hasArray()) decoded.array().fill('\u0000')
        }
    }

    companion object {
        internal const val CRYPTO_VERSION = 1
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val ALIAS_PREFIX = "threadline.saved-ssh-password.v1."
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val GCM_IV_BYTES = 12
    }
}

internal sealed class SavedSshPasswordException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal class SavedSshPasswordUnavailableException : SavedSshPasswordException(
    "The saved password is unavailable on this device. Enter it again or replace it.",
)

internal class SavedSshPasswordKeyInvalidatedException(
    cause: Throwable,
) : SavedSshPasswordException(
    "Device security changed. Enter the password again to replace the saved copy.",
    cause,
)

internal class SavedSshPasswordProtectionException(
    cause: Throwable,
) : SavedSshPasswordException(
    "The saved password could not be protected by this device.",
    cause,
)
