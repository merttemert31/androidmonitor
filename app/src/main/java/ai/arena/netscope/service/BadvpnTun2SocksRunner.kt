package ai.arena.netscope.service

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

class BadvpnTun2SocksRunner(
    private val context: Context,
) {
    private var process: Process? = null
    private var socketPath: String? = null
    private var drainThread: Thread? = null

    fun isBinaryAvailable(): Boolean {
        val binary = resolveBinaryPath() ?: return false
        return File(binary).exists()
    }

    @Throws(IOException::class)
    fun start(
        tunInterface: ParcelFileDescriptor,
        socksHost: String,
        socksPort: Int,
        mtu: Int,
        enableUdpRelay: Boolean = false,
        udpGatewayPort: Int = 7300,
    ) {
        if (process != null) return

        val binary = resolveBinaryPath()
            ?: throw IOException("libtun2socks.so bulunamadı; gerçek badvpn engine paketlenmemiş")

        val socketFile = File(context.filesDir, "tun2socks-${System.currentTimeMillis()}.sock")
        socketFile.delete()
        socketPath = socketFile.absolutePath

        val args = mutableListOf(
            binary,
            "--netif-ipaddr", "10.0.0.2",
            "--netif-netmask", "255.255.255.0",
            "--socks-server-addr", "$socksHost:$socksPort",
            "--tunmtu", mtu.toString(),
            "--sock-path", socketFile.absolutePath,
            "--loglevel", "notice",
        )
        if (enableUdpRelay) {
            args += listOf(
                "--enable-udprelay",
                "--udpgw-remote-server-addr", "$socksHost:$udpGatewayPort",
            )
        }

        process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()

        drainThread = Thread {
            runCatching {
                process?.inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { /* drain native output to avoid pipe backpressure */ }
                }
            }
        }.apply { start() }

        sendTunFd(socketFile.absolutePath, tunInterface.fileDescriptor)
    }

    fun stop() {
        runCatching { process?.destroy() }
        runCatching { drainThread?.interrupt() }
        process = null
        drainThread = null
        socketPath?.let { path -> runCatching { File(path).delete() } }
        socketPath = null
    }

    private fun sendTunFd(sockPath: String, fileDescriptor: java.io.FileDescriptor) {
        var lastError: Exception? = null
        repeat(10) { attempt ->
            try {
                Thread.sleep((attempt * 150L).coerceAtLeast(50L))
                LocalSocket().use { socket ->
                    socket.connect(LocalSocketAddress(sockPath, LocalSocketAddress.Namespace.FILESYSTEM))
                    socket.setFileDescriptorsForSend(arrayOf(fileDescriptor))
                    socket.outputStream.write(42)
                    socket.outputStream.flush()
                }
                return
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw IOException("tun fd gönderilemedi", lastError)
    }

    private fun resolveBinaryPath(): String? {
        val libDir = context.applicationInfo.nativeLibraryDir ?: return null
        val candidates = listOf(
            File(libDir, "libtun2socks.so"),
            File(libDir, "badvpn-tun2socks"),
        )
        return candidates.firstOrNull { it.exists() }?.absolutePath
    }
}
