package os.lols.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import os.lols.kernel.AppInfo
import os.lols.kernel.MessageBus
import os.lols.ui.AppIconTile
import os.lols.ui.GlassSurface
import os.lols.ui.LocalKernel
import os.lols.ui.OsButton
import os.lols.ui.OsPalette
import os.lols.ui.parseHexColor
import os.lols.ui.t

/**
 * The pull-down shade: real notifications on one side, real quick controls
 * on the other. Swipe/click the scrim to close.
 */
@Composable
fun NotificationShade(
    onClose: () -> Unit,
    onLock: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onClose() } }
            .background(Color.Black.copy(alpha = 0.50f)),
    ) {
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically { -it / 2 } + fadeIn(),
            exit = slideOutVertically { -it / 2 } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .padding(10.dp),
            ) {
                GlassSurface(Modifier.fillMaxSize(), shape = RoundedCornerShape(30.dp)) {
                    Row(Modifier.fillMaxSize().padding(14.dp)) {
                        // ---- notifications ----
                        NotificationsColumn(Modifier.weight(1.15f).fillMaxHeight())
                        Spacer(Modifier.width(12.dp))
                        // ---- quick controls ----
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            ControlCenter(onLock, onOpenTerminal, onClose)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationsColumn(modifier: Modifier) {
    val kernel = LocalKernel.current
    val notifs by kernel.bus.notifs.collectAsState()

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t("Notifications", "الإشعارات"), color = OsPalette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (notifs.isNotEmpty()) {
                OsButton(t("Clear all", "مسح الكل"), { kernel.bus.clearAll() }, small = true)
            }
        }
        if (notifs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(t("No notifications", "لا توجد إشعارات"), color = OsPalette.textDim, fontSize = 13.sp)
            }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                notifs.forEach { NotifCard(it) }
            }
        }
    }
}

@Composable
private fun NotifCard(n: MessageBus.Notif) {
    val kernel = LocalKernel.current
    val apps by kernel.packages.apps.collectAsState()
    val app: AppInfo? = apps.firstOrNull { it.id == n.appId }
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.padding(10.dp).clickable { kernel.bus.dismiss(n.id) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconTile(
                glyph = app?.icon ?: "\u25CE",
                color = parseHexColor(app?.color, OsPalette.slate),
                size = 34.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(n.title, color = OsPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (n.body.isNotEmpty()) {
                    Text(n.body, color = OsPalette.textDim, fontSize = 12.sp, maxLines = 3)
                }
            }
            Spacer(Modifier.width(6.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(n.timeMs)),
                    color = OsPalette.textDim, fontSize = 10.sp,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .size(22.dp)
                        .clickable { kernel.bus.dismiss(n.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("\u2715", color = OsPalette.textDim, fontSize = 11.sp)
                }
            }
        }
    }
}
