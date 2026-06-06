package ai.arena.netscope.model

enum class PacketDirection {
    OUTBOUND,
    INBOUND,
    UNKNOWN,
}

enum class PacketProtocol(val label: String) {
    TCP("TCP"),
    UDP("UDP"),
    ICMP("ICMP"),
    DNS("DNS"),
    HTTP("HTTP"),
    IPV6("IPv6"),
    UNKNOWN("UNKNOWN"),
}

data class CapturedPacket(
    val id: Long,
    val timestamp: Long,
    val direction: PacketDirection,
    val ipVersion: Int,
    val protocol: PacketProtocol,
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int? = null,
    val dstPort: Int? = null,
    val length: Int,
    val summary: String,
    val payloadPreview: String,
    val rawHex: String,
    val rawData: ByteArray,
) {
    val sourceLabel: String
        get() = srcIp + (srcPort?.let { ":$it" } ?: "")

    val destinationLabel: String
        get() = dstIp + (dstPort?.let { ":$it" } ?: "")

    val conversationLabel: String
        get() = "$sourceLabel → $destinationLabel"

    val flowKey: String
        get() {
            val left = sourceLabel
            val right = destinationLabel
            val ordered = listOf(left, right).sorted()
            return listOf(protocol.label, ordered[0], ordered[1]).joinToString("|")
        }
}

data class FlowSummary(
    val id: String,
    val protocol: PacketProtocol,
    val endpointA: String,
    val endpointB: String,
    val packetCount: Int,
    val totalBytes: Long,
    val firstSeen: Long,
    val lastSeen: Long,
    val latestSummary: String,
    val latestDirection: PacketDirection,
) {
    val conversationLabel: String
        get() = "$endpointA ↔ $endpointB"
}

data class CaptureSessionSummary(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long?,
    val engineLabel: String,
    val filterSummary: String,
    val packetCount: Long,
    val totalBytes: Long,
    val status: String,
) {
    val isActive: Boolean
        get() = endedAt == null
}

data class CaptureState(
    val isCapturing: Boolean = false,
    val totalPackets: Long = 0,
    val totalBytes: Long = 0,
    val activeFlows: Int = 0,
    val startedAt: Long? = null,
    val statusMessage: String? = null,
    val lastExportPath: String? = null,
)
