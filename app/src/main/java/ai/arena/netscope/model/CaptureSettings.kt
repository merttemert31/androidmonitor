package ai.arena.netscope.model

enum class AppFilterMode {
    ALL_APPS,
    ALLOW_SELECTED,
    BLOCK_SELECTED,
}

data class CaptureSettings(
    val persistPackets: Boolean = true,
    val nativeTcpBridgeEnabled: Boolean = false,
    val tun2SocksHost: String = "127.0.0.1",
    val tun2SocksPort: Int = 1080,
    val appFilterMode: AppFilterMode = AppFilterMode.ALL_APPS,
    val selectedPackages: Set<String> = emptySet(),
    val maxVisiblePackets: Int = 600,
    val maxStoredPackets: Int = 2_000,
)

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
)
