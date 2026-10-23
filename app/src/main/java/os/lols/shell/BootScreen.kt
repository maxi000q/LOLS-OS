package os.lols.shell

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.lols.kernel.Kernel
import os.lols.ui.AppIconTile
import os.lols.ui.GlassSurface
import os.lols.ui.LocalKernel
import os.lols.ui.OsPalette

/**
 * Boot screen: logo pulse + a REAL kernel console. Every line shown here
 * is an actual event emitted by the real boot sequence with real timings.
 */
@Composable
fun BootScreen() {
    val kernel = LocalKernel.current
    var lines by remember { mutableStateOf(listOf<String>()) }
    LaunchedEffect(Unit) {
        kernel.bootLog.collect { line ->
            lines = (lines + line).takeLast(11)
        }
    }

    val transition = rememberInfiniteTransition(label = "boot")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.graphicsLayer { scaleX = pulse; scaleY = pulse }) {
            AppIconTile(glyph = "L", color = OsPalette.accent, size = 84.dp)
        }
        Spacer(Modifier.height(14.dp))
        Text(Kernel.OS_NAME, color = OsPalette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "v${Kernel.KERNEL_VERSION} \u00B7 MIT",
            color = OsPalette.textDim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(28.dp))
        GlassSurface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                if (lines.isEmpty()) {
                    Text("...", color = OsPalette.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                lines.takeLast(11).forEach { line ->
                    Text(
                        line,
                        color = OsPalette.accent.copy(alpha = 0.75f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
