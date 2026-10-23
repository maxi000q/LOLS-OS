package os.lols.ui

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas

/** A glass surface: translucent panel + hairline border + optional real blur. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    scrim: Float = 0.55f,
    content: @Composable BoxScope.() -> Unit,
) {
    val mode = LocalGraphics.current
    Box(
        modifier
            .clip(shape)
            .background(OsPalette.bg.copy(alpha = scrim), shape)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
    ) {
        if (mode == GraphicsMode.HIGH && Build.VERSION.SDK_INT >= 31) {
            Box(
                Modifier
                    .matchParentSize()
                    .blur(26.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                OsPalette.accent.copy(alpha = 0.05f),
                                Color.Transparent,
                                OsPalette.accent2.copy(alpha = 0.05f),
                            )
                        )
                    )
            )
        }
        content()
    }
}

enum class OsButtonStyle { PRIMARY, GLASS, DANGER }

@Composable
fun OsButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: OsButtonStyle = OsButtonStyle.GLASS,
    tint: Color = OsPalette.accent,
    small: Boolean = false,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(if (small) 13.dp else 18.dp)
    val (bg, fg) = when (style) {
        OsButtonStyle.PRIMARY -> tint.copy(alpha = 0.92f) to OsPalette.bgDeep
        OsButtonStyle.DANGER -> OsPalette.red.copy(alpha = 0.16f) to OsPalette.red
        OsButtonStyle.GLASS -> Color.White.copy(alpha = 0.07f) to OsPalette.text
    }
    Box(
        modifier
            .clip(shape)
            .background(bg, shape)
            .border(
                1.dp,
                if (style == OsButtonStyle.PRIMARY) tint.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.10f),
                shape
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = if (small) 8.dp else 13.dp, horizontal = if (small) 12.dp else 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = fg,
            fontSize = if (small) 13.sp else 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Custom toggle switch - glass pill with an animated knob. */
@Composable
fun OsToggle(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val knob by animateDpAsState(if (checked) 22.dp else 4.dp, spring(dampingRatio = 0.6f), label = "knob")
    Box(
        modifier
            .width(52.dp)
            .height(30.dp)
            .clip(CircleShape)
            .background(if (checked) OsPalette.accent.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) OsPalette.bgDeep else OsPalette.text.copy(alpha = 0.85f))
        )
    }
}

/** Row with label + toggle. */
@Composable
fun OsToggleRow(
    label: String,
    sub: String = "",
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = OsPalette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (sub.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(sub, color = OsPalette.textDim, fontSize = 12.sp, fontWeight = null, maxLines = 2)
                }
            }
            OsToggle(checked, onChange)
        }
    }
}

/** Slider wrapped in glass. */
@Composable
fun OsSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    format: (Float) -> String = { "%.0f".format(it) },
    onChange: (Float) -> Unit,
) {
    GlassSurface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = OsPalette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(format(value), color = OsPalette.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = range,
                steps = steps,
                colors = SliderDefaults.colors(
                    thumbColor = OsPalette.accent,
                    activeTrackColor = OsPalette.accent.copy(alpha = 0.8f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.12f),
                ),
            )
        }
    }
}

/** Segmented control with animated selection. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    GlassSurface(modifier, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(4.dp)) {
            options.forEachIndexed { i, opt ->
                val active = i == selected
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) OsPalette.accent.copy(alpha = 0.9f) else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        opt,
                        color = if (active) OsPalette.bgDeep else OsPalette.textDim,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Field colors shared by terminal & settings inputs. */
@Composable
fun TerminalFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedTextColor = OsPalette.text,
    unfocusedTextColor = OsPalette.text,
    focusedBorderColor = OsPalette.accent.copy(alpha = 0.6f),
    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
    cursorColor = OsPalette.accent,
    focusedPlaceholderColor = OsPalette.textDim,
    unfocusedPlaceholderColor = OsPalette.textDim,
    focusedContainerColor = Color.White.copy(alpha = 0.04f),
    unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
)

/** App icon: colored glass rounded square + glyph. */
@Composable
fun AppIconTile(
    glyph: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 58.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.30f))
            .background(
                Brush.linearGradient(
                    listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.14f))
                )
            )
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(size * 0.30f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = color, fontSize = (size.value * 0.42f).sp, fontWeight = null, maxLines = 1)
    }
}

/** PIN pad used by lock screen and setup wizard. */
@Composable
fun PinPad(
    entered: Int,
    maxDigits: Int = 4,
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // dots
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(maxDigits) { i ->
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (i < entered) OsPalette.accent else Color.White.copy(alpha = 0.15f))
                )
            }
        }
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
        )
        for (r in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                for (d in r) {
                    PinKey(d) { onDigit(d) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(72.dp))
            PinKey("0") { onDigit("0") }
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .clickable { onBackspace() },
                contentAlignment = Alignment.Center,
            ) {
                Text("⌫", color = OsPalette.textDim, fontSize = 22.sp, fontWeight = null, maxLines = 1)
            }
        }
        if (onCancel != null) {
            OsButton(t("Cancel", "إلغاء"), onCancel, small = true)
        }
    }
}

@Composable
private fun PinKey(label: String, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (pressed) 0.22f else 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = OsPalette.text, fontSize = 26.sp, fontWeight = FontWeight.Normal, maxLines = 1)
    }
}
