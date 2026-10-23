package os.lols.kernel

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import os.lols.lols.Lexer
import os.lols.lols.LolsError
import os.lols.lols.Parser
import os.lols.lols.Runtime
import java.util.concurrent.ConcurrentHashMap

/**
 * Real process manager: spawns .lols apps as processes with real PIDs,
 * real coroutine scopes, real kills (cancellation), a live process table
 * and a real watchdog protecting the system from runaway apps.
 */
class ProcessManager(private val kernel: Kernel) {

    private val procs = ConcurrentHashMap<Int, LolsProcess>()
    private val _processes = MutableStateFlow<List<ProcInfo>>(emptyList())
    val processes = _processes.asStateFlow()

    private var nextPid = 100

    fun byPid(pid: Int): LolsProcess? = procs[pid]

    fun findByApp(appId: String): LolsProcess? =
        procs.values.firstOrNull { it.appId == appId && it.isAlive }

    fun findByAppAny(appId: String): LolsProcess? =
        procs.values.firstOrNull { it.appId == appId }

    /**
     * Spawn a .lols app as a new process (or return the running one).
     * Loads the real source from /apps/<id>/main.lols, parses it, runs
     * on start and builds the first UI frame.
     */
    suspend fun spawn(app: AppInfo): Int? {
        findByApp(app.id)?.let { return it.pid }
        val source = kernel.fs.read("/apps/${app.id}/main.lols")
        if (source.isEmpty()) {
            kernel.bus.post(app.id, app.name, kernel.t("app sources missing - reinstall it", "ملفات التطبيق مفقودة - أعد تثبيته"))
            return null
        }
        val decl = try {
            Parser(Lexer(source).lex()).parseProgram().app
        } catch (e: LolsError) {
            kernel.bus.post(app.id, app.name, kernel.t("parse error", "خطأ في التحليل") + ": " + (e.message ?: ""))
            kernel.logLine("[pkg] ${app.id} failed to parse: ${e.message}")
            return null
        } catch (e: Exception) {
            kernel.bus.post(app.id, app.name, kernel.t("parse error", "خطأ في التحليل") + ": " + (e.message ?: ""))
            return null
        }

        val pid = nextPid++
        val proc = LolsProcess(pid, decl.name.ifEmpty { app.name }, app.id, app.entry, System.currentTimeMillis())
        val rt = Runtime(kernel, proc, decl)
        proc.rt = rt
        procs[pid] = proc
        refresh()
        kernel.logLine("[proc] spawn pid=$pid ${app.id}")

        proc.scope.launch {
            try {
                rt.start()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                proc.state = LolsProcess.STATE_ERROR
                kernel.logLine("[proc] pid=$pid failed to start: ${e.message}")
                kernel.bus.post(app.id, app.name, kernel.t("failed to start", "فشل التشغيل") + ": " + (e.message ?: ""))
                refresh()
            }
        }
        return pid
    }

    /** Real kill: coroutine cancellation + heap release + process table update. */
    fun kill(pid: Int, reason: String) {
        val proc = procs[pid] ?: return
        proc.state = LolsProcess.STATE_KILLED
        try { proc.rt.shutdown() } catch (e: Exception) { }
        proc.scope.cancel()
        procs.remove(pid)
        kernel.mem.release(pid)
        kernel.logLine("[proc] kill pid=$pid (${reason})")
        refresh()
        if (reason.contains("out of memory") || reason.contains("memory pressure") || reason.contains("watchdog")) {
            kernel.bus.post("os.lols", proc.name, reason)
        }
    }

    fun killAll() {
        for (pid in procs.keys.toList()) kill(pid, "shutdown")
    }

    fun refresh() {
        val now = System.currentTimeMillis()
        _processes.value = procs.values.map {
            ProcInfo(
                pid = it.pid,
                name = it.name,
                appId = it.appId,
                state = it.state,
                heapBytes = kernel.mem.heapOf(it.pid),
                steps = if (rtIsSet(it)) it.rt.steps else 0L,
                uptimeMs = now - it.startedMs,
            )
        }.sortedBy { it.pid }
    }

    private fun rtIsSet(p: LolsProcess): Boolean = try { p.rt; true } catch (e: UninitializedPropertyAccessException) { false }
}
