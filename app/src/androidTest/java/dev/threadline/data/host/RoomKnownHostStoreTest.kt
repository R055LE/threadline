package dev.threadline.data.host

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostKeyDecision
import dev.threadline.core.model.SessionError
import dev.threadline.core.security.KnownHostKey
import dev.threadline.core.security.KnownHostRecord
import dev.threadline.core.security.StrictHostKeyGate
import dev.threadline.data.db.ThreadlineDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@RunWith(AndroidJUnit4::class)
class RoomKnownHostStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: ThreadlineDatabase

    @Before
    fun createDatabase() {
        legacyPreferences().edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(
            context,
            ThreadlineDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
        legacyPreferences().edit().clear().commit()
        context.deleteDatabase(PERSISTENCE_DATABASE_NAME)
    }

    @Test
    fun recordPersistsAndLastSeenOnlyMovesForward() = runBlocking {
        val endpoint = HostEndpoint("Example.Test", 2222)
        val key = KnownHostKey("ssh-ed25519", byteArrayOf(1, 2, 3))
        val store = store()
        store.save(
            KnownHostRecord(
                endpoint = endpoint,
                key = key,
                firstSeenAtMillis = 10,
                lastSeenAtMillis = 10,
            ),
        )

        store.recordTrustedSeen(endpoint, key, 20)
        store.recordTrustedSeen(endpoint, key, 15)

        val record = requireNotNull(store.find(HostEndpoint("example.test", 2222)))
        assertEquals("example.test", record.endpoint.hostname)
        assertEquals(10L, record.firstSeenAtMillis)
        assertEquals(20L, record.lastSeenAtMillis)
        assertEquals(key, record.key)
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun legacyPreferenceMigratesOnceIntoRoom() = runBlocking {
        val endpoint = HostEndpoint("legacy.test", 2200)
        val key = KnownHostKey("ssh-ed25519", byteArrayOf(4, 5, 6))
        legacyPreferences().edit().putString(
            endpoint.storageKey,
            "${key.algorithm}\n${Base64.Default.encode(key.encoded)}",
        ).commit()

        val migrated = store(currentTimeMillis = { 123 }).find(endpoint)

        assertEquals(key, migrated?.key)
        assertEquals(123L, migrated?.firstSeenAtMillis)
        assertEquals(123L, migrated?.lastSeenAtMillis)
        assertTrue(legacyPreferences().all.isEmpty())
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun staleLegacyRecordCannotReplaceRoomTrust() = runBlocking {
        val endpoint = HostEndpoint("priority.test", 22)
        val roomKey = KnownHostKey("ssh-ed25519", byteArrayOf(7, 8, 9))
        val initialStore = store()
        initialStore.save(
            KnownHostRecord(
                endpoint = endpoint,
                key = roomKey,
                firstSeenAtMillis = 10,
                lastSeenAtMillis = 20,
            ),
        )
        val staleKey = KnownHostKey("ssh-ed25519", byteArrayOf(1, 1, 1))
        legacyPreferences().edit().putString(
            endpoint.storageKey,
            "${staleKey.algorithm}\n${Base64.Default.encode(staleKey.encoded)}",
        ).commit()

        val record = store(currentTimeMillis = { 30 }).find(endpoint)

        assertEquals(roomKey, record?.key)
        assertEquals(10L, record?.firstSeenAtMillis)
        assertEquals(20L, record?.lastSeenAtMillis)
        assertTrue(legacyPreferences().all.isEmpty())
    }

    @Test
    fun roomBackedChangedKeyIsBlockedWithoutMutatingTrust() = runBlocking {
        val endpoint = HostEndpoint("changed.test", 22)
        val trusted = KnownHostKey("ssh-ed25519", byteArrayOf(1, 2, 3))
        val store = store()
        store.save(
            KnownHostRecord(
                endpoint = endpoint,
                key = trusted,
                firstSeenAtMillis = 10,
                lastSeenAtMillis = 10,
            ),
        )
        var prompted = false
        val gate = StrictHostKeyGate(
            endpoint = endpoint,
            store = store,
            requestDecision = {
                prompted = true
                HostKeyDecision.ACCEPT_AND_SAVE
            },
            currentTimeMillis = { 20 },
        )

        assertFalse(gate.verify("ssh-ed25519", byteArrayOf(9, 9, 9)))
        assertFalse(prompted)
        assertTrue(gate.rejection is SessionError.HostKeyChanged)
        val unchanged = requireNotNull(store.find(endpoint))
        assertEquals(trusted, unchanged.key)
        assertEquals(10L, unchanged.lastSeenAtMillis)
    }

    @Test
    fun recordSurvivesDatabaseReopen() = runBlocking {
        database.close()
        val endpoint = HostEndpoint("persistent.test", 22)
        val key = KnownHostKey("ssh-ed25519", byteArrayOf(3, 2, 1))
        database = persistentDatabase()
        store().save(
            KnownHostRecord(
                endpoint = endpoint,
                key = key,
                firstSeenAtMillis = 10,
                lastSeenAtMillis = 10,
            ),
        )
        database.close()

        database = persistentDatabase()

        assertEquals(key, store().find(endpoint)?.key)
    }

    private fun store(
        currentTimeMillis: () -> Long = { 1 },
    ): RoomKnownHostStore = RoomKnownHostStore(
        dao = database.knownHosts(),
        legacyPreferences = legacyPreferences(),
        currentTimeMillis = currentTimeMillis,
    )

    private fun legacyPreferences() = context.getSharedPreferences(
        LEGACY_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private fun persistentDatabase(): ThreadlineDatabase =
        Room.databaseBuilder(
            context,
            ThreadlineDatabase::class.java,
            PERSISTENCE_DATABASE_NAME,
        ).build()

    private companion object {
        const val LEGACY_PREFERENCES_NAME = "room_known_host_store_test"
        const val PERSISTENCE_DATABASE_NAME = "room-known-host-store-test.db"
    }
}
