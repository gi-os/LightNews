package com.gios.lightnews.data

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
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * One newsletter. The HTML itself is not in here — an issue of a design newsletter
 * runs to a few hundred kilobytes and a hundred of those would make every query on
 * this table drag. Bodies are files under filesDir/bodies, keyed by message id.
 */
@Entity(tableName = "newsletters")
data class NewsletterEntity(
    @PrimaryKey val id: String,
    val threadId: String,
    val fromName: String,
    val fromEmail: String,
    val subject: String,
    val snippet: String,
    val dateMs: Long,
    val unread: Boolean,
    val hasHtml: Boolean,
    /** Marked read on the phone but not yet accepted by Gmail. */
    val pendingRead: Boolean = false,
    val fetchedAt: Long = System.currentTimeMillis(),
)

@Dao
interface NewsDao {
    @Query("SELECT * FROM newsletters ORDER BY dateMs DESC")
    fun observeAll(): Flow<List<NewsletterEntity>>

    @Query("SELECT * FROM newsletters WHERE id = :id LIMIT 1")
    suspend fun get(id: String): NewsletterEntity?

    @Query("SELECT id FROM newsletters")
    suspend fun allIds(): List<String>

    @Query("SELECT * FROM newsletters ORDER BY dateMs DESC")
    suspend fun all(): List<NewsletterEntity>

    @Query("SELECT * FROM newsletters WHERE pendingRead = 1")
    suspend fun pendingReads(): List<NewsletterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putOne(row: NewsletterEntity)

    /**
     * Write a freshly fetched message without losing a read the user has just made.
     *
     * Has to be one transaction: reads are marked outside the sync lock, so a plain
     * read-then-REPLACE can be overtaken between the two statements, and the row comes
     * back unread with its pendingRead flag gone — meaning the read is never pushed.
     */
    @Transaction
    suspend fun upsertKeepingRead(row: NewsletterEntity) {
        val previous = get(row.id)
        putOne(
            if (previous?.pendingRead == true) {
                row.copy(unread = false, pendingRead = true)
            } else {
                row
            },
        )
    }

    @Query("UPDATE newsletters SET unread = :unread, pendingRead = :pending WHERE id = :id")
    suspend fun setRead(id: String, unread: Boolean, pending: Boolean)

    /**
     * Read-state reconciliation, applied only to ids the caller actually observed.
     *
     * pendingRead rows are excluded from both directions: those carry a read that Gmail
     * has not accepted yet, and letting the server's stale answer win would erase it.
     */
    @Query("UPDATE newsletters SET unread = 0 WHERE pendingRead = 0 AND id IN (:ids)")
    suspend fun markReadIn(ids: List<String>)

    @Query("UPDATE newsletters SET unread = 1 WHERE pendingRead = 0 AND id IN (:ids)")
    suspend fun markUnreadIn(ids: List<String>)

    @Query("DELETE FROM newsletters")
    suspend fun deleteAll()

    @Query("DELETE FROM newsletters WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("SELECT COUNT(*) FROM newsletters WHERE unread = 1")
    fun observeUnreadCount(): Flow<Int>
}

@Database(entities = [NewsletterEntity::class], version = 1, exportSchema = false)
abstract class NewsDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao

    companion object {
        @Volatile
        private var instance: NewsDatabase? = null

        fun get(context: Context): NewsDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NewsDatabase::class.java,
                "lightnews.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
