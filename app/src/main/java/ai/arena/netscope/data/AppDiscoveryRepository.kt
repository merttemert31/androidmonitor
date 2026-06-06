package ai.arena.netscope.data

import android.content.Context
import android.content.Intent
import ai.arena.netscope.model.InstalledAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object AppDiscoveryRepository {
    private val _apps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val apps: StateFlow<List<InstalledAppInfo>> = _apps.asStateFlow()

    private var initialized = false

    fun initialize(context: Context, scope: CoroutineScope) {
        if (initialized) return
        initialized = true

        scope.launch(Dispatchers.IO) {
            val packageManager = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val installedApps = packageManager.queryIntentActivities(intent, 0)
                .map {
                    InstalledAppInfo(
                        packageName = it.activityInfo.packageName,
                        label = it.loadLabel(packageManager)?.toString() ?: it.activityInfo.packageName,
                    )
                }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
            _apps.value = installedApps
        }
    }
}
