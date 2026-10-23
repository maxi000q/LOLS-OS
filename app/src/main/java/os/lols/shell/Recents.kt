package os.lols.shell

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import os.lols.kernel.MemoryManager
import os.lols.kernel.ProcInfo
import os.lols.ui.AppIconTile
import os.lols.ui.GlassSurface
import os.lols.ui.LocalKernel
import os.lols.ui.OsButton
import os.lols.ui.OsPalette
import os.lols.ui.parseHexColor
import os.lols.ui.t

/**
 * Recents: the REAL process table. Every card is a live process with its
 * actual PID, heap usage and uptime - switch or kill for real.
 */
@Composable
fun RecentsOverlay(
    onClose: () -> Unit,
    onSwitch: (Current) -> Unit,
) {
    val kernel = LocalKernel.current
    val scope = rememberCoroutineScope()
    val processes by kernel.processes.processes.collectAsState()
    val apps by kernel.packages.apps.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable { onClose() },
    ) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(t("Running apps", "التطبيقات الجارية"), color = OsPalette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(
                t("real processes with real memory", "عمليات حقيقية بذاكرة حقيقية"),
                color = OsPalette.textDim, fontSize = 12.sp,
            )
            Spacer(Modifier.height(18.dp))
            if (processes.isEmpty()) {
                GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("\u2B21", color = OsPalette.textDim, fontSize = 34.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(t("No apps running", "لا توجد تطبيقات جارية"), color = OsPalette.textDim, fontSize = 14.sp)
                    }
                }
            } else {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    processes.forEach { info ->
                        RecentsCard(
                            info = info,
                            color = parseHexColor(
                                apps.firstOrNull { it.id == info.appId }?.color,
                                OsPalette.accent,
                            ),
                            onSwitch = {
                                if (info.state == "running") {
                                    onSwitch(Current.Lols(info.pid))
                                } else {
                                    val app = apps.firstOrNull { it.id == info.appId }
                                    if (app != null) {
                                        scope.launch {
                                            kernel.processes.kill(info.pid, "relaunch")
                                            val pid = kernel.processes.spawn(app)
                                            if (pid != null) onSwitch(Current.Lols(pid))
                                        }
                                    }
                                }
                            },
                            onKill = { kernel.processes.kill(info.pid, "user closed") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentsCard(
    info: ProcInfo,
    color: Color,
    onSwitch: () -> Unit,
    onKill: () -> Unit,
) {
    GlassSurface(Modifier.width(150.dp), shape = RoundedCornerShape(22.dp)) {
        Column(
            Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIconTile("\u25CE", color, 44.dp)
            Text(info.name, color = OsPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("pid ${info.pid}", color = OsPalette.textDim, fontSize = 10.sp)
            Text(
                when (info.state) {
                    "running" -> t("running", "جارٍ التشغيل")
                    "error" -> t("stopped", "متوقف")
                    else -> info.state
                },
                color = if (info.state == "running") OsPalette.green else OsPalette.red,
                fontSize = 10.sp,
            )
            Text(MemoryManager.fmt(info.heapBytes), color = OsPalette.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(uptimeShort(info.uptimeMs), color = OsPalette.textDim, fontSize = 10.sp)
            Spacer(Modifier.height(2.dp))
            OsButton(
                if (info.state == "running") t("Open", "فتح") else t("Relaunch", "إعادة تشغيل"),
                onSwitch, small = true,
                style = os.lols.ui.OsButtonStyle.PRIMARY, tint = color,
            )
            OsButton(t("Kill", "إنهاء"), onKill, small = true, style = os.lols.ui.OsButtonStyle.DANGER)
        }
    }
}

private fun uptimeShort(ms: Long): String {
    val s = ms / 1000
    return if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"
}
