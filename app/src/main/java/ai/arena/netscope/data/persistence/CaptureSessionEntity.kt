package ai.arena.netscope.data.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ai.arena.netscope.model.CaptureSessionSummary

@Entity(
    tableName = "capture_sessions",
    indices = [Index(value = ["startedAt"]), Index(value = ["endedAt"])],
)
data class CaptureSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long?,
    val engineLabel: String,
    val filterSummary: String,
    val packetCount: Long,
    val totalBytes: Long,
    val status: String,
)

fun CaptureSessionEntity.toDomain(): CaptureSessionSummary {
    return CaptureSessionSummary(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        engineLabel = engineLabel,
        filterSummary = filterSummary,
        packetCount = packetCount,
        totalBytes = totalBytes,
        status = status,
    )
}
