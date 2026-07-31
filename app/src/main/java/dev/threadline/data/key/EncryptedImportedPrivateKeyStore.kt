package dev.threadline.data.key

import android.os.Build
import dev.threadline.core.model.SessionCredential
import dev.threadline.data.db.ImportedPrivateKeyDao
import dev.threadline.data.db.ImportedPrivateKeyEntity
import dev.threadline.data.db.ImportedPrivateKeyMetadataRow
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID
import javax.security.auth.Destroyable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.connectbot.sshlib.KeyFingerprint
import org.connectbot.sshlib.SshKeys

internal data class ImportedPrivateKeyMetadata(
    val id: String,
    val displayName: String,
    val format: String,
    val keyType: String,
    val publicKeyFingerprint: String,
    val createdAtMillis: Long,
)

internal class EncryptedImportedPrivateKeyStore(
    private val dao: ImportedPrivateKeyDao,
    private val cipher: PrivateKeyCipher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val keys: Flow<List<ImportedPrivateKeyMetadata>> = dao.observeAll().map { entities ->
        entities.map(ImportedPrivateKeyMetadataRow::toMetadata)
    }

    suspend fun save(
        displayName: String,
        keyBytes: ByteArray,
        passphrase: CharArray?,
    ): ImportedPrivateKeyMetadata = withContext(ioDispatcher) {
        val normalizedDisplayName = normalizeDisplayName(displayName)
        require(keyBytes.isNotEmpty()) { "The selected private key is empty." }

        val inspected = inspectPrivateKey(keyBytes, passphrase)
        val id = newId()
        val createdAtMillis = currentTimeMillis()
        val aad = associatedData(
            id = id,
            format = inspected.format,
            publicKeyFingerprint = inspected.publicKeyFingerprint,
            cryptoVersion = CRYPTO_VERSION,
        )
        val encrypted = try {
            cipher.encrypt(keyBytes, aad)
        } finally {
            aad.fill(0)
        }
        val entity = ImportedPrivateKeyEntity(
            id = id,
            displayName = normalizedDisplayName,
            format = inspected.format,
            keyType = inspected.keyType,
            publicKeyFingerprint = inspected.publicKeyFingerprint,
            ciphertext = encrypted.ciphertext,
            initializationVector = encrypted.initializationVector,
            createdAtMillis = createdAtMillis,
            cryptoVersion = CRYPTO_VERSION,
        )
        protectStorage("The private key could not be saved securely.") {
            dao.insert(entity)
        }
        entity.toMetadata()
    }

    suspend fun credential(
        id: String,
        passphrase: CharArray?,
    ): SessionCredential.PrivateKey = withContext(ioDispatcher) {
        val entity = protectStorage("The saved private key could not be read securely.") {
            dao.find(id)
        }
            ?: throw ImportedPrivateKeyUnavailableException()
        require(entity.cryptoVersion == CRYPTO_VERSION) {
            "This saved private key uses an unsupported encryption version."
        }
        val aad = associatedData(
            id = entity.id,
            format = entity.format,
            publicKeyFingerprint = entity.publicKeyFingerprint,
            cryptoVersion = entity.cryptoVersion,
        )
        val plaintext = try {
            cipher.decrypt(
                EncryptedPrivateKey(
                    ciphertext = entity.ciphertext,
                    initializationVector = entity.initializationVector,
                ),
                aad,
            )
        } finally {
            aad.fill(0)
        }
        try {
            SessionCredential.PrivateKey.from(plaintext, passphrase)
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun rename(
        id: String,
        displayName: String,
    ) = withContext(ioDispatcher) {
        val normalizedDisplayName = normalizeDisplayName(displayName)
        val updated = protectStorage("The saved private key could not be renamed.") {
            dao.rename(id, normalizedDisplayName)
        }
        if (updated != 1) throw ImportedPrivateKeyUnavailableException()
    }

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        val deleted = protectStorage("The saved private key could not be deleted.") {
            dao.delete(id)
        }
        if (deleted != 1) throw ImportedPrivateKeyUnavailableException()
    }

    private fun inspectPrivateKey(
        keyBytes: ByteArray,
        passphrase: CharArray?,
    ): InspectedPrivateKey {
        val keyPair = try {
            // sshlib's supported parsing facade requires Strings. Keep both
            // conversions inside this call; neither value enters app state.
            SshKeys.decodePemPrivateKey(
                keyBytes.toString(Charsets.UTF_8),
                passphrase?.concatToString(),
            )
        } catch (failure: Exception) {
            throw InvalidImportedPrivateKeyException(failure)
        }
        try {
            val encoded = try {
                SshPublicKeyBlob.encode(keyPair)
            } catch (failure: Exception) {
                throw InvalidImportedPrivateKeyException(failure)
            }
            return try {
                InspectedPrivateKey(
                    format = detectFormat(keyBytes),
                    keyType = encoded.keyType,
                    publicKeyFingerprint = KeyFingerprint.sha256(encoded.blob),
                )
            } finally {
                encoded.blob.fill(0)
            }
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { (keyPair.private as? Destroyable)?.destroy() }
            }
        }
    }

    private fun detectFormat(keyBytes: ByteArray): String {
        val header = keyBytes.decodeToString(0, minOf(keyBytes.size, FORMAT_HEADER_BYTES))
        return when {
            "BEGIN OPENSSH PRIVATE KEY" in header -> "OpenSSH"
            "BEGIN ENCRYPTED PRIVATE KEY" in header -> "PKCS#8 encrypted"
            "BEGIN PRIVATE KEY" in header -> "PKCS#8"
            "BEGIN RSA PRIVATE KEY" in header -> "PEM RSA"
            "BEGIN EC PRIVATE KEY" in header -> "PEM EC"
            else -> "SSH private key"
        }
    }

    private data class InspectedPrivateKey(
        val format: String,
        val keyType: String,
        val publicKeyFingerprint: String,
    )

    companion object {
        private const val CRYPTO_VERSION = 1
        private const val FORMAT_HEADER_BYTES = 128

        internal fun associatedData(
            id: String,
            format: String,
            publicKeyFingerprint: String,
            cryptoVersion: Int,
        ): ByteArray = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF("Threadline imported private key")
                output.writeInt(cryptoVersion)
                output.writeUTF(id)
                output.writeUTF(format)
                output.writeUTF(publicKeyFingerprint)
            }
            bytes.toByteArray()
        }
    }
}

private fun normalizeDisplayName(displayName: String): String =
    displayName.trim().also { normalized ->
        require(normalized.isNotEmpty()) { "Give the imported key a name." }
    }

private suspend inline fun <T> protectStorage(
    message: String,
    crossinline operation: suspend () -> T,
): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw ImportedPrivateKeyStorageException(message, failure)
}

internal class InvalidImportedPrivateKeyException(
    cause: Throwable,
) : Exception("The private key format or passphrase is not valid.", cause)

internal class ImportedPrivateKeyUnavailableException : Exception(
    "The saved private key is no longer available.",
)

internal class ImportedPrivateKeyStorageException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

private fun ImportedPrivateKeyEntity.toMetadata() = ImportedPrivateKeyMetadata(
    id = id,
    displayName = displayName,
    format = format,
    keyType = keyType,
    publicKeyFingerprint = publicKeyFingerprint,
    createdAtMillis = createdAtMillis,
)

private fun ImportedPrivateKeyMetadataRow.toMetadata() = ImportedPrivateKeyMetadata(
    id = id,
    displayName = displayName,
    format = format,
    keyType = keyType,
    publicKeyFingerprint = publicKeyFingerprint,
    createdAtMillis = createdAtMillis,
)

private object SshPublicKeyBlob {
    data class Encoded(
        val keyType: String,
        val blob: ByteArray,
    )

    fun encode(keyPair: KeyPair): Encoded = when (val publicKey = keyPair.public) {
        is RSAPublicKey -> Encoded(
            keyType = "ssh-rsa",
            blob = encodeParts(
                "ssh-rsa".encodeToByteArray(),
                publicKey.publicExponent.toMpint(),
                publicKey.modulus.toMpint(),
            ),
        )

        is ECPublicKey -> {
            val fieldBytes = (publicKey.params.order.bitLength() + 7) / 8
            val curve = when (fieldBytes) {
                32 -> "nistp256"
                48 -> "nistp384"
                66 -> "nistp521"
                else -> error("Unsupported EC private key curve.")
            }
            val keyType = "ecdsa-sha2-$curve"
            val point = byteArrayOf(4) +
                publicKey.w.affineX.toUnsignedFixed(fieldBytes) +
                publicKey.w.affineY.toUnsignedFixed(fieldBytes)
            try {
                Encoded(
                    keyType = keyType,
                    blob = encodeParts(
                        keyType.encodeToByteArray(),
                        curve.encodeToByteArray(),
                        point,
                    ),
                )
            } finally {
                point.fill(0)
            }
        }

        else -> {
            val encoded = publicKey.encoded
            val normalizedAlgorithm = publicKey.algorithm.lowercase()
            require(
                normalizedAlgorithm == "eddsa" ||
                    "25519" in normalizedAlgorithm ||
                    encoded.containsSubsequence(ED25519_OID),
            ) {
                "Unsupported private key algorithm."
            }
            require(encoded.size >= ED25519_PUBLIC_KEY_BYTES)
            val raw = encoded.copyOfRange(
                encoded.size - ED25519_PUBLIC_KEY_BYTES,
                encoded.size,
            )
            try {
                Encoded(
                    keyType = "ssh-ed25519",
                    blob = encodeParts("ssh-ed25519".encodeToByteArray(), raw),
                )
            } finally {
                raw.fill(0)
            }
        }
    }

    private fun encodeParts(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                parts.forEach { part ->
                    output.writeInt(part.size)
                    output.write(part)
                }
            }
            bytes.toByteArray()
        }

    private fun BigInteger.toMpint(): ByteArray = toByteArray()

    private fun BigInteger.toUnsignedFixed(size: Int): ByteArray {
        val signed = toByteArray()
        return when {
            signed.size == size -> signed
            signed.size > size -> signed.copyOfRange(signed.size - size, signed.size)
            else -> ByteArray(size).also { target ->
                signed.copyInto(target, destinationOffset = size - signed.size)
            }
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean =
        indices.any { start ->
            start + needle.size <= size &&
                needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }

    private const val ED25519_PUBLIC_KEY_BYTES = 32
    private val ED25519_OID = byteArrayOf(0x06, 0x03, 0x2b, 0x65, 0x70)
}
