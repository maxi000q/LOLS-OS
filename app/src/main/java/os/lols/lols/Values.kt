package os.lols.lols

/**
 * LOLS runtime value model.
 * Every value a .lols program can hold. Lists and maps are mutable
 * (like real OS script runtimes); functions/closures are first-class.
 */
sealed interface LV {
    object LNull : LV
    data class LNum(val v: Double) : LV
    data class LStr(val s: String) : LV
    data class LBool(val b: Boolean) : LV
    class LList(val items: MutableList<LV>) : LV
    class LMap(val entries: LinkedHashMap<String, LV>) : LV
    data class LFunc(val decl: Ast.FuncDecl, val env: Env) : LV
    data class LNative(val name: String, val fn: suspend (List<LV>) -> LV) : LV
    data class LClosure(val body: Ast.Block, val param: String?, val env: Env) : LV

    fun truthy(): Boolean = when (this) {
        is LNull -> false
        is LBool -> b
        is LNum -> v != 0.0
        is LStr -> s.isNotEmpty()
        is LList -> items.isNotEmpty()
        is LMap -> entries.isNotEmpty()
        is LFunc, is LNative, is LClosure -> true
    }

    /** Display string used by str() builtin and concatenation. */
    fun display(): String = when (this) {
        is LNull -> "null"
        is LBool -> if (b) "true" else "false"
        is LNum -> formatNum(v)
        is LStr -> s
        is LList -> items.joinToString(", ", "[", "]") { it.display() }
        is LMap -> entries.entries.joinToString(", ", "{", "}") { "${it.key}: ${it.value.display()}" }
        is LFunc -> "<func ${decl.name}>"
        is LNative -> "<native $name>"
        is LClosure -> "<closure>"
    }
}

/** Format a double the way a calculator user expects: no float noise. */
fun formatNum(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return if (v.isNaN()) "NaN" else if (v > 0) "Infinity" else "-Infinity"
    if (kotlin.math.abs(v) < 1e15 && v == kotlin.math.floor(v)) {
        return v.toLong().toString()
    }
    var s = String.format(java.util.Locale.US, "%.10g", v)
    if (s.contains('e')) s = s.replace("e+", "e")
    return s
}

/** Structural equality (deep for lists/maps). */
fun lvEquals(a: LV, b: LV): Boolean {
    if (a is LNum && b is LNum) return a.v == b.v
    if (a is LStr && b is LStr) return a.s == b.s
    if (a is LBool && b is LBool) return a.b == b.b
    if (a is LNull && b is LNull) return true
    if (a is LList && b is LList) {
        if (a.items.size != b.items.size) return false
        for (i in a.items.indices) if (!lvEquals(a.items[i], b.items[i])) return false
        return true
    }
    if (a is LMap && b is LMap) {
        if (a.entries.size != b.entries.size) return false
        for ((k, v) in a.entries) {
            val o = b.entries[k] ?: return false
            if (!lvEquals(v, o)) return false
        }
        return true
    }
    return false
}

/** Rough live-memory estimate in bytes, used by the real MemoryManager. */
fun lvSize(v: LV): Long = when (v) {
    is LV.LNull, is LV.LBool -> 8
    is LV.LNum -> 16
    is LV.LStr -> 16L + 2L * v.s.length
    is LV.LList -> 40L + 8L * v.items.size + v.items.sumOf { 8 }
    is LV.LMap -> 48L + 32L * v.entries.size
    is LV.LFunc, is LV.LNative, is LV.LClosure -> 32
}

/**
 * Chained scope environment.
 * - set() walks the chain (globals live & reactive).
 * - Assignment to an unknown name declares it in the CURRENT scope.
 */
class Env(val parent: Env? = null) {
    val vars = HashMap<String, LV>()

    fun lookup(name: String): LV? {
        var e: Env? = this
        while (e != null) {
            val v = e.vars[name]
            if (v != null) return v
            e = e.parent
        }
        return null
    }

    fun has(name: String): Boolean = lookup(name) != null

    /** Assign to an existing binding (walks chain), or declare here if not found. */
    fun set(name: String, v: LV) {
        var e: Env? = this
        while (e != null) {
            if (e.vars.containsKey(name)) {
                e.vars[name] = v
                return
            }
            e = e.parent
        }
        vars[name] = v
    }

    fun declare(name: String, v: LV) {
        vars[name] = v
    }

    /** Snapshot for closure capture: locals copied by value, globals reachable through parent chain. */
    fun capture(globalRoot: Env): Env {
        val snap = Env(parent = globalRoot)
        for ((k, v) in vars) snap.vars[k] = v
        return snap
    }
}

/** Runtime error inside a .lols program. */
class LolsError(message: String, val line: Int = 0) : Exception(if (line > 0) "line $line: $message" else message)

/** Out-of-memory kill signal raised by MemoryManager. */
class LolsOOM(message: String) : Exception(message)
