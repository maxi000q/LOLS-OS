package os.lols.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import os.lols.kernel.Kernel

/** Original LOLS OS palette - deep space glass. */
object OsPalette {
    val bg = Color(0xFF070B14)
    val bgDeep = Color(0xFF05070E)
    val text = Color(0xFFF2F5FF)
    val textDim = Color(0xFF8A93A6)
    val accent = Color(0xFF6EE7D8)      // mint
    val accent2 = Color(0xFFA78BFA)     // violet
    val amber = Color(0xFFFBBF24)
    val rose = Color(0xFFF472B6)
    val sky = Color(0xFF60A5FA)
    val green = Color(0xFF4ADE80)
    val red = Color(0xFFF87171)
    val emerald = Color(0xFF34D399)
    val slate = Color(0xFF94A3B8)
}

/** Graphics quality tier - controlled from Settings, affects REAL rendering. */
enum class GraphicsMode { HIGH, BALANCED, LITE }

/** The kernel, provided at the OS root. */
val LocalKernel = staticCompositionLocalOf<Kernel> { error("Kernel not provided") }

/** Inline bilingual text: t("English", "العربية"). */
val LocalT = staticCompositionLocalOf<(String, String) -> String> { { en, _ -> en } }

/** Graphics mode, provided at the OS root from kernel settings. */
val LocalGraphics = staticCompositionLocalOf<GraphicsMode> { GraphicsMode.HIGH }

@Composable
fun t(en: String, ar: String): String = LocalT.current(en, ar)

/** Parse "#RRGGBB" (or "#AARRGGBB") into a Compose color. */
fun parseHexColor(s: String?, def: Color): Color {
    if (s == null) return def
    return try {
        val hex = s.trim().removePrefix("#")
        when (hex.length) {
            6 -> Color(("FF" + hex).toLong(16))
            8 -> Color(hex.toLong(16))
            else -> def
        }
    } catch (e: Exception) {
        def
    }
}

private val scheme = darkColorScheme(
    primary = OsPalette.accent,
    onPrimary = OsPalette.bgDeep,
    secondary = OsPalette.accent2,
    onSecondary = OsPalette.bgDeep,
    tertiary = OsPalette.amber,
    background = OsPalette.bg,
    onBackground = OsPalette.text,
    surface = OsPalette.bg,
    onSurface = OsPalette.text,
    surfaceVariant = OsPalette.bg.copy(alpha = 0.6f),
    onSurfaceVariant = OsPalette.textDim,
    error = OsPalette.red,
    onError = OsPalette.bgDeep,
    outline = OsPalette.textDim,
)

/** The one and only LOLS theme - always dark, always glass. */
@Composable
fun OsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
