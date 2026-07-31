package dev.threadline.data.profile

import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostProfile
import dev.threadline.data.db.HostProfileDao
import dev.threadline.data.db.HostProfileEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

internal data class SavedHostProfile(
    val id: String,
    val displayName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun toHostProfile() = HostProfile(
        displayName = displayName,
        endpoint = HostEndpoint(hostname, port),
        username = username,
    )
}

internal class RoomHostProfileStore(
    private val dao: HostProfileDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    val profiles: Flow<List<SavedHostProfile>> = dao.observeAll().map { entities ->
        entities.map(HostProfileEntity::toSavedHostProfile)
    }

    suspend fun save(profile: HostProfile): SavedHostProfile = withContext(ioDispatcher) {
        val normalized = profile.normalized()
        val now = currentTimeMillis()
        val entity = HostProfileEntity(
            id = newId(),
            displayName = normalized.displayName,
            hostname = normalized.endpoint.hostname,
            port = normalized.endpoint.port,
            username = normalized.username,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        protectProfileStorage("The host profile could not be saved.") {
            dao.insert(entity)
        }
        entity.toSavedHostProfile()
    }

    suspend fun update(
        id: String,
        profile: HostProfile,
    ) = withContext(ioDispatcher) {
        val normalized = profile.normalized()
        val updated = protectProfileStorage("The host profile could not be updated.") {
            dao.update(
                id = id,
                displayName = normalized.displayName,
                hostname = normalized.endpoint.hostname,
                port = normalized.endpoint.port,
                username = normalized.username,
                updatedAtMillis = currentTimeMillis(),
            )
        }
        if (updated != 1) throw HostProfileUnavailableException()
    }

    suspend fun delete(id: String) = withContext(ioDispatcher) {
        val deleted = protectProfileStorage("The host profile could not be deleted.") {
            dao.delete(id)
        }
        if (deleted != 1) throw HostProfileUnavailableException()
    }
}

internal class HostProfileUnavailableException : Exception(
    "The saved host profile is no longer available.",
)

internal class HostProfileStorageException(
    message: String,
    cause: Throwable,
) : Exception(message, cause)

private fun HostProfile.normalized() = HostProfile(
    displayName = displayName.trim(),
    endpoint = HostEndpoint(endpoint.hostname.trim(), endpoint.port),
    username = username.trim(),
)

private fun HostProfileEntity.toSavedHostProfile() = SavedHostProfile(
    id = id,
    displayName = displayName,
    hostname = hostname,
    port = port,
    username = username,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

private suspend inline fun <T> protectProfileStorage(
    message: String,
    crossinline operation: suspend () -> T,
): T = try {
    operation()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    throw HostProfileStorageException(message, failure)
}
