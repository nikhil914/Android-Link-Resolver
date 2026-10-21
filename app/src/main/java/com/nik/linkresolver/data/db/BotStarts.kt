package com.nik.linkresolver.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

object BotStatus {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val DONE = "DONE"
    const val NO_REPLY = "NO_REPLY"
    const val FAILED = "FAILED"
}

/** A bot start for a final `t.me/<bot>?start=…` link, and what the bot sent back. */
@Entity(tableName = "bot_starts", indices = [Index("finalUrl")])
data class BotStartEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val finalUrl: String,
    val bot: String,
    val status: String,
    val createdAt: Long,
    val finishedAt: Long? = null,
    /** Text of the bot's replies (trimmed). */
    val replyText: String? = null,
    /** URLs in the replies, newline-separated. */
    val replyUrls: String? = null,
    /** Files in the replies, e.g. "Video: ep05.mp4 (1.2 GB)", newline-separated. */
    val replyFiles: String? = null,
    val forwarded: Boolean = false,
    val error: String? = null,
)

@Dao
abstract class BotStartDao {
    @Insert
    abstract suspend fun insert(b: BotStartEntity): Long

    @Query("SELECT * FROM bot_starts WHERE id = :id")
    abstract suspend fun get(id: Long): BotStartEntity?

    /** Latest start per final link. */
    @Query("SELECT * FROM bot_starts WHERE id IN (SELECT MAX(id) FROM bot_starts GROUP BY finalUrl)")
    abstract fun observeLatest(): Flow<List<BotStartEntity>>

    @Query("SELECT * FROM bot_starts WHERE finalUrl = :url ORDER BY id DESC LIMIT 1")
    abstract suspend fun latestFor(url: String): BotStartEntity?

    @Query("SELECT * FROM bot_starts WHERE status = 'PENDING' ORDER BY id LIMIT 1")
    abstract suspend fun nextPending(): BotStartEntity?

    @Query("UPDATE bot_starts SET status = :status WHERE id = :id")
    abstract suspend fun setStatus(id: Long, status: String)

    @Query(
        "UPDATE bot_starts SET status = :status, finishedAt = :now, replyText = :text, replyUrls = :urls, replyFiles = :files, " +
            "forwarded = :forwarded, error = :error WHERE id = :id",
    )
    abstract suspend fun finish(id: Long, status: String, now: Long, text: String?, urls: String?, files: String?, forwarded: Boolean, error: String?)

    @Query("UPDATE bot_starts SET status = 'PENDING' WHERE status = 'RUNNING'")
    abstract suspend fun requeueRunning()
}
