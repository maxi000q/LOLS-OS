package os.lols.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import os.lols.kernel.AppInfo
import os.lols.lols.LolsError
import os.lols.ui.AppIconTile
import os.lols.ui.GlassSurface
import os.lols.ui.LocalKernel
import os.lols.ui.OsButton
import os.lols.ui.OsPalette
import os.lols.ui.parseHexColor
import os.lols.ui.t

/** Home screen: real app grid (from the package manager) + dock + recents. */
@Composable
fun HomeScreen(
    onOpen: (Current) -> Unit,
) {
    val kernel = LocalKernel.current
    val scope = rememberCoroutineScope()
    val apps by kernel.packages.apps.collectAsState()
    val notifs by kernel.bus.notifs.collectAsState()
    val processes by kernel.processes.processes.collectAsState()

    var menuFor by remember { mutableStateOf<AppInfo?>(null) }
    var recentsOpen by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(6.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(apps, key = { it.id }) { app ->
                    val unread = notifs.count { it.appId == app.id }
                    AppGridIcon(
                        app = app,
                        badge = unread,
                        onOpen = {
                            if (app.entry.startsWith("native:")) {
                                onOpen(Current.Native(app.entry.removePrefix("native:")))
                            } else {
                                scope.launch {
                                    val pid = kernel.processes.spawn(app)
                                    if (pid != null) onOpen(Current.Lols(pid))
                                }
                            }
                        },
                        onLongPress = { menuFor = app },
                    )
                }
            }

            // dock
            GlassSurface(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp, top = 6.dp),
                shape = RoundedCornerShape(26.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val dockApps = dockApps(apps)
                    for (app in dockApps) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            AppIconTile(
                                glyph = app.icon,
                                color = parseHexColor(app.color, OsPalette.accent),
                                size = 50.dp,
                                modifier = Modifier.clickable {
                                    if (app.entry.startsWith("native:")) {
                                        onOpen(Current.Native(app.entry.removePrefix("native:")))
                                    } else {
                                        scope.launch {
                                            val pid = kernel.processes.spawn(app)
                                            if (pid != null) onOpen(Current.Lols(pid))
                                        }
                                    }
                                },
                            )
                        }
                    }
                    // recents button
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(OsPalette.accent.copy(alpha = 0.85f), OsPalette.accent2.copy(alpha = 0.85f))
                                    )
                                )
                                .clickable { recentsOpen = true },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("\u2B21", color = OsPalette.bgDeep, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // long-press context menu
        menuFor?.let { app ->
            ContextMenu(
                app = app,
                running = processes.any { it.appId == app.id && it.state == "running" },
                onClose = { menuFor = null },
                onUninstall = {
                    menuFor = null
                    scope.launch {
                        try {
                            kernel.packages.uninstall(app.id)
                        } catch (e: LolsError) {
                            kernel.bus.post("os.lols", app.name, e.message ?: "")
                        }
                    }
                },
                onForceStop = {
                    menuFor = null
                    kernel.processes.findByAppAny(app.id)?.let { kernel.processes.kill(it.pid, "force stop") }
                },
            )
        }

        if (recentsOpen) {
            RecentsOverlay(
                onClose = { recentsOpen = false },
                onSwitch = {
                    recentsOpen = false
                    onOpen(it)
                },
            )
        }
    }
}

private fun dockApps(apps: List<AppInfo>): List<AppInfo> {
    val preferred = listOf("os.lols.store", "com.lols.notes", "com.lols.files")
    val found = preferred.mapNotNull { id -> apps.firstOrNull { it.id == id } }
    if (found.size >= 3) return found
    return apps.filter { it.id !in found }.take(3 - found.size).let { found + it }
}

@Composable
private fun AppGridIcon(
    app: AppInfo,
    badge: Int,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .pointerInput(app.id) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onLongPress = { onLongPress() },
                )
            }
            .padding(vertical = 6.dp),
    ) {
        Box {
            AppIconTile(
                glyph = app.icon,
                color = parseHexColor(app.color, OsPalette.accent),
                size = 58.dp,
            )
            if (badge > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(OsPalette.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (badge > 9) "9+" else "$badge",
                        color = OsPalette.bgDeep,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            app.name,
            color = OsPalette.textDim,
            fontSize = 11.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun ContextMenu(
    app: AppInfo,
    running: Boolean,
    onClose: () -> Unit,
    onUninstall: () -> Unit,
    onForceStop: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(Modifier.padding(24.dp), shape = RoundedCornerShape(24.dp)) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppIconTile(app.icon, parseHexColor(app.color, OsPalette.accent), 54.dp)
                Text(app.name, color = OsPalette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("v${app.version} \u00B7 ${app.id}", color = OsPalette.textDim, fontSize = 11.sp)
                if (app.perms.isNotEmpty()) {
                    Text(
                        t("permissions", "الصلاحيات") + ": " + app.perms.joinToString(", "),
                        color = OsPalette.amber, fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(4.dp))
                if (running) {
                    OsButton(t("Force stop", "إيقاف إجباري"), onForceStop, style = os.lols.ui.OsButtonStyle.DANGER)
                }
                if (!app.system) {
                    OsButton(t("Uninstall", "إلغاء التثبيت"), onUninstall, style = os.lols.ui.OsButtonStyle.DANGER)
                }
                OsButton(t("Close", "إغلاق"), onClose)
            }
        }
    }
}
