package os.lols.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.lols.ui.LocalKernel
import os.lols.ui.OsPalette
import os.lols.ui.PinPad
import os.lols.ui.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lock screen: the PIN is verified for real against a salted SHA-256 hash
 * stored in /sys/security. Wrong PIN shakes - no fake unlocks.
 */
@Composable
fun LockScreen(onUnlock: () -> Unit) {
    val kernel = LocalKernel.current
    val settings by kernel.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }

    var timeText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    LaunchedEffect(settings.langArabic) {
        while (true) {
            val locale = Locale(if (settings.langArabic) "ar" else "en")
            timeText = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            dateText = SimpleDateFormat("EEEE, d MMMM", locale).format(Date())
            delay(1000L - System.currentTimeMillis() % 1000L)
        }
    }

    fun tryUnlock() {
        scope.launch {
            val ok = kernel.checkPin(pin)
            if (ok) {
                onUnlock()
            } else {
                error = true
                shake.animateTo(
                    0f,
                    keyframes {
                        durationMillis = 320
                        0f at 0
                        -16f at 40
                        16f at 100
                        -9f at 160
                        9f at 220
                        0f at 320
                    },
                )
                delay(500)
                pin = ""
                error = false
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(shake.value.roundToInt(), 0) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(30.dp))
        Text(
            timeText,
            color = OsPalette.text,
            fontSize = 58.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Default,
        )
        Text(dateText, color = OsPalette.textDim, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        Text(
            t("Enter PIN", "أدخل رمز القفل"),
            color = if (error) OsPalette.red else OsPalette.textDim,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(18.dp))
        PinPad(
            entered = pin.length,
            onDigit = {
                if (pin.length < 4) {
                    pin += it
                    if (pin.length == 4) tryUnlock()
                }
            },
            onBackspace = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
        )
        Spacer(Modifier.height(46.dp))
    }
}
