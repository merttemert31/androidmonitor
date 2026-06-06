package ai.arena.netscope.data.persistence

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ai.arena.netscope.model.CapturedPacket
import ai.arena.netscope.model.PacketDirection
import ai.arena.netscope.model.PacketProtocol
import kotlin.math.min

@Entity(
    tableName = "captured_packets",
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["timestamp"]),
        Index(value = ["protocol"]),
        Index(value = ["srcIp"]),
        Index(value = ["dstIp"]),
        Index(value = ["srcPort"]),
        Index(value = ["dstPort"]),
    ],
)
data class PacketEntity(
    @PrimaryKey(autoGenerate = true) val dbId: Long = 0,
    val sessionId: Long?,
    val timestamp: Long,
    val direction: String,
    val ipVersion: Int,
    val protocol: String,
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int?,
    val dstPort: Int?,
    val length: Int,
    val summary: String,
    val payloadPreview: String,
    val rawData: ByteArray,
)

fun PacketEntity.toDomain(): CapturedPacket {
    return CapturedPacket(
        id = dbId,
        timestamp = timestamp,
        direction = runCatching { PacketDirection.valueOf(direction) }.getOrDefault(PacketDirection.UNKNOWN),
        ipVersion = ipVersion,
        protocol = runCatching { PacketProtocol.valueOf(protocol) }.getOrDefault(PacketProtocol.UNKNOWN),
        srcIp = srcIp,
        dstIp = dstIp,
        srcPort = srcPort,
        dstPort = dstPort,
        length = length,
        summary = summary,
        payloadPreview = payloadPreview,
        rawHex = toHex(rawData),
        rawData = rawData,
    )
}

fun CapturedPacket.toEntity(sessionId: Long?): PacketEntity {
    return PacketEntity(
        sessionId = sessionId,
        timestamp = timestamp,
        direction = direction.name,
        ipVersion = ipVersion,
        protocol = protocol.name,
        srcIp = srcIp,
        dstIp = dstIp,
        srcPort = srcPort,
        dstPort = dstPort,
        length = length,
        summary = summary,
        payloadPreview = payloadPreview,
        rawData = rawData,
    )
}

private fun toHex(bytes: ByteArray): String {
    val limit = min(bytes.size, 256)
    val sb = StringBuilder(limit * 3)
    for (index in 0 until limit) {
        if (index > 0 && index % 16 == 0) sb.append('\n')
        sb.append(String.format("%02X ", bytes[index]))
    }
    if (bytes.size > limit) sb.append("\n… truncated …")
    return sb.toString().trimEnd()
}
