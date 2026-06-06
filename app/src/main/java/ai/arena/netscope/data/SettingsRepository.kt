package ai.arena.netscope.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import ai.arena.netscope.model.AppFilterMode
import ai.arena.netscope.model.CaptureSettings
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

object SettingsRepository {
    private val persistPacketsKey = booleanPreferencesKey("persist_packets")
    private val nativeBridgeKey = booleanPreferencesKey("native_tcp_bridge")
    private val tun2SocksHostKey = stringPreferencesKey("tun2socks_host")
    private val tun2SocksPortKey = intPreferencesKey("tun2socks_port")
    private val appFilterModeKey = stringPreferencesKey("app_filter_mode")
    private val selectedPackagesKey = stringPreferencesKey("selected_packages_csv")
    private val maxVisiblePacketsKey = intPreferencesKey("max_visible_packets")
    private val maxStoredPacketsKey = intPreferencesKey("max_stored_packets")

    private val _settings = MutableStateFlow(CaptureSettings())
    val settings: StateFlow<CaptureSettings> = _settings

    private var initialized = false

    fun initialize(context: Context, scope: CoroutineScope) {
        if (initialized) return
        initialized = true

        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { context.preferencesDataStoreFile("netscope_settings") },
        )

        dataStore.data
            .catch { error ->
                if (error is IOException) emit(emptyPreferences()) else throw error
            }
            .map { prefs ->
                CaptureSettings(
                    persistPackets = prefs[persistPacketsKey] ?: true,
                    nativeTcpBridgeEnabled = prefs[nativeBridgeKey] ?: false,
                    tun2SocksHost = prefs[tun2SocksHostKey] ?: "127.0.0.1",
                    tun2SocksPort = prefs[tun2SocksPortKey] ?: 1080,
                    appFilterMode = runCatching {
                        AppFilterMode.valueOf(prefs[appFilterModeKey] ?: AppFilterMode.ALL_APPS.name)
                    }.getOrDefault(AppFilterMode.ALL_APPS),
                    selectedPackages = prefs[selectedPackagesKey]
                        ?.split(',')
                        ?.filter { it.isNotBlank() }
                        ?.toSet()
                        ?: emptySet(),
                    maxVisiblePackets = prefs[maxVisiblePacketsKey] ?: 600,
                    maxStoredPackets = prefs[maxStoredPacketsKey] ?: 2_000,
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, CaptureSettings())
            .also { stateFlow ->
                scope.launch {
                    stateFlow.collect { _settings.value = it }
                }
            }

        this.dataStore = dataStore
    }

    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>

    suspend fun updatePersistPackets(enabled: Boolean) {
        requireInitialized()
        dataStore.edit { it[persistPacketsKey] = enabled }
    }

    suspend fun updateNativeBridgeEnabled(enabled: Boolean) {
        requireInitialized()
        dataStore.edit { it[nativeBridgeKey] = enabled }
    }

    suspend fun updateTun2SocksHost(host: String) {
        requireInitialized()
        dataStore.edit { it[tun2SocksHostKey] = host.trim() }
    }

    suspend fun updateTun2SocksPort(port: Int) {
        requireInitialized()
        dataStore.edit { it[tun2SocksPortKey] = port.coerceIn(1, 65535) }
    }

    suspend fun updateAppFilterMode(mode: AppFilterMode) {
        requireInitialized()
        dataStore.edit { it[appFilterModeKey] = mode.name }
    }

    suspend fun updateSelectedPackages(packages: Set<String>) {
        requireInitialized()
        dataStore.edit { it[selectedPackagesKey] = packages.sorted().joinToString(",") }
    }

    suspend fun toggleSelectedPackage(packageName: String) {
        val next = _settings.value.selectedPackages.toMutableSet().apply {
            if (!add(packageName)) remove(packageName)
        }
        updateSelectedPackages(next)
    }

    suspend fun updateMaxVisiblePackets(value: Int) {
        requireInitialized()
        dataStore.edit { it[maxVisiblePacketsKey] = value.coerceIn(100, 5_000) }
    }

    suspend fun updateMaxStoredPackets(value: Int) {
        requireInitialized()
        dataStore.edit { it[maxStoredPacketsKey] = value.coerceIn(100, 20_000) }
    }

    private fun requireInitialized() {
        check(::dataStore.isInitialized) { "SettingsRepository.initialize must be called first" }
    }
}
