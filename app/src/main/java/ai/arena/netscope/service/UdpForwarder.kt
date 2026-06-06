package ai.arena.netscope.service

import ai.arena.netscope.parser.UdpDatagram
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UdpForwarder(
    private val serviceScope: CoroutineScope,
    private val protectSocket: (DatagramSocket) -> Boolean,
    private val writePacketToTun: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit,
) {
    private val flows = ConcurrentHashMap<String, UdpFlowContext>()

    fun forward(datagram: UdpDatagram) {
        val key = datagram.flowKey()
        val context = flows[key] ?: createFlow(datagram).also { flows[key] = it }
        context.lastSeen = System.currentTimeMillis()

        val packet = DatagramPacket(datagram.payload, datagram.payload.size)
        context.socket.send(packet)
    }

    fun close() {
        flows.values.forEach { it.close() }
        flows.clear()
    }

    private fun createFlow(datagram: UdpDatagram): UdpFlowContext {
        val socket = DatagramSocket(null)
        socket.soTimeout = 1_000
        require(protectSocket(socket)) { "Unable to protect UDP socket" }
        socket.connect(InetSocketAddress(datagram.dstIp, datagram.dstPort))

        val context = UdpFlowContext(
            key = datagram.flowKey(),
            clientIp = datagram.srcIp,
            clientPort = datagram.srcPort,
            remoteIp = datagram.dstIp,
            remotePort = datagram.dstPort,
            socket = socket,
            lastSeen = System.currentTimeMillis(),
        )

        context.receiveJob = serviceScope.launch {
            val buffer = ByteArray(65_535)
            while (isActive && !socket.isClosed) {
                try {
                    val responsePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(responsePacket)
                    context.lastSeen = System.currentTimeMillis()

                    val inbound = RawPacketBuilder.buildIpv4UdpPacket(
                        srcIp = context.remoteIp,
                        dstIp = context.clientIp,
                        srcPort = context.remotePort,
                        dstPort = context.clientPort,
                        payload = responsePacket.data.copyOfRange(0, responsePacket.length),
                    )
                    writePacketToTun(inbound)
                } catch (_: SocketTimeoutException) {
                    if (System.currentTimeMillis() - context.lastSeen > IDLE_TIMEOUT_MS) {
                        break
                    }
                } catch (error: Exception) {
                    onLog("UDP flow error ${context.key}: ${error.message ?: error.javaClass.simpleName}")
                    break
                }
            }
            flows.remove(context.key)
            context.close()
        }

        return context
    }

    private data class UdpFlowContext(
        val key: String,
        val clientIp: String,
        val clientPort: Int,
        val remoteIp: String,
        val remotePort: Int,
        val socket: DatagramSocket,
        var lastSeen: Long,
        var receiveJob: Job? = null,
    ) {
        fun close() {
            receiveJob?.cancel()
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 30_000L
    }
}

private fun UdpDatagram.flowKey(): String = listOf(srcIp, srcPort, dstIp, dstPort).joinToString("|")
