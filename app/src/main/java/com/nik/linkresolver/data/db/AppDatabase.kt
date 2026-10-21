package com.nik.linkresolver.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
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

object Status {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}

object Source {
    const val PASTE = "paste"
    const val SHARE = "share"
    const val LINK = "link"
    const val TELEGRAM = "telegram"
    const val CLIPBOARD = "clipboard"
    const val WATCH = "watch"
    const val COLLECTION = "collection"
}

@Entity(tableName = "batches")
data class BatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val source: String,
    val label: String,
    /** Set when the batch processed a saved collection. */
    val collectionId: Long? = null,
)

@Entity(
    tableName = "resolves",
    indices = [Index("batchId"), Index("sourceUrl"), Index("status")],
)
data class ResolveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val batchId: Long?,
    val sourceUrl: String,
    val finalUrl: String? = null,
    val status: String,
    val error: String? = null,
    val errorKind: String? = null,
    val site: String,
    val source: String,
    /** Telegram chat/channel the link came from (Phase 3). */
    val channel: String? = null,
    val messageId: Long? = null,
    val createdAt: Long,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val durationMs: Long? = null,
    /** Result copied from an earlier successful resolve of the same link instead of resolving again. */
    val fromCache: Boolean = false,
    /** Approximate bytes transferred for this resolve (null for older rows). */
    val bytes: Long? = null,
)

/** Debug log per resolve, kept out of the list queries. */
@Entity(tableName = "resolve_logs")
data class ResolveLogEntity(@PrimaryKey val resolveId: Long, val text: String)

data class BatchSummary(
    val id: Long,
    val createdAt: Long,
    val source: String,
    val label: String,
    val total: Int,
    val done: Int,
    val failed: Int,
    val pending: Int,
)

@Dao
abstract class ResolveDao {
    @Insert
    abstract suspend fun insertBatch(batch: BatchEntity): Long

    @Insert
    abstract suspend fun insertAll(items: List<ResolveEntity>): List<Long>

    @Insert
    abstract suspend fun insert(item: ResolveEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putLog(log: ResolveLogEntity)

    @Query("SELECT text FROM resolve_logs WHERE resolveId = :id")
    abstract suspend fun log(id: Long): String?

    @Query("SELECT * FROM resolves WHERE status = 'QUEUED' ORDER BY id LIMIT 1")
    protected abstract suspend fun firstQueued(): ResolveEntity?

    @Query("UPDATE resolves SET status = 'RUNNING', startedAt = :now WHERE id = :id AND status = 'QUEUED'")
    protected abstract suspend fun markRunning(id: Long, now: Long): Int

    @Transaction
    open suspend fun claimNext(now: Long): ResolveEntity? {
        repeat(5) {
            val next = firstQueued() ?: return null
            if (markRunning(next.id, now) == 1) return next.copy(status = Status.RUNNING, startedAt = now)
        }
        return null
    }

    @Query(
        "UPDATE resolves SET status = :status, finalUrl = :finalUrl, error = :error, errorKind = :errorKind, " +
            "finishedAt = :finishedAt, durationMs = :durationMs, bytes = :bytes WHERE id = :id",
    )
    abstract suspend fun finish(
        id: Long, status: String, finalUrl: String?, error: String?, errorKind: String?, finishedAt: Long, durationMs: Long?, bytes: Long? = null,
    )

    /** After a crash/kill, links left RUNNING go back to the queue. */
    @Query("UPDATE resolves SET status = 'QUEUED', startedAt = NULL WHERE status = 'RUNNING'")
    abstract suspend fun requeueRunning(): Int

    @Query("UPDATE resolves SET status = 'CANCELLED', finishedAt = :now WHERE status = 'QUEUED' AND (:batchId IS NULL OR batchId = :batchId)")
    abstract suspend fun cancelQueued(batchId: Long?, now: Long): Int

    @Query("SELECT id FROM resolves WHERE status = 'RUNNING' AND (:batchId IS NULL OR batchId = :batchId)")
    abstract suspend fun runningIds(batchId: Long?): List<Long>

    @Query(
        "UPDATE resolves SET status = 'QUEUED', error = NULL, errorKind = NULL, finalUrl = NULL, startedAt = NULL, " +
            "finishedAt = NULL, durationMs = NULL, fromCache = 0 WHERE batchId = :batchId AND status IN ('FAILED', 'CANCELLED')",
    )
    abstract suspend fun requeueFailed(batchId: Long): Int

    @Query(
        "UPDATE resolves SET status = 'QUEUED', error = NULL, errorKind = NULL, finalUrl = NULL, startedAt = NULL, " +
            "finishedAt = NULL, durationMs = NULL, fromCache = 0 WHERE id = :id AND status NOT IN ('QUEUED', 'RUNNING')",
    )
    abstract suspend fun requeue(id: Long): Int

    @Query("SELECT * FROM resolves WHERE sourceUrl = :url AND status = 'DONE' ORDER BY id DESC LIMIT 1")
    abstract suspend fun lastDone(url: String): ResolveEntity?

    /** Latest row per source URL among [urls] (any status), for "already resolved" badges. */
    @Query(
        "SELECT * FROM resolves WHERE id IN (SELECT MAX(id) FROM resolves WHERE sourceUrl IN (:urls) GROUP BY sourceUrl)",
    )
    abstract fun observeLatestFor(urls: List<String>): Flow<List<ResolveEntity>>

    /** Most recent link that really resolved (not copied from history) for a site — the health check's test link. */
    @Query("SELECT * FROM resolves WHERE status = 'DONE' AND fromCache = 0 AND site = :site ORDER BY id DESC LIMIT 1")
    abstract suspend fun lastSuccessForSite(site: String): ResolveEntity?

    /** True if this link was ever queued or resolved (any status except cancelled). */
    @Query("SELECT EXISTS(SELECT 1 FROM resolves WHERE sourceUrl = :url AND status != 'CANCELLED')")
    abstract suspend fun known(url: String): Boolean

    @Query("SELECT * FROM resolves WHERE batchId = :batchId ORDER BY id")
    abstract suspend fun batchRows(batchId: Long): List<ResolveEntity>

    @Query("SELECT COUNT(*) FROM resolves WHERE status = 'QUEUED'")
    abstract suspend fun queuedCount(): Int

    // ---- stats (real resolves only, not results copied from history)

    @Query(
        "SELECT site, COUNT(*) AS total, SUM(status = 'DONE') AS done, SUM(status = 'FAILED') AS failed, " +
            "AVG(CASE WHEN status = 'DONE' THEN durationMs END) AS avgMs, SUM(bytes) AS bytes " +
            "FROM resolves WHERE fromCache = 0 AND status IN ('DONE', 'FAILED') GROUP BY site ORDER BY total DESC",
    )
    abstract fun observeSiteStats(): Flow<List<SiteStat>>

    @Query("SELECT errorKind, COUNT(*) AS n FROM resolves WHERE status = 'FAILED' GROUP BY errorKind ORDER BY n DESC")
    abstract fun observeFailureKinds(): Flow<List<KindCount>>

    @Query(
        "SELECT date(finishedAt / 1000, 'unixepoch', 'localtime') AS day, SUM(status = 'DONE') AS done, SUM(status = 'FAILED') AS failed " +
            "FROM resolves WHERE fromCache = 0 AND finishedAt >= :since AND status IN ('DONE', 'FAILED') GROUP BY day ORDER BY day DESC",
    )
    abstract fun observeDays(since: Long): Flow<List<DayCount>>

    @Query(
        "SELECT SUM(status = 'QUEUED') AS queued, SUM(status = 'RUNNING') AS running, " +
            "SUM(status = 'DONE' AND finishedAt >= :since) AS done, SUM(status = 'FAILED' AND finishedAt >= :since) AS failed " +
            "FROM resolves",
    )
    abstract suspend fun counts(since: Long): QueueCounts

    @Query("SELECT * FROM resolves WHERE batchId = :batchId ORDER BY id")
    abstract fun observeBatch(batchId: Long): Flow<List<ResolveEntity>>

    @Query("SELECT * FROM batches WHERE id = :id")
    abstract fun observeBatchInfo(id: Long): Flow<BatchEntity?>

    @Query(
        "SELECT b.id, b.createdAt, b.source, b.label, COUNT(r.id) AS total, " +
            "COALESCE(SUM(r.status = 'DONE'), 0) AS done, COALESCE(SUM(r.status IN ('FAILED', 'CANCELLED')), 0) AS failed, " +
            "COALESCE(SUM(r.status IN ('QUEUED', 'RUNNING')), 0) AS pending " +
            "FROM batches b LEFT JOIN resolves r ON r.batchId = b.id GROUP BY b.id ORDER BY b.id DESC LIMIT :limit",
    )
    abstract fun observeBatches(limit: Int = 30): Flow<List<BatchSummary>>

    @Query(
        "SELECT * FROM resolves WHERE (:q = '' OR sourceUrl LIKE '%' || :q || '%' OR finalUrl LIKE '%' || :q || '%' " +
            "OR channel LIKE '%' || :q || '%') AND (:status = '' OR status = :status) ORDER BY id DESC LIMIT 1000",
    )
    abstract fun observeHistory(q: String, status: String): Flow<List<ResolveEntity>>

    @Query("DELETE FROM resolves WHERE batchId = :batchId AND status NOT IN ('QUEUED', 'RUNNING')")
    protected abstract suspend fun deleteBatchRows(batchId: Long)

    @Query("DELETE FROM batches WHERE id = :batchId AND NOT EXISTS (SELECT 1 FROM resolves WHERE batchId = :batchId)")
    protected abstract suspend fun deleteEmptyBatch(batchId: Long)

    @Query("DELETE FROM resolve_logs WHERE resolveId NOT IN (SELECT id FROM resolves)")
    protected abstract suspend fun deleteOrphanLogs()

    @Transaction
    open suspend fun deleteBatch(batchId: Long) {
        deleteBatchRows(batchId)
        deleteEmptyBatch(batchId)
        deleteOrphanLogs()
    }
}

data class SiteStat(val site: String, val total: Int, val done: Int, val failed: Int, val avgMs: Double?, val bytes: Long?)
data class KindCount(val errorKind: String?, val n: Int)
data class DayCount(val day: String, val done: Int, val failed: Int)

data class QueueCounts(val queued: Int?, val running: Int?, val done: Int?, val failed: Int?)

@Database(
    entities = [
        BatchEntity::class, ResolveEntity::class, ResolveLogEntity::class, CollectionEntity::class, CollectionPostEntity::class,
        BotStartEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun resolves(): ResolveDao
    abstract fun collections(): CollectionDao
    abstract fun botStarts(): BotStartDao

    companion object {
        /** v2: saved collections (whole channels / selections) and batches linked to a collection. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Statements copied from Room's generated schema so validation matches exactly.
                db.execSQL("ALTER TABLE `batches` ADD COLUMN `collectionId` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `chatId` INTEGER NOT NULL, `chatTitle` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, `postCount` INTEGER NOT NULL, `newestMessageId` INTEGER NOT NULL, `saving` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `collection_posts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`collectionId` INTEGER NOT NULL, `messageId` INTEGER NOT NULL, `date` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                        "`urls` TEXT NOT NULL)",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_collection_posts_collectionId_messageId` ON `collection_posts` (`collectionId`, `messageId`)",
                )
            }
        }

        /** v3: bot starts for final t.me bot links; bytes used per resolve. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `resolves` ADD COLUMN `bytes` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `bot_starts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `finalUrl` TEXT NOT NULL, " +
                        "`bot` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `finishedAt` INTEGER, `replyText` TEXT, " +
                        "`replyUrls` TEXT, `replyFiles` TEXT, `forwarded` INTEGER NOT NULL, `error` TEXT)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bot_starts_finalUrl` ON `bot_starts` (`finalUrl`)")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "link-resolver.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
