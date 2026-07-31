package dev.threadline.data.profile

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostProfile
import dev.threadline.data.db.HostProfileDao
import dev.threadline.data.db.HostProfileEntity
import dev.threadline.data.db.ThreadlineDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomHostProfileStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: ThreadlineDatabase

    @Before
    fun createDatabase() {
        context.deleteDatabase(DATABASE_NAME)
        database = persistentDatabase()
    }

    @After
    fun closeDatabase() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun saveUpdateDeleteAreNormalizedAndRecordScoped() = runBlocking {
        var now = 10L
        var id = 0
        val store = RoomHostProfileStore(
            dao = database.hostProfiles(),
            ioDispatcher = Dispatchers.Unconfined,
            currentTimeMillis = { now },
            newId = { "profile-${++id}" },
        )

        val first = store.save(profile("  Lab  ", " LAB.EXAMPLE ", 22, " operator "))
        val second = store.save(profile("Backup", "backup.example", 2200, "root"))
        assertEquals("Lab", first.displayName)
        assertEquals("LAB.EXAMPLE", first.hostname)
        assertEquals("operator", first.username)

        now = 20L
        store.update(first.id, profile("Renamed", "new.example", 2222, "admin"))

        val updated = requireNotNull(database.hostProfiles().find(first.id))
        assertEquals("Renamed", updated.displayName)
        assertEquals("new.example", updated.hostname)
        assertEquals(2222, updated.port)
        assertEquals("admin", updated.username)
        assertEquals(10L, updated.createdAtMillis)
        assertEquals(20L, updated.updatedAtMillis)

        store.delete(first.id)

        assertNull(database.hostProfiles().find(first.id))
        assertEquals(second.id, requireNotNull(database.hostProfiles().find(second.id)).id)
        assertEquals(listOf(second.id), store.profiles.first().map(SavedHostProfile::id))
    }

    @Test
    fun savedProfileSurvivesDatabaseReopenWithoutCredentialFields() = runBlocking {
        val store = RoomHostProfileStore(
            dao = database.hostProfiles(),
            ioDispatcher = Dispatchers.Unconfined,
            currentTimeMillis = { 30L },
            newId = { "persistent-profile" },
        )
        store.save(profile("Persistent", "fixture.test", 22, "threadline"))

        database.close()
        database = persistentDatabase()

        val reopened = RoomHostProfileStore(
            dao = database.hostProfiles(),
            ioDispatcher = Dispatchers.Unconfined,
        ).profiles.first().single()
        assertEquals("persistent-profile", reopened.id)
        assertEquals("Persistent", reopened.displayName)
        assertEquals("fixture.test", reopened.hostname)
        assertEquals(22, reopened.port)
        assertEquals("threadline", reopened.username)
    }

    @Test
    fun lowLevelFailureBecomesSanitizedStorageError() = runBlocking {
        val store = RoomHostProfileStore(
            dao = FailingHostProfileDao(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = runCatching {
            store.save(profile("Fixture", "fixture.test", 22, "operator"))
        }.exceptionOrNull()

        assertTrue(failure is HostProfileStorageException)
        assertEquals("The host profile could not be saved.", failure?.message)
        assertTrue(failure?.message?.contains("database-path") == false)
    }

    private fun persistentDatabase() = Room.databaseBuilder(
        context,
        ThreadlineDatabase::class.java,
        DATABASE_NAME,
    ).addMigrations(
        ThreadlineDatabase.MIGRATION_1_2,
        ThreadlineDatabase.MIGRATION_2_3,
    ).build()

    private fun profile(
        displayName: String,
        hostname: String,
        port: Int,
        username: String,
    ) = HostProfile(
        displayName = displayName,
        endpoint = HostEndpoint(hostname, port),
        username = username,
    )

    private class FailingHostProfileDao : HostProfileDao {
        override fun observeAll(): Flow<List<HostProfileEntity>> = emptyFlow()

        override suspend fun find(id: String): HostProfileEntity? = null

        override suspend fun insert(entity: HostProfileEntity) {
            error("database-path")
        }

        override suspend fun update(
            id: String,
            displayName: String,
            hostname: String,
            port: Int,
            username: String,
            updatedAtMillis: Long,
        ): Int = error("database-path")

        override suspend fun delete(id: String): Int = error("database-path")
    }

    private companion object {
        const val DATABASE_NAME = "threadline-host-profile-test"
    }
}
