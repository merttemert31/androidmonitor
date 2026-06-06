package ai.arena.netscope.data.persistence

import android.content.Context
import androidx.room.Room
import ai.arena.netscope.model.CapturedPacket
import ai.arena.netscope.model.CaptureSessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

object PacketPersistenceManager {
    private lateinit var database: PacketDatabase
    private lateinit var scope: CoroutineScope

    @Volatile
    private var enabled: Boolean = true

    @Volatile
    private var maxStoredPackets: Int = 2_000

    @Volatile
    private var activeSessionId: Long? = null

    @Volatile
    private var activeSessionPacketCount: Long = 0

    @Volatile
    private var activeSessionBytes: Long = 0

    private val writeQueue = Channel<CapturedPacket>(capacity = 2_048)
    private val _sessions = MutableStateFlow<List<CaptureSessionSummary>>(emptyList())
    val sessions: StateFlow<List<CaptureSessionSummary>> = _sessions.asStateFlow()

    private var initialized = false

    fun initialize(context: Context, applicationScope: CoroutineScope) {
        if (initialized) return
        initialized = true
        scope = applicationScope
        database = Room.databaseBuilder(
            context.applicationContext,
            PacketDatabase::class.java,
            "netscope_packets.db",
        ).fallbackToDestructiveMigration().build()

        refreshSessionsAsync()

        scope.launch(Dispatchers.IO) {
            val buffer = mutableListOf<CapturedPacket>()
            while (isActive) {
                val first = writeQueue.receive()
                buffer += first
                delay(150)
                while (true) {
                    val next = writeQueue.tryReceive().getOrNull() ?: break
                    buffer += next
                    if (buffer.size >= 50) break
                }
                flush(buffer)
                buffer.clear()
            }
        }
    }

    fun configure(enabled: Boolean, maxStoredPackets: Int) {
        this.enabled = enabled
        this.maxStoredPackets = maxStoredPackets.coerceAtLeast(100)
    }

    fun beginSession(engineLabel: String, filterSummary: String) {
        if (!enabled || !initialized) return
        runBlocking(Dispatchers.IO) {
            val sessionId = database.captureSessionDao().insert(
                CaptureSessionEntity(
                    startedAt = System.currentTimeMillis(),
                    endedAt = null,
                    engineLabel = engineLabel,
                    filterSummary = filterSummary,
                    packetCount = 0,
                    totalBytes = 0,
                    status = "ACTIVE",
                ),
            )
            activeSessionId = sessionId
            activeSessionPacketCount = 0
            activeSessionBytes = 0
            refreshSessions()
        }
    }

    fun endSession(status: String) {
        val sessionId = activeSessionId ?: return
        if (!initialized) return
        scope.launch(Dispatchers.IO) {
            database.captureSessionDao().updateStats(sessionId, activeSessionPacketCount, activeSessionBytes)
            database.captureSessionDao().closeSession(sessionId, System.currentTimeMillis(), status)
            activeSessionId = null
            activeSessionPacketCount = 0
            activeSessionBytes = 0
            refreshSessions()
        }
    }

    fun enqueue(packet: CapturedPacket) {
        if (!enabled || !initialized) return
        writeQueue.trySend(packet)
    }

    suspend fun loadRecentPackets(limit: Int): List<CapturedPacket> {
        if (!initialized) return emptyList()
        return withContext(Dispatchers.IO) {
            database.packetDao().getRecent(limit).map { it.toDomain() }.sortedBy { it.timestamp }
        }
    }

    suspend fun loadSessionPackets(sessionId: Long, limit: Int): List<CapturedPacket> {
        if (!initialized) return emptyList()
        return withContext(Dispatchers.IO) {
            database.packetDao().getRecentForSession(sessionId, limit)
                .map { it.toDomain() }
                .sortedByDescending { it.timestamp }
        }
    }

    suspend fun loadAllSessionPackets(sessionId: Long): List<CapturedPacket> {
        if (!initialized) return emptyList()
        return withContext(Dispatchers.IO) {
            database.packetDao().getAllForSession(sessionId).map { it.toDomain() }
        }
    }

    suspend fun clearAll() {
        if (!initialized) return
        while (writeQueue.tryReceive().getOrNull() != null) {
            // drain queued packets before clearing the database
        }
        withContext(Dispatchers.IO) {
            database.packetDao().clearAll()
            database.captureSessionDao().clearAll()
            activeSessionId = null
            activeSessionPacketCount = 0
            activeSessionBytes = 0
            refreshSessions()
        }
    }

    private suspend fun flush(items: List<CapturedPacket>) {
        if (items.isEmpty() || !enabled) return
        val sessionId = activeSessionId
        database.packetDao().insertAll(items.map { it.toEntity(sessionId) })
        database.packetDao().pruneTo(maxStoredPackets)

        if (sessionId != null) {
            activeSessionPacketCount += items.size
            activeSessionBytes += items.sumOf { it.length.toLong() }
            database.captureSessionDao().updateStats(sessionId, activeSessionPacketCount, activeSessionBytes)
        }
        refreshSessions()
    }

    private fun refreshSessionsAsync() {
        if (!initialized) return
        scope.launch(Dispatchers.IO) {
            refreshSessions()
        }
    }

    private suspend fun refreshSessions() {
        _sessions.value = database.captureSessionDao().getRecent(limit = 40).map { it.toDomain() }
    }
}
