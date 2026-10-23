package os.lols.lols

/**
 * Recursive-descent parser for LOLS.
 * Line-sensitivity rule: a binary operator continues an expression only if
 * it sits on the same line as the end of its left operand.
 *
 * UI blocks are parsed in "ui mode": IDENT followed directly by '{' is a UI
 * node; inside node bodies, `name = expr` is a property statement.
 */
class Parser(private val toks: List<Tok>) {
    private var i = 0
    private var lastLine = 1

    private val FLOW_KEYWORDS = setOf("if", "else", "while", "for", "in", "return", "break", "continue", "true", "false", "null", "app", "state", "ui", "func", "on")

    private fun peek(o: Int = 0): Tok = toks[minOf(i + o, toks.size - 1)]
    private fun next(): Tok {
        val t = toks[i]
        if (t.kind != TKind.EOF) { i++; lastLine = t.line }
        return t
    }
    private fun expect(k: TKind, what: String): Tok {
        val t = peek()
        if (t.kind != k) throw LolsError("expected $what but found '${t.text.ifEmpty { "end of file" }}'", t.line)
        return next()
    }
    private fun expectIdent(what: String): String {
        val t = peek()
        if (t.kind != TKind.IDENT) throw LolsError("expected $what but found '${t.text}'", t.line)
        next()
        return t.text
    }
    private fun sameLine(): Boolean = peek().line == lastLine

    fun parseProgram(): Ast.Program {
        val kw = expectIdent("start of program")
        if (kw != "app") throw LolsError("a .lols program must start with: app \"Name\" { ... }", 1)
        val name = expect(TKind.STR, "app display name string after 'app'")
        expect(TKind.LBRACE, "'{' to open the app block")
        val app = parseAppBody(name.text, name.line)
        expect(TKind.EOF, "end of file (check for extra '}')")
        return Ast.Program(app)
    }

    fun parseAppBody(name: String, line: Int): Ast.AppDecl {
        val metas = LinkedHashMap<String, String>()
        val state = ArrayList<Ast.Stmt.Assign>()
        var onStart: Ast.Block? = null
        var tickMs: Long? = null
        var onTick: Ast.Block? = null
        var uiBody: List<Ast.Stmt> = emptyList()
        val funcs = ArrayList<Ast.FuncDecl>()
        val metasAllowed = setOf("id", "version", "icon", "color", "permission", "name", "native", "system")

        while (peek().kind != TKind.RBRACE && peek().kind != TKind.EOF) {
            val t = peek()
            if (t.kind != TKind.IDENT) throw LolsError("unexpected '${t.text}' in app block", t.line)
            when (t.text) {
                "state" -> {
                    next(); expect(TKind.LBRACE, "'{' after state")
                    while (peek().kind != TKind.RBRACE && peek().kind != TKind.EOF) state.add(parseAssign())
                    next()
                }
                "on" -> {
                    next()
                    val ev = expectIdent("event name after 'on'")
                    if (ev == "start") {
                        onStart = parseBlock(ui = false)
                    } else if (ev == "tick") {
                        expect(TKind.LPAREN, "'(' after on tick")
                        val ms = expect(TKind.NUM, "tick interval in milliseconds")
                        expect(TKind.RPAREN, "')' after tick interval")
                        tickMs = ms.num.toLong()
                        onTick = parseBlock(ui = false)
                    } else throw LolsError("unknown event '$ev' (use: start, tick)", t.line)
                }
                "ui" -> {
                    next()
                    uiBody = parseUiBlock()
                }
                "func" -> funcs.add(parseFunc())
                in metasAllowed -> {
                    next()
                    val v = peek()
                    if (v.kind == TKind.STR || v.kind == TKind.IDENT || v.kind == TKind.NUM) {
                        next(); metas[t.text] = v.text
                    } else throw LolsError("expected value after '${t.text}'", t.line)
                }
                else -> throw LolsError("unknown item '${t.text}' in app block (allowed: state, on, ui, func, id, version, icon, color, permission)", t.line)
            }
        }
        expect(TKind.RBRACE, "'}' to close the app block")
        return Ast.AppDecl(name, line, metas, state, onStart, tickMs, onTick, uiBody, funcs)
    }

    private fun parseFunc(): Ast.FuncDecl {
        val kw = next() // func
        val name = expectIdent("function name")
        expect(TKind.LPAREN, "'(' after function name")
        val params = ArrayList<String>()
        if (peek().kind != TKind.RPAREN) {
            params.add(expectIdent("parameter name"))
            while (peek().kind == TKind.COMMA) { next(); params.add(expectIdent("parameter name")) }
        }
        expect(TKind.RPAREN, "')' after parameters")
        val body = parseBlock(ui = false)
        return Ast.FuncDecl(name, params, body, kw.line)
    }

    // ---------- blocks & statements ----------

    private fun parseBlock(ui: Boolean): Ast.Block {
        expect(TKind.LBRACE, "'{'")
        val stmts = ArrayList<Ast.Stmt>()
        while (peek().kind != TKind.RBRACE && peek().kind != TKind.EOF) {
            stmts.add(if (ui) parseUiStmt() else parseStmt(ui = false))
        }
        expect(TKind.RBRACE, "'}'")
        return Ast.Block(stmts)
    }

    private fun parseUiBlock(): List<Ast.Stmt> {
        expect(TKind.LBRACE, "'{' after ui")
        val stmts = ArrayList<Ast.Stmt>()
        while (peek().kind != TKind.RBRACE && peek().kind != TKind.EOF) {
            stmts.add(parseUiStmt())
        }
        expect(TKind.RBRACE, "'}' to close the ui block")
        return stmts
    }

    private fun parseUiStmt(): Ast.Stmt {
        val t = peek()
        if (t.kind == TKind.IDENT && peek(1).kind == TKind.LBRACE && t.text !in FLOW_KEYWORDS) {
            return Ast.Stmt.Node(parseUiNode())
        }
        return parseStmt(ui = true)
    }

    private fun parseUiNode(): Ast.UiNode {
        val t = peek()
        if (t.kind != TKind.IDENT) throw LolsError("expected UI node name, found '${t.text}'", t.line)
        next()
        expect(TKind.LBRACE, "'{' after UI node '${t.text}'")
        val body = ArrayList<Ast.Stmt>()
        while (peek().kind != TKind.RBRACE && peek().kind != TKind.EOF) {
            val p = peek()
            if (p.kind == TKind.IDENT && peek(1).kind == TKind.EQ) {
                // property: name = expr
                next()
                next()
                body.add(Ast.Stmt.Prop(p.text, parseExpr(), p.line))
                skipSemi()
            } else if (p.kind == TKind.IDENT && peek(1).kind == TKind.LBRACE && p.text !in FLOW_KEYWORDS) {
                body.add(Ast.Stmt.Node(parseUiNode()))
            } else {
                body.add(parseStmt(ui = true))
            }
        }
        expect(TKind.RBRACE, "'}' to close UI node '${t.text}'")
        return Ast.UiNode(t.text, body, t.line)
    }

    private fun parseStmt(ui: Boolean): Ast.Stmt {
        val t = peek()
        if (t.kind == TKind.IDENT) {
            when (t.text) {
                "if" -> return parseIf(ui)
                "while" -> {
                    next()
                    val cond = parseExpr()
                    val body = parseBlock(ui)
                    return Ast.Stmt.While(cond, body, t.line)
                }
                "for" -> {
                    next()
                    val v = expectIdent("loop variable name")
                    val kw = expectIdent("'in'")
                    if (kw != "in") throw LolsError("expected 'in' in for loop", t.line)
                    val iter = parseExpr()
                    val body = parseBlock(ui)
                    return Ast.Stmt.ForIn(v, iter, body, t.line)
                }
                "return" -> {
                    next()
                    val value = if (peek().kind == TKind.SEMI || peek().kind == TKind.RBRACE || !sameLine()) null else parseExpr()
                    skipSemi()
                    return Ast.Stmt.Return(value, t.line)
                }
                "break" -> { next(); skipSemi(); return Ast.Stmt.Break }
                "continue" -> { next(); skipSemi(); return Ast.Stmt.Continue }
            }
        }
        // assignment or expression statement
        val start = i
        val startLine = lastLine
        val e = parseExpr()
        if (peek().kind == TKind.EQ || (peek().kind == TKind.PLUS && peek(1).kind == TKind.EQ && sameLine()) ||
            (peek().kind == TKind.MINUS && peek(1).kind == TKind.EQ && sameLine())) {
            i = start; lastLine = startLine
            val target = parsePostfix()
            if (target !is Ast.Expr.Ident && target !is Ast.Expr.Index && target !is Ast.Expr.Member)
                throw LolsError("invalid assignment target", t.line)
            var op = "="
            if (peek().kind == TKind.PLUS || peek().kind == TKind.MINUS) { op = next().text + "=" }
            expect(TKind.EQ, "'='")
            val value = parseExpr()
            skipSemi()
            return Ast.Stmt.Assign(target, op, value, t.line)
        }
        skipSemi()
        return Ast.Stmt.ExprStmt(e, t.line)
    }

    private fun parseAssign(): Ast.Stmt.Assign {
        val t = peek()
        val target = parsePostfix()
        if (target !is Ast.Expr.Ident && target !is Ast.Expr.Index && target !is Ast.Expr.Member)
            throw LolsError("invalid assignment target in state block", t.line)
        var op = "="
        if (peek().kind == TKind.PLUS || peek().kind == TKind.MINUS) { op = next().text + "=" }
        expect(TKind.EQ, "'=' in assignment")
        val value = parseExpr()
        skipSemi()
        return Ast.Stmt.Assign(target, op, value, t.line)
    }

    private fun parseIf(ui: Boolean): Ast.Stmt.If {
        val kw = next() // if
        val branches = ArrayList<Pair<Ast.Expr, Ast.Block>>()
        val cond = parseExpr()
        val body = parseBlock(ui)
        branches.add(cond to body)
        var elseBody: Ast.Block? = null
        while (peek().kind == TKind.IDENT && peek().text == "else" && peek().line == lastLine) {
            next()
            if (peek().kind == TKind.IDENT && peek().text == "if") {
                next()
                val c2 = parseExpr()
                val b2 = parseBlock(ui)
                branches.add(c2 to b2)
            } else {
                elseBody = parseBlock(ui)
                break
            }
        }
        return Ast.Stmt.If(branches, elseBody, kw.line)
    }

    private fun skipSemi() { while (peek().kind == TKind.SEMI) next() }

    // ---------- expressions ----------

    private fun parseExpr(): Ast.Expr = parseOr()

    private fun parseOr(): Ast.Expr {
        var l = parseAnd()
        while (peek().kind == TKind.OROR && sameLine()) {
            val t = next(); val r = parseAnd(); l = Ast.Expr.Or(l, r, t.line)
        }
        return l
    }

    private fun parseAnd(): Ast.Expr {
        var l = parseEquality()
        while (peek().kind == TKind.ANDAND && sameLine()) {
            val t = next(); val r = parseEquality(); l = Ast.Expr.And(l, r, t.line)
        }
        return l
    }

    private fun parseEquality(): Ast.Expr {
        var l = parseComparison()
        while (sameLine() && (peek().kind == TKind.EQEQ || peek().kind == TKind.NEQ)) {
            val t = next(); val r = parseComparison(); l = Ast.Expr.BinOp(t.text, l, r, t.line)
        }
        return l
    }

    private fun parseComparison(): Ast.Expr {
        var l = parseAdditive()
        while (sameLine() && (peek().kind == TKind.LT || peek().kind == TKind.LE || peek().kind == TKind.GT || peek().kind == TKind.GE)) {
            val t = next(); val r = parseAdditive(); l = Ast.Expr.BinOp(t.text, l, r, t.line)
        }
        return l
    }

    private fun parseAdditive(): Ast.Expr {
        var l = parseMultiplicative()
        while (sameLine() && (peek().kind == TKind.PLUS || peek().kind == TKind.MINUS)) {
            if (peek(1).kind == TKind.EQ) break // compound assignment (+=, -=)
            val t = next(); val r = parseMultiplicative(); l = Ast.Expr.BinOp(t.text, l, r, t.line)
        }
        return l
    }

    private fun parseMultiplicative(): Ast.Expr {
        var l = parseUnary()
        while (sameLine() && (peek().kind == TKind.STAR || peek().kind == TKind.SLASH || peek().kind == TKind.PERCENT)) {
            val t = next(); val r = parseUnary(); l = Ast.Expr.BinOp(t.text, l, r, t.line)
        }
        return l
    }

    private fun parseUnary(): Ast.Expr {
        val t = peek()
        if (t.kind == TKind.MINUS || t.kind == TKind.BANG) {
            next()
            return Ast.Expr.Unary(t.text, parseUnary(), t.line)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Ast.Expr {
        var e = parsePrimary()
        while (true) {
            val t = peek()
            if (t.kind == TKind.DOT && sameLine()) {
                next()
                val name = expectIdent("member name after '.'")
                e = Ast.Expr.Member(e, name, t.line)
            } else if (t.kind == TKind.LBRACK && sameLine()) {
                next()
                val idx = parseExpr()
                expect(TKind.RBRACK, "']'")
                e = Ast.Expr.Index(e, idx, t.line)
            } else if (t.kind == TKind.LPAREN && sameLine()) {
                next()
                val args = ArrayList<Ast.Expr>()
                if (peek().kind != TKind.RPAREN) {
                    args.add(parseExpr())
                    while (peek().kind == TKind.COMMA) { next(); args.add(parseExpr()) }
                }
                expect(TKind.RPAREN, "')'")
                e = Ast.Expr.Call(e, args, t.line)
            } else break
        }
        return e
    }

    private fun parsePrimary(): Ast.Expr {
        val t = peek()
        when (t.kind) {
            TKind.NUM -> { next(); return Ast.Expr.Num(t.num, t.line) }
            TKind.STR -> { next(); return Ast.Expr.Str(t.text, t.line) }
            TKind.LPAREN -> {
                next()
                val e = parseExpr()
                expect(TKind.RPAREN, "')'")
                return e
            }
            TKind.LBRACK -> return parseListOrMapLit()
            TKind.LBRACE -> return parseClosureLit()
            TKind.IDENT -> {
                next()
                return when (t.text) {
                    "true" -> Ast.Expr.BoolV(true, t.line)
                    "false" -> Ast.Expr.BoolV(false, t.line)
                    "null" -> Ast.Expr.NullV
                    in FLOW_KEYWORDS -> throw LolsError("keyword '${t.text}' cannot be used as a value", t.line)
                    else -> Ast.Expr.Ident(t.text, t.line)
                }
            }
            else -> throw LolsError("unexpected '${t.text.ifEmpty { "end of file" }}' in expression", t.line)
        }
    }

    /** [a, b, c] is a list; [a: 1, b: 2] is a map. */
    private fun parseListOrMapLit(): Ast.Expr {
        val open = expect(TKind.LBRACK, "'['")
        if (peek().kind == TKind.RBRACK) { next(); return Ast.Expr.ListLit(emptyList(), open.line) }
        val isMap = (peek().kind == TKind.IDENT || peek().kind == TKind.STR) && peek(1).kind == TKind.COLON
        if (isMap) {
            val pairs = ArrayList<Pair<String, Ast.Expr>>()
            while (true) {
                val k = peek()
                val key = if (k.kind == TKind.STR || k.kind == TKind.IDENT) next().text
                else throw LolsError("map key must be a name or string", k.line)
                expect(TKind.COLON, "':' after map key")
                pairs.add(key to parseExpr())
                if (peek().kind == TKind.COMMA) { next(); continue }
                break
            }
            expect(TKind.RBRACK, "']' to close map")
            return Ast.Expr.MapLit(pairs, open.line)
        }
        val items = ArrayList<Ast.Expr>()
        while (true) {
            items.add(parseExpr())
            if (peek().kind == TKind.COMMA) { next(); continue }
            break
        }
        expect(TKind.RBRACK, "']' to close list")
        return Ast.Expr.ListLit(items, open.line)
    }

    /** Closure: { stmts } or { param | stmts } */
    private fun parseClosureLit(): Ast.Expr {
        val open = expect(TKind.LBRACE, "'{'")
        var param: String? = null
        if (peek().kind == TKind.IDENT && peek(1).kind == TKind.PIPE) {
            param = next().text
            next() // |
        }
        val stmts = ArrayList<Ast.Stmt>()
        while (peek().kind != TKind.RBRACE && peek().kind != TKind.EOF) {
            stmts.add(parseUiStmt()) // closures may also declare UI nodes
        }
        expect(TKind.RBRACE, "'}' to close closure")
        return Ast.Expr.Closure(param, Ast.Block(stmts), open.line)
    }
}
