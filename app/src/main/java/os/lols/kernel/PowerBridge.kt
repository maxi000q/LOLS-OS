package os.lols.kernel

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Real host-device telemetry bridge.
 * All numbers here are REAL: live battery level, charging state, actual
 * RAM of the phone, CPU core count, model and Android version - read
 * through public Android APIs with zero permissions.
 */
class PowerBridge(private val ctx: Context, scope: CoroutineScope) {

    data class HostPower(
        val batteryPct: Int,
        val charging: Boolean,
        val hostRamTotalMb: Int,
        val hostRamAvailMb: Int,
        val cores: Int,
        val model: String,
        val androidVersion: String,
    )

    private val _flow = MutableStateFlow(snapshot())
    val flow = _flow.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                kotlinx.coroutines.delay(15_000)
                _flow.value = snapshot()
            }
        }
    }

    @Suppress("DEPRECATION")
    fun snapshot(): HostPower {
        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = ctx.registerReceiver(null, ifilter)
        var pct = 100
        var charging = false
        if (intent != null) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) pct = (level * 100 / scale).coerceIn(0, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(mi)
        val totalMb = if (mi != null) (mi.totalMem / (1024 * 1024)).toInt() else 0
        val availMb = if (mi != null) (mi.availMem / (1024 * 1024)).toInt() else 0
        return HostPower(
            batteryPct = pct,
            charging = charging,
            hostRamTotalMb = totalMb,
            hostRamAvailMb = availMb,
            cores = Runtime.getRuntime().availableProcessors(),
            model = Build.MODEL ?: "unknown",
            androidVersion = Build.VERSION.RELEASE ?: "?",
        )
    }
}
