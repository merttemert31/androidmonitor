package ai.arena.netscope.data.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PacketEntity::class, CaptureSessionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class PacketDatabase : RoomDatabase() {
    abstract fun packetDao(): PacketDao
    abstract fun captureSessionDao(): CaptureSessionDao
}
