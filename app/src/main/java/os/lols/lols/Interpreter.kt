package os.lols.lols

/**
 * The LOLS tree-walking interpreter. Fully suspendable: every statement
 * reports to the runtime scheduler (step counting, cooperative yielding,
 * memory accounting) so the kernel can preempt, throttle and OOM-kill
 * processes exactly like a real operating system does.
 */
class Interpreter(private val rt: Runtime) {

    class RetEx(val value: LV) : Exception()
    object BreakEx : Exception()
    object ContinueEx : Exception()

    private fun step(line: Int) = rt.step(line)
    private fun alloc(bytes: Long) = rt.alloc(bytes)

    // ---------------- statements ----------------

    suspend fun execBlock(block: Ast.Block, env: Env) {
        for (s in block.stmts) exec(s, env)
    }

    suspend fun exec(s: Ast.Stmt, env: Env) {
        step(0)
        when (s) {
            is Ast.Stmt.Assign -> execAssign(s, env)
            is Ast.Stmt.If -> {
                for ((cond, body) in s.branches) {
                    if (eval(cond, env).truthy()) { execBlock(body, env); return }
                }
                s.elseBody?.let { execBlock(it, env) }
            }
            is Ast.Stmt.While -> {
                while (eval(s.cond, env).truthy()) {
                    step(s.line)
                    try { execBlock(s.body, env) } catch (e: BreakEx) { break } catch (e: ContinueEx) { }
                }
            }
            is Ast.Stmt.ForIn -> {
                val it = eval(s.iterable, env)
                when (it) {
                    is LV.LList -> {
                        var k = 0
                        while (k < it.items.size) {
                            step(s.line)
                            env.declare(s.varName, it.items[k])
                            try { execBlock(s.body, env) } catch (e: BreakEx) { break } catch (e: ContinueEx) { }
                            k++
                        }
                    }
                    is LV.LMap -> {
                        val keys = ArrayList(it.entries.keys)
                        for (k in keys) {
                            step(s.line)
                            env.declare(s.varName, LV.LStr(k))
                            try { execBlock(s.body, env) } catch (e: BreakEx) { break } catch (e: ContinueEx) { }
                        }
                    }
                    is LV.LStr -> {
                        for (ch in it.s) {
                            step(s.line)
                            env.declare(s.varName, LV.LStr(ch.toString()))
                            try { execBlock(s.body, env) } catch (e: BreakEx) { break } catch (e: ContinueEx) { }
                        }
                    }
                    else -> throw LolsError("for-in needs a list, map or string", s.line)
                }
            }
            is Ast.Stmt.Return -> throw RetEx(s.value?.let { eval(it, env) } ?: LV.LNull)
            is Ast.Stmt.ExprStmt -> eval(s.expr, env)
            is Ast.Stmt.Break -> throw BreakEx
            is Ast.Stmt.Continue -> throw ContinueEx
            is Ast.Stmt.Node -> rt.buildNode(s.node, env)
            is Ast.Stmt.Prop -> rt.attachProp(s, env)
        }
    }

    private suspend fun execAssign(s: Ast.Stmt.Assign, env: Env) {
        when (s.target) {
            is Ast.Expr.Ident -> {
                var value = eval(s.value, env)
                if (s.op != "=") value = compound(s.op, env.lookup(s.target.name) ?: LV.LNull, value, s.line)
                env.set(s.target.name, value)
                rt.onGlobalWrite(s.target.name)
            }
            is Ast.Expr.Index -> {
                val obj = eval(s.target.obj, env)
                val idx = eval(s.target.idx, env)
                var value = eval(s.value, env)
                when (obj) {
                    is LV.LList -> {
                        val k = asIndex(idx, s.line)
                        if (k < 0 || k >= obj.items.size) throw LolsError("list index $k out of range (size ${obj.items.size})", s.line)
                        if (s.op != "=") value = compound(s.op, obj.items[k], value, s.line)
                        obj.items[k] = value
                    }
                    is LV.LMap -> {
                        val key = asKey(idx, s.line)
                        if (s.op != "=") value = compound(s.op, obj.entries[key] ?: LV.LNull, value, s.line)
                        obj.entries[key] = value
                    }
                    else -> throw LolsError("only lists and maps support [] assignment", s.line)
                }
            }
            is Ast.Expr.Member -> {
                val obj = eval(s.target.obj, env)
                var value = eval(s.value, env)
                if (obj !is LV.LMap) throw LolsError("only maps support property assignment", s.line)
                if (s.op != "=") value = compound(s.op, obj.entries[s.target.name] ?: LV.LNull, value, s.line)
                obj.entries[s.target.name] = value
            }
            else -> throw LolsError("invalid assignment target", s.line)
        }
    }

    private fun compound(op: String, old: LV, inc: LV, line: Int): LV {
        return when {
            old is LV.LNum && inc is LV.LNum -> LV.LNum(if (op == "+=") old.v + inc.v else old.v - inc.v)
            old is LV.LStr || inc is LV.LStr -> LV.LStr(old.display() + inc.display()).also { alloc(16L + 2L * it.s.length) }
            old is LV.LList && inc is LV.LList -> {
                val m = ArrayList<LV>(old.items.size + inc.items.size)
                m.addAll(old.items); m.addAll(inc.items)
                LV.LList(m).also { alloc(40L + 8L * m.size) }
            }
            old is LV.LNull && inc is LV.LNum && op == "-=" -> LV.LNum(-inc.v)
            else -> throw LolsError("cannot apply $op to ${old.display()} and ${inc.display()}", line)
        }
    }

    // ---------------- expressions ----------------

    suspend fun eval(e: Ast.Expr, env: Env): LV {
        return when (e) {
            is Ast.Expr.Num -> LV.LNum(e.v)
            is Ast.Expr.Str -> { alloc(16L + 2L * e.s.length); LV.LStr(e.s) }
            is Ast.Expr.BoolV -> LV.LBool(e.b)
            is Ast.Expr.NullV -> LV.LNull
            is Ast.Expr.Ident -> env.lookup(e.name)
                ?: throw LolsError("unknown name '${e.name}'", e.line)
            is Ast.Expr.Or -> {
                val l = eval(e.l, env)
                if (l.truthy()) LV.LBool(true) else LV.LBool(eval(e.r, env).truthy())
            }
            is Ast.Expr.And -> {
                val l = eval(e.l, env)
                if (!l.truthy()) LV.LBool(false) else LV.LBool(eval(e.r, env).truthy())
            }
            is Ast.Expr.BinOp -> {
                val l = eval(e.l, env)
                val r = eval(e.r, env)
                binOp(e.op, l, r, e.line)
            }
            is Ast.Expr.Unary -> {
                val v = eval(e.e, env)
                when (e.op) {
                    "-" -> if (v is LV.LNum) LV.LNum(-v.v) else throw LolsError("unary '-' needs a number", e.line)
                    "!" -> LV.LBool(!v.truthy())
                    else -> throw LolsError("unknown unary operator ${e.op}", e.line)
                }
            }
            is Ast.Expr.Call -> {
                step(e.line)
                val fn = eval(e.callee, env)
                val args = ArrayList<LV>(e.args.size)
                for (a in e.args) args.add(eval(a, env))
                call(fn, args, e.line)
            }
            is Ast.Expr.Member -> {
                val obj = eval(e.obj, env)
                if (obj is LV.LMap) obj.entries[e.name] ?: LV.LNull
                else throw LolsError("cannot read '.${e.name}' from ${obj.display()}", e.line)
            }
            is Ast.Expr.Index -> {
                val obj = eval(e.obj, env)
                val idx = eval(e.idx, env)
                when (obj) {
                    is LV.LList -> {
                        val k = asIndex(idx, e.line)
                        if (k < 0 || k >= obj.items.size) throw LolsError("list index $k out of range (size ${obj.items.size})", e.line)
                        obj.items[k]
                    }
                    is LV.LMap -> obj.entries[asKey(idx, e.line)] ?: LV.LNull
                    is LV.LStr -> {
                        val k = asIndex(idx, e.line)
                        if (k < 0 || k >= obj.s.length) throw LolsError("string index $k out of range", e.line)
                        LV.LStr(obj.s[k].toString())
                    }
                    else -> throw LolsError("cannot index ${obj.display()}", e.line)
                }
            }
            is Ast.Expr.ListLit -> {
                alloc(40L + 8L * e.items.size)
                val items = ArrayList<LV>(e.items.size)
                for (x in e.items) items.add(eval(x, env))
                LV.LList(items)
            }
            is Ast.Expr.MapLit -> {
                alloc(48L + 32L * e.pairs.size)
                val m = LinkedHashMap<String, LV>(e.pairs.size)
                for ((k, x) in e.pairs) m[k] = eval(x, env)
                LV.LMap(m)
            }
            is Ast.Expr.Closure -> {
                alloc(32)
                LV.LClosure(e.body, e.param, env.capture(rt.globalEnv))
            }
        }
    }

    /** Call a function/closure/native with real arguments. */
    suspend fun call(fn: LV, args: List<LV>, line: Int): LV {
        return when (fn) {
            is LV.LNative -> fn.fn(args)
            is LV.LFunc -> {
                if (args.size != fn.decl.params.size)
                    throw LolsError("func ${fn.decl.name} expects ${fn.decl.params.size} argument(s), got ${args.size}", line)
                val env = Env(fn.env)
                for ((k, p) in fn.decl.params.withIndex()) env.declare(p, args[k])
                try {
                    execBlock(fn.decl.body, env)
                    LV.LNull
                } catch (e: RetEx) { e.value }
            }
            is LV.LClosure -> {
                val env = Env(fn.env)
                if (fn.param != null) env.declare(fn.param, args.getOrElse(0) { LV.LNull })
                try {
                    execBlock(fn.body, env)
                    LV.LNull
                } catch (e: RetEx) { e.value }
            }
            else -> throw LolsError("value is not callable: ${fn.display()}", line)
        }
    }

    private fun binOp(op: String, l: LV, r: LV, line: Int): LV {
        return when (op) {
            "+" -> when {
                l is LV.LNum && r is LV.LNum -> LV.LNum(l.v + r.v)
                l is LV.LStr || r is LV.LStr -> {
                    val s = l.display() + r.display()
                    alloc(16L + 2L * s.length)
                    LV.LStr(s)
                }
                l is LV.LList && r is LV.LList -> {
                    alloc(40L + 8L * (l.items.size + r.items.size))
                    val m = ArrayList<LV>(l.items.size + r.items.size)
                    m.addAll(l.items); m.addAll(r.items)
                    LV.LList(m)
                }
                else -> throw LolsError("cannot add ${typeName(l)} and ${typeName(r)}", line)
            }
            "-", "*", "/", "%" -> {
                if (l !is LV.LNum || r !is LV.LNum) throw LolsError("operator '$op' needs numbers (got ${typeName(l)} and ${typeName(r)})", line)
                val v = when (op) {
                    "-" -> l.v - r.v
                    "*" -> l.v * r.v
                    "/" -> if (r.v == 0.0) throw LolsError("division by zero", line) else l.v / r.v
                    else -> if (r.v == 0.0) throw LolsError("modulo by zero", line) else l.v % r.v
                }
                LV.LNum(v)
            }
            "==" -> LV.LBool(lvEquals(l, r))
            "!=" -> LV.LBool(!lvEquals(l, r))
            "<", "<=", ">", ">=" -> {
                if (l is LV.LNum && r is LV.LNum) {
                    LV.LBool(when (op) {
                        "<" -> l.v < r.v; "<=" -> l.v <= r.v; ">" -> l.v > r.v; else -> l.v >= r.v
                    })
                } else if (l is LV.LStr && r is LV.LStr) {
                    LV.LBool(when (op) {
                        "<" -> l.s < r.s; "<=" -> l.s <= r.s; ">" -> l.s > r.s; else -> l.s >= r.s
                    })
                } else throw LolsError("comparison '$op' needs two numbers or two strings", line)
            }
            else -> throw LolsError("unknown operator '$op'", line)
        }
    }

    private fun asIndex(v: LV, line: Int): Int {
        if (v !is LV.LNum) throw LolsError("index must be a number", line)
        return v.v.toInt()
    }

    private fun asKey(v: LV, line: Int): String = when (v) {
        is LV.LStr -> v.s
        is LV.LNum -> formatNum(v.v)
        is LV.LBool -> v.b.toString()
        else -> throw LolsError("invalid map key", line)
    }

    private fun typeName(v: LV): String = when (v) {
        is LV.LNull -> "null"
        is LV.LNum -> "number"
        is LV.LStr -> "string"
        is LV.LBool -> "bool"
        is LV.LList -> "list"
        is LV.LMap -> "map"
        is LV.LFunc -> "func"
        is LV.LNative -> "native"
        is LV.LClosure -> "closure"
    }
}
