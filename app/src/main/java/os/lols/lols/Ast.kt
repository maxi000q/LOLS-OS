package os.lols.lols

/**
 * AST for the LOLS language.
 * Grammar summary (full guide in README.md):
 *
 * program  := 'app' STRING '{' appItem* '}'
 * appItem  := meta | 'state' '{' assign* '}' | onBlock | 'ui' uiBlock | funcDecl
 * onBlock  := 'on' 'start' block | 'on' 'tick' '(' NUMBER ')' block
 * uiBlock  := '{' uiStmt* '}'
 * uiStmt   := node | stmt
 * node     := IDENT '{' nodeItem* '}'
 * nodeItem := IDENT '=' expr        (property; also visible as a build-local variable)
 *           | node | stmt           (if / while / for flow control is allowed)
 * func     := 'func' IDENT '(' params? ')' block
 * stmt     := assign | if | while | for | return | break | continue | expr
 * expr     := or / and / equality / comparison / additive / multiplicative / unary / postfix / primary
 */
object Ast {

    data class Program(val app: AppDecl)

    data class AppDecl(
        val name: String,
        val line: Int,
        val metas: Map<String, String>,
        val state: List<Stmt.Assign>,
        val onStart: Block?,
        val tickMs: Long?,
        val onTick: Block?,
        val uiBody: List<Stmt>,
        val funcs: List<FuncDecl>,
    )

    sealed class Stmt {
        data class Assign(val target: Expr, val op: String, val value: Expr, val line: Int) : Stmt()
        data class If(val branches: List<Pair<Expr, Block>>, val elseBody: Block?, val line: Int) : Stmt()
        data class While(val cond: Expr, val body: Block, val line: Int) : Stmt()
        data class ForIn(val varName: String, val iterable: Expr, val body: Block, val line: Int) : Stmt()
        data class Return(val value: Expr?, val line: Int) : Stmt()
        data class ExprStmt(val expr: Expr, val line: Int) : Stmt()
        object Break : Stmt()
        object Continue : Stmt()

        /** A UI node declaration, allowed inside ui blocks and node bodies. */
        data class Node(val node: UiNode) : Stmt()

        /** `name = value` inside a node body: attaches a property to the node
         *  and also declares a build-local variable. */
        data class Prop(val name: String, val value: Expr, val line: Int) : Stmt()
    }

    data class Block(val stmts: List<Stmt>)

    sealed class Expr {
        data class Num(val v: Double, val line: Int) : Expr()
        data class Str(val s: String, val line: Int) : Expr()
        data class BoolV(val b: Boolean, val line: Int) : Expr()
        object NullV : Expr()
        data class Ident(val name: String, val line: Int) : Expr()
        data class BinOp(val op: String, val l: Expr, val r: Expr, val line: Int) : Expr()
        data class Unary(val op: String, val e: Expr, val line: Int) : Expr()
        data class And(val l: Expr, val r: Expr, val line: Int) : Expr()
        data class Or(val l: Expr, val r: Expr, val line: Int) : Expr()
        data class Call(val callee: Expr, val args: List<Expr>, val line: Int) : Expr()
        data class Member(val obj: Expr, val name: String, val line: Int) : Expr()
        data class Index(val obj: Expr, val idx: Expr, val line: Int) : Expr()
        data class ListLit(val items: List<Expr>, val line: Int) : Expr()
        data class MapLit(val pairs: List<Pair<String, Expr>>, val line: Int) : Expr()
        data class Closure(val param: String?, val body: Block, val line: Int) : Expr()
    }

    /** UI node: kind + body (props, nested nodes, flow control). */
    data class UiNode(
        val kind: String,
        val body: List<Stmt>,
        val line: Int,
    )

    /** A parsed function declaration. */
    data class FuncDecl(val name: String, val params: List<String>, val body: Block, val line: Int)
}
