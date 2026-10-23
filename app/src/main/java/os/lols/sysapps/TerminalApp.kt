package os.lols.sysapps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import os.lols.kernel.Kernel
import os.lols.kernel.LolsFS
import os.lols.kernel.MemoryManager
import os.lols.ui.GlassSurface
import os.lols.ui.LocalKernel
import os.lols.ui.OsButton
import os.lols.ui.OsPalette
import os.lols.ui.OsButtonStyle
import os.lols.ui.t
import os.lols.kernel.ProcInfo

/**
 * A REAL terminal into the kernel: ps, kill, ls, cat, free, df, bootlog...
 * Every command hits the real process table, the real filesystem and the
 * real memory manager. Nothing is scripted output.
 */
@Composable
fun TerminalApp() {
    val kernel = LocalKernel.current
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()

    fun prompt(cmd: String) {
        lines.add("\u276F $cmd")
    }

    LaunchedEffect(Unit) {
        lines.add("LOLS terminal - kernel ${Kernel.KERNEL_VERSION} (lols runtime ${Kernel.LOLS_RUNTIME_VERSION})")
        lines.add("type 'help' for commands")
        lines.add("")
    }
    LaunchedEffect(lines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp).padding(bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassSurface(Modifier.weight(1f), shape = RoundedCornerShape(18.dp)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(12.dp),
            ) {
                lines.forEach { line ->
                    Text(
                        line,
                        color = when {
                            line.startsWith("\u276F") -> OsPalette.accent
                            line.startsWith("[") -> OsPalette.textDim
                            else -> OsPalette.text.copy(alpha = 0.85f)
                        },
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("\u276F", color = OsPalette.accent, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                textStyle = TextStyle(fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = OsPalette.text),
                placeholder = { Text(t("command\u2026", "أمر\u2026"), color = OsPalette.textDim, fontSize = 12.sp) },
                colors = os.lols.ui.TerminalFieldColors(),
            )
            Spacer(Modifier.width(8.dp))
            OsButton(t("Run", "تنفيذ"), {
                val cmd = input.trim()
                input = ""
                if (cmd.isEmpty()) return@OsButton
                prompt(cmd)
                scope.launch {
                    val out = execCommand(kernel, cmd)
                    if (out == null) {
                        lines.clear()
                        lines.add("LOLS terminal - cleared")
                    } else {
                        lines.addAll(out)
                    }
                    lines.add("")
                }
            }, small = true, style = OsButtonStyle.PRIMARY)
        }
    }
}

/** Returns null for the 'clear' command. */
private suspend fun execCommand(kernel: Kernel, cmd: String): List<String>? {
    val parts = cmd.trim().split(Regex("\\s+"))
    if (parts.isEmpty() || parts[0].isEmpty()) return emptyList()
    return when (parts[0]) {
        "help" -> listOf(
            "commands:",
            "  help              show this help",
            "  neofetch          system summary",
            "  ps                process table (real)",
            "  kill <pid>        terminate a process (real)",
            "  ls [path]         list LolsFS directory",
            "  cat <path>        print a file",
            "  rm <path>         delete a file/dir",
            "  mkdir <path>      create a directory",
            "  echo <text>       print text",
            "  free              virtual + host memory (real)",
            "  df                LolsFS disk usage (real)",
            "  uptime            kernel uptime",
            "  bootlog           last kernel log lines",
            "  notify <text>     post a notification",
            "  clear             clear the screen",
        )
        "neofetch" -> {
            val p = kernel.power.snapshot()
            listOf(
                "  \u2554\u2550\u2550\u2557 \u2554\u2550\u2550\u2557 \u2554\u2550\u2550\u2550\u2550\u2557  _____  ____   ____  ",
                "  \u2551\u2551\u2551\u2551\u2551 \u2551\u2551  \u2551 \u2551  \u2551\u2551\u2551\u2551\u2551 / ____|/ __ \\ / __ \\ ",
                "  \u2551\u2551\u2551\u2551\u2551 \u2551\u2551  \u2551 \u2551  \u2551\u2551\u2551\u2551\u2551| |     | |  | | |  | |",
                "  \u2551\u2551\u2551\u2551\u2551 \u2551\u2551  \u2551 \u2551  \u2551\u2551\u2551\u2551\u2551| |____ | |__| | |__| |",
                "  \u2561\u2557\u2554\u2555  \u2561\u2557\u2554\u2555 \u2561\u2557  \u2561\u2557 \\_____|\\____/ \\____/ ",
                "   \u2561\u2561\u2561   \u2561\u2561\u2561  \u2561\u2561\u2561 \u2561\u2561\u2561      os.lols",
                "",
                "os       ${Kernel.OS_NAME} v${Kernel.KERNEL_VERSION} (MIT)",
                "runtime  lols ${Kernel.LOLS_RUNTIME_VERSION}",
                "host     ${p.model} - android ${p.androidVersion}, ${p.cores} cores",
                "battery  ${p.batteryPct}%${if (p.charging) " charging" else ""}",
                "memory   ${MemoryManager.fmt(kernel.mem.usedBytes())} / ${MemoryManager.fmt(kernel.mem.quotaBytes())} virtual",
                "disk     ${MemoryManager.fmt(kernel.fs.usedBytes())} / ${MemoryManager.fmt(LolsFS.QUOTA_BYTES)} on LolsFS",
                "apps     ${kernel.packages.apps.value.size} installed",
            )
        }
        "ps" -> {
            val list = kernel.processes.processes.value
            if (list.isEmpty()) listOf("no processes")
            else listOf("PID   STATE    HEAP      NAME") + list.map { psLine(it) }
        }
        "kill" -> {
            val pid = parts.getOrNull(1)?.toIntOrNull()
            if (pid == null) listOf("usage: kill <pid>")
            else {
                kernel.processes.kill(pid, "killed from terminal")
                listOf("terminated pid $pid")
            }
        }
        "ls" -> {
            val path = parts.getOrElse(1) { "/" }
            val entries = kernel.fs.list(path)
            if (entries.isEmpty()) listOf("(empty)")
            else entries.map { e ->
                (if (e.dir) "d " else "- ") + e.name + if (!e.dir && e.name != "..") "  ${e.size}B" else ""
            }
        }
        "cat" -> {
            if (parts.size < 2) listOf("usage: cat <path>")
            else {
                val content = kernel.fs.read(parts[1])
                if (content.isEmpty()) listOf("(empty or missing)")
                else content.lines().take(60)
            }
        }
        "rm" -> {
            if (parts.size < 2) listOf("usage: rm <path>")
            else if (kernel.fs.delete(parts[1])) listOf("deleted ${parts[1]}")
            else listOf("not found: ${parts[1]}")
        }
        "mkdir" -> {
            if (parts.size < 2) listOf("usage: mkdir <path>")
            else if (kernel.fs.mkdir(parts[1])) listOf("created ${parts[1]}")
            else listOf("failed")
        }
        "echo" -> listOf(parts.drop(1).joinToString(" "))
        "free" -> {
            val p = kernel.power.snapshot()
            listOf(
                "virtual pool : ${MemoryManager.fmt(kernel.mem.usedBytes())} used / ${MemoryManager.fmt(kernel.mem.quotaBytes())} quota",
                "host ram     : ${p.hostRamAvailMb}MB avail / ${p.hostRamTotalMb}MB total",
                "processes    : ${kernel.processes.processes.value.size}",
            )
        }
        "df" -> listOf(
            "LolsFS : ${MemoryManager.fmt(kernel.fs.usedBytes())} used / ${MemoryManager.fmt(LolsFS.QUOTA_BYTES)} quota",
        )
        "uptime" -> {
            val s = kernel.uptimeMs() / 1000
            listOf("up ${s / 3600}h ${(s % 3600) / 60}m ${s % 60}s")
        }
        "bootlog" -> kernel.logLines().takeLast(30).ifEmpty { listOf("(no log)") }
        "notify" -> {
            kernel.bus.post("os.lols.terminal", "Terminal", parts.drop(1).joinToString(" "))
            listOf("notification posted")
        }
        "clear" -> null
        else -> listOf("${parts[0]}: command not found (try 'help')")
    }
}

private fun psLine(p: ProcInfo): String {
    val pid = p.pid.toString().padEnd(6)
    val state = p.state.padEnd(9)
    val heap = MemoryManager.fmt(p.heapBytes).padEnd(10)
    return "$pid$state$heap${p.name}"
}
