package ai.arena.netscope

import android.app.Application
import ai.arena.netscope.data.AppDiscoveryRepository
import ai.arena.netscope.data.CaptureRepository
import ai.arena.netscope.data.SettingsRepository
import ai.arena.netscope.data.persistence.PacketPersistenceManager
import ai.arena.netscope.parser.PacketParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class NetScopeApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        SettingsRepository.initialize(this, applicationScope)
        AppDiscoveryRepository.initialize(this, applicationScope)
        PacketPersistenceManager.initialize(this, applicationScope)

        applicationScope.launch {
            SettingsRepository.settings.collectLatest { settings ->
                CaptureRepository.configureVisiblePacketLimit(settings.maxVisiblePackets)
                PacketPersistenceManager.configure(
                    enabled = settings.persistPackets,
                    maxStoredPackets = settings.maxStoredPackets,
                )
            }
        }

        applicationScope.launch {
            val history = PacketPersistenceManager.loadRecentPackets(limit = 200)
            val nextId = (history.maxOfOrNull { it.id } ?: 0L) + 1L
            PacketParser.ensureNextPacketIdAtLeast(nextId)
            CaptureRepository.restoreHistory(history)
        }
    }
}
