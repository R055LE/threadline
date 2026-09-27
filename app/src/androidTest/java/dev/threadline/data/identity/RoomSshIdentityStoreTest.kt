package dev.threadline.data.identity

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.threadline.core.model.HostEndpoint
import dev.threadline.core.model.HostProfile
import dev.threadline.data.db.ImportedPrivateKeyEntity
import dev.threadline.data.db.ThreadlineDatabase
import dev.threadline.data.profile.RoomHostProfileStore
import kotlinx.coroutines.Dispatchers
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
class RoomSshIdentityStoreTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var database: ThreadlineDatabase

    @Before
    fun createDatabase() {
        context.deleteDatabase(DATABASE_NAME)
        database = Room.databaseBuilder(
            context,
            ThreadlineDatabase::class.java,
            DATABASE_NAME,
        ).addMigrations(
            ThreadlineDatabase.MIGRATION_1_2,
            ThreadlineDatabase.MIGRATION_2_3,
            ThreadlineDatabase.MIGRATION_3_4,
            ThreadlineDatabase.MIGRATION_4_5,
            ThreadlineDatabase.MIGRATION_5_6,
            ThreadlineDatabase.MIGRATION_6_7,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun identityNormalizesUpdatesAndRemainsReusableAcrossProfiles() = runBlocking {
        var now = 10L
        var nextId = 0
        val store = RoomSshIdentityStore(
            dao = database.sshIdentities(),
            ioDispatcher = Dispatchers.Unconfined,
            currentTimeMillis = { now },
            newId = { "identity-${++nextId}" },
        )
        val identity = store.save(
            label = "  Work  ",
            username = " operator ",
            authenticationMethod = IdentityAuthenticationMethod.PASSWORD,
            importedPrivateKeyId = null,
        )
        val profiles = RoomHostProfileStore(
            dao = database.hostProfiles(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val first = profiles.save(profile("One", "one.example", "old"), identity.id)
        val second = profiles.save(profile("Two", "two.example", "old"), identity.id)

        now = 20L
        store.update(
            id = identity.id,
            label = "Work account",
            username = "deploy",
            authenticationMethod = IdentityAuthenticationMethod.UNCONFIGURED,
            importedPrivateKeyId = null,
        )

        val updated = store.identities.first().single()
        assertEquals(identity.id, updated.id)
        assertEquals("Work account", updated.label)
        assertEquals("deploy", updated.username)
        assertEquals(10L, updated.createdAtMillis)
        assertEquals(20L, updated.updatedAtMillis)
        assertEquals("deploy", profiles.profiles.first().first { it.id == first.id }.username)
        assertEquals("deploy", profiles.profiles.first().first { it.id == second.id }.username)
    }

    @Test
    fun deletingIdentityUnlinksProfilesAndKeepsImportedKey() = runBlocking {
        database.importedPrivateKeys().insert(
            ImportedPrivateKeyEntity(
                id = "key-id",
                displayName = "Fixture key",
                format = "OpenSSH",
                keyType = "ssh-ed25519",
                publicKeyFingerprint = "fixture-fingerprint",
                ciphertext = byteArrayOf(1, 2, 3),
                initializationVector = byteArrayOf(4, 5, 6),
                createdAtMillis = 1,
                cryptoVersion = 1,
            ),
        )
        val store = RoomSshIdentityStore(
            dao = database.sshIdentities(),
            ioDispatcher = Dispatchers.Unconfined,
            newId = { "identity-id" },
        )
        val identity = store.save(
            label = "Fixture",
            username = "threadline",
            authenticationMethod = IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY,
            importedPrivateKeyId = "key-id",
        )
        val profiles = RoomHostProfileStore(
            dao = database.hostProfiles(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val profile = profiles.save(profile("Fixture", "fixture.test", "threadline"), identity.id)

        store.delete(identity.id)

        assertNull(database.sshIdentities().find(identity.id))
        assertNull(database.hostProfiles().find(profile.id)?.preferredIdentityId)
        assertEquals("key-id", database.importedPrivateKeys().find("key-id")?.id)
        assertEquals("threadline", profiles.profiles.first().single().username)
    }

    @Test
    fun deletingImportedKeyLeavesIdentityPresentAndRequiresKeyRepair() = runBlocking {
        database.importedPrivateKeys().insert(
            ImportedPrivateKeyEntity(
                id = "key-id",
                displayName = "Fixture key",
                format = "OpenSSH",
                keyType = "ssh-ed25519",
                publicKeyFingerprint = "fixture-fingerprint",
                ciphertext = byteArrayOf(1, 2, 3),
                initializationVector = byteArrayOf(4, 5, 6),
                createdAtMillis = 1,
                cryptoVersion = 1,
            ),
        )
        val store = RoomSshIdentityStore(
            dao = database.sshIdentities(),
            ioDispatcher = Dispatchers.Unconfined,
            newId = { "identity-id" },
        )
        store.save(
            label = "Fixture",
            username = "threadline",
            authenticationMethod = IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY,
            importedPrivateKeyId = "key-id",
        )

        database.importedPrivateKeys().delete("key-id")

        val identity = store.identities.first().single()
        assertEquals(IdentityAuthenticationMethod.IMPORTED_PRIVATE_KEY, identity.authenticationMethod)
        assertNull(identity.importedPrivateKeyId)
        assertTrue(database.importedPrivateKeys().find("key-id") == null)
    }

    private fun profile(
        displayName: String,
        hostname: String,
        username: String,
    ) = HostProfile(
        displayName = displayName,
        endpoint = HostEndpoint(hostname, 22),
        username = username,
    )

    private companion object {
        const val DATABASE_NAME = "threadline-ssh-identity-test"
    }
}
