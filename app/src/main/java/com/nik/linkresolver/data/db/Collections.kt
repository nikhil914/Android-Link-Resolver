package com.nik.linkresolver.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

object CollectionKind {
    /** The whole history of a channel (can be updated with newer posts). */
    const val CHANNEL = "channel"

    /** Posts/links picked by hand. */
    const val SELECTION = "selection"
}

/** A named, saved set of channel posts, browsable offline and resolvable later. */
@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val chatId: Long,
    val chatTitle: String,
    val createdAt: Long,
    val updatedAt: Long,
    val postCount: Int = 0,
    /** Newest message id stored (for "Update" on whole-channel collections). */
    val newestMessageId: Long = 0,
    /** True while a whole-channel save is still reading history. */
    val saving: Boolean = false,
)

/** One saved post: its text and every URL found in it (support is decided when shown, so new domains count). */
@Entity(
    tableName = "collection_posts",
    indices = [Index(value = ["collectionId", "messageId"], unique = true)],
)
data class CollectionPostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val collectionId: Long,
    val messageId: Long,
    val date: Int,
    val text: String,
    /** URLs separated by newlines. */
    val urls: String,
)

data class CollectionSummary(
    val id: Long,
    val name: String,
    val kind: String,
    val chatTitle: String,
    val createdAt: Long,
    val updatedAt: Long,
    val postCount: Int,
    val saving: Boolean,
    val runs: Int,
    val resolved: Int,
)

@Dao
abstract class CollectionDao {
    @Insert
    abstract suspend fun insert(c: CollectionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPosts(posts: List<CollectionPostEntity>): List<Long>

    @Query("SELECT * FROM collections WHERE id = :id")
    abstract suspend fun get(id: Long): CollectionEntity?

    @Query("SELECT * FROM collections WHERE id = :id")
    abstract fun observe(id: Long): Flow<CollectionEntity?>

    @Query(
        "SELECT c.id, c.name, c.kind, c.chatTitle, c.createdAt, c.updatedAt, c.postCount, c.saving, " +
            "(SELECT COUNT(*) FROM batches b WHERE b.collectionId = c.id) AS runs, " +
            "(SELECT COUNT(DISTINCT r.sourceUrl) FROM resolves r JOIN batches b ON r.batchId = b.id " +
            " WHERE b.collectionId = c.id AND r.status = 'DONE') AS resolved " +
            "FROM collections c ORDER BY c.updatedAt DESC",
    )
    abstract fun observeAll(): Flow<List<CollectionSummary>>

    @Query("SELECT * FROM collection_posts WHERE collectionId = :id ORDER BY messageId DESC")
    abstract fun observePosts(id: Long): Flow<List<CollectionPostEntity>>

    @Query("SELECT * FROM collection_posts WHERE collectionId = :id ORDER BY messageId DESC")
    abstract suspend fun posts(id: Long): List<CollectionPostEntity>

    @Query("SELECT COUNT(*) FROM collection_posts WHERE collectionId = :id")
    abstract suspend fun postCount(id: Long): Int

    @Query("UPDATE collections SET postCount = :count, newestMessageId = MAX(newestMessageId, :newest), updatedAt = :now, saving = :saving WHERE id = :id")
    abstract suspend fun updateProgress(id: Long, count: Int, newest: Long, now: Long, saving: Boolean)

    @Query("UPDATE collections SET name = :name, updatedAt = :now WHERE id = :id")
    abstract suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE collections SET saving = 0 WHERE saving = 1")
    abstract suspend fun clearStaleSaving()

    /** Latest result per link for this collection's runs. */
    @Query(
        "SELECT r.* FROM resolves r WHERE r.id IN (SELECT MAX(r2.id) FROM resolves r2 JOIN batches b ON r2.batchId = b.id " +
            "WHERE b.collectionId = :id GROUP BY r2.sourceUrl) ORDER BY r.messageId DESC, r.id",
    )
    abstract fun observeResults(id: Long): Flow<List<ResolveEntity>>

    @Query("DELETE FROM collection_posts WHERE collectionId = :id")
    protected abstract suspend fun deletePosts(id: Long)

    @Query("DELETE FROM collections WHERE id = :id")
    protected abstract suspend fun deleteRow(id: Long)

    /** Keeps the runs' results in History, just detaches them. */
    @Query("UPDATE batches SET collectionId = NULL WHERE collectionId = :id")
    protected abstract suspend fun detachBatches(id: Long)

    @Transaction
    open suspend fun delete(id: Long) {
        deletePosts(id)
        detachBatches(id)
        deleteRow(id)
    }
}
