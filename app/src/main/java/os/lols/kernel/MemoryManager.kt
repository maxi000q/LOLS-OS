package os.lols.kernel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import os.lols.lols.LolsOOM

/**
 * The real memory manager of LOLS OS.
 * Every allocation by every .lols process is accounted. When the global
 * virtual RAM pool (configurable in Settings) is exceeded, the kernel's
 * OOM killer terminates the largest consumer - exactly like a real OS.
 */
class MemoryManager(private val kernel: Kernel) {

    private val heaps = HashMap<Int, Long>()
    private val _used = MutableStateFlow(0L)
    val used = _used.asStateFlow()

    fun quotaBytes(): Long = kernel.settings.value.ramMb * 1024L * 1024L

    @Synchronized
    fun usedBytes(): Long = heaps.values.sum()

    @Synchronized
    fun heapOf(pid: Int): Long = heaps[pid] ?: 0L

    /**
     * Report an allocation. May trigger the OOM killer:
     * either another (larger) process dies, or this one is terminated
     * with a real LolsOOM kill signal.
     */
    @Synchronized
    fun reportAlloc(proc: LolsProcess, bytes: Long) {
        heaps[proc.pid] = (heaps[proc.pid] ?: 0L) + bytes
        _used.value = heaps.values.sum()
        if (usedBytes() > quotaBytes()) {
            val victimEntry = heaps.entries.maxByOrNull { it.value } ?: return
            val victim = kernel.processes.byPid(victimEntry.key)
            if (victim == null) {
                heaps.remove(victimEntry.key)
                return
            }
            if (victim.pid == proc.pid) {
                kernel.processes.kill(proc.pid, "out of memory")
                throw LolsOOM("out of memory: pool ${fmt(usedBytes())} exceeded quota ${fmt(quotaBytes())}")
            } else {
                kernel.processes.kill(victim.pid, "memory pressure: terminated ${victim.name}")
            }
        }
    }

    /** After each handler completes, the process reports its live set. */
    @Synchronized
    fun settle(pid: Int, liveBytes: Long) {
        heaps[pid] = liveBytes
        _used.value = heaps.values.sum()
    }

    @Synchronized
    fun release(pid: Int) {
        heaps.remove(pid)
        _used.value = heaps.values.sum()
    }

    companion object {
        fun fmt(bytes: Long): String = when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1fMB", bytes / 1048576.0)
            bytes >= 1024 -> String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0)
            else -> "${bytes}B"
        }
    }
}
