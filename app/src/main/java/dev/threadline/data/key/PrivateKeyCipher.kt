package dev.threadline.data.key

internal data class EncryptedPrivateKey(
    val ciphertext: ByteArray,
    val initializationVector: ByteArray,
)

internal interface PrivateKeyCipher {
    fun encrypt(
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): EncryptedPrivateKey

    fun decrypt(
        encrypted: EncryptedPrivateKey,
        associatedData: ByteArray,
    ): ByteArray
}

internal open class PrivateKeyProtectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

internal class PrivateKeyProtectionKeyMissingException : PrivateKeyProtectionException(
    "The device encryption key is unavailable. Re-import this private key.",
)
