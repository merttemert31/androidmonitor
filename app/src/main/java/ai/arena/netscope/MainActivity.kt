package ai.arena.netscope

import android.Manifest
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import ai.arena.netscope.service.PacketCaptureVpnService
import ai.arena.netscope.ui.NetScopeApp
import ai.arena.netscope.ui.theme.NetScopeTheme

class MainActivity : ComponentActivity() {
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            startCaptureService()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            NetScopeTheme {
                NetScopeApp(
                    onStartCapture = ::requestVpnPermission,
                    onStopCapture = ::stopCaptureService,
                )
            }
        }
    }

    private fun requestVpnPermission() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            vpnPermissionLauncher.launch(prepareIntent)
        } else {
            startCaptureService()
        }
    }

    private fun startCaptureService() {
        val intent = Intent(this, PacketCaptureVpnService::class.java)
            .setAction(PacketCaptureVpnService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopCaptureService() {
        val intent = Intent(this, PacketCaptureVpnService::class.java)
            .setAction(PacketCaptureVpnService.ACTION_STOP)
        startService(intent)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
