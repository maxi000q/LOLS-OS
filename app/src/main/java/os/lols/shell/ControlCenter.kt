package os.lols.shell

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import os.lols.kernel.MemoryManager
import os.lols.ui.GlassSurface
import os.lols.ui.GraphicsMode
import os.lols.ui.LocalKernel
import os.lols.ui.OsPalette
import os.lols.ui.Segmented
import os.lols.ui.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Control center: every control here does something REAL.
 * - Brightness changes the actual window brightness.
 * - Memory shows live kernel heap usage vs the configured pool.
 * - Battery ring is the real host battery.
 * - Graphics/Language/DND write real kernel settings.
 */
@Composable
fun ControlCenter(
    onLock: () -> Unit,
    onOpenTerminal: () -> Unit,
    onCloseShade: () -> Unit,
) {
    val kernel = LocalKernel.current
    val context = LocalContext.current
    val settings by kernel.settings.collectAsState()
    val power by kernel.power.flow.collectAsState()
    val memUsed by kernel.mem.used.collectAsState()

    var brightness by remember { mutableStateOf(0.5f) }
    var timeText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // clock + battery ring
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(timeText, color = OsPalette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(uptime(kernel.uptimeMs()), color = OsPalette.textDim, fontSize = 11.sp)
            }
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(62.dp)) {
                    drawArc(
                        color = Color.White.copy(alpha = 0.12f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = if (power.charging) OsPalette.green else OsPalette.accent,
                        startAngle = -90f,
                        sweepAngle = 360f * power.batteryPct / 100f,
                        useCenter = false,
                        style = Stroke(width = 7.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                Text("${power.batteryPct}", color = OsPalette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        // brightness (REAL window brightness)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(t("Brightness", "السطوع"), color = OsPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            androidx.compose.material3.Slider(
                value = brightness,
                onValueChange = { v ->
                    brightness = v
                    (context as? Activity)?.window?.attributes =
                        (context as? Activity)?.window?.attributes?.apply { screenBrightness = v }
                },
                valueRange = 0.05f..1f,
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = OsPalette.accent,
                    activeTrackColor = OsPalette.accent.copy(alpha = 0.8f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.12f),
                ),
            )
        }

        // tiles
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CcTile(
                glyph = if (settings.dnd) "\u263E" else "\u263D",
                label = t("Do not disturb", "عدم الإزعاج"),
                active = settings.dnd,
                modifier = Modifier.weight(1f),
            ) { kernel.update { it.copy(dnd = !it.dnd) } }
            CcTile(
                glyph = if (settings.langArabic) "A" else "\u0639",
                label = t("Language", "اللغة"),
                active = settings.langArabic,
                modifier = Modifier.weight(1f),
            ) { kernel.update { it.copy(langArabic = !it.langArabic) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CcTile(
                glyph = "\u2726",
                label = t("Lock now", "قفل الآن"),
                active = false,
                modifier = Modifier.weight(1f),
            ) {
                onCloseShade()
                onLock()
            }
            CcTile(
                glyph = "\u276F",
                label = t("Terminal", "الطرفية"),
                active = false,
                modifier = Modifier.weight(1f),
            ) {
                onCloseShade()
                onOpenTerminal()
            }
        }

        // graphics quality (REAL rendering difference)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(10.dp),
        ) {
            Text(t("Graphics", "الرسوميات"), color = OsPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Segmented(
                options = listOf(t("High", "عالية"), t("Balanced", "متوازنة"), t("Lite", "خفيفة")),
                selected = settings.graphics.coerceIn(0, 2),
            ) { i -> kernel.update { it.copy(graphics = i) } }
        }

        // live memory (REAL kernel heap usage)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row {
                Text(t("Memory", "الذاكرة"), color = OsPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Text(
                    "${MemoryManager.fmt(memUsed)} / ${MemoryManager.fmt(kernel.mem.quotaBytes())}",
                    color = OsPalette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            val frac = if (kernel.mem.quotaBytes() > 0) {
                (memUsed.toFloat() / kernel.mem.quotaBytes()).coerceIn(0f, 1f)
            } else 0f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(7.dp)
                        .clip(CircleShape)
                        .background(
                            if (frac > 0.85f) OsPalette.red else OsPalette.accent
                        ),
                )
            }
        }
    }
}

@Composable
private fun CcTile(
    glyph: String,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (active) OsPalette.accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                glyph,
                color = if (active) OsPalette.accent else OsPalette.text,
                fontSize = 20.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = if (active) OsPalette.accent else OsPalette.textDim,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun uptime(ms: Long): String {
    if (ms <= 0) return t("starting...", "جارٍ التشغيل...")
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    return when {
        h > 0 -> "up ${h}h ${m}m"
        m > 0 -> "up ${m}m ${s % 60}s"
        else -> "up ${s}s"
    }
}
