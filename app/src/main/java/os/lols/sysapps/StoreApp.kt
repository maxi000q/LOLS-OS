package os.lols.sysapps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import os.lols.kernel.AppInfo
import os.lols.kernel.RepoEntry
import os.lols.lols.LolsError
import os.lols.ui.AppIconTile
import os.lols.ui.GlassSurface
import os.lols.ui.LocalKernel
import os.lols.ui.OsButton
import os.lols.ui.OsButtonStyle
import os.lols.ui.OsPalette
import os.lols.ui.Segmented
import os.lols.ui.parseHexColor
import os.lols.ui.t

/**
 * Store: manages REAL packages. Install from the bundled repo (actual
 * .lols ZIPs), uninstall, reinstall - all hitting the real package
 * manager and the real filesystem.
 */
@Composable
fun StoreApp() {
    val kernel = LocalKernel.current
    val scope = rememberCoroutineScope()
    val apps by kernel.packages.apps.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val repo = remember { kernel.packages.repoList() }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Segmented(
            options = listOf(t("Installed", "المثبتة"), t("Store", "المتجر")),
            selected = tab,
        ) { tab = it }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (tab == 0) {
                if (apps.isEmpty()) {
                    EmptyHint(t("No apps installed", "لا توجد تطبيقات مثبتة"))
                }
                for (app in apps) {
                    InstalledRow(app) {
                        scope.launch {
                            try {
                                kernel.packages.uninstall(app.id)
                            } catch (e: LolsError) {
                                kernel.bus.post("os.lols", app.name, e.message ?: "")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    t(
                        "All apps run on the LOLS runtime and are MIT licensed.",
                        "جميع التطبيقات تعمل على بيئة LOLS ورخصتها MIT."
                    ),
                    color = OsPalette.textDim, fontSize = 11.sp,
                )
            } else {
                if (repo.isEmpty()) {
                    EmptyHint(t("Repo is empty", "المستودع فارغ"))
                }
                for (entry in repo) {
                    val installed = apps.any { it.id == entry.id }
                    RepoRow(entry, installed, busy = busyId == entry.id) {
                        scope.launch {
                            busyId = entry.id
                            try {
                                kernel.packages.installFromRepo(entry.id)
                                kernel.bus.post("os.lols", entry.name, kernel.t("installed successfully", "تم التثبيت بنجاح"))
                            } catch (e: Exception) {
                                kernel.bus.post("os.lols", entry.name, kernel.t("install failed", "فشل التثبيت") + ": " + (e.message ?: ""))
                            }
                            busyId = null
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    t(
                        ".lols packages are real ZIP containers: manifest.lols + main.lols.",
                        "حزم .lols هي ملفات ZIP حقيقية: manifest.lols + main.lols."
                    ),
                    color = OsPalette.textDim, fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun InstalledRow(app: AppInfo, onUninstall: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconTile(app.icon, parseHexColor(app.color, OsPalette.accent), 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, color = OsPalette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "v${app.version} \u00B7 " + if (app.system) t("system", "نظام") else t("user app", "تطبيق مستخدم"),
                    color = OsPalette.textDim, fontSize = 11.sp,
                )
                if (app.perms.isNotEmpty()) {
                    Text(app.perms.joinToString(", "), color = OsPalette.amber, fontSize = 10.sp)
                }
            }
            if (!app.system) {
                OsButton(t("Uninstall", "إلغاء التثبيت"), onUninstall, small = true, style = OsButtonStyle.DANGER)
            }
        }
    }
}

@Composable
private fun RepoRow(entry: RepoEntry, installed: Boolean, busy: Boolean, onInstall: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconTile(entry.icon, parseHexColor(entry.color, OsPalette.accent), 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, color = OsPalette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "v${entry.version} \u00B7 ${entry.sizeBytes / 1024} KB \u00B7 ${entry.id}",
                    color = OsPalette.textDim, fontSize = 11.sp,
                )
            }
            if (busy) {
                Text(t("installing\u2026", "جارٍ التثبيت\u2026"), color = OsPalette.accent, fontSize = 12.sp)
            } else {
                OsButton(
                    if (installed) t("Reinstall", "إعادة تثبيت") else t("Install", "تثبيت"),
                    onInstall, small = true,
                    style = if (installed) OsButtonStyle.GLASS else OsButtonStyle.PRIMARY,
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
        Text(text, color = OsPalette.textDim, fontSize = 13.sp)
    }
}
