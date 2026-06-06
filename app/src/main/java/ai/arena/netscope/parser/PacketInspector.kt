package ai.arena.netscope.parser

import ai.arena.netscope.model.CapturedPacket
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import kotlin.math.min

data class PacketField(
    val label: String,
    val value: String,
)

data class PacketSection(
    val title: String,
    val fields: List<PacketField>,
)

object PacketInspector {
    fun inspect(packet: CapturedPacket): List<PacketSection> {
        val bytes = packet.rawData
        if (bytes.isEmpty()) return emptyList()
        val version = (bytes[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> inspectIpv4(packet, bytes)
            6 -> inspectIpv6(packet, bytes)
            else -> listOf(PacketSection("Unknown", listOf(PacketField("Reason", "Unsupported IP version"))))
        }
    }

    private fun inspectIpv4(packet: CapturedPacket, bytes: ByteArray): List<PacketSection> {
        if (bytes.size < 20) return emptyList()
        val ihl = (bytes[0].toInt() and 0x0F) * 4
        val totalLength = readU16(bytes, 2)
        val identification = readU16(bytes, 4)
        val flagsAndFragment = readU16(bytes, 6)
        val ttl = bytes[8].toInt() and 0xFF
        val protocol = bytes[9].toInt() and 0xFF
        val checksum = readU16(bytes, 10)

        val sections = mutableListOf<PacketSection>()
        sections += PacketSection(
            title = "IP header",
            fields = listOf(
                PacketField("Version", "IPv4"),
                PacketField("Header length", "$ihl byte"),
                PacketField("Total length", "$totalLength byte"),
                PacketField("Identification", identification.toString()),
                PacketField("Flags/Fragment", "0x${flagsAndFragment.toString(16)}"),
                PacketField("TTL", ttl.toString()),
                PacketField("Protocol number", protocol.toString()),
                PacketField("Checksum", "0x${checksum.toString(16)}"),
                PacketField("Source", packet.sourceLabel),
                PacketField("Destination", packet.destinationLabel),
            ),
        )
        inspectTransport(packet, bytes, ihl)?.let(sections::add)
        inspectApplication(packet, bytes, ihl)?.let(sections::add)
        return sections
    }

    private fun inspectIpv6(packet: CapturedPacket, bytes: ByteArray): List<PacketSection> {
        if (bytes.size < 40) return emptyList()
        val payloadLength = readU16(bytes, 4)
        val nextHeader = bytes[6].toInt() and 0xFF
        val hopLimit = bytes[7].toInt() and 0xFF

        val sections = mutableListOf<PacketSection>()
        sections += PacketSection(
            title = "IP header",
            fields = listOf(
                PacketField("Version", "IPv6"),
                PacketField("Payload length", "$payloadLength byte"),
                PacketField("Next header", nextHeader.toString()),
                PacketField("Hop limit", hopLimit.toString()),
                PacketField("Source", packet.sourceLabel),
                PacketField("Destination", packet.destinationLabel),
            ),
        )
        inspectTransport(packet, bytes, 40)?.let(sections::add)
        inspectApplication(packet, bytes, 40)?.let(sections::add)
        return sections
    }

    private fun inspectTransport(packet: CapturedPacket, bytes: ByteArray, offset: Int): PacketSection? {
        if (offset >= bytes.size) return null
        return when (packet.protocol.label) {
            "TCP", "HTTP" -> inspectTcp(bytes, offset)
            "UDP", "DNS" -> inspectUdp(bytes, offset)
            "ICMP" -> inspectIcmp(bytes, offset)
            else -> null
        }
    }

    private fun inspectApplication(packet: CapturedPacket, bytes: ByteArray, offset: Int): PacketSection? {
        return when (packet.protocol.label) {
            "TCP", "HTTP" -> inspectTcpApplication(bytes, offset)
            "UDP", "DNS" -> inspectUdpApplication(bytes, offset)
            else -> null
        }
    }

    private fun inspectTcp(bytes: ByteArray, offset: Int): PacketSection? {
        if (bytes.size < offset + 20) return null
        val srcPort = readU16(bytes, offset)
        val dstPort = readU16(bytes, offset + 2)
        val seq = readU32(bytes, offset + 4)
        val ack = readU32(bytes, offset + 8)
        val dataOffset = ((bytes[offset + 12].toInt() ushr 4) and 0x0F) * 4
        val flags = bytes[offset + 13].toInt() and 0xFF
        val window = readU16(bytes, offset + 14)
        val checksum = readU16(bytes, offset + 16)

        return PacketSection(
            "Transport",
            listOf(
                PacketField("Type", "TCP"),
                PacketField("Source port", srcPort.toString()),
                PacketField("Destination port", dstPort.toString()),
                PacketField("Sequence", seq.toString()),
                PacketField("Ack", ack.toString()),
                PacketField("Header length", "$dataOffset byte"),
                PacketField("Flags", tcpFlags(flags)),
                PacketField("Window", window.toString()),
                PacketField("Checksum", "0x${checksum.toString(16)}"),
            ),
        )
    }

    private fun inspectTcpApplication(bytes: ByteArray, offset: Int): PacketSection? {
        if (bytes.size < offset + 20) return null
        val dataOffset = ((bytes[offset + 12].toInt() ushr 4) and 0x0F) * 4
        val payloadOffset = (offset + dataOffset).coerceAtMost(bytes.size)
        val payload = bytes.copyOfRange(payloadOffset, bytes.size)
        if (payload.isEmpty()) return null

        parseTls(payload)?.let { return it }

        httpPreview(payload)?.let {
            return PacketSection(
                "Application",
                listOf(
                    PacketField("Protocol", "HTTP"),
                    PacketField("First line", it),
                ),
            )
        }

        return PacketSection(
            "Application",
            listOf(
                PacketField("Payload length", payload.size.toString()),
                PacketField("ASCII preview", asciiPreview(payload)),
            ),
        )
    }

    private fun inspectUdp(bytes: ByteArray, offset: Int): PacketSection? {
        if (bytes.size < offset + 8) return null
        val srcPort = readU16(bytes, offset)
        val dstPort = readU16(bytes, offset + 2)
        val length = readU16(bytes, offset + 4)
        val checksum = readU16(bytes, offset + 6)
        return PacketSection(
            "Transport",
            listOf(
                PacketField("Type", "UDP"),
                PacketField("Source port", srcPort.toString()),
                PacketField("Destination port", dstPort.toString()),
                PacketField("Length", length.toString()),
                PacketField("Checksum", "0x${checksum.toString(16)}"),
            ),
        )
    }

    private fun inspectUdpApplication(bytes: ByteArray, offset: Int): PacketSection? {
        if (bytes.size < offset + 8) return null
        val payload = bytes.copyOfRange((offset + 8).coerceAtMost(bytes.size), bytes.size)
        if (payload.isEmpty()) return null
        parseDns(payload)?.let { return it }
        return PacketSection(
            "Application",
            listOf(
                PacketField("Payload length", payload.size.toString()),
                PacketField("ASCII preview", asciiPreview(payload)),
            ),
        )
    }

    private fun inspectIcmp(bytes: ByteArray, offset: Int): PacketSection? {
        if (bytes.size < offset + 4) return null
        val type = bytes[offset].toInt() and 0xFF
        val code = bytes[offset + 1].toInt() and 0xFF
        val checksum = readU16(bytes, offset + 2)
        return PacketSection(
            "Transport",
            listOf(
                PacketField("Type", "ICMP"),
                PacketField("ICMP type", type.toString()),
                PacketField("ICMP code", code.toString()),
                PacketField("Checksum", "0x${checksum.toString(16)}"),
            ),
        )
    }

    private fun parseDns(payload: ByteArray): PacketSection? {
        if (payload.size < 12) return null
        val transactionId = readU16(payload, 0)
        val flags = readU16(payload, 2)
        val qr = if ((flags and 0x8000) != 0) "Response" else "Query"
        val opcode = (flags ushr 11) and 0x0F
        val rcode = flags and 0x0F
        val qdCount = readU16(payload, 4)
        val anCount = readU16(payload, 6)
        val nsCount = readU16(payload, 8)
        val arCount = readU16(payload, 10)

        val fields = mutableListOf(
            PacketField("Protocol", "DNS"),
            PacketField("Transaction ID", transactionId.toString()),
            PacketField("QR", qr),
            PacketField("Opcode", opcode.toString()),
            PacketField("RCode", dnsRcode(rcode)),
            PacketField("Questions", qdCount.toString()),
            PacketField("Answers", anCount.toString()),
            PacketField("Authority RRs", nsCount.toString()),
            PacketField("Additional RRs", arCount.toString()),
        )

        var cursor = 12
        repeat(min(qdCount, 3)) { index ->
            val question = parseDnsQuestion(payload, cursor) ?: return@repeat
            cursor = question.nextOffset
            fields += PacketField("Question ${index + 1}", "${question.name} ${dnsTypeName(question.type)} class=${question.clazz}")
        }

        val answers = parseDnsRecords(payload, cursor, anCount, "Answer", limit = 5)
        cursor = answers.nextOffset
        fields += answers.fields

        val authorities = parseDnsRecords(payload, cursor, nsCount, "Authority", limit = 2)
        cursor = authorities.nextOffset
        fields += authorities.fields

        val additionals = parseDnsRecords(payload, cursor, arCount, "Additional", limit = 2)
        fields += additionals.fields

        return PacketSection("Application", fields)
    }

    private fun parseDnsQuestion(payload: ByteArray, offset: Int): ParsedDnsQuestion? {
        val parsedName = parseDnsName(payload, offset)
        val name = parsedName.name ?: return null
        val cursor = parsedName.nextOffset
        if (cursor + 4 > payload.size) return null
        val type = readU16(payload, cursor)
        val clazz = readU16(payload, cursor + 2)
        return ParsedDnsQuestion(name, type, clazz, cursor + 4)
    }

    private fun parseDnsRecords(
        payload: ByteArray,
        startOffset: Int,
        count: Int,
        labelPrefix: String,
        limit: Int,
    ): ParsedDnsRecords {
        var cursor = startOffset
        val fields = mutableListOf<PacketField>()

        repeat(count) { index ->
            val rr = parseDnsResourceRecord(payload, cursor) ?: return ParsedDnsRecords(fields, cursor)
            cursor = rr.nextOffset
            if (index < limit) {
                fields += PacketField("$labelPrefix ${index + 1}", rr.description)
            }
        }

        return ParsedDnsRecords(fields, cursor)
    }

    private fun parseDnsResourceRecord(payload: ByteArray, offset: Int): ParsedDnsResourceRecord? {
        val parsedName = parseDnsName(payload, offset)
        val name = parsedName.name ?: return null
        var cursor = parsedName.nextOffset
        if (cursor + 10 > payload.size) return null
        val type = readU16(payload, cursor)
        val clazz = readU16(payload, cursor + 2)
        val ttl = readU32(payload, cursor + 4)
        val rdLength = readU16(payload, cursor + 8)
        cursor += 10
        if (cursor + rdLength > payload.size) return null
        val value = decodeDnsRdata(type, payload, cursor, rdLength)
        return ParsedDnsResourceRecord(
            description = "$name ${dnsTypeName(type)} ttl=$ttl class=$clazz → $value",
            nextOffset = cursor + rdLength,
        )
    }

    private fun decodeDnsRdata(type: Int, payload: ByteArray, offset: Int, length: Int): String {
        return when (type) {
            1 -> if (length == 4) ipv4(payload, offset) else "invalid A"
            2, 5, 12 -> parseDnsName(payload, offset).name ?: "compressed name"
            15 -> {
                if (length < 3) "invalid MX" else {
                    val preference = readU16(payload, offset)
                    val exchange = parseDnsName(payload, offset + 2).name ?: "?"
                    "pref=$preference exchange=$exchange"
                }
            }
            16 -> decodeTxtRecord(payload, offset, length)
            28 -> if (length == 16) ipv6(payload.copyOfRange(offset, offset + 16)) else "invalid AAAA"
            33 -> {
                if (length < 7) "invalid SRV" else {
                    val priority = readU16(payload, offset)
                    val weight = readU16(payload, offset + 2)
                    val port = readU16(payload, offset + 4)
                    val target = parseDnsName(payload, offset + 6).name ?: "?"
                    "priority=$priority weight=$weight port=$port target=$target"
                }
            }
            6 -> decodeSoaRecord(payload, offset, length)
            else -> payload.copyOfRange(offset, offset + length).joinToString(" ") { "%02X".format(it) }
        }
    }

    private fun decodeTxtRecord(payload: ByteArray, offset: Int, length: Int): String {
        val end = offset + length
        var cursor = offset
        val strings = mutableListOf<String>()
        while (cursor < end) {
            val size = payload[cursor].toInt() and 0xFF
            cursor += 1
            if (cursor + size > end) break
            strings += payload.copyOfRange(cursor, cursor + size).toString(StandardCharsets.UTF_8)
            cursor += size
        }
        return strings.joinToString(" | ").ifBlank { "TXT" }
    }

    private fun decodeSoaRecord(payload: ByteArray, offset: Int, length: Int): String {
        val end = offset + length
        val mName = parseDnsName(payload, offset)
        val rName = parseDnsName(payload, mName.nextOffset)
        var cursor = rName.nextOffset
        if (cursor + 20 > end) return "invalid SOA"
        val serial = readU32(payload, cursor)
        val refresh = readU32(payload, cursor + 4)
        val retry = readU32(payload, cursor + 8)
        val expire = readU32(payload, cursor + 12)
        val minimum = readU32(payload, cursor + 16)
        return "mname=${mName.name} rname=${rName.name} serial=$serial refresh=$refresh retry=$retry expire=$expire minimum=$minimum"
    }

    private fun parseTls(payload: ByteArray): PacketSection? {
        if (payload.size < 5) return null
        val contentType = payload[0].toInt() and 0xFF
        val versionMajor = payload[1].toInt() and 0xFF
        val versionMinor = payload[2].toInt() and 0xFF
        val recordLength = readU16(payload, 3)
        if (contentType !in listOf(20, 21, 22, 23) || versionMajor != 3) return null

        val fields = mutableListOf(
            PacketField("Protocol", "TLS"),
            PacketField("Record type", tlsRecordType(contentType)),
            PacketField("Version", tlsVersion(versionMajor, versionMinor)),
            PacketField("Record length", recordLength.toString()),
        )

        if (contentType == 22 && payload.size >= 9) {
            val handshakeType = payload[5].toInt() and 0xFF
            val handshakeLength = readU24(payload, 6)
            fields += PacketField("Handshake type", tlsHandshakeType(handshakeType))
            fields += PacketField("Handshake length", handshakeLength.toString())

            when (handshakeType) {
                1 -> parseTlsClientHello(payload)?.forEach(fields::add)
                2 -> parseTlsServerHello(payload)?.forEach(fields::add)
                11 -> parseTlsCertificateHandshake(payload)?.forEach(fields::add)
            }
        }

        return PacketSection("Application", fields)
    }

    private fun parseTlsClientHello(payload: ByteArray): List<PacketField>? {
        if (payload.size < 43) return null
        var cursor = 9
        if (cursor + 2 > payload.size) return null
        val helloVersion = tlsVersion(payload[cursor].toInt() and 0xFF, payload[cursor + 1].toInt() and 0xFF)
        cursor += 2
        cursor += 32
        if (cursor >= payload.size) return null
        val sessionIdLength = payload[cursor].toInt() and 0xFF
        cursor += 1 + sessionIdLength
        if (cursor + 2 > payload.size) return null
        val cipherSuitesLength = readU16(payload, cursor)
        cursor += 2 + cipherSuitesLength
        if (cursor >= payload.size) return null
        val compressionLength = payload[cursor].toInt() and 0xFF
        cursor += 1 + compressionLength
        if (cursor + 2 > payload.size) return buildList {
            add(PacketField("ClientHello version", helloVersion))
        }
        val extensionsLength = readU16(payload, cursor)
        cursor += 2
        val extensionEnd = min(payload.size, cursor + extensionsLength)

        var sni: String? = null
        var alpn: String? = null
        var supportedVersions: String? = null
        while (cursor + 4 <= extensionEnd) {
            val type = readU16(payload, cursor)
            val len = readU16(payload, cursor + 2)
            cursor += 4
            if (cursor + len > extensionEnd) break
            when (type) {
                0 -> sni = parseTlsSni(payload, cursor, len)
                16 -> alpn = parseTlsAlpn(payload, cursor, len)
                43 -> supportedVersions = parseTlsSupportedVersions(payload, cursor, len)
            }
            cursor += len
        }

        return buildList {
            add(PacketField("ClientHello version", helloVersion))
            sni?.let { add(PacketField("SNI", it)) }
            alpn?.let { add(PacketField("ALPN", it)) }
            supportedVersions?.let { add(PacketField("Supported versions", it)) }
        }
    }

    private fun parseTlsServerHello(payload: ByteArray): List<PacketField>? {
        if (payload.size < 44) return null
        var cursor = 9
        val serverVersion = tlsVersion(payload[cursor].toInt() and 0xFF, payload[cursor + 1].toInt() and 0xFF)
        cursor += 2
        cursor += 32
        if (cursor >= payload.size) return null
        val sessionIdLength = payload[cursor].toInt() and 0xFF
        cursor += 1 + sessionIdLength
        if (cursor + 3 > payload.size) return null
        val cipherSuite = readU16(payload, cursor)
        cursor += 2
        val compression = payload[cursor].toInt() and 0xFF
        cursor += 1

        var selectedVersion: String? = null
        var alpn: String? = null
        if (cursor + 2 <= payload.size) {
            val extensionsLength = readU16(payload, cursor)
            cursor += 2
            val extensionEnd = min(payload.size, cursor + extensionsLength)
            while (cursor + 4 <= extensionEnd) {
                val type = readU16(payload, cursor)
                val len = readU16(payload, cursor + 2)
                cursor += 4
                if (cursor + len > extensionEnd) break
                when (type) {
                    16 -> alpn = parseTlsAlpn(payload, cursor, len)
                    43 -> if (len >= 2) selectedVersion = tlsVersion(payload[cursor].toInt() and 0xFF, payload[cursor + 1].toInt() and 0xFF)
                }
                cursor += len
            }
        }

        return buildList {
            add(PacketField("ServerHello version", serverVersion))
            add(PacketField("Cipher suite", "0x${cipherSuite.toString(16)}"))
            add(PacketField("Compression", compression.toString()))
            selectedVersion?.let { add(PacketField("Selected version", it)) }
            alpn?.let { add(PacketField("Selected ALPN", it)) }
        }
    }

    private fun parseTlsCertificateHandshake(payload: ByteArray): List<PacketField>? {
        if (payload.size < 12) return null
        val bodyOffset = 9
        var listOffset = bodyOffset
        var entryHasExtensions = false

        if (bodyOffset + 4 <= payload.size) {
            val contextLen = payload[bodyOffset].toInt() and 0xFF
            val tls13ListOffset = bodyOffset + 1 + contextLen
            if (tls13ListOffset + 3 <= payload.size) {
                val tls13ListLength = readU24(payload, tls13ListOffset)
                if (tls13ListOffset + 3 + tls13ListLength <= payload.size) {
                    listOffset = tls13ListOffset
                    entryHasExtensions = true
                }
            }
        }

        if (listOffset + 3 > payload.size) return null
        val certificateListLength = readU24(payload, listOffset)
        var cursor = listOffset + 3
        val listEnd = min(payload.size, cursor + certificateListLength)
        var certificateCount = 0
        val subjectCns = linkedSetOf<String>()
        val issuerCns = linkedSetOf<String>()
        val sanNames = linkedSetOf<String>()
        var firstCertificateLength: Int? = null

        while (cursor + 3 <= listEnd) {
            val certLength = readU24(payload, cursor)
            cursor += 3
            if (cursor + certLength > listEnd) break
            val certBytes = payload.copyOfRange(cursor, cursor + certLength)
            if (firstCertificateLength == null) firstCertificateLength = certLength
            certificateCount += 1
            extractCommonNames(certBytes).forEach(subjectCns::add)
            extractIssuerCommonNames(certBytes).forEach(issuerCns::add)
            extractSubjectAltDnsNames(certBytes).forEach(sanNames::add)
            cursor += certLength
            if (entryHasExtensions) {
                if (cursor + 2 > listEnd) break
                val extLength = readU16(payload, cursor)
                cursor += 2 + extLength
            }
        }

        return buildList {
            add(PacketField("Certificate list length", certificateListLength.toString()))
            add(PacketField("Certificate count", certificateCount.toString()))
            firstCertificateLength?.let { add(PacketField("First certificate length", "$it byte")) }
            if (subjectCns.isNotEmpty()) add(PacketField("Subject CN", subjectCns.joinToString(", ")))
            if (issuerCns.isNotEmpty()) add(PacketField("Issuer CN", issuerCns.joinToString(", ")))
            if (sanNames.isNotEmpty()) add(PacketField("SAN DNS", sanNames.joinToString(", ")))
        }
    }

    private fun extractCommonNames(der: ByteArray): List<String> {
        val results = linkedSetOf<String>()
        var offset = 0
        val pattern = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
        while (offset <= der.size - pattern.size) {
            if (matchesPattern(der, offset, pattern)) {
                val node = readDerNode(der, offset + pattern.size) ?: break
                if (node.tag in setOf(0x0C, 0x13, 0x16, 0x14, 0x1E)) {
                    val value = der.copyOfRange(node.contentOffset, node.endOffset).toString(StandardCharsets.UTF_8).trim()
                    if (value.isNotBlank()) results += value
                }
                offset = node.endOffset
            } else {
                offset += 1
            }
        }
        return results.toList()
    }

    private fun extractIssuerCommonNames(der: ByteArray): List<String> {
        val all = extractCommonNames(der)
        return if (all.size > 1) listOf(all.first()) else all
    }

    private fun extractSubjectAltDnsNames(der: ByteArray): List<String> {
        val results = linkedSetOf<String>()
        var offset = 0
        val pattern = byteArrayOf(0x06, 0x03, 0x55, 0x1D, 0x11)
        while (offset <= der.size - pattern.size) {
            if (matchesPattern(der, offset, pattern)) {
                var cursor = offset + pattern.size
                var node = readDerNode(der, cursor) ?: break
                if (node.tag == 0x01) {
                    cursor = node.endOffset
                    node = readDerNode(der, cursor) ?: break
                }
                if (node.tag == 0x04) {
                    parseSanDnsFromOctetString(der.copyOfRange(node.contentOffset, node.endOffset)).forEach(results::add)
                    offset = node.endOffset
                } else {
                    offset = cursor + 1
                }
            } else {
                offset += 1
            }
        }
        return results.toList()
    }

    private fun parseSanDnsFromOctetString(octets: ByteArray): List<String> {
        val results = linkedSetOf<String>()
        val seq = readDerNode(octets, 0) ?: return emptyList()
        var cursor = seq.contentOffset
        while (cursor < seq.endOffset) {
            val node = readDerNode(octets, cursor) ?: break
            if (node.tag == 0x82) {
                val value = octets.copyOfRange(node.contentOffset, node.endOffset).toString(StandardCharsets.US_ASCII).trim()
                if (value.isNotBlank()) results += value
            }
            cursor = node.endOffset
        }
        return results.toList()
    }

    private fun parseTlsSni(payload: ByteArray, offset: Int, length: Int): String? {
        if (length < 5 || offset + length > payload.size) return null
        var cursor = offset + 2
        val end = offset + length
        while (cursor + 3 <= end) {
            val nameType = payload[cursor].toInt() and 0xFF
            val nameLen = readU16(payload, cursor + 1)
            cursor += 3
            if (cursor + nameLen > end) return null
            if (nameType == 0) return payload.copyOfRange(cursor, cursor + nameLen).toString(StandardCharsets.US_ASCII)
            cursor += nameLen
        }
        return null
    }

    private fun parseTlsAlpn(payload: ByteArray, offset: Int, length: Int): String? {
        if (length < 3 || offset + length > payload.size) return null
        var cursor = offset + 2
        val end = offset + length
        val values = mutableListOf<String>()
        while (cursor < end) {
            val itemLen = payload[cursor].toInt() and 0xFF
            cursor += 1
            if (cursor + itemLen > end) break
            values += payload.copyOfRange(cursor, cursor + itemLen).toString(StandardCharsets.US_ASCII)
            cursor += itemLen
        }
        return values.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun parseTlsSupportedVersions(payload: ByteArray, offset: Int, length: Int): String? {
        if (length < 3 || offset + length > payload.size) return null
        val listLen = payload[offset].toInt() and 0xFF
        var cursor = offset + 1
        val end = min(offset + 1 + listLen, offset + length)
        val versions = mutableListOf<String>()
        while (cursor + 1 < end) {
            versions += tlsVersion(payload[cursor].toInt() and 0xFF, payload[cursor + 1].toInt() and 0xFF)
            cursor += 2
        }
        return versions.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    private fun httpPreview(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val text = payload.copyOfRange(0, min(payload.size, 256)).toString(StandardCharsets.US_ASCII)
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

    private fun parseDnsName(payload: ByteArray, startOffset: Int): ParsedDnsName {
        val labels = mutableListOf<String>()
        var cursor = startOffset
        var jumped = false
        var nextOffset = startOffset
        var safety = 0

        while (cursor < payload.size && safety < 64) {
            val len = payload[cursor].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (!jumped) nextOffset = cursor + 1
                    return ParsedDnsName(labels.takeIf { it.isNotEmpty() }?.joinToString("."), nextOffset)
                }
                len and 0xC0 == 0xC0 -> {
                    if (cursor + 1 >= payload.size) return ParsedDnsName(null, cursor + 1)
                    val pointer = ((len and 0x3F) shl 8) or (payload[cursor + 1].toInt() and 0xFF)
                    if (!jumped) nextOffset = cursor + 2
                    cursor = pointer
                    jumped = true
                }
                else -> {
                    cursor += 1
                    if (cursor + len > payload.size) return ParsedDnsName(null, cursor)
                    labels += payload.copyOfRange(cursor, cursor + len).toString(StandardCharsets.UTF_8)
                    cursor += len
                    if (!jumped) nextOffset = cursor
                }
            }
            safety += 1
        }
        return ParsedDnsName(null, nextOffset)
    }

    private fun readDerNode(bytes: ByteArray, offset: Int): DerNode? {
        if (offset >= bytes.size) return null
        val tag = bytes[offset].toInt() and 0xFF
        if (offset + 1 >= bytes.size) return null
        val lengthInfo = readDerLength(bytes, offset + 1) ?: return null
        val contentOffset = offset + 1 + lengthInfo.headerBytes
        val endOffset = contentOffset + lengthInfo.length
        if (endOffset > bytes.size) return null
        return DerNode(tag, contentOffset, endOffset)
    }

    private fun readDerLength(bytes: ByteArray, offset: Int): DerLength? {
        if (offset >= bytes.size) return null
        val first = bytes[offset].toInt() and 0xFF
        if (first and 0x80 == 0) {
            return DerLength(first, 1)
        }
        val count = first and 0x7F
        if (count == 0 || count > 4 || offset + count >= bytes.size) return null
        var value = 0
        repeat(count) { index ->
            value = (value shl 8) or (bytes[offset + 1 + index].toInt() and 0xFF)
        }
        return DerLength(value, 1 + count)
    }

    private fun matchesPattern(bytes: ByteArray, offset: Int, pattern: ByteArray): Boolean {
        if (offset + pattern.size > bytes.size) return false
        for (index in pattern.indices) {
            if (bytes[offset + index] != pattern[index]) return false
        }
        return true
    }

    private fun asciiPreview(bytes: ByteArray): String {
        return buildString {
            bytes.take(96).forEach { byte ->
                val c = byte.toInt().toChar()
                append(if (c.code in 32..126) c else '.')
            }
        }.trim()
    }

    private fun dnsRcode(rcode: Int): String = when (rcode) {
        0 -> "NOERROR"
        1 -> "FORMERR"
        2 -> "SERVFAIL"
        3 -> "NXDOMAIN"
        4 -> "NOTIMP"
        5 -> "REFUSED"
        else -> rcode.toString()
    }

    private fun dnsTypeName(type: Int): String = when (type) {
        1 -> "A"
        2 -> "NS"
        5 -> "CNAME"
        6 -> "SOA"
        12 -> "PTR"
        15 -> "MX"
        16 -> "TXT"
        28 -> "AAAA"
        33 -> "SRV"
        else -> type.toString()
    }

    private fun tlsRecordType(type: Int): String = when (type) {
        20 -> "ChangeCipherSpec"
        21 -> "Alert"
        22 -> "Handshake"
        23 -> "ApplicationData"
        else -> type.toString()
    }

    private fun tlsHandshakeType(type: Int): String = when (type) {
        1 -> "ClientHello"
        2 -> "ServerHello"
        8 -> "EncryptedExtensions"
        11 -> "Certificate"
        12 -> "ServerKeyExchange"
        13 -> "CertificateRequest"
        14 -> "ServerHelloDone"
        15 -> "CertificateVerify"
        16 -> "ClientKeyExchange"
        20 -> "Finished"
        else -> type.toString()
    }

    private fun tlsVersion(major: Int, minor: Int): String = when (major shl 8 or minor) {
        0x0301 -> "TLS 1.0"
        0x0302 -> "TLS 1.1"
        0x0303 -> "TLS 1.2"
        0x0304 -> "TLS 1.3"
        else -> "0x${major.toString(16)}${minor.toString(16)}"
    }

    private fun tcpFlags(flags: Int): String {
        val names = mutableListOf<String>()
        if (flags and 0x01 != 0) names += "FIN"
        if (flags and 0x02 != 0) names += "SYN"
        if (flags and 0x04 != 0) names += "RST"
        if (flags and 0x08 != 0) names += "PSH"
        if (flags and 0x10 != 0) names += "ACK"
        if (flags and 0x20 != 0) names += "URG"
        if (flags and 0x40 != 0) names += "ECE"
        if (flags and 0x80 != 0) names += "CWR"
        return if (names.isEmpty()) "None" else names.joinToString(", ")
    }

    private fun ipv4(bytes: ByteArray, offset: Int): String {
        if (offset + 3 >= bytes.size) return "0.0.0.0"
        return listOf(
            bytes[offset].toInt() and 0xFF,
            bytes[offset + 1].toInt() and 0xFF,
            bytes[offset + 2].toInt() and 0xFF,
            bytes[offset + 3].toInt() and 0xFF,
        ).joinToString(".")
    }

    private fun ipv6(bytes: ByteArray): String = runCatching {
        InetAddress.getByAddress(bytes).hostAddress ?: "::"
    }.getOrDefault("::")

    private fun readU16(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readU24(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        if (offset + 3 >= bytes.size) return 0
        return ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)
    }

    private data class ParsedDnsName(
        val name: String?,
        val nextOffset: Int,
    )

    private data class ParsedDnsQuestion(
        val name: String,
        val type: Int,
        val clazz: Int,
        val nextOffset: Int,
    )

    private data class ParsedDnsRecords(
        val fields: List<PacketField>,
        val nextOffset: Int,
    )

    private data class ParsedDnsResourceRecord(
        val description: String,
        val nextOffset: Int,
    )

    private data class DerLength(
        val length: Int,
        val headerBytes: Int,
    )

    private data class DerNode(
        val tag: Int,
        val contentOffset: Int,
        val endOffset: Int,
    )
}
