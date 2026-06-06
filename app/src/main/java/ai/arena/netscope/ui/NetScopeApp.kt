package ai.arena.netscope.ui

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ai.arena.netscope.data.AppDiscoveryRepository
import ai.arena.netscope.data.CaptureRepository
import ai.arena.netscope.data.SettingsRepository
import ai.arena.netscope.data.persistence.PacketPersistenceManager
import ai.arena.netscope.model.AppFilterMode
import ai.arena.netscope.model.CaptureSessionSummary
import ai.arena.netscope.model.CaptureSettings
import ai.arena.netscope.model.CapturedPacket
import ai.arena.netscope.model.FlowSummary
import ai.arena.netscope.model.InstalledAppInfo
import ai.arena.netscope.model.PacketDirection
import ai.arena.netscope.parser.PacketInspector
import ai.arena.netscope.parser.PacketSection
import ai.arena.netscope.service.BadvpnTun2SocksRunner
import ai.arena.netscope.service.ExportSharer
import ai.arena.netscope.service.PcapWriter
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ScreenTab { PACKETS, FLOWS, SESSIONS, SETTINGS }

private data class SearchQuery(
    val plainTerms: List<String>,
    val ip: String? = null,
    val src: String? = null,
    val dst: String? = null,
    val port: Int? = null,
    val protocol: String? = null,
    val direction: String? = null,
    val fromInclusive: Long? = null,
    val toExclusive: Long? = null,
)

@Composable
fun NetScopeApp(
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
) {
    val context = LocalContext.current
    val packets by CaptureRepository.packets.collectAsStateWithLifecycle()
    val flows by CaptureRepository.flows.collectAsStateWithLifecycle()
    val captureState by CaptureRepository.captureState.collectAsStateWithLifecycle()
    val settings by SettingsRepository.settings.collectAsStateWithLifecycle()
    val installedApps by AppDiscoveryRepository.apps.collectAsStateWithLifecycle()
    val sessions by PacketPersistenceManager.sessions.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    var selectedProtocol by rememberSaveable { mutableStateOf("ALL") }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedPacket by remember { mutableStateOf<CapturedPacket?>(null) }
    var selectedFlow by remember { mutableStateOf<FlowSummary?>(null) }
    var selectedSession by remember { mutableStateOf<CaptureSessionSummary?>(null) }
    var selectedSessionPackets by remember { mutableStateOf<List<CapturedPacket>>(emptyList()) }
    var sessionDialogLoading by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(ScreenTab.PACKETS.name) }
    var sessionFromDate by rememberSaveable { mutableStateOf<Long?>(null) }
    var sessionToDate by rememberSaveable { mutableStateOf<Long?>(null) }

    val parsedQuery = remember(query) { parseSearchQuery(query) }
    val filteredPackets = remember(packets, selectedProtocol, parsedQuery) {
        packets.asReversed().filter { packet -> packetMatches(packet, selectedProtocol, parsedQuery) }
    }
    val filteredFlows = remember(flows, selectedProtocol, parsedQuery) {
        flows.filter { flow -> flowMatches(flow, selectedProtocol, parsedQuery) }
    }
    val filteredSessions = remember(sessions, parsedQuery, sessionFromDate, sessionToDate) {
        sessions.filter { session ->
            sessionMatches(
                session = session,
                query = parsedQuery,
                pickerFrom = sessionFromDate,
                pickerTo = sessionToDate,
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Scaffold(modifier = Modifier.fillMaxSize()) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            ) {
                HeaderSection()
                Spacer(modifier = Modifier.height(16.dp))

                CaptureControls(
                    isCapturing = captureState.isCapturing,
                    canExport = packets.isNotEmpty(),
                    canShare = !captureState.lastExportPath.isNullOrBlank(),
                    onStartCapture = onStartCapture,
                    onStopCapture = onStopCapture,
                    onExport = {
                        coroutineScope.launch(Dispatchers.IO) {
                            runCatching { PcapWriter.export(context, packets, filePrefix = "netscope-live") }
                                .onSuccess { file -> CaptureRepository.setExportPath(file.absolutePath) }
                                .onFailure { error ->
                                    CaptureRepository.reportError(
                                        "PCAP export error: ${error.message ?: error.javaClass.simpleName}",
                                    )
                                }
                        }
                        Toast.makeText(context, "PCAP export başlatıldı", Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        captureState.lastExportPath?.let { path ->
                            runCatching { ExportSharer.share(context, path) }
                                .onFailure {
                                    Toast.makeText(context, "Paylaşım başlatılamadı", Toast.LENGTH_SHORT).show()
                                }
                        }
                    },
                    onClearHistory = {
                        coroutineScope.launch(Dispatchers.IO) {
                            PacketPersistenceManager.clearAll()
                            CaptureRepository.clearPersistedHistory()
                        }
                        Toast.makeText(context, "Yerel geçmiş siliniyor", Toast.LENGTH_SHORT).show()
                    },
                )

                Spacer(modifier = Modifier.height(12.dp))

                StatsSection(
                    totalPackets = captureState.totalPackets,
                    totalBytes = captureState.totalBytes,
                    activeFlows = captureState.activeFlows,
                )

                if (!captureState.statusMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    StatusCard(
                        message = captureState.statusMessage.orEmpty(),
                        path = captureState.lastExportPath,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                TabSelector(
                    selected = ScreenTab.valueOf(selectedTab),
                    onSelected = { selectedTab = it.name },
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (ScreenTab.valueOf(selectedTab) != ScreenTab.SETTINGS) {
                    SearchCard(
                        selectedProtocol = selectedProtocol,
                        onProtocolSelected = { selectedProtocol = it },
                        query = query,
                        onQueryChange = { query = it },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (ScreenTab.valueOf(selectedTab) == ScreenTab.SESSIONS) {
                    SessionDateFilterCard(
                        fromDate = sessionFromDate,
                        toDate = sessionToDate,
                        onFromDateChange = { sessionFromDate = it },
                        onToDateChange = { sessionToDate = it },
                        onClear = {
                            sessionFromDate = null
                            sessionToDate = null
                        },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                when (ScreenTab.valueOf(selectedTab)) {
                    ScreenTab.PACKETS -> PacketsTab(filteredPackets) { selectedPacket = it }
                    ScreenTab.FLOWS -> FlowsTab(filteredFlows) { selectedFlow = it }
                    ScreenTab.SESSIONS -> SessionsTab(filteredSessions) { session ->
                        selectedSession = session
                        selectedSessionPackets = emptyList()
                        sessionDialogLoading = true
                        coroutineScope.launch(Dispatchers.IO) {
                            val items = PacketPersistenceManager.loadSessionPackets(session.id, limit = 50)
                            withContext(Dispatchers.Main) {
                                selectedSessionPackets = items
                                sessionDialogLoading = false
                            }
                        }
                    }
                    ScreenTab.SETTINGS -> SettingsTab(settings = settings, installedApps = installedApps)
                }
            }
        }
    }

    selectedPacket?.let { packet ->
        PacketDetailDialog(packet = packet, onDismiss = { selectedPacket = null })
    }

    selectedFlow?.let { flow ->
        val relatedPackets = packets.filter { it.flowKey == flow.id }.sortedByDescending { it.timestamp }.take(50)
        FlowDetailDialog(flow = flow, packets = relatedPackets, onDismiss = { selectedFlow = null })
    }

    selectedSession?.let { session ->
        SessionDetailDialog(
            session = session,
            packets = selectedSessionPackets,
            loading = sessionDialogLoading,
            onDismiss = {
                selectedSession = null
                selectedSessionPackets = emptyList()
                sessionDialogLoading = false
            },
            onExport = {
                coroutineScope.launch(Dispatchers.IO) {
                    runCatching {
                        val allPackets = PacketPersistenceManager.loadAllSessionPackets(session.id)
                        PcapWriter.export(context, allPackets, filePrefix = "session-${session.id}")
                    }.onSuccess { file ->
                        CaptureRepository.setExportPath(file.absolutePath)
                    }.onFailure { error ->
                        CaptureRepository.reportError("Session export error: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            },
            onShare = {
                coroutineScope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        val allPackets = PacketPersistenceManager.loadAllSessionPackets(session.id)
                        PcapWriter.export(context, allPackets, filePrefix = "session-${session.id}")
                    }
                    withContext(Dispatchers.Main) {
                        result.onSuccess { file ->
                            CaptureRepository.setExportPath(file.absolutePath)
                            ExportSharer.share(context, file.absolutePath)
                        }.onFailure { error ->
                            Toast.makeText(context, "Session paylaşımı başarısız", Toast.LENGTH_SHORT).show()
                            CaptureRepository.reportError("Session share error: ${error.message ?: error.javaClass.simpleName}")
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun HeaderSection() {
    Text(
        text = "NetScope",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
    )
    Text(
        text = "Android için Wireshark-benzeri ağ analiz prototipi",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun CaptureControls(
    isCapturing: Boolean,
    canExport: Boolean,
    canShare: Boolean,
    onStartCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onClearHistory: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onStartCapture, enabled = !isCapturing, modifier = Modifier.weight(1f)) {
            Text("Capture başlat")
        }
        OutlinedButton(onClick = onStopCapture, enabled = isCapturing, modifier = Modifier.weight(1f)) {
            Text("Durdur")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onExport, enabled = canExport, modifier = Modifier.weight(1f)) {
            Text("PCAP dışa aktar")
        }
        OutlinedButton(onClick = onShare, enabled = canShare, modifier = Modifier.weight(1f)) {
            Text("Export paylaş")
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedButton(onClick = onClearHistory, modifier = Modifier.fillMaxWidth()) {
        Text("Yerel geçmişi temizle")
    }
}

@Composable
private fun StatusCard(message: String, path: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium)
            path?.takeIf { it.isNotBlank() }?.let {
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun PacketsTab(
    filteredPackets: List<CapturedPacket>,
    onPacketSelected: (CapturedPacket) -> Unit,
) {
    Text("Paketler (${filteredPackets.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(8.dp))
    if (filteredPackets.isEmpty()) {
        EmptyState("Henüz paket yok. Capture başlatıp trafik oluşturabilirsin.")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filteredPackets, key = { it.id }) { packet ->
                PacketRow(packet = packet, onClick = { onPacketSelected(packet) })
            }
        }
    }
}

@Composable
private fun FlowsTab(
    filteredFlows: List<FlowSummary>,
    onFlowSelected: (FlowSummary) -> Unit,
) {
    Text("Akışlar (${filteredFlows.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(8.dp))
    if (filteredFlows.isEmpty()) {
        EmptyState("Henüz akış yok. UDP forwarding ile gelen trafik burada gruplanır.")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filteredFlows, key = { it.id }) { flow ->
                FlowRow(flow = flow, onClick = { onFlowSelected(flow) })
            }
        }
    }
}

@Composable
private fun SessionsTab(
    sessions: List<CaptureSessionSummary>,
    onSessionSelected: (CaptureSessionSummary) -> Unit,
) {
    Text("Oturumlar (${sessions.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(modifier = Modifier.height(8.dp))
    if (sessions.isEmpty()) {
        EmptyState("Henüz kayıtlı oturum yok.")
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(session = session, onClick = { onSessionSelected(session) })
            }
        }
    }
}

@Composable
private fun SessionDateFilterCard(
    fromDate: Long?,
    toDate: Long?,
    onFromDateChange: (Long?) -> Unit,
    onToDateChange: (Long?) -> Unit,
    onClear: () -> Unit,
) {
    val context = LocalContext.current

    fun openPicker(current: Long?, onPicked: (Long) -> Unit) {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = current ?: System.currentTimeMillis()
        }
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                onPicked(selected)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Session tarih filtresi", fontWeight = FontWeight.Bold)
            Text("Takvim seçici ile oturumları tarih aralığına göre daralt.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openPicker(fromDate, onFromDateChange) }, modifier = Modifier.weight(1f)) {
                    Text(fromDate?.let(::dateOnlyText) ?: "Başlangıç seç")
                }
                OutlinedButton(onClick = { openPicker(toDate, onToDateChange) }, modifier = Modifier.weight(1f)) {
                    Text(toDate?.let(::dateOnlyText) ?: "Bitiş seç")
                }
            }
            OutlinedButton(onClick = onClear, modifier = Modifier.fillMaxWidth()) {
                Text("Tarih filtresini temizle")
            }
        }
    }
}

@Composable
private fun SettingsTab(
    settings: CaptureSettings,
    installedApps: List<InstalledAppInfo>,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val engineAvailable = remember(context) { BadvpnTun2SocksRunner(context).isBinaryAvailable() }

    var tunHost by remember { mutableStateOf(settings.tun2SocksHost) }
    var tunPort by remember { mutableStateOf(settings.tun2SocksPort.toString()) }
    var maxVisible by remember { mutableStateOf(settings.maxVisiblePackets.toString()) }
    var maxStored by remember { mutableStateOf(settings.maxStoredPackets.toString()) }
    var appSearch by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(settings.tun2SocksHost, settings.tun2SocksPort, settings.maxVisiblePackets, settings.maxStoredPackets) {
        tunHost = settings.tun2SocksHost
        tunPort = settings.tun2SocksPort.toString()
        maxVisible = settings.maxVisiblePackets.toString()
        maxStored = settings.maxStoredPackets.toString()
    }

    val visibleApps = remember(installedApps, appSearch) {
        val q = appSearch.trim().lowercase()
        if (q.isBlank()) installedApps else installedApps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsSectionCard(
            title = "Engine",
            subtitle = if (engineAvailable) {
                "Paketlenmiş libtun2socks.so bulundu. Gerçek process tabanlı tun2socks denenebilir."
            } else {
                "Gerçek badvpn binary henüz paketlenmemiş. Java UDP fallback çalışır; TCP için jniLibs üretmelisin."
            },
        ) {
            SwitchRow(
                title = "badvpn tun2socks dene",
                description = "Açıkken servis önce gerçek badvpn process yolunu, sonra JNI scaffold'u dener.",
                checked = settings.nativeTcpBridgeEnabled,
                onCheckedChange = { enabled -> coroutineScope.launch { SettingsRepository.updateNativeBridgeEnabled(enabled) } },
            )
            OutlinedTextField(
                value = tunHost,
                onValueChange = { tunHost = it },
                label = { Text("SOCKS host") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = tunPort,
                onValueChange = { tunPort = it.filter(Char::isDigit) },
                label = { Text("SOCKS port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = {
                coroutineScope.launch {
                    SettingsRepository.updateTun2SocksHost(tunHost)
                    SettingsRepository.updateTun2SocksPort(tunPort.toIntOrNull() ?: 1080)
                }
            }) {
                Text("Engine ayarlarını kaydet")
            }
        }

        SettingsSectionCard(
            title = "Persistence ve performans",
            subtitle = "Room arşivi, görünür paket limiti ve saklama limiti bu bölümden yönetilir.",
        ) {
            SwitchRow(
                title = "Room ile paketleri sakla",
                description = "Uygulama açıldığında son kayıtlar geri yüklenir ve oturum arşivi tutulur.",
                checked = settings.persistPackets,
                onCheckedChange = { enabled -> coroutineScope.launch { SettingsRepository.updatePersistPackets(enabled) } },
            )
            OutlinedTextField(
                value = maxVisible,
                onValueChange = { maxVisible = it.filter(Char::isDigit) },
                label = { Text("UI görünür paket limiti") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = maxStored,
                onValueChange = { maxStored = it.filter(Char::isDigit) },
                label = { Text("Room saklama limiti") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Button(onClick = {
                coroutineScope.launch {
                    SettingsRepository.updateMaxVisiblePackets(maxVisible.toIntOrNull() ?: 600)
                    SettingsRepository.updateMaxStoredPackets(maxStored.toIntOrNull() ?: 2_000)
                }
            }) {
                Text("Performans ayarlarını kaydet")
            }
        }

        SettingsSectionCard(
            title = "Uygulama bazlı filtre",
            subtitle = "VpnService seviyesinde hangi uygulamaların tünele gireceğini belirler. Paketleri UID bazında etiketlemez.",
        ) {
            AppFilterModeSelector(
                selected = settings.appFilterMode,
                onSelected = { mode -> coroutineScope.launch { SettingsRepository.updateAppFilterMode(mode) } },
            )
            OutlinedTextField(
                value = appSearch,
                onValueChange = { appSearch = it },
                label = { Text("Uygulama ara") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Text("Seçili uygulamalar: ${settings.selectedPackages.size}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (visibleApps.isEmpty()) {
                Text("Eşleşen uygulama yok")
            } else {
                visibleApps.forEach { app ->
                    AppSelectionRow(
                        app = app,
                        selected = settings.selectedPackages.contains(app.packageName),
                        onToggle = { coroutineScope.launch { SettingsRepository.toggleSelectedPackage(app.packageName) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = title, fontWeight = FontWeight.Bold)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatsSection(totalPackets: Long, totalBytes: Long, activeFlows: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard(title = "Paket", value = totalPackets.toString(), modifier = Modifier.weight(1f))
        StatCard(title = "Veri", value = formatBytes(totalBytes), modifier = Modifier.weight(1f))
        StatCard(title = "Akış", value = activeFlows.toString(), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TabSelector(selected: ScreenTab, onSelected: (ScreenTab) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == ScreenTab.PACKETS, onClick = { onSelected(ScreenTab.PACKETS) }, label = { Text("Paketler") })
        FilterChip(selected = selected == ScreenTab.FLOWS, onClick = { onSelected(ScreenTab.FLOWS) }, label = { Text("Akışlar") })
        FilterChip(selected = selected == ScreenTab.SESSIONS, onClick = { onSelected(ScreenTab.SESSIONS) }, label = { Text("Oturumlar") })
        FilterChip(selected = selected == ScreenTab.SETTINGS, onClick = { onSelected(ScreenTab.SETTINGS) }, label = { Text("Ayarlar") })
    }
}

@Composable
private fun SearchCard(
    selectedProtocol: String,
    onProtocolSelected: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val protocols = listOf("ALL", "TCP", "UDP", "ICMP", "DNS", "HTTP")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                protocols.forEach { protocol ->
                    FilterChip(
                        selected = protocol == selectedProtocol,
                        onClick = { onProtocolSelected(protocol) },
                        label = { Text(protocol) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ara: ip:, src:, dst:, port:, proto:, dir:, from:, to:") },
                supportingText = {
                    Text("Örnek: ip:8.8.8.8 port:53 dir:out from:2026-06-01 dns")
                },
                singleLine = true,
            )
        }
    }
}

@Composable
private fun PacketRow(packet: CapturedPacket, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(packet.protocol.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(timeText(packet.timestamp), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(packet.conversationLabel, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(packet.summary, style = MaterialTheme.typography.bodyMedium)
            if (packet.payloadPreview.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(packet.payloadPreview, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun FlowRow(flow: FlowSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(flow.protocol.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("${flow.packetCount} pkt", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(flow.conversationLabel, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(flow.latestSummary, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("${formatBytes(flow.totalBytes)} • son: ${dateTimeText(flow.lastSeen)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SessionRow(session: CaptureSessionSummary, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (session.isActive) "ACTIVE" else session.status, fontWeight = FontWeight.Bold)
                Text(dateTimeText(session.startedAt), style = MaterialTheme.typography.labelMedium)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(session.engineLabel, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(session.filterSummary, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(4.dp))
            Text("${session.packetCount} paket • ${formatBytes(session.totalBytes)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AppFilterModeSelector(selected: AppFilterMode, onSelected: (AppFilterMode) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppFilterMode.entries.forEach { mode ->
            FilterChip(
                selected = mode == selected,
                onClick = { onSelected(mode) },
                label = {
                    Text(
                        when (mode) {
                            AppFilterMode.ALL_APPS -> "Tümü"
                            AppFilterMode.ALLOW_SELECTED -> "Sadece seçilenler"
                            AppFilterMode.BLOCK_SELECTED -> "Seçilenleri hariç tut"
                        },
                    )
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
    }
}

@Composable
private fun AppSelectionRow(app: InstalledAppInfo, selected: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(app.label)
            Text(app.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun PacketDetailDialog(packet: CapturedPacket, onDismiss: () -> Unit) {
    val sections = remember(packet.id) { PacketInspector.inspect(packet) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
        title = { Text("Paket detayı") },
        text = {
            SelectionContainer {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Zaman: ${dateTimeText(packet.timestamp)}")
                            Text("Yön: ${packet.direction}")
                            Text("Protokol: ${packet.protocol.label}")
                            Text("Kaynak: ${packet.sourceLabel}")
                            Text("Hedef: ${packet.destinationLabel}")
                            Text("Uzunluk: ${packet.length} byte")
                            Text("Özet: ${packet.summary}")
                        }
                    }
                    sections.forEach { section ->
                        PacketSectionCard(section)
                    }
                    if (packet.payloadPreview.isNotBlank()) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Payload preview", fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(packet.payloadPreview, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Ham hex", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(packet.rawHex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PacketSectionCard(section: PacketSection) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(section.title, fontWeight = FontWeight.Bold)
            section.fields.forEach { field ->
                Text("${field.label}: ${field.value}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FlowDetailDialog(flow: FlowSummary, packets: List<CapturedPacket>, onDismiss: () -> Unit) {
    val inboundCount = packets.count { it.direction == PacketDirection.INBOUND }
    val outboundCount = packets.count { it.direction == PacketDirection.OUTBOUND }
    val avgSize = if (packets.isNotEmpty()) packets.sumOf { it.length }.toDouble() / packets.size else 0.0
    val topSummaries = packets.groupingBy { it.summary }.eachCount().entries.sortedByDescending { it.value }.take(5)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kapat") } },
        title = { Text("Akış detayı") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Protokol: ${flow.protocol.label}")
                Text("Uçlar: ${flow.conversationLabel}")
                Text("Paket sayısı: ${flow.packetCount}")
                Text("Toplam veri: ${formatBytes(flow.totalBytes)}")
                Text("İlk görülen: ${dateTimeText(flow.firstSeen)}")
                Text("Son görülen: ${dateTimeText(flow.lastSeen)}")
                Text("Inbound / Outbound: $inboundCount / $outboundCount")
                Text("Ortalama boyut: ${DecimalFormat("0.0").format(avgSize)} byte")
                Text("Son özet: ${flow.latestSummary}")
                if (topSummaries.isNotEmpty()) {
                    Text("En sık özetler:", fontWeight = FontWeight.Bold)
                    topSummaries.forEach { entry ->
                        Text("• ${entry.key} (${entry.value})", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("Son paketler:", fontWeight = FontWeight.Bold)
                packets.forEach { packet ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(timeText(packet.timestamp), style = MaterialTheme.typography.labelMedium)
                            Text(packet.summary, style = MaterialTheme.typography.bodyMedium)
                            Text(packet.conversationLabel, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun SessionDetailDialog(
    session: CaptureSessionSummary,
    packets: List<CapturedPacket>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Row {
                TextButton(onClick = onExport) { Text("Export") }
                TextButton(onClick = onShare) { Text("Paylaş") }
                TextButton(onClick = onDismiss) { Text("Kapat") }
            }
        },
        title = { Text("Oturum detayı") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Başlangıç: ${dateTimeText(session.startedAt)}")
                Text("Bitiş: ${session.endedAt?.let(::dateTimeText) ?: "aktif"}")
                Text("Engine: ${session.engineLabel}")
                Text("Filtre: ${session.filterSummary}")
                Text("Durum: ${session.status}")
                Text("Paket sayısı: ${session.packetCount}")
                Text("Toplam veri: ${formatBytes(session.totalBytes)}")
                Spacer(modifier = Modifier.height(4.dp))
                Text("Bu oturumdan son paketler:", fontWeight = FontWeight.Bold)
                if (loading) {
                    CircularProgressIndicator()
                } else if (packets.isEmpty()) {
                    Text("Bu oturum için kayıtlı paket bulunamadı")
                } else {
                    packets.forEach { packet ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Text(timeText(packet.timestamp), style = MaterialTheme.typography.labelMedium)
                                Text(packet.summary, style = MaterialTheme.typography.bodyMedium)
                                Text(packet.conversationLabel, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text)
    }
}

private fun parseSearchQuery(query: String): SearchQuery {
    val plain = mutableListOf<String>()
    var ip: String? = null
    var src: String? = null
    var dst: String? = null
    var port: Int? = null
    var protocol: String? = null
    var direction: String? = null
    var fromInclusive: Long? = null
    var toExclusive: Long? = null

    query.trim().split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .forEach { tokenRaw ->
            val token = tokenRaw.trim()
            when {
                token.startsWith("ip:", true) -> ip = token.substringAfter(':').lowercase()
                token.startsWith("src:", true) -> src = token.substringAfter(':').lowercase()
                token.startsWith("dst:", true) -> dst = token.substringAfter(':').lowercase()
                token.startsWith("port:", true) -> port = token.substringAfter(':').toIntOrNull()
                token.startsWith("proto:", true) -> protocol = token.substringAfter(':').uppercase()
                token.startsWith("dir:", true) -> direction = token.substringAfter(':').lowercase()
                token.startsWith("from:", true) -> fromInclusive = parseDayStartMillis(token.substringAfter(':'))
                token.startsWith("to:", true) -> toExclusive = parseDayStartMillis(token.substringAfter(':'))?.plus(DAY_MS)
                else -> plain += token.lowercase()
            }
        }

    return SearchQuery(
        plainTerms = plain,
        ip = ip,
        src = src,
        dst = dst,
        port = port,
        protocol = protocol,
        direction = direction,
        fromInclusive = fromInclusive,
        toExclusive = toExclusive,
    )
}

private fun packetMatches(packet: CapturedPacket, selectedProtocol: String, query: SearchQuery): Boolean {
    val protocolFilter = query.protocol ?: selectedProtocol.takeIf { it != "ALL" }
    if (protocolFilter != null && packet.protocol.label != protocolFilter) return false
    if (!timestampMatches(packet.timestamp, query)) return false
    if (query.ip != null && !listOf(packet.srcIp, packet.dstIp).any { it.lowercase().contains(query.ip) }) return false
    if (query.src != null && !packet.srcIp.lowercase().contains(query.src) && !packet.sourceLabel.lowercase().contains(query.src)) return false
    if (query.dst != null && !packet.dstIp.lowercase().contains(query.dst) && !packet.destinationLabel.lowercase().contains(query.dst)) return false
    if (query.port != null && packet.srcPort != query.port && packet.dstPort != query.port) return false
    if (query.direction != null) {
        val normalized = when (packet.direction) {
            PacketDirection.INBOUND -> "in"
            PacketDirection.OUTBOUND -> "out"
            PacketDirection.UNKNOWN -> "unknown"
        }
        if (!normalized.startsWith(query.direction)) return false
    }

    val haystack = buildString {
        append(packet.srcIp.lowercase())
        append(' ')
        append(packet.dstIp.lowercase())
        append(' ')
        append(packet.summary.lowercase())
        append(' ')
        append(packet.payloadPreview.lowercase())
        append(' ')
        append(packet.sourceLabel.lowercase())
        append(' ')
        append(packet.destinationLabel.lowercase())
    }
    return query.plainTerms.all { haystack.contains(it) }
}

private fun flowMatches(flow: FlowSummary, selectedProtocol: String, query: SearchQuery): Boolean {
    val protocolFilter = query.protocol ?: selectedProtocol.takeIf { it != "ALL" }
    if (protocolFilter != null && flow.protocol.label != protocolFilter) return false
    if (!rangeMatches(flow.firstSeen, flow.lastSeen, query)) return false
    if (query.ip != null && !listOf(flow.endpointA, flow.endpointB).any { it.lowercase().contains(query.ip) }) return false
    if (query.src != null && !flow.endpointA.lowercase().contains(query.src) && !flow.endpointB.lowercase().contains(query.src)) return false
    if (query.dst != null && !flow.endpointA.lowercase().contains(query.dst) && !flow.endpointB.lowercase().contains(query.dst)) return false
    if (query.port != null && !flow.endpointA.contains(":${query.port}") && !flow.endpointB.contains(":${query.port}")) return false

    val haystack = buildString {
        append(flow.endpointA.lowercase())
        append(' ')
        append(flow.endpointB.lowercase())
        append(' ')
        append(flow.latestSummary.lowercase())
    }
    return query.plainTerms.all { haystack.contains(it) }
}

private fun sessionMatches(
    session: CaptureSessionSummary,
    query: SearchQuery,
    pickerFrom: Long?,
    pickerTo: Long?,
): Boolean {
    if (!rangeMatches(session.startedAt, session.endedAt ?: session.startedAt, query)) return false
    val sessionEnd = session.endedAt ?: session.startedAt
    if (pickerFrom != null && sessionEnd < pickerFrom) return false
    if (pickerTo != null && session.startedAt >= pickerTo + DAY_MS) return false
    val haystack = buildString {
        append(session.engineLabel.lowercase())
        append(' ')
        append(session.filterSummary.lowercase())
        append(' ')
        append(session.status.lowercase())
    }
    return query.plainTerms.all { haystack.contains(it) }
}

private fun timestampMatches(timestamp: Long, query: SearchQuery): Boolean {
    if (query.fromInclusive != null && timestamp < query.fromInclusive) return false
    if (query.toExclusive != null && timestamp >= query.toExclusive) return false
    return true
}

private fun rangeMatches(start: Long, end: Long, query: SearchQuery): Boolean {
    if (query.fromInclusive != null && end < query.fromInclusive) return false
    if (query.toExclusive != null && start >= query.toExclusive) return false
    return true
}

private fun parseDayStartMillis(value: String): Long? {
    return try {
        LocalDate.parse(value.trim())
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

private fun timeText(timestamp: Long): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

private fun dateOnlyText(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

private fun dateTimeText(timestamp: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "${DecimalFormat("0.0").format(bytes / 1024.0)} KB"
    return "${DecimalFormat("0.0").format(bytes / 1024.0 / 1024.0)} MB"
}

private const val DAY_MS = 86_400_000L
