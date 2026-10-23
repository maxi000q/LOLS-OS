package os.lols.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import os.lols.kernel.Kernel
import os.lols.lols.LolsAppView
import os.lols.sysapps.SettingsApp
import os.lols.sysapps.StoreApp
import os.lols.sysapps.TerminalApp
import os.lols.ui.AppIconTile
import os.lols.ui.GraphicsMode
import os.lols.ui.GlassSurface
import os.lols.ui.LocalGraphics
import os.lols.ui.LocalKernel
import os.lols.ui.LocalT
import os.lols.ui.OsPalette
import os.lols.ui.OsTheme
import os.lols.ui.Wallpaper
import os.lols.ui.parseHexColor
import os.lols.ui.t

/** What is currently in the foreground. */
sealed class Current {
    object None : Current()
    data class Native(val key: String) : Current()
    data class Lols(val pid: Int) : Current()
}

private enum class OsPhase { BOOT, SETUP, LOCK, DESKTOP }

/**
 * The OS root: boot -> setup/lock -> desktop. Hosts the wallpaper, the
 * status bar, the current app, the notification shade and banners.
 */
@Composable
fun OsRoot() {
    val kernel = LocalKernel.current
    val settings by kernel.settings.collectAsState()
    val bootDone by kernel.bootDone.collectAsState()

    var phase by remember { mutableStateOf(OsPhase.BOOT) }
    var current by remember { mutableStateOf<Current>(Current.None) }
    var shadeOpen by remember { mutableStateOf(false) }
    var backPressedAt by remember { mutableStateOf(0L) }

    LaunchedEffect(bootDone) {
        if (!bootDone) {
            // boot (or reboot) in progress: return to the boot screen
            phase = OsPhase.BOOT
            current = Current.None
            shadeOpen = false
        } else if (phase == OsPhase.BOOT) {
            val hasPin = kernel.hasPin()
            phase = when {
                !hasPin -> OsPhase.SETUP
                settings.lockEnabled -> OsPhase.LOCK
                else -> OsPhase.DESKTOP
            }
        }
    }

    fun goHome() {
        current = Current.None
    }
    fun lockNow() {
        current = Current.None
        shadeOpen = false
        phase = OsPhase.LOCK
    }

    val langArabic = settings.langArabic
    val tFun = remember(langArabic) {
        { en: String, ar: String -> if (langArabic) ar else en }
    }

    CompositionLocalProvider(
        LocalT provides tFun,
        LocalGraphics provides GraphicsMode.entries[settings.graphics.coerceIn(0, 2)],
        LocalLayoutDirection provides if (langArabic) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        OsTheme {
            Box(Modifier.fillMaxSize().background(OsPalette.bg)) {
                Wallpaper(settings.wallpaper, Modifier.fillMaxSize())

                when (phase) {
                    OsPhase.BOOT -> BootScreen()
                    OsPhase.SETUP -> SetupWizard(onDone = { phase = OsPhase.DESKTOP })
                    OsPhase.LOCK -> LockScreen(onUnlock = { phase = OsPhase.DESKTOP })
                    OsPhase.DESKTOP -> {
                        Column(Modifier.fillMaxSize().statusBarsPadding()) {
                            StatusBar(onPullDown = { shadeOpen = true })
                            Box(Modifier.fillMaxSize()) {
                                when (val c = current) {
                                    is Current.None -> HomeScreen(onOpen = { current = it })
                                    is Current.Native -> NativeHost(c.key, goHome = ::goHome, lockNow = ::lockNow)
                                    is Current.Lols -> LolsHost(c.pid, goHome = ::goHome)
                                }
                            }
                        }
                    }
                }

                if (shadeOpen && phase == OsPhase.DESKTOP) {
                    NotificationShade(
                        onClose = { shadeOpen = false },
                        onLock = { lockNow() },
                        onOpenTerminal = { current = Current.Native("terminal") },
                    )
                }

                BannerHost()
            }

            // back navigation (registered low -> high priority)
            val exitMsg = t("Press back again to exit", "اضغط رجوع مرة أخرى للخروج")
            BackHandler(enabled = current == Current.None && phase == OsPhase.DESKTOP && !shadeOpen) {
                val now = System.currentTimeMillis()
                if (now - backPressedAt < 2200) {
                    kernel.shutdown()
                    android.os.Process.killProcess(android.os.Process.myPid())
                } else {
                    backPressedAt = now
                    kernel.bus.post("os.lols", Kernel.OS_NAME, exitMsg)
                }
            }
            BackHandler(enabled = current != Current.None && !shadeOpen) { goHome() }
            BackHandler(enabled = shadeOpen) { shadeOpen = false }
        }
    }
}

/** Header shown above every foreground app. */
@Composable
private fun AppChrome(
    title: String,
    glyph: String,
    color: Color,
    goHome: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .clickable { goHome() },
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2039", color = OsPalette.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        AppIconTile(glyph, color, 30.dp)
        Spacer(Modifier.width(10.dp))
        Text(title, color = OsPalette.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LolsHost(pid: Int, goHome: () -> Unit) {
    val kernel = LocalKernel.current
    val processes by kernel.processes.processes.collectAsState()
    val apps by kernel.packages.apps.collectAsState()
    val info = processes.firstOrNull { it.pid == pid }
    val appInfo = apps.firstOrNull { it.id == info?.appId }

    Column(Modifier.fillMaxSize()) {
        AppChrome(
            title = info?.name ?: "...",
            glyph = appInfo?.icon ?: "\u25CE",
            color = parseHexColor(appInfo?.color, OsPalette.accent),
            goHome = goHome,
        )
        val proc = kernel.processes.byPid(pid)
        if (info == null || proc == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t("This app is no longer running", "هذا التطبيق لم يعد جارياً"), color = OsPalette.textDim, fontSize = 14.sp)
                    OsButtonMini(t("Back to home", "العودة للرئيسية"), goHome)
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 10.dp)) {
                LolsAppView(proc.rt)
            }
        }
    }
}

@Composable
private fun NativeHost(key: String, goHome: () -> Unit, lockNow: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        when (key) {
            "store" -> {
                AppChrome("Store", "\u25C6", OsPalette.emerald, goHome)
                StoreApp()
            }
            "settings" -> {
                AppChrome("Settings", "\u2699", OsPalette.slate, goHome)
                SettingsApp(lockNow = lockNow)
            }
            "terminal" -> {
                AppChrome("Terminal", "\u276F", OsPalette.green, goHome)
                TerminalApp()
            }
        }
    }
}

@Composable
private fun OsButtonMini(label: String, onClick: () -> Unit) {
    GlassSurface(Modifier, shape = RoundedCornerShape(18.dp)) {
        Box(Modifier.clickable { onClick() }.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(label, color = OsPalette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** DND-aware notification banners. */
@Composable
private fun BannerHost() {
    val kernel = LocalKernel.current
    var banner by remember { mutableStateOf<os.lols.kernel.MessageBus.Notif?>(null) }

    LaunchedEffect(Unit) {
        kernel.bus.banner.collect { banner = it }
    }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(2800)
            banner = null
        }
    }

    AnimatedVisibility(
        visible = banner != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .statusBarsPadding(),
    ) {
        val n = banner ?: return@AnimatedVisibility
        GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp).clickable { banner = null },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIconTile("\u25CE", OsPalette.accent2, 30.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(n.title, color = OsPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (n.body.isNotEmpty()) {
                        Text(n.body, color = OsPalette.textDim, fontSize = 12.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}
