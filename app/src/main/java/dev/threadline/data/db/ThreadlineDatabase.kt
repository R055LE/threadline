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

@Database(
    entities = [KnownHostEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class ThreadlineDatabase : RoomDatabase() {
    abstract fun knownHosts(): KnownHostDao

    companion object {
        private const val DATABASE_NAME = "threadline.db"

        fun create(context: Context): ThreadlineDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ThreadlineDatabase::class.java,
                DATABASE_NAME,
            ).build()
    }
}
