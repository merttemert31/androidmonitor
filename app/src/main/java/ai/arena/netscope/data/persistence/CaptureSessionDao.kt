package ai.arena.netscope.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CaptureSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: CaptureSessionEntity): Long

    @Query("SELECT * FROM capture_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<CaptureSessionEntity>

    @Query("SELECT * FROM capture_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CaptureSessionEntity?

    @Query("UPDATE capture_sessions SET packetCount = :packetCount, totalBytes = :totalBytes WHERE id = :sessionId")
    suspend fun updateStats(sessionId: Long, packetCount: Long, totalBytes: Long)

    @Query("UPDATE capture_sessions SET endedAt = :endedAt, status = :status WHERE id = :sessionId")
    suspend fun closeSession(sessionId: Long, endedAt: Long, status: String)

    @Query("DELETE FROM capture_sessions")
    suspend fun clearAll()
}
