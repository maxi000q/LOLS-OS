package os.lols.kernel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import os.lols.lols.Runtime

/**
 * A real LOLS process: its own PID, its own coroutine scope (cancellation
 * here is a real kill), its own heap accounting and its own runtime.
 */
class LolsProcess(
    val pid: Int,
    val name: String,
    val appId: String,
    val entry: String,
    val startedMs: Long,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    var state: String = STATE_RUNNING

    lateinit var rt: Runtime

    val isAlive: Boolean get() = state == STATE_RUNNING

    companion object {
        const val STATE_RUNNING = "running"
        const val STATE_KILLED = "killed"
        const val STATE_ERROR = "error"
    }
}

/** Snapshot for UI surfaces (recents, terminal ps, settings). */
data class ProcInfo(
    val pid: Int,
    val name: String,
    val appId: String,
    val state: String,
    val heapBytes: Long,
    val steps: Long,
    val uptimeMs: Long,
)
