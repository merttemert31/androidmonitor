package ai.arena.netscope.service

import java.net.InetAddress
import kotlin.math.min

object RawPacketBuilder {
    fun buildIpv4UdpPacket(
        srcIp: String,
        dstIp: String,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val ipHeaderLength = 20
        val udpHeaderLength = 8
        val totalLength = ipHeaderLength + udpHeaderLength + payload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45
        packet[1] = 0x00
        writeU16(packet, 2, totalLength)
        writeU16(packet, 4, 0)
        writeU16(packet, 6, 0)
        packet[8] = 64
        packet[9] = 17

        val src = InetAddress.getByName(srcIp).address
        val dst = InetAddress.getByName(dstIp).address
        require(src.size == 4 && dst.size == 4) { "IPv4 address required" }
        src.copyInto(packet, 12)
        dst.copyInto(packet, 16)

        writeU16(packet, ipHeaderLength, srcPort)
        writeU16(packet, ipHeaderLength + 2, dstPort)
        writeU16(packet, ipHeaderLength + 4, udpHeaderLength + payload.size)
        writeU16(packet, ipHeaderLength + 6, 0)
        payload.copyInto(packet, ipHeaderLength + udpHeaderLength)

        val ipChecksum = ipv4HeaderChecksum(packet, 0, ipHeaderLength)
        writeU16(packet, 10, ipChecksum)

        val udpChecksum = udpChecksum(packet, src, dst, ipHeaderLength, udpHeaderLength + payload.size)
        writeU16(packet, ipHeaderLength + 6, udpChecksum)

        return packet
    }

    fun describe(bytes: ByteArray): String {
        val previewLength = min(bytes.size, 24)
        return bytes.take(previewLength).joinToString(" ") { "%02X".format(it) }
    }

    private fun ipv4HeaderChecksum(packet: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        while (index < offset + length) {
            if (index == offset + 10) {
                index += 2
                continue
            }
            sum += readU16(packet, index).toLong()
            index += 2
        }
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    private fun udpChecksum(packet: ByteArray, src: ByteArray, dst: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0L

        sum += readU16(src, 0).toLong()
        sum += readU16(src, 2).toLong()
        sum += readU16(dst, 0).toLong()
        sum += readU16(dst, 2).toLong()
        sum += 17
        sum += udpLength

        var index = udpOffset
        while (index < udpOffset + udpLength) {
            val high = packet[index].toInt() and 0xFF
            val low = if (index + 1 < udpOffset + udpLength) packet[index + 1].toInt() and 0xFF else 0
            sum += ((high shl 8) or low).toLong()
            index += 2
        }

        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        val checksum = sum.inv().toInt() and 0xFFFF
        return if (checksum == 0) 0xFFFF else checksum
    }

    private fun writeU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = ((value ushr 8) and 0xFF).toByte()
        buffer[offset + 1] = (value and 0xFF).toByte()
    }

    private fun readU16(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }
}
