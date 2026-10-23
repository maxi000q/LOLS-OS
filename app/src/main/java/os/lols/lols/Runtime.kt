package os.lols.lols

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import os.lols.kernel.Kernel
import os.lols.kernel.LolsProcess

/** A resolved UI node: kind + evaluated props + children. Rendered by UiRenderer. */
class RNode(val kind: String, val line: Int) {
    val props = LinkedHashMap<String, LV>()
    val children = ArrayList<RNode>()

    fun str(name: String, def: String = ""): String {
        val v = props[name] ?: return def
        return v.display()
    }
    fun num(name: String, def: Double): Double {
        val v = props[name] as? LV.LNum ?: return def
        return v.v
    }
    fun flag(name: String, def: Boolean = false): Boolean {
        val v = props[name] ?: return def
        return v.truthy()
    }
    fun callable(name: String): LV? {
        val v = props[name] ?: return null
        return if (v is LV.LClosure || v is LV.LFunc || v is LV.LNative) v else null
    }
}

/**
 * Per-process LOLS runtime: owns the global environment, executes handlers
 * under a real watchdog, rebuilds the UI tree after every event, keeps
 * memory accounting honest, and drives tick timers.
 */
class Runtime(
    val kernel: Kernel,
    val process: LolsProcess,
    private val app: Ast.AppDecl,
) {
    val interp = Interpreter(this)
    val globalEnv = Env()
    val uiTree = MutableStateFlow<RNode?>(null)

    private val execMutex = Mutex()
    private val nodeStack = ArrayDeque<RNode>()
    private var tickJob: kotlinx.coroutines.Job? = null

    @Volatile var steps: Long = 0
        private set
    @Volatile var heapBytes: Long = 0
        private set
    @Volatile var peakHeapBytes: Long = 0
        private set

    val accentColor: String get() = app.metas["color"] ?: "#6EE7D8"

    // ---------------- lifecycle ----------------

    suspend fun start() {
        Builtins.install(globalEnv, this)
        // state block declares reactive globals
        for (a in app.state) {
            val v = interp.eval(a.value, globalEnv)
            globalEnv.declare((a.target as Ast.Expr.Ident).name, v)
        }
        // funcs
        for (f in app.funcs) globalEnv.declare(f.name, LV.LFunc(f, globalEnv))
        heapBytes = liveEstimate()
        kernel.mem.settle(process.pid, heapBytes)

        app.onStart?.let { runHandler(LV.LClosure(it, null, globalEnv), null, quiet = true) }
        rebuildSafe()

        app.onTick?.let { body ->
            val ms = (app.tickMs ?: 1000L).coerceAtLeast(100L)
            val handler = LV.LClosure(body, null, globalEnv)
            tickJob = process.scope.launch {
                while (isActive) {
                    delay(ms)
                    runHandler(handler, null, quiet = true)
                }
            }
        }
    }

    fun shutdown() {
        tickJob?.cancel()
        kernel.mem.release(process.pid)
    }

    // ---------------- scheduling & memory (called by the interpreter) ----------------

    suspend fun step(@Suppress("UNUSED_PARAMETER") line: Int) {
        steps++
        val y = kernel.settings.value.yieldSteps
        if (steps % y == 0L) {
            yield() // cooperative preemption point - the kernel breathes here
        }
    }

    fun alloc(bytes: Long) {
        heapBytes += bytes
        if (heapBytes > peakHeapBytes) peakHeapBytes = heapBytes
        kernel.mem.reportAlloc(process, bytes)
    }

    /** Interpreter callback: writes to globals mark the UI stale. */
    fun onGlobalWrite(@Suppress("UNUSED_PARAMETER") name: String) { /* rebuild happens after every handler anyway */ }

    private fun liveEstimate(): Long {
        var total = 2048L
        for (v in globalEnv.vars.values) total += lvSize(v)
        return total
    }

    // ---------------- handler execution ----------------

    fun fire(callable: LV, arg: LV?) {
        process.scope.launch { runHandler(callable, arg, quiet = false) }
    }

    suspend fun runHandler(callable: LV, arg: LV?, quiet: Boolean) {
        execMutex.withLock {
            try {
                val watchdogMs = kernel.settings.value.watchdogS * 1000L
                val done = withTimeoutOrNull(watchdogMs) {
                    interp.call(callable, if (arg == null) emptyList() else listOf(arg))
                    true
                }
                if (done == null) {
                    kernel.processes.kill(process.pid, "watchdog: not responding")
                    return@withLock
                }
                rebuildSafe()
                heapBytes = liveEstimate()
                kernel.mem.settle(process.pid, heapBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: LolsOOM) {
                kernel.processes.kill(process.pid, "out of memory")
            } catch (e: LolsError) {
                appError(e.message ?: "error", quiet)
            } catch (e: Exception) {
                appError(e.message ?: e.javaClass.simpleName, quiet)
            }
        }
    }

    private fun appError(msg: String, quiet: Boolean) {
        process.state = LolsProcess.STATE_ERROR
        kernel.processes.refresh()
        kernel.logLine("[${process.name}] error: $msg")
        if (!quiet) kernel.bus.post(process.appId, process.name, msg)
        uiTree.value = errorNode(msg)
    }

    private fun errorNode(msg: String): RNode {
        val root = RNode("Screen", 0)
        val col = RNode("Column", 0)
        col.props["padding"] = LV.LNum(24.0)
        col.props["spacing"] = LV.LNum(12.0)
        val t1 = RNode("Text", 0)
        t1.props["value"] = LV.LStr(kernel.t("App stopped", "توقف التطبيق"))
        t1.props["style"] = LV.LStr("title")
        val t2 = RNode("Text", 0)
        t2.props["value"] = LV.LStr(msg)
        t2.props["style"] = LV.LStr("caption")
        col.children.add(t1); col.children.add(t2)
        root.children.add(col)
        return root
    }

    // ---------------- UI building ----------------

    suspend fun rebuildSafe() {
        try {
            rebuild()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            uiTree.value = errorNode(e.message ?: "build error")
        }
    }

    private suspend fun rebuild() {
        if (app.uiBody.isEmpty()) {
            uiTree.value = errorNode("this app has no ui block")
            return
        }
        val root = RNode("Screen", 0)
        nodeStack.clear()
        nodeStack.add(root)
        val buildEnv = Env(globalEnv)
        for (s in app.uiBody) interp.exec(s, buildEnv)
        nodeStack.clear()
        uiTree.value = root
    }

    /** Called by the interpreter when a Node statement executes. */
    suspend fun buildNode(n: Ast.UiNode, env: Env) {
        val parent = nodeStack.last()
        val rn = RNode(n.kind, n.line)
        nodeStack.add(rn)
        for (s in n.body) interp.exec(s, env)
        nodeStack.removeLast()
        parent.children.add(rn)
    }

    /** Called by the interpreter when a Prop statement executes. */
    suspend fun attachProp(p: Ast.Stmt.Prop, env: Env) {
        val v = interp.eval(p.value, env)
        nodeStack.last().props[p.name] = v
        env.declare(p.name, v)
    }

    // ---------------- filesystem sandbox ----------------

    fun fsGate(path: String, write: Boolean): String {
        val norm = normalize(path)
        val own = "/data/${process.appId}"
        val perms = kernel.packages.permsOf(process.appId)
        val full = perms.contains("fs.full")
        if (norm == own || norm.startsWith("$own/")) return norm
        if (norm == "/home" || norm.startsWith("/home/")) return norm
        if (full) {
            if (norm == "/sys/security" || norm.startsWith("/sys/security/")) {
                throw LolsError("restricted: /sys/security")
            }
            if (write && (norm == "/sys" || norm.startsWith("/sys/"))) {
                throw LolsError("write to /sys is not allowed")
            }
            return norm
        }
        if (!write) {
            val ownCode = "/apps/${process.appId}"
            if (norm == ownCode || norm.startsWith("$ownCode/")) return norm
        }
        throw LolsError("permission denied: $path (apps may use $own and /home; declare the fs.full permission for full access)")
    }

    companion object {
        fun normalize(path: String): String {
            val sb = StringBuilder("/")
            for (part in path.split('/')) {
                when (part) {
                    "", "." -> {}
                    ".." -> {
                        val idx = sb.lastIndexOf('/')
                        if (idx > 0) sb.setLength(idx)
                    }
                    else -> { if (sb.length > 1) sb.append('/'); sb.append(part) }
                }
            }
            return sb.toString()
        }
    }
}
