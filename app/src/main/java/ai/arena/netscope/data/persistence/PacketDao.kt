package ai.arena.netscope.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PacketDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PacketEntity>)

    @Query("SELECT * FROM captured_packets ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<PacketEntity>

    @Query("SELECT * FROM captured_packets WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentForSession(sessionId: Long, limit: Int): List<PacketEntity>

    @Query("SELECT * FROM captured_packets WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getAllForSession(sessionId: Long): List<PacketEntity>

    @Query("DELETE FROM captured_packets WHERE dbId NOT IN (SELECT dbId FROM captured_packets ORDER BY timestamp DESC LIMIT :maxRows)")
    suspend fun pruneTo(maxRows: Int)

    @Query("DELETE FROM captured_packets")
    suspend fun clearAll()
}
