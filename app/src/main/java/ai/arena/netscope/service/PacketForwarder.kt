package ai.arena.netscope.service

import ai.arena.netscope.parser.PacketParser

class PacketForwarder(
    private val udpForwarder: UdpForwarder,
    private val onStatus: (String) -> Unit,
) {
    private var tcpNoticeShown = false
    private var ipv6NoticeShown = false

    fun forward(packetBytes: ByteArray) {
        val udp = PacketParser.extractUdpDatagram(packetBytes)
        if (udp != null) {
            udpForwarder.forward(udp)
            return
        }

        when (PacketParser.peekTransportProtocol(packetBytes)) {
            6 -> {
                if (!tcpNoticeShown) {
                    tcpNoticeShown = true
                    onStatus("UDP forwarding aktif. Tam TCP transparent forwarding için native tun2socks katmanı eklenmeli.")
                }
            }

            null -> Unit
            else -> {
                if (((packetBytes.firstOrNull()?.toInt() ?: 0) ushr 4) == 6 && !ipv6NoticeShown) {
                    ipv6NoticeShown = true
                    onStatus("IPv6 paketleri yakalanıyor ancak forwarding şimdilik yalnızca IPv4/UDP için etkin.")
                }
            }
        }
    }

    fun close() {
        udpForwarder.close()
    }
}
