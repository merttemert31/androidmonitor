package ai.arena.netscope.parser

import ai.arena.netscope.model.CapturedPacket
import ai.arena.netscope.model.PacketDirection
import ai.arena.netscope.model.PacketProtocol
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

data class UdpDatagram(
    val ipVersion: Int,
    val srcIp: String,
    val dstIp: String,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

object PacketParser {
    private val nextId = AtomicLong(1)

    fun ensureNextPacketIdAtLeast(nextValue: Long) {
        while (true) {
            val current = nextId.get()
            if (current >= nextValue) return
            if (nextId.compareAndSet(current, nextValue)) return
        }
    }

    fun parse(packetBytes: ByteArray, direction: PacketDirection = PacketDirection.OUTBOUND): CapturedPacket? {
        if (packetBytes.isEmpty()) return null

        val version = (packetBytes[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> parseIpv4(packetBytes, direction)
            6 -> parseIpv6(packetBytes, direction)
            else -> null
        }
    }

    fun peekTransportProtocol(packetBytes: ByteArray): Int? {
        if (packetBytes.isEmpty()) return null
        return when ((packetBytes[0].toInt() ushr 4) and 0x0F) {
            4 -> packetBytes.getOrNull(9)?.let { it.toInt() and 0xFF }
            6 -> packetBytes.getOrNull(6)?.let { it.toInt() and 0xFF }
            else -> null
        }
    }

    fun extractUdpDatagram(packetBytes: ByteArray): UdpDatagram? {
        if (packetBytes.size < 28) return null
        val version = (packetBytes[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null

        val headerLength = (packetBytes[0].toInt() and 0x0F) * 4
        if (packetBytes.size < headerLength + 8) return null
        if ((packetBytes[9].toInt() and 0xFF) != 17) return null

        val totalLength = readU16(packetBytes, 2).coerceAtMost(packetBytes.size)
        val srcIp = ipv4(packetBytes, 12)
        val dstIp = ipv4(packetBytes, 16)
        val srcPort = readU16(packetBytes, headerLength)
        val dstPort = readU16(packetBytes, headerLength + 2)
        val udpLength = readU16(packetBytes, headerLength + 4)
        val payloadStart = headerLength + 8
        val payloadEnd = min(totalLength, headerLength + udpLength)
        if (payloadEnd < payloadStart) return null

        return UdpDatagram(
            ipVersion = 4,
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            payload = packetBytes.copyOfRange(payloadStart, payloadEnd),
        )
    }

    private fun parseIpv4(packetBytes: ByteArray, direction: PacketDirection): CapturedPacket? {
        if (packetBytes.size < 20) return null

        val headerLength = (packetBytes[0].toInt() and 0x0F) * 4
        if (packetBytes.size < headerLength) return null

        val totalLength = readU16(packetBytes, 2).coerceAtMost(packetBytes.size)
        val protocolNumber = packetBytes[9].toInt() and 0xFF
        val srcIp = ipv4(packetBytes, 12)
        val dstIp = ipv4(packetBytes, 16)
        val payloadOffset = headerLength
        val payload = packetBytes.copyOfRange(payloadOffset, totalLength)

        return buildTransportPacket(
            packetBytes = packetBytes.copyOf(totalLength),
            direction = direction,
            ipVersion = 4,
            srcIp = srcIp,
            dstIp = dstIp,
            protocolNumber = protocolNumber,
            transportOffset = headerLength,
            payload = payload,
            packetLength = totalLength,
        )
    }

    private fun parseIpv6(packetBytes: ByteArray, direction: PacketDirection): CapturedPacket? {
        if (packetBytes.size < 40) return null

        val payloadLength = readU16(packetBytes, 4)
        val protocolNumber = packetBytes[6].toInt() and 0xFF
        val totalLength = min(packetBytes.size, payloadLength + 40)
        val srcIp = ipv6(packetBytes.copyOfRange(8, 24))
        val dstIp = ipv6(packetBytes.copyOfRange(24, 40))
        val payload = packetBytes.copyOfRange(40, totalLength)

        return buildTransportPacket(
            packetBytes = packetBytes.copyOf(totalLength),
            direction = direction,
            ipVersion = 6,
            srcIp = srcIp,
            dstIp = dstIp,
            protocolNumber = protocolNumber,
            transportOffset = 40,
            payload = payload,
            packetLength = totalLength,
        )
    }

    private fun buildTransportPacket(
        packetBytes: ByteArray,
        direction: PacketDirection,
        ipVersion: Int,
        srcIp: String,
        dstIp: String,
        protocolNumber: Int,
        transportOffset: Int,
        payload: ByteArray,
        packetLength: Int,
    ): CapturedPacket {
        var srcPort: Int? = null
        var dstPort: Int? = null
        var protocol = PacketProtocol.UNKNOWN
        var summary = "IP packet"
        var payloadPreview = asciiPreview(payload)

        when (protocolNumber) {
            6 -> {
                protocol = PacketProtocol.TCP
                if (packetBytes.size >= transportOffset + 20) {
                    srcPort = readU16(packetBytes, transportOffset)
                    dstPort = readU16(packetBytes, transportOffset + 2)
                    val dataOffset = ((packetBytes[transportOffset + 12].toInt() ushr 4) and 0x0F) * 4
                    val appPayloadOffset = (transportOffset + dataOffset).coerceAtMost(packetBytes.size)
                    val appPayload = packetBytes.copyOfRange(appPayloadOffset, packetBytes.size)
                    if (srcPort == 53 || dstPort == 53) {
                        protocol = PacketProtocol.DNS
                        summary = dnsSummary(appPayload, srcIp, dstIp)
                    } else if (srcPort == 80 || dstPort == 80 || srcPort == 8080 || dstPort == 8080) {
                        val http = httpSummary(appPayload)
                        if (http != null) {
                            protocol = PacketProtocol.HTTP
                            summary = http
                        } else {
                            summary = "TCP $srcPort → $dstPort"
                        }
                    } else {
                        summary = "TCP $srcPort → $dstPort"
                    }
                    payloadPreview = asciiPreview(appPayload)
                } else {
                    summary = "TCP (truncated)"
                }
            }

            17 -> {
                protocol = PacketProtocol.UDP
                if (packetBytes.size >= transportOffset + 8) {
                    srcPort = readU16(packetBytes, transportOffset)
                    dstPort = readU16(packetBytes, transportOffset + 2)
                    val appPayload = packetBytes.copyOfRange((transportOffset + 8).coerceAtMost(packetBytes.size), packetBytes.size)
                    if (srcPort == 53 || dstPort == 53) {
                        protocol = PacketProtocol.DNS
                        summary = dnsSummary(appPayload, srcIp, dstIp)
                    } else {
                        summary = "UDP $srcPort → $dstPort"
                    }
                    payloadPreview = asciiPreview(appPayload)
                } else {
                    summary = "UDP (truncated)"
                }
            }

            1, 58 -> {
                protocol = PacketProtocol.ICMP
                summary = if (protocolNumber == 58) "ICMPv6" else "ICMP"
            }

            else -> {
                protocol = if (ipVersion == 6) PacketProtocol.IPV6 else PacketProtocol.UNKNOWN
                summary = "IP protocol=$protocolNumber"
            }
        }

        return CapturedPacket(
            id = nextId.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            direction = direction,
            ipVersion = ipVersion,
            protocol = protocol,
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            length = packetLength,
            summary = summary,
            payloadPreview = payloadPreview,
            rawHex = toHex(packetBytes),
            rawData = packetBytes,
        )
    }

    private fun dnsSummary(payload: ByteArray, srcIp: String, dstIp: String): String {
        if (payload.size < 12) return "DNS $srcIp → $dstIp"

        val flags = readU16(payload, 2)
        val isResponse = (flags and 0x8000) != 0
        val qdCount = readU16(payload, 4)
        val name = if (qdCount > 0) parseDnsName(payload, 12) else null
        val kind = if (isResponse) "DNS response" else "DNS query"
        return listOfNotNull(kind, name).joinToString(": ")
    }

    private fun parseDnsName(payload: ByteArray, startOffset: Int): String? {
        val labels = mutableListOf<String>()
        var cursor = startOffset
        var safety = 0

        while (cursor < payload.size && safety < 64) {
            val len = payload[cursor].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) return labels.takeIf { it.isNotEmpty() }?.joinToString(".")
            cursor += 1
            if (cursor + len > payload.size) return null
            labels += payload.copyOfRange(cursor, cursor + len).toString(StandardCharsets.UTF_8)
            cursor += len
            safety += 1
        }

        return labels.takeIf { it.isNotEmpty() }?.joinToString(".")
    }

    private fun httpSummary(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val text = payload.copyOfRange(0, min(payload.size, 128)).toString(StandardCharsets.US_ASCII)
        val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
        return if (
            firstLine.startsWith("GET ") ||
            firstLine.startsWith("POST ") ||
            firstLine.startsWith("PUT ") ||
            firstLine.startsWith("DELETE ") ||
            firstLine.startsWith("HTTP/")
        ) {
            firstLine
        } else {
            null
        }
    }

    private fun asciiPreview(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val preview = buildString {
            bytes.take(64).forEach { byte ->
                val c = byte.toInt().toChar()
                append(if (c.code in 32..126) c else '.')
            }
        }
        return preview.trim()
    }

    private fun ipv4(bytes: ByteArray, offset: Int): String {
        return listOf(
            bytes[offset].toInt() and 0xFF,
            bytes[offset + 1].toInt() and 0xFF,
            bytes[offset + 2].toInt() and 0xFF,
            bytes[offset + 3].toInt() and 0xFF,
        ).joinToString(".")
    }

    private fun ipv6(bytes: ByteArray): String = InetAddress.getByAddress(bytes).hostAddress ?: "::"

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
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
}
