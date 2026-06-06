package ai.arena.netscope.data

import ai.arena.netscope.data.persistence.PacketPersistenceManager
import ai.arena.netscope.model.CaptureState
import ai.arena.netscope.model.CapturedPacket
import ai.arena.netscope.model.FlowSummary
import ai.arena.netscope.model.PacketDirection
import ai.arena.netscope.model.PacketProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CaptureRepository {
    private var maxVisiblePackets = 1_000

    private val _packets = MutableStateFlow<List<CapturedPacket>>(emptyList())
    val packets: StateFlow<List<CapturedPacket>> = _packets.asStateFlow()

    private val _flows = MutableStateFlow<List<FlowSummary>>(emptyList())
    val flows: StateFlow<List<FlowSummary>> = _flows.asStateFlow()

    private val _captureState = MutableStateFlow(CaptureState())
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val flowAccumulators = linkedMapOf<String, MutableFlowAccumulator>()

    @Synchronized
    fun configureVisiblePacketLimit(limit: Int) {
        maxVisiblePackets = limit.coerceIn(100, 5_000)
        if (_packets.value.size > maxVisiblePackets) {
            _packets.value = _packets.value.takeLast(maxVisiblePackets)
            rebuildFlowsFromVisiblePackets()
        }
    }

    @Synchronized
    fun restoreHistory(history: List<CapturedPacket>) {
        _packets.value = history.takeLast(maxVisiblePackets)
        _captureState.value = _captureState.value.copy(
            totalPackets = history.size.toLong(),
            totalBytes = history.sumOf { it.length }.toLong(),
            activeFlows = history.map { it.flowKey }.distinct().size,
            statusMessage = if (history.isNotEmpty()) "Önceki oturumdan ${history.size} paket geri yüklendi" else _captureState.value.statusMessage,
        )
        rebuildFlowsFromVisiblePackets()
    }

    @Synchronized
    fun startCapture(
        statusMessage: String? = null,
        engineLabel: String = "Java capture",
        filterSummary: String = "all apps",
    ) {
        _packets.value = emptyList()
        _flows.value = emptyList()
        flowAccumulators.clear()
        PacketPersistenceManager.beginSession(engineLabel = engineLabel, filterSummary = filterSummary)
        _captureState.value = CaptureState(
            isCapturing = true,
            startedAt = System.currentTimeMillis(),
            statusMessage = statusMessage,
            lastExportPath = _captureState.value.lastExportPath,
        )
    }

    @Synchronized
    fun stopCapture(statusMessage: String? = null) {
        PacketPersistenceManager.endSession(statusMessage ?: "STOPPED")
        _captureState.value = _captureState.value.copy(
            isCapturing = false,
            statusMessage = statusMessage ?: _captureState.value.statusMessage,
        )
    }

    @Synchronized
    fun reportError(message: String) {
        _captureState.value = _captureState.value.copy(statusMessage = message)
    }

    @Synchronized
    fun updateStatus(message: String) {
        _captureState.value = _captureState.value.copy(statusMessage = message)
    }

    @Synchronized
    fun setExportPath(path: String) {
        _captureState.value = _captureState.value.copy(
            lastExportPath = path,
            statusMessage = "PCAP exported: $path",
        )
    }

    @Synchronized
    fun pushPacket(packet: CapturedPacket) {
        val previousSize = _packets.value.size
        val nextPackets = (_packets.value + packet).takeLast(maxVisiblePackets)
        val droppedVisiblePacket = previousSize == maxVisiblePackets && nextPackets.size == maxVisiblePackets
        _packets.value = nextPackets
        PacketPersistenceManager.enqueue(packet)

        val accumulator = flowAccumulators.getOrPut(packet.flowKey) {
            val endpoints = listOf(packet.sourceLabel, packet.destinationLabel).sorted()
            MutableFlowAccumulator(
                id = packet.flowKey,
                protocol = packet.protocol,
                endpointA = endpoints[0],
                endpointB = endpoints[1],
                firstSeen = packet.timestamp,
                lastSeen = packet.timestamp,
                latestSummary = packet.summary,
                latestDirection = packet.direction,
            )
        }
        accumulator.packetCount += 1
        accumulator.totalBytes += packet.length
        accumulator.lastSeen = packet.timestamp
        accumulator.latestSummary = packet.summary
        accumulator.latestDirection = packet.direction

        _flows.value = flowAccumulators.values
            .map {
                FlowSummary(
                    id = it.id,
                    protocol = it.protocol,
                    endpointA = it.endpointA,
                    endpointB = it.endpointB,
                    packetCount = it.packetCount,
                    totalBytes = it.totalBytes,
                    firstSeen = it.firstSeen,
                    lastSeen = it.lastSeen,
                    latestSummary = it.latestSummary,
                    latestDirection = it.latestDirection,
                )
            }
            .sortedByDescending { it.lastSeen }

        _captureState.value = _captureState.value.copy(
            totalPackets = _captureState.value.totalPackets + 1,
            totalBytes = _captureState.value.totalBytes + packet.length,
            activeFlows = flowAccumulators.size,
        )

        if (droppedVisiblePacket) {
            rebuildFlowsFromVisiblePackets()
        }
    }

    @Synchronized
    fun clearPersistedHistory() {
        _packets.value = emptyList()
        _flows.value = emptyList()
        flowAccumulators.clear()
        _captureState.value = _captureState.value.copy(
            totalPackets = 0,
            totalBytes = 0,
            activeFlows = 0,
            statusMessage = "Yerel geçmiş temizlendi",
        )
    }

    @Synchronized
    private fun rebuildFlowsFromVisiblePackets() {
        flowAccumulators.clear()
        _packets.value.forEach { packet ->
            val endpoints = listOf(packet.sourceLabel, packet.destinationLabel).sorted()
            val accumulator = flowAccumulators.getOrPut(packet.flowKey) {
                MutableFlowAccumulator(
                    id = packet.flowKey,
                    protocol = packet.protocol,
                    endpointA = endpoints[0],
                    endpointB = endpoints[1],
                    firstSeen = packet.timestamp,
                    lastSeen = packet.timestamp,
                    latestSummary = packet.summary,
                    latestDirection = packet.direction,
                )
            }
            accumulator.packetCount += 1
            accumulator.totalBytes += packet.length
            accumulator.lastSeen = packet.timestamp
            accumulator.latestSummary = packet.summary
            accumulator.latestDirection = packet.direction
        }

        _flows.value = flowAccumulators.values
            .map {
                FlowSummary(
                    id = it.id,
                    protocol = it.protocol,
                    endpointA = it.endpointA,
                    endpointB = it.endpointB,
                    packetCount = it.packetCount,
                    totalBytes = it.totalBytes,
                    firstSeen = it.firstSeen,
                    lastSeen = it.lastSeen,
                    latestSummary = it.latestSummary,
                    latestDirection = it.latestDirection,
                )
            }
            .sortedByDescending { it.lastSeen }

        _captureState.value = _captureState.value.copy(activeFlows = flowAccumulators.size)
    }

    private data class MutableFlowAccumulator(
        val id: String,
        val protocol: PacketProtocol,
        val endpointA: String,
        val endpointB: String,
        val firstSeen: Long,
        var lastSeen: Long,
        var latestSummary: String,
        var latestDirection: PacketDirection,
        var packetCount: Int = 0,
        var totalBytes: Long = 0,
    )
}
