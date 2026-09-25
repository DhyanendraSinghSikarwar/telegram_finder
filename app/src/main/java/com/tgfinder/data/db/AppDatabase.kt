package com.tgfinder.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Cached result of matching a cleaned title against TMDB search. [tmdbId] = 0 means "no match". */
@Entity(tableName = "tmdb_match")
data class TmdbMatchEntity(
    @PrimaryKey val queryKey: String,
    val tmdbId: Int,
    val mediaType: String, // "movie" or "tv"
    val title: String,
    val year: Int?,
    val posterPath: String?,
    val backdropPath: String?,
    val rating: Double,
    val voteCount: Int,
    val fetchedAt: Long,
)

/** Raw TMDB details JSON (details + credits + videos + certifications) keyed by "movie:123" / "tv:456". */
@Entity(tableName = "tmdb_detail")
data class TmdbDetailEntity(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAt: Long,
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val key: String, // "$chatId:$messageId"
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileName: String,
    val mimeType: String,
    val title: String,
    val subtitle: String?, // year / episode label
    val posterPath: String?,
    val thumbPath: String?,
    val quality: String?,
    val chatTitle: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val status: String,
    val error: String?,
    val contentUri: String?,
    val createdAt: Long,
) {
    companion object {
        const val QUEUED = "QUEUED"
        const val DOWNLOADING = "DOWNLOADING"
        const val PAUSED = "PAUSED"
        const val SAVING = "SAVING"
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
        val ACTIVE = listOf(QUEUED, DOWNLOADING, SAVING)
    }
}

@Entity(tableName = "playback_position")
data class PlaybackPositionEntity(
    @PrimaryKey val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface TmdbDao {
    @Query("SELECT * FROM tmdb_match WHERE queryKey = :key")
    suspend fun match(key: String): TmdbMatchEntity?

    @Upsert
    suspend fun putMatch(entity: TmdbMatchEntity)

    @Query("SELECT * FROM tmdb_detail WHERE `key` = :key")
    suspend fun detail(key: String): TmdbDetailEntity?

    @Upsert
    suspend fun putDetail(entity: TmdbDetailEntity)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE `key` = :key")
    suspend fun get(key: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED','DOWNLOADING','SAVING') ORDER BY createdAt")
    suspend fun active(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, error = :error WHERE `key` = :key")
    suspend fun setStatus(key: String, status: String, error: String? = null)

    @Query("UPDATE downloads SET downloadedBytes = :downloaded, totalBytes = :total WHERE `key` = :key")
    suspend fun setProgress(key: String, downloaded: Long, total: Long)

    @Query("UPDATE downloads SET fileId = :fileId WHERE `key` = :key")
    suspend fun setFileId(key: String, fileId: Int)

    @Query("UPDATE downloads SET status = 'COMPLETED', contentUri = :uri, downloadedBytes = totalBytes, error = NULL WHERE `key` = :key")
    suspend fun setCompleted(key: String, uri: String)

    @Query("UPDATE downloads SET status = 'PAUSED' WHERE status IN ('QUEUED','DOWNLOADING','SAVING')")
    suspend fun pauseAllActive()

    @Query("DELETE FROM downloads WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_position WHERE `key` = :key")
    suspend fun get(key: String): PlaybackPositionEntity?

    @Upsert
    suspend fun put(entity: PlaybackPositionEntity)

    @Query("DELETE FROM playback_position WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Database(
    entities = [TmdbMatchEntity::class, TmdbDetailEntity::class, DownloadEntity::class, PlaybackPositionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tmdb(): TmdbDao
    abstract fun downloads(): DownloadDao
    abstract fun playback(): PlaybackDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "tgfinder.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
