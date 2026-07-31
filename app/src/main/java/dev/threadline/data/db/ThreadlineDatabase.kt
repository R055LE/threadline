package dev.threadline.data.db

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
}

@Database(
    entities = [KnownHostEntity::class, ImportedPrivateKeyEntity::class],
    version = 2,
    exportSchema = true,
)
internal abstract class ThreadlineDatabase : RoomDatabase() {
    abstract fun knownHosts(): KnownHostDao
    abstract fun importedPrivateKeys(): ImportedPrivateKeyDao

    companion object {
        private const val DATABASE_NAME = "threadline.db"

        fun create(context: Context): ThreadlineDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ThreadlineDatabase::class.java,
                DATABASE_NAME,
            ).addMigrations(MIGRATION_1_2).build()

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
    }
}
