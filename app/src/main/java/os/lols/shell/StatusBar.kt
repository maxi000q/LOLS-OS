package os.lols.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import os.lols.ui.LocalKernel
import os.lols.ui.OsPalette
import os.lols.ui.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Top status bar: real clock, real battery, DND state, real notification count. */
@Composable
fun StatusBar(onPullDown: () -> Unit) {
    val kernel = LocalKernel.current
    val power by kernel.power.flow.collectAsState()
    val settings by kernel.settings.collectAsState()
    val notifs by kernel.bus.notifs.collectAsState()

    var timeText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, amount ->
                    change.consume()
                    if (amount > 16f) onPullDown()
                }
            }
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(timeText, color = OsPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        if (settings.dnd) {
            Text("\u263E", color = OsPalette.amber, fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
        }
        if (notifs.isNotEmpty()) {
            Text("\u25CF", color = OsPalette.accent, fontSize = 8.sp)
            Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.weight(1f))
        if (settings.langArabic) {
            Text("\u0639", color = OsPalette.textDim, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
        }
        Text("${power.batteryPct}", color = OsPalette.textDim, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        BatteryGlyph(pct = power.batteryPct, charging = power.charging, tint = OsPalette.text)
    }
}

/** Minimal battery glyph drawn for real from host battery state. */
@Composable
fun BatteryGlyph(pct: Int, charging: Boolean, tint: Color) {
    Canvas(Modifier.size(26.dp, 13.dp)) {
        val stroke = Stroke(width = 1.6f)
        drawRoundRect(
            color = tint.copy(alpha = 0.45f),
            topLeft = Offset(1f, 1.5f),
            size = Size(size.width - 5f, size.height - 3f),
            cornerRadius = CornerRadius(4f, 4f),
            style = stroke,
        )
        drawRoundRect(
            color = tint.copy(alpha = 0.45f),
            topLeft = Offset(size.width - 2.5f, size.height / 2f - 2f),
            size = Size(2.5f, 4f),
            cornerRadius = CornerRadius(1.5f, 1.5f),
        )
        val w = (size.width - 11f) * pct.coerceIn(0, 100) / 100f
        if (w > 1f) {
            drawRoundRect(
                color = tint,
                topLeft = Offset(4f, 4.5f),
                size = Size(w, size.height - 9f),
                cornerRadius = CornerRadius(2f, 2f),
            )
        }
        if (charging) {
            drawLine(
                color = OsPalette.bgDeep,
                start = Offset(size.width * 0.32f, 3f),
                end = Offset(size.width * 0.55f, size.height - 3f),
                strokeWidth = 2.4f,
                cap = StrokeCap.Round,
            )
        }
    }
}
