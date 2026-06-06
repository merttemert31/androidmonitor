package ai.arena.netscope.service

object Tun2SocksBridge {
    init {
        runCatching {
            System.loadLibrary("netscope-tun2socks")
        }
    }

    external fun nativeIsAvailable(): Boolean
    external fun nativeStart(
        tunFd: Int,
        mtu: Int,
        socksHost: String,
        socksPort: Int,
    ): Boolean
    external fun nativeStop()

    fun isAvailable(): Boolean = runCatching { nativeIsAvailable() }.getOrDefault(false)

    fun start(tunFd: Int, mtu: Int, socksHost: String, socksPort: Int): Boolean {
        return runCatching { nativeStart(tunFd, mtu, socksHost, socksPort) }.getOrDefault(false)
    }

    fun stop() {
        runCatching { nativeStop() }
    }
}
