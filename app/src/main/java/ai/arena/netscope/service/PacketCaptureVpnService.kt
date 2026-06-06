package ai.arena.netscope.service


import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.arena.netscope.MainActivity
import ai.arena.netscope.R
import ai.arena.netscope.data.CaptureRepository
import ai.arena.netscope.data.SettingsRepository
import ai.arena.netscope.model.AppFilterMode
import ai.arena.netscope.model.CaptureSettings
import ai.arena.netscope.model.PacketDirection
import ai.arena.netscope.parser.PacketParser
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PacketCaptureVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Any()

    private var tunInterface: ParcelFileDescriptor? = null
    private var readJob: Job? = null
    private var packetForwarder: PacketForwarder? = null
    private var jniTunFd: Int? = null
    private var jniBridgeStarted = false
    private var badvpnRunner: BadvpnTun2SocksRunner? = null
    private var badvpnProcessStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> {
                stopCapture("Capture stopped")
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture("Capture stopped")
        super.onDestroy()
    }

    private fun startCapture() {
        if (tunInterface != null) return

        val settings = SettingsRepository.settings.value
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                getString(R.string.app_name),
                "Capture aktif • gerçek badvpn varsa TCP tun2socks, yoksa Java UDP fallback",
            ),
        )

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(DEFAULT_MTU)
            .addAddress(TUN_INTERFACE_IP, 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .allowBypass()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(true)
        }

        applyAppFiltering(builder, settings)

        tunInterface = builder.establish()
        val tun = tunInterface
        if (tun == null) {
            CaptureRepository.reportError("VPN arayüzü oluşturulamadı")
            stopSelf()
            return
        }

        val initialEngineLabel = when {
            settings.nativeTcpBridgeEnabled && BadvpnTun2SocksRunner(this).isBinaryAvailable() ->
                "badvpn tun2socks (${settings.tun2SocksHost}:${settings.tun2SocksPort})"
            settings.nativeTcpBridgeEnabled -> "Java fallback (badvpn unavailable)"
            else -> "Java capture + UDP forwarding"
        }

        CaptureRepository.startCapture(
            statusMessage = buildStatusMessage(settings),
            engineLabel = initialEngineLabel,
            filterSummary = buildFilterSummary(settings),
        )

        if (settings.nativeTcpBridgeEnabled && tryStartBadvpnProcess(settings, tun)) {
            CaptureRepository.updateStatus(
                "Gerçek badvpn tun2socks process başlatıldı. TCP trafiği SOCKS üstünden yönlendirilecek; Java UDP fallback devre dışı.",
            )
            return
        }

        if (settings.nativeTcpBridgeEnabled && tryStartJniBridge(settings, tun.fileDescriptor)) {
            CaptureRepository.updateStatus(
                "JNI tun2socks bridge etkin. Bu yol ancak gerçek native motor bağlandıysa çalışır.",
            )
            return
        }

        startJavaFallbackLoop(tun.fileDescriptor)
    }

    private fun tryStartBadvpnProcess(settings: CaptureSettings, tun: ParcelFileDescriptor): Boolean {
        if (settings.tun2SocksHost.isBlank()) return false
        val runner = BadvpnTun2SocksRunner(this)
        if (!runner.isBinaryAvailable()) {
            CaptureRepository.updateStatus(
                "libtun2socks.so paketlenmemiş; gerçek TCP forwarding için third_party/badvpn build scripti ile jniLibs üretmelisin.",
            )
            return false
        }

        return runCatching {
            runner.start(
                tunInterface = tun,
                socksHost = settings.tun2SocksHost,
                socksPort = settings.tun2SocksPort,
                mtu = DEFAULT_MTU,
                enableUdpRelay = false,
            )
            badvpnRunner = runner
            badvpnProcessStarted = true
            true
        }.getOrElse { error ->
            CaptureRepository.updateStatus("badvpn process başlatılamadı: ${error.message}")
            runner.stop()
            false
        }
    }

    private fun tryStartJniBridge(settings: CaptureSettings, descriptor: java.io.FileDescriptor): Boolean {
        if (settings.tun2SocksHost.isBlank()) return false
        if (!Tun2SocksBridge.isAvailable()) return false

        val detached = runCatching { ParcelFileDescriptor.dup(descriptor).detachFd() }
            .getOrElse {
                CaptureRepository.updateStatus("JNI bridge için TUN fd kopyalanamadı: ${it.message}")
                return false
            }

        jniTunFd = detached
        val started = Tun2SocksBridge.start(
            tunFd = detached,
            mtu = DEFAULT_MTU,
            socksHost = settings.tun2SocksHost,
            socksPort = settings.tun2SocksPort,
        )
        if (!started) {
            jniTunFd = null
            runCatching { ParcelFileDescriptor.adoptFd(detached).close() }
            CaptureRepository.updateStatus(
                "JNI/NDK scaffold hazır ancak gerçek native motor henüz bağlanmadı; Java UDP forwarding moduna dönüldü.",
            )
        } else {
            jniBridgeStarted = true
        }
        return started
    }

    private fun startJavaFallbackLoop(descriptor: java.io.FileDescriptor) {
        val inputStream = FileInputStream(descriptor)
        val outputStream = FileOutputStream(descriptor)

        packetForwarder = PacketForwarder(
            udpForwarder = UdpForwarder(
                serviceScope = serviceScope,
                protectSocket = { socket -> protect(socket) },
                writePacketToTun = { inboundPacket ->
                    synchronized(writeLock) {
                        outputStream.write(inboundPacket)
                        outputStream.flush()
                    }
                    PacketParser.parse(inboundPacket, PacketDirection.INBOUND)?.let(CaptureRepository::pushPacket)
                },
                onLog = CaptureRepository::updateStatus,
            ),
            onStatus = CaptureRepository::updateStatus,
        )

        readJob = serviceScope.launch {
            val buffer = ByteArray(65_535)
            try {
                while (isActive) {
                    val length = inputStream.read(buffer)
                    if (length <= 0) {
                        delay(10)
                        continue
                    }
                    val packetBytes = buffer.copyOf(length)
                    PacketParser.parse(packetBytes, PacketDirection.OUTBOUND)?.let(CaptureRepository::pushPacket)
                    runCatching { packetForwarder?.forward(packetBytes) }
                        .onFailure { error ->
                            CaptureRepository.reportError("Forwarding error: ${error.message ?: error.javaClass.simpleName}")
                        }
                }
            } catch (error: Exception) {
                if (isActive) {
                    CaptureRepository.reportError("Capture error: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                runCatching { inputStream.close() }
                runCatching { outputStream.close() }
            }
        }
    }

    private fun applyAppFiltering(builder: Builder, settings: CaptureSettings) {
        when (settings.appFilterMode) {
            AppFilterMode.ALL_APPS -> runCatching { builder.addDisallowedApplication(packageName) }
            AppFilterMode.BLOCK_SELECTED -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                settings.selectedPackages.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            }
            AppFilterMode.ALLOW_SELECTED -> {
                settings.selectedPackages.forEach { pkg -> runCatching { builder.addAllowedApplication(pkg) } }
            }
        }
    }

    private fun buildStatusMessage(settings: CaptureSettings): String {
        val filterSummary = buildFilterSummary(settings)
        val persistence = if (settings.persistPackets) "kalıcı kayıt açık" else "kalıcı kayıt kapalı"
        val engine = buildEngineLabel(settings)
        return "$engine • $filterSummary • $persistence"
    }

    private fun buildFilterSummary(settings: CaptureSettings): String {
        return when (settings.appFilterMode) {
            AppFilterMode.ALL_APPS -> "tüm uygulamalar"
            AppFilterMode.ALLOW_SELECTED -> "yalnız seçili uygulamalar"
            AppFilterMode.BLOCK_SELECTED -> "seçili uygulamalar hariç"
        }
    }

    private fun buildEngineLabel(settings: CaptureSettings): String {
        return if (settings.nativeTcpBridgeEnabled) {
            "badvpn tun2socks denemesi (${settings.tun2SocksHost}:${settings.tun2SocksPort})"
        } else {
            "Java capture + UDP forwarding"
        }
    }

    private fun stopCapture(message: String) {
        readJob?.cancel()
        readJob = null

        packetForwarder?.close()
        packetForwarder = null

        if (badvpnProcessStarted) {
            badvpnRunner?.stop()
            badvpnRunner = null
            badvpnProcessStarted = false
        }

        if (jniBridgeStarted) {
            Tun2SocksBridge.stop()
            jniBridgeStarted = false
        }
        jniTunFd?.let { fd -> runCatching { ParcelFileDescriptor.adoptFd(fd).close() } }
        jniTunFd = null

        runCatching { tunInterface?.close() }
        tunInterface = null

        CaptureRepository.stopCapture(message)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(title: String, text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Packet Capture", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_START = "ai.arena.netscope.action.START_CAPTURE"
        const val ACTION_STOP = "ai.arena.netscope.action.STOP_CAPTURE"

        private const val CHANNEL_ID = "packet_capture"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_MTU = 1500
        private const val TUN_INTERFACE_IP = "10.0.0.1"
    }
}
