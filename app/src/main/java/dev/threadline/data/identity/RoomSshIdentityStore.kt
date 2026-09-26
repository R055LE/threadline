package dev.threadline.data.identity

import dev.threadline.data.db.SshIdentityDao
import dev.threadline.data.db.SshIdentityEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal enum class IdentityAuthenticationMethod {
    UNCONFIGURED,
    PASSWORD,
    IMPORTED_PRIVATE_KEY,
}

internal data class SshIdentity(
    val id: String,
    val label: String,
    val username: String,
    val authenticationMethod: IdentityAuthenticationMethod,
    val importedPrivateKeyId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

internal class RoomSshIdentityStore(
    private val dao: SshIdentityDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val identities: Flow<List<SshIdentity>> = dao.observeAll().map { entities ->
        entities.map(SshIdentityEntity::toSshIdentity)
    }

    suspend fun save(
        label: String,
        username: String,
        authenticationMethod: IdentityAuthenticationMethod,
        importedPrivateKeyId: String?,
    ): SshIdentity = withContext(ioDispatcher) {
        val normalized = normalize(label, username, authenticationMethod, importedPrivateKeyId)
        val now = currentTimeMillis()
        val entity = SshIdentityEntity(
            id = newId(),
            label = normalized.label,
            username = normalized.username,
            authenticationMethod = normalized.authenticationMethod.name,
            importedPrivateKeyId = normalized.importedPrivateKeyId,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        protectIdentityStorage("The SSH identity could not be saved.") {
            dao.insert(entity)
        }
        entity.toSshIdentity()
    }

    suspend fun update(
        id: String,
        label: String,
        username: String,
        authenticationMethod: IdentityAuthenticationMethod,
        importedPrivateKeyId: String?,
    ) = withContext(ioDispatcher) {
        val normalized = normalize(label, username, authenticationMethod, importedPrivateKeyId)
        val updated = protectIdentityStorage("The SSH identity could not be updated.") {
            dao.update(
                id = id,
                label = normalized.label,
                username = normalized.username,
                authenticationMethod = normalized.authenticationMethod.name,
                importedPrivateKeyId = normalized.importedPrivateKeyId,
                updatedAtMillis = currentTimeMillis(),
            )
        }
        if (updated != 1) throw SshIdentityUnavailableException()
    }

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        val deleted = protectIdentityStorage("The SSH identity could not be deleted.") {
            dao.deleteAndUnlink(id)
        }
        if (deleted != 1) throw SshIdentityUnavailableException()
    }
}

internal class SshIdentityUnavailableException : Exception(
    "The saved SSH identity is no longer available.",
)

internal class SshIdentityStorageException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

private data class NormalizedIdentity(
    val label: String,
    val username: String,
    val authenticationMethod: IdentityAuthenticationMethod,
    val importedPrivateKeyId: String?,
)

private fun normalize(
    label: String,
    username: String,
    authenticationMethod: IdentityAuthenticationMethod,
    importedPrivateKeyId: String?,
): NormalizedIdentity {
    val normalizedLabel = label.trim()
    val normalizedUsername = username.trim()
    require(normalizedLabel.isNotEmpty()) { "An identity label is required." }
    require(normalizedUsername.isNotEmpty()) { "A username is required." }
    require(
        (authenticationMethod == IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY) ==
            (importedPrivateKeyId != null),
    ) { "Choose a saved private key for this authentication method." }
    return NormalizedIdentity(
        label = normalizedLabel,
        username = normalizedUsername,
        authenticationMethod = authenticationMethod,
        importedPrivateKeyId = importedPrivateKeyId,
    )
}

private fun SshIdentityEntity.toSshIdentity() = SshIdentity(
    id = id,
    label = label,
    username = username,
    authenticationMethod = IdentityAuthenticationMethod.valueOf(authenticationMethod),
    importedPrivateKeyId = importedPrivateKeyId,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

private suspend inline fun <T> protectIdentityStorage(
    message: String,
    crossinline operation: suspend () -> T,
): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw SshIdentityStorageException(message, failure)
}
