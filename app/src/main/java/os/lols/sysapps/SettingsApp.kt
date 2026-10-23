package os.lols.sysapps

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import os.lols.kernel.Kernel
import os.lols.kernel.LolsFS
import os.lols.kernel.MemoryManager
import os.lols.ui.GlassSurface
import os.lols.ui.LocalKernel
import os.lols.ui.OsButton
import os.lols.ui.OsButtonStyle
import os.lols.ui.OsPalette
import os.lols.ui.OsSliderRow
import os.lols.ui.OsToggleRow
import os.lols.ui.Segmented
import os.lols.ui.WALLPAPERS
import os.lols.ui.t

/**
 * Settings: REAL hardware control for the virtual OS.
 * The RAM slider changes the actual memory pool - set it low and the
 * kernel's OOM killer really terminates processes. Watchdog & scheduler
 * quantum change real runtime behavior. The device panel shows real
 * host telemetry. Nothing here is cosmetic.
 */
@Composable
fun SettingsApp(lockNow: () -> Unit) {
    var section by remember { mutableStateOf("main") }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (section) {
            "main" -> MainSection { section = it }
            "appearance" -> AppearanceSection { section = "main" }
            "hardware" -> HardwareSection { section = "main" }
            "storage" -> StorageSection { section = "main" }
            "device" -> DeviceSection { section = "main" }
            "security" -> SecuritySection({ section = "main" }, lockNow)
            "about" -> AboutSection { section = "main" }
        }
    }
}

@Composable
private fun SectionHeader(title: String, back: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(Color.White.copy(alpha = 0.08f))
                .clickable { back() },
            contentAlignment = Alignment.Center,
        ) {
            Text("\u2039", color = OsPalette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, color = OsPalette.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun MainSection(nav: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(t("Settings", "الإعدادات"), color = OsPalette.text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        SettingsRow("\u2726", t("Appearance", "المظهر"), t("graphics quality, wallpaper", "جودة الرسوميات، الخلفية")) { nav("appearance") }
        SettingsRow("\u25A6", t("Hardware", "الهاردوير"), t("RAM pool, watchdog, scheduler", "ذاكرة، حارس النظام، المجدول")) { nav("hardware") }
        SettingsRow("\u25A4", t("Storage", "التخزين"), t("disk usage of LolsFS", "استخدام قرص LolsFS")) { nav("storage") }
        SettingsRow("\u2318", t("Device", "الجهاز"), t("real host telemetry", "قياسات الجهاز الحقيقية")) { nav("device") }
        SettingsRow("\u26BF", t("Security", "الأمان"), t("PIN, lock screen", "الرمز، شاشة القفل")) { nav("security") }
        SettingsRow("\u2139", t("About", "حول النظام"), t("versions, uptime, restart", "الإصدارات، زمن التشغيل، إعادة التشغيل")) { nav("about") }
    }
}

@Composable
private fun SettingsRow(glyph: String, title: String, sub: String, onClick: () -> Unit) {
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
        Row(
            Modifier.padding(14.dp).clickable { onClick() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.07f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, color = OsPalette.accent, fontSize = 16.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = OsPalette.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(sub, color = OsPalette.textDim, fontSize = 11.sp)
            }
            Text("\u203A", color = OsPalette.textDim, fontSize = 18.sp)
        }
    }
}

// ---------------- appearance ----------------

@Composable
private fun AppearanceSection(back: () -> Unit) {
    val kernel = LocalKernel.current
    val settings by kernel.settings.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(t("Appearance", "المظهر"), back)

        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t("Graphics quality", "جودة الرسوميات"), color = OsPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    t(
                        "Changes real rendering: blur, wallpaper animation, glass effects.",
                        "تغيّر الرسم فعليًا: الضباب، حركة الخلفية، تأثيرات الزجاج."
                    ),
                    color = OsPalette.textDim, fontSize = 11.sp,
                )
                Segmented(
                    options = listOf(t("High", "عالية"), t("Balanced", "متوازنة"), t("Lite", "خفيفة")),
                    selected = settings.graphics.coerceIn(0, 2),
                ) { i -> kernel.update { it.copy(graphics = i) } }
            }
        }

        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t("Wallpaper", "الخلفية"), color = OsPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val names = listOf(t("Nebula", "سديم"), t("Ember", "جمر"), t("Forest", "غابة"))
                    names.forEachIndexed { i, name ->
                        val selected = settings.wallpaper == i
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { kernel.update { it.copy(wallpaper = i) } },
                        ) {
                            Box(
                                Modifier
                                    .size(74.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        Brush.verticalGradient(
                                            listOf(WALLPAPERS[i].top, WALLPAPERS[i].bottom)
                                        )
                                    )
                                    .padding(8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(WALLPAPERS[i].glows[0])
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                name,
                                color = if (selected) OsPalette.accent else OsPalette.textDim,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }

        OsToggleRow(
            label = t("Arabic interface", "واجهة عربية"),
            sub = t("Switches the whole OS to Arabic with RTL", "تحوّل النظام كله للعربية مع اتجاه RTL"),
            checked = settings.langArabic,
        ) { v -> kernel.update { it.copy(langArabic = v) } }
    }
}

// ---------------- hardware (the real deal) ----------------

@Composable
private fun HardwareSection(back: () -> Unit) {
    val kernel = LocalKernel.current
    val settings by kernel.settings.collectAsState()
    val memUsed by kernel.mem.used.collectAsState()
    val processes by kernel.processes.processes.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(t("Hardware", "الهاردوير"), back)

        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text(t("Memory pool", "تجمع الذاكرة"), color = OsPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${MemoryManager.fmt(memUsed)} / ${MemoryManager.fmt(kernel.mem.quotaBytes())}",
                        color = OsPalette.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
                val frac = if (kernel.mem.quotaBytes() > 0) (memUsed.toFloat() / kernel.mem.quotaBytes()).coerceIn(0f, 1f) else 0f
                ProgressBar(frac)
                Text(
                    t(
                        "This is the real virtual RAM. If apps exceed it, the kernel's OOM killer terminates the largest process - try 4MB and open many apps.",
                        "هذه هي الذاكرة الافتراضية الحقيقية. لو زادت التطبيقات عنها سيُنهي قاتل OOM بالنواة أكبر عملية - جرّب 4MB وافتح عدة تطبيقات."
                    ),
                    color = OsPalette.textDim, fontSize = 11.sp,
                )
            }
        }

        OsSliderRow(
            label = t("RAM pool", "ذاكرة النظام"),
            value = settings.ramMb.toFloat(),
            range = 4f..64f,
        ) { v -> kernel.update { it.copy(ramMb = v.toInt().coerceIn(4, 64)) } }

        OsSliderRow(
            label = t("Watchdog timeout", "مهلة حارس النظام"),
            value = settings.watchdogS.toFloat(),
            range = 1f..10f,
        ) { v -> kernel.update { it.copy(watchdogS = v.toInt().coerceIn(1, 10)) } }

        OsSliderRow(
            label = t("Scheduler quantum", "شريحة المجدول"),
            value = settings.yieldSteps.toFloat(),
            range = 128f..2048f,
        ) { v -> kernel.update { it.copy(yieldSteps = (v.toInt() / 128 * 128).coerceIn(128, 2048)) } }

        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t("Live processes", "العمليات الجارية"), color = OsPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (processes.isEmpty()) {
                    Text(t("none", "لا شيء"), color = OsPalette.textDim, fontSize = 12.sp)
                }
                for (p in processes) {
                    Row {
                        Text("${p.pid}", color = OsPalette.textDim, fontSize = 12.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(p.name, color = OsPalette.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(MemoryManager.fmt(p.heapBytes), color = OsPalette.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProgressBar(frac: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(frac)
                .height(8.dp)
                .clip(CircleShape)
                .background(if (frac > 0.85f) OsPalette.red else OsPalette.accent),
        )
    }
}

// ---------------- storage ----------------

@Composable
private fun StorageSection(back: () -> Unit) {
    val kernel = LocalKernel.current
    val scope = rememberCoroutineScope()
    var used by remember { mutableStateOf(0L) }
    var dirs by remember { mutableStateOf(listOf<Pair<String, Long>>()) }

    LaunchedEffect(Unit) {
        used = kernel.fs.usedBytes()
        dirs = listOf("/apps", "/data", "/home", "/sys").map { it to kernel.fs.du(it) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(t("Storage", "التخزين"), back)
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text("LolsFS", color = OsPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${MemoryManager.fmt(used)} / ${MemoryManager.fmt(LolsFS.QUOTA_BYTES)}",
                        color = OsPalette.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
                ProgressBar((used.toFloat() / LolsFS.QUOTA_BYTES).coerceIn(0f, 1f))
            }
        }
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((dir, size) in dirs.sortedByDescending { it.second }) {
                    Row {
                        Text(dir, color = OsPalette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text(MemoryManager.fmt(size), color = OsPalette.textDim, fontSize = 13.sp)
                    }
                }
            }
        }
        OsButton(
            t("Clear /home", "مسح /home"),
            style = OsButtonStyle.DANGER,
            onClick = {
                scope.launch {
                    kernel.fs.delete("/home")
                    kernel.fs.mkdir("/home")
                    used = kernel.fs.usedBytes()
                    dirs = listOf("/apps", "/data", "/home", "/sys").map { it to kernel.fs.du(it) }
                }
            },
        )
    }
}

// ---------------- device (real host data) ----------------

@Composable
private fun DeviceSection(back: () -> Unit) {
    val kernel = LocalKernel.current
    val power by kernel.power.flow.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(t("Device (real host data)", "الجهاز (بيانات حقيقية)"), back)
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(t("Model", "الموديل"), power.model)
                InfoRow("Android", power.androidVersion)
                InfoRow(t("CPU cores", "أنوية المعالج"), "${power.cores}")
                InfoRow(t("Host RAM", "ذاكرة الجهاز"), "${power.hostRamAvailMb}MB ${t("free of", "متاحة من")} ${power.hostRamTotalMb}MB")
                InfoRow(t("Battery", "البطارية"), "${power.batteryPct}%${if (power.charging) " \u26A1" else ""}")
                InfoRow("LOLS uptime", formatUptime(kernel.uptimeMs()))
            }
        }
        Text(
            t(
                "Read through public Android APIs - zero permissions requested.",
                "تُقرأ عبر واجهات أندرويد العامة - بدون أي أذونات."
            ),
            color = OsPalette.textDim, fontSize = 11.sp,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, color = OsPalette.textDim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = OsPalette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private fun formatUptime(ms: Long): String {
    val s = ms / 1000
    return when {
        s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
        s >= 60 -> "${s / 60}m ${s % 60}s"
        else -> "${s}s"
    }
}

// ---------------- security ----------------

@Composable
private fun SecuritySection(back: () -> Unit, lockNow: () -> Unit) {
    val kernel = LocalKernel.current
    val scope = rememberCoroutineScope()
    val settings by kernel.settings.collectAsState()

    var stage by remember { mutableStateOf(0) } // 0 idle, 1 old, 2 new, 3 confirm
    var oldPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(t("Security", "الأمان"), back)

        OsToggleRow(
            label = t("Lock screen", "شاشة القفل"),
            sub = t("Ask for PIN when LOLS OS starts", "اطلب الرمز عند تشغيل LOLS OS"),
            checked = settings.lockEnabled,
        ) { v -> kernel.update { it.copy(lockEnabled = v) } }

        OsButton(t("Lock now", "قفل الآن"), style = OsButtonStyle.PRIMARY, onClick = lockNow)

        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t("Change PIN", "تغيير الرمز"), color = OsPalette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (stage == 0) {
                    OsButton(t("Start", "ابدأ"), { stage = 1 }, small = true)
                } else {
                    val value = when (stage) {
                        1 -> oldPin; 2 -> newPin; else -> confirmPin
                    }
                    val label = when (stage) {
                        1 -> t("Current PIN", "الرمز الحالي")
                        2 -> t("New PIN (4 digits)", "الرمز الجديد (4 أرقام)")
                        else -> t("Confirm new PIN", "تأكيد الرمز الجديد")
                    }
                    OutlinedTextField(
                        value = value,
                        onValueChange = { v ->
                            val digits = v.filter { it.isDigit() }.take(4)
                            when (stage) {
                                1 -> oldPin = digits
                                2 -> newPin = digits
                                else -> confirmPin = digits
                            }
                        },
                        label = { Text(label, color = OsPalette.textDim, fontSize = 12.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    OsButton(
                        t("Continue", "متابعة"),
                        {
                            scope.launch {
                                when (stage) {
                                    1 -> {
                                        if (kernel.checkPin(oldPin)) {
                                            stage = 2; message = ""
                                        } else message = t("Wrong PIN", "الرمز الحالي غير صحيح")
                                    }
                                    2 -> {
                                        if (newPin.length == 4) { stage = 3; message = "" }
                                        else message = t("PIN must be 4 digits", "يجب أن يكون الرمز 4 أرقام")
                                    }
                                    else -> {
                                        if (confirmPin == newPin) {
                                            kernel.setPin(newPin)
                                            stage = 0; oldPin = ""; newPin = ""; confirmPin = ""
                                            message = t("PIN changed", "تم تغيير الرمز")
                                        } else {
                                            stage = 2
                                            message = t("PINs did not match", "الرمزان غير متطابقين")
                                        }
                                    }
                                }
                            }
                        },
                        small = true, style = OsButtonStyle.PRIMARY,
                    )
                }
                if (message.isNotEmpty()) {
                    Text(message, color = OsPalette.accent, fontSize = 12.sp)
                }
            }
        }
    }
}

// ---------------- about ----------------

@Composable
private fun AboutSection(back: () -> Unit) {
    val kernel = LocalKernel.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(t("About", "حول النظام"), back)
        GlassSurface(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow("OS", "${Kernel.OS_NAME} v${Kernel.KERNEL_VERSION}")
                InfoRow("LOLS runtime", Kernel.LOLS_RUNTIME_VERSION)
                InfoRow("Uptime", formatUptime(kernel.uptimeMs()))
                InfoRow("License", "MIT")
                InfoRow("Apps", "${kernel.packages.apps.value.size}")
            }
        }
        Text(
            t(
                "LOLS OS is an original, MIT-licensed operating environment. All design, code and icons are original works.",
                "LOLS OS بيئة تشغيل أصلية برخصة MIT. كل التصميم والكود والأيقونات أعمال أصلية."
            ),
            color = OsPalette.textDim, fontSize = 11.sp,
        )
        OsButton(
            t("Restart LOLS OS", "إعادة تشغيل النظام"),
            style = OsButtonStyle.DANGER,
            onClick = { kernel.reboot() },
        )
    }
}
