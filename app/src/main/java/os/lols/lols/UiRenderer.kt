package os.lols.lols

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import os.lols.ui.OsButton
import os.lols.ui.OsButtonStyle
import os.lols.ui.OsPalette
import os.lols.ui.parseHexColor

/**
 * Renders a resolved RNode tree (produced by the LOLS interpreter) as real
 * Compose UI. Events fire back into the interpreter through rt.fire().
 */
@Composable
fun LolsAppView(rt: Runtime) {
    val tree by rt.uiTree.collectAsState()
    val node = tree
    if (node == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(rt.process.name, color = OsPalette.textDim, fontSize = 14.sp)
        }
    } else {
        NodeView(node, rt, Modifier.fillMaxSize())
    }
}

@Composable
fun NodeView(n: RNode, rt: Runtime, modifier: Modifier) {
    when (n.kind) {
        "Screen" -> Box(modifier) {
            n.children.forEach { NodeView(it, rt, Modifier.fillMaxSize()) }
        }

        "Column" -> Column(
            modifier.padding(n.num("padding", 0.0).dp),
            verticalArrangement = Arrangement.spacedBy(n.num("spacing", 0.0).dp),
            horizontalAlignment = when (n.str("align", "start")) {
                "center" -> Alignment.CenterHorizontally
                "end" -> Alignment.End
                else -> Alignment.Start
            },
        ) {
            n.children.forEach { child ->
                val m = if (child.kind == "Scroll" || child.kind == "Canvas") {
                    Modifier.weight(1f).fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth()
                }
                NodeView(child, rt, m)
            }
        }

        "Row" -> Row(
            modifier.padding(n.num("padding", 0.0).dp),
            horizontalArrangement = Arrangement.spacedBy(n.num("spacing", 0.0).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            n.children.forEach { child ->
                NodeView(child, rt, if (n.children.size > 1) Modifier.weight(1f) else Modifier)
            }
        }

        "Grid" -> {
            val cols = n.num("columns", 4.0).toInt().coerceIn(1, 8)
            val spacing = n.num("spacing", 8.0).dp
            Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
                n.children.chunked(cols).forEach { rowChildren ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                    ) {
                        repeat(cols) { i ->
                            val child = rowChildren.getOrNull(i)
                            if (child != null) NodeView(child, rt, Modifier.weight(1f))
                            else Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        "Scroll" -> Column(
            modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(n.num("spacing", 0.0).dp),
        ) {
            n.children.forEach { NodeView(it, rt, Modifier.fillMaxWidth()) }
        }

        "Card" -> Box(
            modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
                .padding(n.num("padding", 12.0).dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(n.num("spacing", 0.0).dp)) {
                n.children.forEach { NodeView(it, rt, Modifier.fillMaxWidth()) }
            }
        }

        "Text" -> {
            val style = n.str("style", "body")
            val defColor = if (style == "caption") OsPalette.textDim else OsPalette.text
            Text(
                text = n.str("value"),
                color = parseHexColor(n.props["color"]?.display(), defColor),
                style = textStyle(style),
                textAlign = when (n.str("align", "start")) {
                    "center" -> TextAlign.Center
                    "end" -> TextAlign.End
                    else -> TextAlign.Start
                },
                modifier = modifier,
            )
        }

        "Button" -> {
            val h = n.callable("onClick")
            OsButton(
                text = n.str("label"),
                onClick = { h?.let { rt.fire(it, null) } },
                modifier = modifier,
                style = when (n.str("style", "glass")) {
                    "primary" -> OsButtonStyle.PRIMARY
                    "danger" -> OsButtonStyle.DANGER
                    else -> OsButtonStyle.GLASS
                },
                tint = parseHexColor(n.props["color"]?.display(), OsPalette.accent),
                enabled = n.flag("enabled", true),
            )
        }

        "Field" -> {
            val h = n.callable("onChange")
            val multiline = n.flag("multiline", false)
            OutlinedTextField(
                value = n.str("value"),
                onValueChange = { txt -> h?.let { rt.fire(it, LV.LStr(txt)) } },
                placeholder = { Text(n.str("placeholder"), color = OsPalette.textDim, fontSize = 14.sp) },
                singleLine = !multiline,
                minLines = if (multiline) 3 else 1,
                modifier = modifier,
                shape = RoundedCornerShape(16.dp),
                textStyle = TextStyle(fontSize = 15.sp, color = OsPalette.text),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OsPalette.text,
                    unfocusedTextColor = OsPalette.text,
                    focusedBorderColor = OsPalette.accent.copy(alpha = 0.6f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.14f),
                    cursorColor = OsPalette.accent,
                    focusedPlaceholderColor = OsPalette.textDim,
                    unfocusedPlaceholderColor = OsPalette.textDim,
                    focusedContainerColor = Color.White.copy(alpha = 0.04f),
                    unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
                ),
            )
        }

        "Canvas" -> LolsCanvas(n, rt, modifier)

        "Divider" -> Box(
            modifier
                .fillMaxWidth(0.75f)
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.10f))
        )

        "Spacer" -> Spacer(modifier.height(n.num("h", 8.0).dp))

        else -> {} // unknown node kinds are ignored (forward compatibility)
    }
}

private fun textStyle(style: String): TextStyle = when (style) {
    "display" -> TextStyle(fontSize = 42.sp, fontWeight = FontWeight.Bold, lineHeight = 50.sp)
    "title" -> TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    "caption" -> TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal)
    "mono" -> TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    else -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal)
}

@Composable
private fun LolsCanvas(n: RNode, rt: Runtime, modifier: Modifier) {
    val handler = n.callable("onPoint")
    var lastX by remember { mutableStateOf(0) }
    var lastY by remember { mutableStateOf(0) }
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(OsPalette.bgDeep.copy(alpha = 0.6f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp)),
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(rt) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            lastX = (pos.x / size.width * 1000f).toInt().coerceIn(0, 1000)
                            lastY = (pos.y / size.height * 1000f).toInt().coerceIn(0, 1000)
                            handler?.let { rt.fire(it, pointArg(lastX, lastY, "down")) }
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            lastX = (change.position.x / size.width * 1000f).toInt().coerceIn(0, 1000)
                            lastY = (change.position.y / size.height * 1000f).toInt().coerceIn(0, 1000)
                            handler?.let { rt.fire(it, pointArg(lastX, lastY, "move")) }
                        },
                        onDragEnd = {
                            handler?.let { rt.fire(it, pointArg(lastX, lastY, "up")) }
                        },
                    )
                },
        ) {
            drawStrokes(n.props["strokes"] as? LV.LList)
        }
    }
}

private fun pointArg(x: Int, y: Int, phase: String): LV.LMap {
    val m = LinkedHashMap<String, LV>()
    m["x"] = LV.LNum(x.toDouble())
    m["y"] = LV.LNum(y.toDouble())
    m["phase"] = LV.LStr(phase)
    return LV.LMap(m)
}

private fun DrawScope.drawStrokes(strokes: LV.LList?) {
    if (strokes == null) return
    val sx = size.width / 1000f
    val sy = size.height / 1000f
    for (s in strokes.items) {
        if (s !is LV.LMap) continue
        val pts = (s.entries["points"] as? LV.LList)?.items ?: continue
        if (pts.isEmpty()) continue
        val path = Path()
        for ((i, p) in pts.withIndex()) {
            if (p !is LV.LMap) continue
            val x = ((p.entries["x"] as? LV.LNum)?.v?.toFloat() ?: 0f) * sx
            val y = ((p.entries["y"] as? LV.LNum)?.v?.toFloat() ?: 0f) * sy
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val color = parseHexColor((s.entries["color"] as? LV.LStr)?.s, OsPalette.rose)
        val w = ((s.entries["width"] as? LV.LNum)?.v?.toFloat() ?: 6f) * ((sx + sy) / 2f)
        drawPath(
            path,
            color = color,
            style = Stroke(width = w.coerceAtLeast(2f), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
