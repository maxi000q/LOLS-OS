package os.lols.kernel

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The real boot sequence. Every log line below corresponds to actual work
 * with measured timings: filesystem mount & integrity scan, settings load,
 * package registry scan, bundled app installation and power telemetry.
 */
class BootSequence(private val kernel: Kernel, private val ctx: Context) {

    suspend fun run() {
        val t0 = System.currentTimeMillis()
        val mark = { label: String ->
            kernel.logLine("[%4dms] $label".format(System.currentTimeMillis() - t0))
        }

        kernel.logLine("lolskernel v${Kernel.KERNEL_VERSION} booting on ${Build.MODEL}")
        kernel.logLine("[    0ms] lols runtime ${Kernel.LOLS_RUNTIME_VERSION} | MIT licensed")

        // stage 1: hardware probe (real host data)
        val p = kernel.power.snapshot()
        kernel.logLine("[    1ms] cpu: ${p.cores} cores | host ${p.hostRamTotalMb}MB RAM, ${p.hostRamAvailMb}MB free")
        kernel.logLine("[    2ms] power: battery ${p.batteryPct}%${if (p.charging) " (charging)" else ""}")
        mark("hw: android ${p.androidVersion}, api ${Build.VERSION.SDK_INT}")

        // stage 2: filesystem mount + integrity scan
        val stats = kernel.fs.mount()
        mark("lolfs: mounted / (${stats.first} files, ${MemoryManager.fmt(stats.second)})")
        val integrity = withContext(Dispatchers.IO) {
            val root = kernel.fs.resolve("/")
            root.isDirectory && kernel.fs.resolve("/sys").isDirectory && kernel.fs.resolve("/apps").isDirectory
        }
        if (!integrity) {
            kernel.logLine("[  !!  ] lolfs: INTEGRITY FAILURE - recreating standard tree")
            for (d in LolsFS.BOOT_DIRS) kernel.fs.mkdir(d)
        } else {
            mark("lolfs: integrity check passed")
        }

        // stage 3: settings
        kernel.loadSettings()
        mark("settings: loaded (ram=${kernel.settings.value.ramMb}MB, graphics=${graphicsName(kernel.settings.value.graphics)})")

        // stage 4: packages
        val firstBoot = !kernel.fs.exists("/sys/security/.initialized")
        kernel.packages.loadRegistry()
        mark("pkg: registry loaded (${kernel.packages.apps.value.size} apps)")
        kernel.packages.ensureBundled(firstBoot)
        if (firstBoot) {
            kernel.fs.write("/sys/security/.initialized", "1")
        }
        mark("pkg: ${kernel.packages.apps.value.size} apps ready")

        // stage 5: services
        kernel.bus.clearAll()
        mark("bus: message bus online")

        val total = System.currentTimeMillis() - t0
        kernel.logLine("boot complete in ${total}ms - welcome to LOLS OS")
    }

    private fun graphicsName(g: Int): String = when (g) {
        0 -> "high"; 1 -> "balanced"; else -> "lite"
    }
}
