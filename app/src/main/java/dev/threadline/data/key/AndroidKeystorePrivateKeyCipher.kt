package dev.threadline.data.key

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AndroidKeystorePrivateKeyCipher(
    private val alias: String = DEFAULT_ALIAS,
) : PrivateKeyCipher {
    private val keyLock = Any()

    override fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): EncryptedPrivateKey = protect {
        require(plaintext.isNotEmpty())
        require(associatedData.isNotEmpty())
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(associatedData)
        EncryptedPrivateKey(
            ciphertext = cipher.doFinal(plaintext),
            initializationVector = cipher.iv.copyOf(),
        )
    }

    override fun decrypt(
        encrypted: EncryptedPrivateKey,
        associatedData: ByteArray,
    ): ByteArray = protect {
        require(encrypted.initializationVector.size == GCM_IV_BYTES)
        require(encrypted.ciphertext.size >= GCM_TAG_BYTES)
        require(associatedData.isNotEmpty())
        val key = findKey() ?: throw PrivateKeyProtectionKeyMissingException()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_BITS, encrypted.initializationVector),
        )
        cipher.updateAAD(associatedData)
        cipher.doFinal(encrypted.ciphertext)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(keyLock) {
        findKey() ?: KeyGenerator.getInstance(
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
                    .build(),
            )
            generateKey()
        }
    }

    private fun findKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(alias, null) as? SecretKey
    }

    private inline fun <T> protect(block: () -> T): T = try {
        block()
    } catch (missing: PrivateKeyProtectionKeyMissingException) {
        throw missing
    } catch (failure: Exception) {
        throw PrivateKeyProtectionException(
            "The private key could not be protected by this device.",
            failure,
        )
    }

    companion object {
        internal const val DEFAULT_ALIAS = "threadline.imported-private-keys.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_BITS = 256
        private const val GCM_TAG_BITS = 128
        private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        private const val GCM_IV_BYTES = 12
    }
}
