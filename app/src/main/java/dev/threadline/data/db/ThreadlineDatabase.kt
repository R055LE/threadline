package dev.threadline.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "known_hosts")
internal data class KnownHostEntity(
    @PrimaryKey
    @ColumnInfo(name = "endpoint_key")
    val endpointKey: String,
    val hostname: String,
    val port: Int,
    val algorithm: String,
    @ColumnInfo(name = "encoded_key", typeAffinity = ColumnInfo.BLOB)
    val encodedKey: ByteArray,
    @ColumnInfo(name = "first_seen_at_millis")
    val firstSeenAtMillis: Long,
    @ColumnInfo(name = "last_seen_at_millis")
    val lastSeenAtMillis: Long,
)

@Dao
internal interface KnownHostDao {
    @Query(
        """
        SELECT * FROM known_hosts
        ORDER BY hostname COLLATE NOCASE, port, algorithm, endpoint_key
        """,
    )
    fun observeAll(): Flow<List<KnownHostEntity>>

    @Query("SELECT * FROM known_hosts WHERE endpoint_key = :endpointKey")
    suspend fun find(endpointKey: String): KnownHostEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: KnownHostEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLegacy(entities: List<KnownHostEntity>)

    @Query(
        """
        UPDATE known_hosts
        SET last_seen_at_millis =
            CASE
                WHEN last_seen_at_millis < :seenAtMillis THEN :seenAtMillis
                ELSE last_seen_at_millis
            END
        WHERE endpoint_key = :endpointKey
          AND algorithm = :algorithm
          AND encoded_key = :encodedKey
        """,
    )
    suspend fun recordTrustedSeen(
        endpointKey: String,
        algorithm: String,
        encodedKey: ByteArray,
        seenAtMillis: Long,
    ): Int

    @Query("DELETE FROM known_hosts WHERE endpoint_key = :endpointKey")
    suspend fun delete(endpointKey: String): Int
}

@Entity(tableName = "imported_private_keys")
internal data class ImportedPrivateKeyEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    val format: String,
    @ColumnInfo(name = "key_type")
    val keyType: String,
    @ColumnInfo(name = "public_key_fingerprint")
    val publicKeyFingerprint: String,
    @ColumnInfo(name = "ciphertext", typeAffinity = ColumnInfo.BLOB)
    val ciphertext: ByteArray,
    @ColumnInfo(name = "initialization_vector", typeAffinity = ColumnInfo.BLOB)
    val initializationVector: ByteArray,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
    @ColumnInfo(name = "crypto_version")
    val cryptoVersion: Int,
)

internal data class ImportedPrivateKeyMetadataRow(
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    val format: String,
    @ColumnInfo(name = "key_type")
    val keyType: String,
    @ColumnInfo(name = "public_key_fingerprint")
    val publicKeyFingerprint: String,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
)

@Dao
internal interface ImportedPrivateKeyDao {
    @Query(
        """
        SELECT id, display_name, format, key_type, public_key_fingerprint,
               created_at_millis
        FROM imported_private_keys
        ORDER BY display_name COLLATE NOCASE, created_at_millis, id
        """,
    )
    fun observeAll(): Flow<List<ImportedPrivateKeyMetadataRow>>

    @Query("SELECT * FROM imported_private_keys WHERE id = :id")
    suspend fun find(id: String): ImportedPrivateKeyEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ImportedPrivateKeyEntity)

    @Query("UPDATE imported_private_keys SET display_name = :displayName WHERE id = :id")
    suspend fun rename(
        id: String,
        displayName: String,
    ): Int

    @Query("DELETE FROM imported_private_keys WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Entity(
    tableName = "host_profiles",
    indices = [Index(value = ["preferred_identity_id"])],
)
internal data class HostProfileEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    @ColumnInfo(name = "preferred_identity_id")
    val preferredIdentityId: String?,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "ssh_identities",
    foreignKeys = [
        ForeignKey(
            entity = ImportedPrivateKeyEntity::class,
            parentColumns = ["id"],
            childColumns = ["imported_private_key_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["imported_private_key_id"])],
)
internal data class SshIdentityEntity(
    @PrimaryKey
    val id: String,
    val label: String,
    val username: String,
    @ColumnInfo(name = "authentication_method")
    val authenticationMethod: String,
    @ColumnInfo(name = "imported_private_key_id")
    val importedPrivateKeyId: String?,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "saved_ssh_passwords",
    foreignKeys = [
        ForeignKey(
            entity = SshIdentityEntity::class,
            parentColumns = ["id"],
            childColumns = ["identity_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SavedSshPasswordEntity(
    @PrimaryKey
    @ColumnInfo(name = "identity_id")
    val identityId: String,
    @ColumnInfo(name = "ciphertext", typeAffinity = ColumnInfo.BLOB)
    val ciphertext: ByteArray,
    @ColumnInfo(name = "initialization_vector", typeAffinity = ColumnInfo.BLOB)
    val initializationVector: ByteArray,
    @ColumnInfo(name = "crypto_version")
    val cryptoVersion: Int,
)

internal data class SshIdentityRow(
    val id: String,
    val label: String,
    val username: String,
    @ColumnInfo(name = "authentication_method")
    val authenticationMethod: String,
    @ColumnInfo(name = "imported_private_key_id")
    val importedPrivateKeyId: String?,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long,
    @ColumnInfo(name = "has_saved_password")
    val hasSavedPassword: Boolean,
)

internal data class HostProfileRow(
    val id: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    @ColumnInfo(name = "preferred_identity_id")
    val preferredIdentityId: String?,
    @ColumnInfo(name = "created_at_millis")
    val createdAtMillis: Long,
    @ColumnInfo(name = "updated_at_millis")
    val updatedAtMillis: Long,
)

@Dao
internal interface SshIdentityDao {
    @Query(
        """
        SELECT ssh_identities.*,
               EXISTS(
                   SELECT 1 FROM saved_ssh_passwords
                   WHERE saved_ssh_passwords.identity_id = ssh_identities.id
               ) AS has_saved_password
        FROM ssh_identities
        ORDER BY label COLLATE NOCASE, username COLLATE NOCASE, id
        """,
    )
    fun observeAll(): Flow<List<SshIdentityRow>>

    @Query("SELECT * FROM ssh_identities WHERE id = :id")
    suspend fun find(id: String): SshIdentityEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SshIdentityEntity)

    @Query(
        """
        UPDATE ssh_identities
        SET label = :label,
            username = :username,
            authentication_method = :authenticationMethod,
            imported_private_key_id = :importedPrivateKeyId,
            updated_at_millis = :updatedAtMillis
        WHERE id = :id
        """,
    )
    suspend fun update(
        id: String,
        label: String,
        username: String,
        authenticationMethod: String,
        importedPrivateKeyId: String?,
        updatedAtMillis: Long,
    ): Int

    @Query("UPDATE host_profiles SET preferred_identity_id = NULL WHERE preferred_identity_id = :id")
    suspend fun unlinkProfiles(id: String)

    @Query("DELETE FROM ssh_identities WHERE id = :id")
    suspend fun delete(id: String): Int

    @Transaction
    suspend fun deleteAndUnlink(id: String): Int {
        unlinkProfiles(id)
        return delete(id)
    }
}

@Dao
internal interface SavedSshPasswordDao {
    @Query("SELECT * FROM saved_ssh_passwords WHERE identity_id = :identityId")
    suspend fun find(identityId: String): SavedSshPasswordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedSshPasswordEntity)

    @Query("DELETE FROM saved_ssh_passwords WHERE identity_id = :identityId")
    suspend fun delete(identityId: String): Int
}

@Dao
internal interface HostProfileDao {
    @Query(
        """
        SELECT host_profiles.id,
               host_profiles.display_name,
               host_profiles.hostname,
               host_profiles.port,
               COALESCE(ssh_identities.username, host_profiles.username) AS username,
               host_profiles.preferred_identity_id,
               host_profiles.created_at_millis,
               host_profiles.updated_at_millis
        FROM host_profiles
        LEFT JOIN ssh_identities
            ON ssh_identities.id = host_profiles.preferred_identity_id
        ORDER BY host_profiles.display_name COLLATE NOCASE,
                 host_profiles.hostname COLLATE NOCASE,
                 host_profiles.port,
                 username,
                 host_profiles.id
        """,
    )
    fun observeAll(): Flow<List<HostProfileRow>>

    @Query("SELECT * FROM host_profiles WHERE id = :id")
    suspend fun find(id: String): HostProfileEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: HostProfileEntity)

    @Query(
        """
        UPDATE host_profiles
        SET display_name = :displayName,
            hostname = :hostname,
            port = :port,
            username = :username,
            preferred_identity_id = :preferredIdentityId,
            updated_at_millis = :updatedAtMillis
        WHERE id = :id
        """,
    )
    suspend fun update(
        id: String,
        displayName: String,
        hostname: String,
        port: Int,
        username: String,
        preferredIdentityId: String?,
        updatedAtMillis: Long,
    ): Int

    @Query("DELETE FROM host_profiles WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Entity(tableName = "transcript_sessions")
internal data class TranscriptSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    @ColumnInfo(name = "started_at_millis")
    val startedAtMillis: Long,
    @ColumnInfo(name = "ended_at_millis")
    val endedAtMillis: Long,
    @ColumnInfo(name = "turns_truncated")
    val turnsTruncated: Boolean,
)

@Entity(
    tableName = "transcript_turns",
    primaryKeys = ["session_id", "command_id"],
    foreignKeys = [
        ForeignKey(
            entity = TranscriptSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
internal data class TranscriptTurnEntity(
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "command_id")
    val commandId: String,
    @ColumnInfo(name = "turn_index")
    val turnIndex: Int,
    val command: String,
    @ColumnInfo(name = "execution_mode")
    val executionMode: String,
    @ColumnInfo(name = "command_truncated")
    val commandTruncated: Boolean,
    @ColumnInfo(name = "directory_at_start")
    val directoryAtStart: String?,
    @ColumnInfo(name = "submitted_at_millis")
    val submittedAtMillis: Long,
    @ColumnInfo(name = "started_at_millis")
    val startedAtMillis: Long?,
    @ColumnInfo(name = "completed_at_millis")
    val completedAtMillis: Long?,
    val status: String,
    @ColumnInfo(name = "exit_status")
    val exitStatus: Int?,
    @ColumnInfo(name = "current_directory")
    val currentDirectory: String?,
    @ColumnInfo(name = "output_truncated")
    val outputTruncated: Boolean,
    @ColumnInfo(name = "output_approximate")
    val outputApproximate: Boolean,
    @ColumnInfo(name = "output_byte_count")
    val outputByteCount: Long,
)

@Entity(
    tableName = "transcript_output_chunks",
    primaryKeys = ["session_id", "command_id", "chunk_index"],
    foreignKeys = [
        ForeignKey(
            entity = TranscriptTurnEntity::class,
            parentColumns = ["session_id", "command_id"],
            childColumns = ["session_id", "command_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["session_id", "command_id"])],
)
internal data class TranscriptOutputChunkEntity(
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "command_id")
    val commandId: String,
    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int,
    val text: String,
)

internal data class TranscriptSessionSummaryRow(
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    @ColumnInfo(name = "display_name")
    val displayName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    @ColumnInfo(name = "started_at_millis")
    val startedAtMillis: Long,
    @ColumnInfo(name = "ended_at_millis")
    val endedAtMillis: Long,
    @ColumnInfo(name = "turns_truncated")
    val turnsTruncated: Boolean,
    @ColumnInfo(name = "turn_count")
    val turnCount: Int,
)

internal data class TranscriptSessionRows(
    val session: TranscriptSessionEntity,
    val turns: List<TranscriptTurnEntity>,
    val chunks: List<TranscriptOutputChunkEntity>,
)

@Dao
internal interface TranscriptArchiveDao {
    @Query(
        """
        SELECT transcript_sessions.*,
               (SELECT COUNT(*) FROM transcript_turns
                WHERE transcript_turns.session_id = transcript_sessions.session_id) AS turn_count
        FROM transcript_sessions
        ORDER BY ended_at_millis DESC, session_id DESC
        """,
    )
    fun observeSummaries(): Flow<List<TranscriptSessionSummaryRow>>

    @Query("SELECT * FROM transcript_sessions WHERE session_id = :sessionId")
    suspend fun findSession(sessionId: String): TranscriptSessionEntity?

    @Query(
        """
        SELECT * FROM transcript_turns
        WHERE session_id = :sessionId
        ORDER BY turn_index, command_id
        """,
    )
    suspend fun findTurns(sessionId: String): List<TranscriptTurnEntity>

    @Query(
        """
        SELECT * FROM transcript_output_chunks
        WHERE session_id = :sessionId
        ORDER BY command_id, chunk_index
        """,
    )
    suspend fun findChunks(sessionId: String): List<TranscriptOutputChunkEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(entity: TranscriptSessionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTurns(entities: List<TranscriptTurnEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChunks(entities: List<TranscriptOutputChunkEntity>)

    @Query("DELETE FROM transcript_sessions WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String): Int

    @Query("DELETE FROM transcript_sessions")
    suspend fun clearSessions(): Int

    @Query(
        """
        DELETE FROM transcript_sessions
        WHERE session_id IN (
            SELECT session_id FROM transcript_sessions
            ORDER BY ended_at_millis DESC, session_id DESC
            LIMIT -1 OFFSET :maximumSessions
        )
        """,
    )
    suspend fun pruneSessions(maximumSessions: Int)

    @Transaction
    suspend fun replaceArchive(
        session: TranscriptSessionEntity,
        turns: List<TranscriptTurnEntity>,
        chunks: List<TranscriptOutputChunkEntity>,
        maximumSessions: Int,
    ) {
        deleteSession(session.sessionId)
        insertSession(session)
        if (turns.isNotEmpty()) insertTurns(turns)
        if (chunks.isNotEmpty()) insertChunks(chunks)
        pruneSessions(maximumSessions)
    }

    @Transaction
    suspend fun loadArchive(sessionId: String): TranscriptSessionRows? {
        val session = findSession(sessionId) ?: return null
        return TranscriptSessionRows(
            session = session,
            turns = findTurns(sessionId),
            chunks = findChunks(sessionId),
        )
    }
}

@Database(
    entities = [
        KnownHostEntity::class,
        ImportedPrivateKeyEntity::class,
        HostProfileEntity::class,
        SshIdentityEntity::class,
        SavedSshPasswordEntity::class,
        TranscriptSessionEntity::class,
        TranscriptTurnEntity::class,
        TranscriptOutputChunkEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
internal abstract class ThreadlineDatabase : RoomDatabase() {
    abstract fun knownHosts(): KnownHostDao
    abstract fun importedPrivateKeys(): ImportedPrivateKeyDao
    abstract fun hostProfiles(): HostProfileDao
    abstract fun sshIdentities(): SshIdentityDao
    abstract fun savedSshPasswords(): SavedSshPasswordDao
    abstract fun transcriptArchives(): TranscriptArchiveDao

    companion object {
        private const val DATABASE_NAME = "threadline.db"

        fun create(context: Context): ThreadlineDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ThreadlineDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
            ).build()

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `imported_private_keys` (
                        `id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `format` TEXT NOT NULL,
                        `key_type` TEXT NOT NULL,
                        `public_key_fingerprint` TEXT NOT NULL,
                        `ciphertext` BLOB NOT NULL,
                        `initialization_vector` BLOB NOT NULL,
                        `created_at_millis` INTEGER NOT NULL,
                        `crypto_version` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `host_profiles` (
                        `id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `hostname` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `username` TEXT NOT NULL,
                        `created_at_millis` INTEGER NOT NULL,
                        `updated_at_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transcript_sessions` (
                        `session_id` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `hostname` TEXT NOT NULL,
                        `port` INTEGER NOT NULL,
                        `username` TEXT NOT NULL,
                        `started_at_millis` INTEGER NOT NULL,
                        `ended_at_millis` INTEGER NOT NULL,
                        `turns_truncated` INTEGER NOT NULL,
                        PRIMARY KEY(`session_id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transcript_turns` (
                        `session_id` TEXT NOT NULL,
                        `command_id` TEXT NOT NULL,
                        `turn_index` INTEGER NOT NULL,
                        `command` TEXT NOT NULL,
                        `command_truncated` INTEGER NOT NULL,
                        `directory_at_start` TEXT,
                        `submitted_at_millis` INTEGER NOT NULL,
                        `started_at_millis` INTEGER,
                        `completed_at_millis` INTEGER,
                        `status` TEXT NOT NULL,
                        `exit_status` INTEGER,
                        `current_directory` TEXT,
                        `output_truncated` INTEGER NOT NULL,
                        `output_approximate` INTEGER NOT NULL,
                        `output_byte_count` INTEGER NOT NULL,
                        PRIMARY KEY(`session_id`, `command_id`),
                        FOREIGN KEY(`session_id`) REFERENCES `transcript_sessions`(`session_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transcript_turns_session_id` " +
                        "ON `transcript_turns` (`session_id`)",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transcript_output_chunks` (
                        `session_id` TEXT NOT NULL,
                        `command_id` TEXT NOT NULL,
                        `chunk_index` INTEGER NOT NULL,
                        `text` TEXT NOT NULL,
                        PRIMARY KEY(`session_id`, `command_id`, `chunk_index`),
                        FOREIGN KEY(`session_id`, `command_id`)
                            REFERENCES `transcript_turns`(`session_id`, `command_id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_transcript_output_chunks_session_id_command_id` " +
                        "ON `transcript_output_chunks` (`session_id`, `command_id`)",
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `transcript_turns` ADD COLUMN " +
                        "`execution_mode` TEXT NOT NULL DEFAULT 'PERSISTENT'",
                )
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ssh_identities` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `authentication_method` TEXT NOT NULL,
                        `imported_private_key_id` TEXT,
                        `created_at_millis` INTEGER NOT NULL,
                        `updated_at_millis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`imported_private_key_id`)
                            REFERENCES `imported_private_keys`(`id`)
                            ON UPDATE NO ACTION ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_ssh_identities_imported_private_key_id` " +
                        "ON `ssh_identities` (`imported_private_key_id`)",
                )
                db.execSQL(
                    "ALTER TABLE `host_profiles` ADD COLUMN `preferred_identity_id` TEXT",
                )
                db.execSQL(
                    """
                    INSERT INTO `ssh_identities` (
                        `id`, `label`, `username`, `authentication_method`,
                        `imported_private_key_id`, `created_at_millis`, `updated_at_millis`
                    )
                    SELECT 'legacy-' || `id`, `display_name`, `username`, 'UNCONFIGURED',
                           NULL, `created_at_millis`, `updated_at_millis`
                    FROM `host_profiles`
                    """.trimIndent(),
                )
                db.execSQL(
                    "UPDATE `host_profiles` SET `preferred_identity_id` = 'legacy-' || `id`",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_host_profiles_preferred_identity_id` " +
                        "ON `host_profiles` (`preferred_identity_id`)",
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_ssh_passwords` (
                        `identity_id` TEXT NOT NULL,
                        `ciphertext` BLOB NOT NULL,
                        `initialization_vector` BLOB NOT NULL,
                        `crypto_version` INTEGER NOT NULL,
                        PRIMARY KEY(`identity_id`),
                        FOREIGN KEY(`identity_id`) REFERENCES `ssh_identities`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }
    }
}
