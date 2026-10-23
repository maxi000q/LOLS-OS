package os.lols.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.sin
import kotlin.random.Random

/**
 * The living wallpaper: animated aurora ribbons over a deep gradient.
 * Quality tier controls REAL behavior: HIGH animates, BALANCED slows it
 * down, LITE draws a static frame - saving real GPU work.
 */
class WpPalette(
    val top: Color,
    val bottom: Color,
    val glows: List<Color>,
    val stars: Boolean,
)

val WALLPAPERS = listOf(
    WpPalette(
        top = Color(0xFF0B1026),
        bottom = Color(0xFF060913),
        glows = listOf(Color(0xFF6EE7D8), Color(0xFFA78BFA), Color(0xFF60A5FA)),
        stars = true,
    ),
    WpPalette(
        top = Color(0xFF1C0A0A),
        bottom = Color(0xFF0A0505),
        glows = listOf(Color(0xFFFB923C), Color(0xFFF87171), Color(0xFFFBBF24)),
        stars = false,
    ),
    WpPalette(
        top = Color(0xFF07140D),
        bottom = Color(0xFF04100B),
        glows = listOf(Color(0xFF6EE7B7), Color(0xFF34D399), Color(0xFFFBBF24)),
        stars = true,
    ),
)

@Composable
fun Wallpaper(
    index: Int = 0,
    modifier: Modifier = Modifier,
) {
    val mode = LocalGraphics.current
    val palette = WALLPAPERS[index.coerceIn(0, WALLPAPERS.size - 1)]
    val duration = when (mode) {
        GraphicsMode.HIGH -> 22000
        GraphicsMode.BALANCED -> 55000
        GraphicsMode.LITE -> Int.MAX_VALUE
    }
    val phase: Float
    if (mode == GraphicsMode.LITE) {
        phase = 0.4f
    } else {
        val t = rememberInfiniteTransition(label = "wp")
        val p by t.animateFloat(
            initialValue = 0f,
            targetValue = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing), RepeatMode.Restart),
            label = "phase",
        )
        phase = p
    }
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(palette.top, palette.bottom)))
        if (palette.stars) drawStars(seed = index * 31 + 7)
        drawRibbons(palette, phase)
    }
}

private fun DrawScope.drawStars(seed: Int) {
    val rng = Random(seed)
    repeat(46) {
        val x = rng.nextFloat() * size.width
        val y = rng.nextFloat() * size.height * 0.7f
        val r = 0.8f + rng.nextFloat() * 1.8f
        drawCircle(Color.White.copy(alpha = 0.04f + rng.nextFloat() * 0.10f), radius = r, center = Offset(x, y))
    }
}

private fun DrawScope.drawRibbons(p: WpPalette, phase: Float) {
    for (i in 0..2) {
        val baseY = size.height * (0.28f + 0.22f * i)
        val amp = size.height * (0.10f + 0.04f * i)
        val freq = 1.4f + 0.5f * i
        val path = Path()
        var x = 0f
        path.moveTo(0f, baseY + amp * sin(phase * (1f + i * 0.3f)))
        while (x < size.width) {
            val y = baseY + amp * sin(phase * (1f + i * 0.3f) + x / size.width * Math.PI.toFloat() * freq)
            path.lineTo(x, y)
            x += 26f
        }
        drawPath(
            path,
            color = p.glows[i].copy(alpha = 0.13f),
            style = Stroke(width = size.height * (0.16f - i * 0.03f), cap = StrokeCap.Round),
        )
    }
}
