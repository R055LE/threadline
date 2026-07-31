package dev.threadline.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThreadlineDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ThreadlineDatabase::class.java,
    )

    @After
    fun removeDatabase() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .deleteDatabase(DATABASE_NAME)
    }

    @Test
    @Throws(IOException::class)
    fun migrationFromOnePreservesKnownHostsAndCreatesEncryptedKeyTable() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO known_hosts (
                    endpoint_key, hostname, port, algorithm, encoded_key,
                    first_seen_at_millis, last_seen_at_millis
                ) VALUES ('fixture.test:22', 'fixture.test', 22, 'ssh-ed25519', X'010203', 10, 20)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            ThreadlineDatabase.MIGRATION_1_2,
        )

        migrated.query(
            "SELECT endpoint_key, last_seen_at_millis FROM known_hosts",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("fixture.test:22", cursor.getString(0))
            assertEquals(20L, cursor.getLong(1))
        }
        migrated.query("SELECT COUNT(*) FROM imported_private_keys").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    @Throws(IOException::class)
    fun migrationFromTwoPreservesEncryptedKeysAndCreatesHostProfileTable() {
        helper.createDatabase(DATABASE_NAME, 2).apply {
            execSQL(
                """
                INSERT INTO imported_private_keys (
                    id, display_name, format, key_type, public_key_fingerprint,
                    ciphertext, initialization_vector, created_at_millis, crypto_version
                ) VALUES (
                    'key-id', 'Fixture key', 'OpenSSH', 'ssh-ed25519', 'fixture-fingerprint',
                    X'010203', X'040506', 30, 1
                )
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            3,
            true,
            ThreadlineDatabase.MIGRATION_2_3,
        )

        migrated.query(
            "SELECT id, ciphertext FROM imported_private_keys",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("key-id", cursor.getString(0))
            assertTrue(byteArrayOf(1, 2, 3).contentEquals(cursor.getBlob(1)))
        }
        migrated.query("SELECT COUNT(*) FROM host_profiles").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    private companion object {
        const val DATABASE_NAME = "threadline-migration-test"
    }
}
