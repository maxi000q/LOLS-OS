package os.lols.lols

import os.lols.kernel.Kernel
import java.util.Calendar

/**
 * Native builtin library and syscalls for the LOLS runtime.
 * Everything here does REAL work: real filesystem calls, real notifications,
 * real host-device data (battery, RAM, CPU) coming from the kernel.
 */
object Builtins {

    fun install(env: Env, rt: Runtime) {
        fun n(name: String, fn: suspend (List<LV>) -> LV) {
            env.declare(name, LV.LNative(name, fn))
        }
        fun s(v: LV): String = v.display()
        fun num(v: LV, def: Double = 0.0): Double = if (v is LV.LNum) v.v else def

        fun argStr(a: List<LV>, i: Int, what: String): String {
            if (i >= a.size) throw LolsError("$what expects a string argument")
            return a[i].display()
        }
        fun argNum(a: List<LV>, i: Int, what: String): Double {
            if (i >= a.size || a[i] !is LV.LNum) throw LolsError("$what expects a number argument")
            return (a[i] as LV.LNum).v
        }
        fun argList(a: List<LV>, i: Int, what: String): LV.LList {
            if (i >= a.size || a[i] !is LV.LList) throw LolsError("$what expects a list argument")
            return a[i] as LV.LList
        }
        fun argMap(a: List<LV>, i: Int, what: String): LV.LMap {
            if (i >= a.size || a[i] !is LV.LMap) throw LolsError("$what expects a map argument")
            return a[i] as LV.LMap
        }

        // ---- console / identity ----
        n("log") { a -> rt.kernel.logLine("[${rt.process.name}] ${argStr(a, 0, "log")}"); LV.LNull }
        n("str") { a -> LV.LStr(a.getOrElse(0) { LV.LNull }.display()) }
        n("num") { a ->
            val v = a.getOrElse(0) { LV.LNull }
            val d = when (v) {
                is LV.LNum -> v.v
                is LV.LStr -> v.s.trim().toDoubleOrNull() ?: 0.0
                is LV.LBool -> if (v.b) 1.0 else 0.0
                else -> 0.0
            }
            LV.LNum(d)
        }
        n("len") { a ->
            val v = a.getOrElse(0) { LV.LNull }
            val l = when (v) {
                is LV.LStr -> v.s.length
                is LV.LList -> v.items.size
                is LV.LMap -> v.entries.size
                else -> throw LolsError("len() needs a string, list or map")
            }
            LV.LNum(l.toDouble())
        }

        // ---- lists / maps ----
        n("push") { a ->
            val l = argList(a, 0, "push")
            if (a.size < 2) throw LolsError("push(list, value) needs a value")
            l.items.add(a[1]); rt.alloc(8); l
        }
        n("pop") { a ->
            val l = argList(a, 0, "pop")
            if (l.items.isEmpty()) throw LolsError("pop from an empty list")
            val idx = if (a.size > 1 && a[1] is LV.LNum) (a[1] as LV.LNum).v.toInt() else l.items.size - 1
            if (idx < 0 || idx >= l.items.size) throw LolsError("pop index out of range")
            l.items.removeAt(idx)
        }
        n("keys") { a ->
            val m = argMap(a, 0, "keys")
            rt.alloc(40L + 8L * m.entries.size)
            LV.LList(m.entries.keys.map { LV.LStr(it) }.toMutableList())
        }

        // ---- math ----
        n("floor") { a -> LV.LNum(kotlin.math.floor(argNum(a, 0, "floor"))) }
        n("round") { a -> LV.LNum(kotlin.math.round(argNum(a, 0, "round")).toDouble()) }
        n("abs") { a -> LV.LNum(kotlin.math.abs(argNum(a, 0, "abs"))) }
        n("min") { a -> LV.LNum(kotlin.math.min(argNum(a, 0, "min"), argNum(a, 1, "min"))) }
        n("max") { a -> LV.LNum(kotlin.math.max(argNum(a, 0, "max"), argNum(a, 1, "max"))) }
        n("sqrt") { a -> LV.LNum(kotlin.math.sqrt(argNum(a, 0, "sqrt"))) }
        n("random") { a ->
            val max = if (a.isNotEmpty()) argNum(a, 0, "random").toInt() else 2
            if (max <= 1) LV.LNum(0.0) else LV.LNum((0 until max).random().toDouble())
        }

        // ---- time ----
        n("now") { _ -> LV.LNum(System.currentTimeMillis().toDouble()) }
        n("timeParts") { _ ->
            val c = Calendar.getInstance()
            mapOf(
                "h" to LV.LNum(c.get(Calendar.HOUR_OF_DAY).toDouble()),
                "m" to LV.LNum(c.get(Calendar.MINUTE).toDouble()),
                "s" to LV.LNum(c.get(Calendar.SECOND).toDouble()),
            ).toLv()
        }
        n("dateParts") { _ ->
            val c = Calendar.getInstance()
            mapOf(
                "y" to LV.LNum(c.get(Calendar.YEAR).toDouble()),
                "mo" to LV.LNum(c.get(Calendar.MONTH) + 1.0),
                "d" to LV.LNum(c.get(Calendar.DAY_OF_MONTH).toDouble()),
                "dow" to LV.LNum((c.get(Calendar.DAY_OF_WEEK) + 5) % 7.0), // 0 = Monday
            ).toLv()
        }

        // ---- strings ----
        n("split") { a ->
            val str = argStr(a, 0, "split")
            val sep = if (a.size > 1) argStr(a, 1, "split") else "\n"
            rt.alloc(40L + 8L * str.length)
            LV.LList((if (sep.isEmpty()) str.map { LV.LStr(it.toString()) } else str.split(sep).map { LV.LStr(it) }).toMutableList())
        }
        n("join") { a ->
            val l = argList(a, 0, "join")
            val sep = if (a.size > 1) argStr(a, 1, "join") else ""
            val out = l.items.joinToString(sep) { it.display() }
            rt.alloc(16L + 2L * out.length)
            LV.LStr(out)
        }
        n("trim") { a -> LV.LStr(argStr(a, 0, "trim").trim()) }
        n("upper") { a -> LV.LStr(argStr(a, 0, "upper").uppercase()) }
        n("lower") { a -> LV.LStr(argStr(a, 0, "lower").lowercase()) }
        n("replace") { a ->
            val out = argStr(a, 0, "replace").replace(argStr(a, 1, "replace"), argStr(a, 2, "replace"))
            rt.alloc(16L + 2L * out.length)
            LV.LStr(out)
        }
        n("find") { a ->
            val idx = argStr(a, 0, "find").indexOf(argStr(a, 1, "find"))
            LV.LNum(idx.toDouble())
        }
        n("sub") { a ->
            val str = argStr(a, 0, "sub")
            val start = argNum(a, 1, "sub").toInt().coerceIn(0, str.length)
            val len = if (a.size > 2) argNum(a, 2, "sub").toInt() else str.length - start
            if (len <= 0) LV.LStr("") else LV.LStr(str.substring(start, minOf(str.length, start + len)))
        }

        // ---- OS integration ----
        n("lang") { _ -> LV.LStr(if (rt.kernel.settings.value.langArabic) "ar" else "en") }
        n("t") { a -> LV.LStr(if (rt.kernel.settings.value.langArabic) argStr(a, 1, "t") else argStr(a, 0, "t")) }
        n("sysinfo") { _ ->
            val p = rt.kernel.power.snapshot()
            mapOf(
                "battery" to LV.LNum(p.batteryPct.toDouble()),
                "charging" to LV.LBool(p.charging),
                "hostRamTotalMb" to LV.LNum(p.hostRamTotalMb.toDouble()),
                "hostRamAvailMb" to LV.LNum(p.hostRamAvailMb.toDouble()),
                "cores" to LV.LNum(p.cores.toDouble()),
                "model" to LV.LStr(p.model),
                "android" to LV.LStr(p.androidVersion),
                "vramMb" to LV.LNum(rt.kernel.settings.value.ramMb.toDouble()),
                "pid" to LV.LNum(rt.process.pid.toDouble()),
                "uptimeMs" to LV.LNum(rt.kernel.uptimeMs().toDouble()),
                "runtime" to LV.LStr(Kernel.LOLS_RUNTIME_VERSION),
            ).toLv()
        }
        n("notify") { a ->
            rt.kernel.bus.post(rt.process.appId, argStr(a, 0, "notify"), argStr(a, 1, "notify"))
            LV.LNull
        }
        n("dataDir") { _ -> LV.LStr("/data/${rt.process.appId}") }

        // ---- fs namespace (sandboxed per app by the kernel) ----
        val fs = LinkedHashMap<String, LV>()
        fun fsn(name: String, fn: suspend (List<LV>) -> LV) {
            fs[name] = LV.LNative("fs.$name", fn)
        }
        fsn("read") { a ->
            val path = rt.fsGate(argStr(a, 0, "fs.read"), write = false)
            LV.LStr(rt.kernel.fs.read(path))
        }
        fsn("write") { a ->
            val path = rt.fsGate(argStr(a, 0, "fs.write"), write = true)
            rt.alloc(16L + 2L * argStr(a, 1, "fs.write").length)
            LV.LBool(rt.kernel.fs.write(path, argStr(a, 1, "fs.write")))
        }
        fsn("append") { a ->
            val path = rt.fsGate(argStr(a, 0, "fs.append"), write = true)
            rt.alloc(16L + 2L * argStr(a, 1, "fs.append").length)
            LV.LBool(rt.kernel.fs.append(path, argStr(a, 1, "fs.append")))
        }
        fsn("list") { a ->
            val path = rt.fsGate(argStr(a, 0, "fs.list"), write = false)
            val items = rt.kernel.fs.list(path)
            rt.alloc(48L * items.size)
            LV.LList(items.map { ent ->
                val m = LinkedHashMap<String, LV>()
                m["name"] = LV.LStr(ent.name)
                m["size"] = LV.LNum(ent.size.toDouble())
                m["dir"] = LV.LBool(ent.dir)
                LV.LMap(m)
            }.toMutableList())
        }
        fsn("delete") { a ->
            val path = rt.fsGate(argStr(a, 0, "fs.delete"), write = true)
            LV.LBool(rt.kernel.fs.delete(path))
        }
        fsn("mkdir") { a ->
            val path = rt.fsGate(argStr(a, 0, "fs.mkdir"), write = true)
            LV.LBool(rt.kernel.fs.mkdir(path))
        }
        fsn("exists") { a ->
            val path = rt.fsGate(argStr(a, 0, "fs.exists"), write = false)
            LV.LBool(rt.kernel.fs.exists(path))
        }
        env.declare("fs", LV.LMap(fs))
    }

    private fun Map<String, LV>.toLv(): LV.LMap {
        val m = LinkedHashMap<String, LV>()
        for ((k, v) in this) m[k] = v
        return LV.LMap(m)
    }
}
