package com.example.myapplicationlibretv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.migration.Migration
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {
    @Query("SELECT * FROM favorites ORDER BY timestamp DESC")
    fun getFavorites(): Flow<List<FavoriteVideo>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(video: FavoriteVideo)

    @Query("DELETE FROM favorites WHERE id = :videoId")
    suspend fun deleteFavorite(videoId: Int)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :videoId)")
    fun isFavorite(videoId: Int): Flow<Boolean>

    @Query("SELECT * FROM history ORDER BY timestamp DESC")
    fun getHistory(): Flow<List<HistoryVideo>>

    @Query("SELECT * FROM history WHERE id = :videoId LIMIT 1")
    fun getHistoryById(videoId: Int): Flow<HistoryVideo?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(video: HistoryVideo)

    @Query("DELETE FROM history WHERE id = :videoId")
    suspend fun deleteHistory(videoId: Int)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query(
        """
        UPDATE history
        SET progress = :progress,
            duration = :duration,
            timestamp = :timestamp
        WHERE id = :videoId
        """
    )
    suspend fun updateHistoryProgress(
        videoId: Int,
        progress: Long,
        duration: Long,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun getDownloads(): Flow<List<DownloadVideo>>

    @Query("SELECT * FROM downloads WHERE taskId = :taskId LIMIT 1")
    suspend fun getDownloadById(taskId: String): DownloadVideo?

    @Query("SELECT * FROM downloads WHERE fileUri = :fileUri LIMIT 1")
    suspend fun getDownloadByUri(fileUri: String): DownloadVideo?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadVideo)

    @Query("DELETE FROM downloads WHERE taskId = :taskId")
    suspend fun deleteDownload(taskId: String)
}

@Database(
    entities = [FavoriteVideo::class, HistoryVideo::class, DownloadVideo::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS downloads (
                        taskId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        status TEXT NOT NULL,
                        progressText TEXT NOT NULL,
                        rawUrl TEXT NOT NULL,
                        fileName TEXT,
                        fileUri TEXT,
                        errorMessage TEXT,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE favorites ADD COLUMN siteKey TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE favorites ADD COLUMN sourceVideoId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE favorites SET sourceVideoId = id WHERE sourceVideoId = 0")

                database.execSQL("ALTER TABLE history ADD COLUMN siteKey TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE history ADD COLUMN sourceVideoId INTEGER NOT NULL DEFAULT 0")
                database.execSQL("UPDATE history SET sourceVideoId = id WHERE sourceVideoId = 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "libretv_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
