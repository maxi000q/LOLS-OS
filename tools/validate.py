#!/usr/bin/env python3
"""
validate.py - offline checks for the LOLS OS repo:
  1. Every lolsrc/**/*.lols file parses against the LOLS grammar
     (a faithful Python mirror of the Kotlin lexer/parser).
  2. All XML resources are well-formed.
  3. Kotlin sources have balanced braces (heuristic warning only).
  4. .lols packages in assets/repo are readable ZIPs with both entries.

Run from the repo root:  python3 tools/validate.py
"""
import re
import sys
import zipfile
import xml.dom.minidom
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

FLOW_KEYWORDS = {"if", "else", "while", "for", "in", "return", "break",
                 "continue", "true", "false", "null", "app", "state", "ui",
                 "func", "on"}
METAS = {"id", "version", "icon", "color", "permission", "name", "native", "system"}

SYMBOLS = [
    ("==",), ("!=",), ("<=",), (">=",), ("&&",), ("||",),
    ("{",), ("}",), ("(",), (")",), ("[",), ("]",), (",",), (";",),
    (".",), ("|",), (":",), ("=",), ("<",), (">",), ("+",), ("-",),
    ("*",), ("/",), ("%",), ("!",),
]


class LolsError(Exception):
    pass


class Tok:
    def __init__(self, kind, text, line, value=None):
        self.kind = kind
        self.text = text
        self.line = line
        self.value = value

    def __repr__(self):
        return f"{self.kind}({self.text})@{self.line}"


def lex(src: str):
    toks = []
    pos, line = 0, 1
    n = len(src)
    while pos < n:
        c = src[pos]
        if c == "\n":
            line += 1
            pos += 1
        elif c in " \t\r":
            pos += 1
        elif c == "/" and pos + 1 < n and src[pos + 1] == "/":
            while pos < n and src[pos] != "\n":
                pos += 1
        elif c == "/" and pos + 1 < n and src[pos + 1] == "*":
            pos += 2
            while pos + 1 < n and not (src[pos] == "*" and src[pos + 1] == "/"):
                if src[pos] == "\n":
                    line += 1
                pos += 1
            pos += 2
        elif c == '"':
            start_line = line
            pos += 1
            buf = []
            while pos < n and src[pos] != '"':
                ch = src[pos]
                if ch == "\\" and pos + 1 < n:
                    pos += 1
                    e = src[pos]
                    if e == "u":
                        hexs = src[pos + 1:pos + 5]
                        if len(hexs) != 4 or not all(h in "0123456789abcdefABCDEF" for h in hexs):
                            raise LolsError(f"line {start_line}: bad \\u escape")
                        buf.append("u")
                        pos += 4
                    elif e in "ntr\"\\":
                        buf.append(e)
                    else:
                        raise LolsError(f"line {start_line}: bad escape \\{e}")
                else:
                    if ch == "\n":
                        line += 1
                    buf.append(ch)
                pos += 1
            if pos >= n:
                raise LolsError(f"line {start_line}: unterminated string")
            pos += 1
            toks.append(Tok("STR", "".join(buf), start_line))
        elif c.isdigit() or (c == "." and pos + 1 < n and src[pos + 1].isdigit()):
            start = pos
            while pos < n and (src[pos].isdigit() or src[pos] == "."):
                pos += 1
            text = src[start:pos]
            try:
                float(text)
            except ValueError:
                raise LolsError(f"line {line}: bad number {text}")
            toks.append(Tok("NUM", text, line))
        elif c.isalpha() or c == "_":
            start = pos
            while pos < n and (src[pos].isalnum() or src[pos] == "_"):
                pos += 1
            toks.append(Tok("IDENT", src[start:pos], line))
        else:
            matched = None
            for (sym,) in SYMBOLS:
                if src.startswith(sym, pos):
                    matched = sym
                    break
            if not matched:
                raise LolsError(f"line {line}: unexpected character {c!r}")
            pos += len(matched)
            toks.append(Tok(matched, matched, line))
    toks.append(Tok("EOF", "", line))
    return toks


class Parser:
    def __init__(self, toks):
        self.toks = toks
        self.i = 0
        self.last_line = 1

    def peek(self, o=0):
        j = min(self.i + o, len(self.toks) - 1)
        return self.toks[j]

    def next(self):
        t = self.toks[self.i]
        if t.kind != "EOF":
            self.i += 1
            self.last_line = t.line
        return t

    def expect(self, kind, what):
        t = self.peek()
        if t.kind != kind:
            raise LolsError(f"line {t.line}: expected {what}, found {t.text or 'EOF'!r}")
        return self.next()

    def expect_ident(self, what):
        t = self.peek()
        if t.kind != "IDENT":
            raise LolsError(f"line {t.line}: expected {what}, found {t.text!r}")
        return self.next().text

    def same_line(self):
        return self.peek().line == self.last_line

    def skip_semi(self):
        while self.peek().kind == ";":
            self.next()

    # ---- program ----

    def parse_program(self):
        kw = self.expect_ident("start of program")
        if kw != "app":
            raise LolsError("line 1: program must start with: app \"Name\" { ... }")
        self.expect("STR", "app name string")
        self.expect("{", "'{' after app name")
        self.parse_app_body()
        self.expect("EOF", "end of file (check for extra '}')")

    def parse_app_body(self):
        while self.peek().kind != "}" and self.peek().kind != "EOF":
            t = self.peek()
            if t.kind != "IDENT":
                raise LolsError(f"line {t.line}: unexpected {t.text!r} in app block")
            if t.text == "state":
                self.next()
                self.expect("{", "'{' after state")
                while self.peek().kind not in ("}", "EOF"):
                    self.parse_assign()
                self.next()
            elif t.text == "on":
                self.next()
                ev = self.expect_ident("event name")
                if ev == "start":
                    self.parse_block(ui=False)
                elif ev == "tick":
                    self.expect("(", "'(' after tick")
                    self.expect("NUM", "tick interval")
                    self.expect(")", "')' after tick interval")
                    self.parse_block(ui=False)
                else:
                    raise LolsError(f"line {t.line}: unknown event {ev!r}")
            elif t.text == "ui":
                self.next()
                self.parse_ui_block()
            elif t.text == "func":
                self.parse_func()
            elif t.text in METAS:
                self.next()
                if self.peek().kind not in ("STR", "IDENT", "NUM"):
                    raise LolsError(f"line {t.line}: expected value after {t.text}")
                self.next()
            else:
                raise LolsError(f"line {t.line}: unknown app item {t.text!r}")
        self.expect("}", "'}' to close app block")

    def parse_func(self):
        self.next()  # func
        self.expect_ident("function name")
        self.expect("(", "'(' after name")
        if self.peek().kind != ")":
            self.expect_ident("parameter")
            while self.peek().kind == ",":
                self.next()
                self.expect_ident("parameter")
        self.expect(")", "')'")
        self.parse_block(ui=False)

    def parse_block(self, ui):
        self.expect("{", "'{'")
        while self.peek().kind not in ("}", "EOF"):
            if ui:
                self.parse_ui_stmt()
            else:
                self.parse_stmt(ui=False)
        self.expect("}", "'}'")

    def parse_ui_block(self):
        self.expect("{", "'{' after ui")
        while self.peek().kind not in ("}", "EOF"):
            self.parse_ui_stmt()
        self.expect("}", "'}' to close ui block")

    def parse_ui_stmt(self):
        t = self.peek()
        if t.kind == "IDENT" and self.peek(1).kind == "{" and t.text not in FLOW_KEYWORDS:
            self.parse_ui_node()
        else:
            self.parse_stmt(ui=True)

    def parse_ui_node(self):
        t = self.peek()
        if t.kind != "IDENT":
            raise LolsError(f"line {t.line}: expected UI node name")
        self.next()
        self.expect("{", "'{' after UI node")
        while self.peek().kind not in ("}", "EOF"):
            p = self.peek()
            if p.kind == "IDENT" and self.peek(1).kind == "=":
                self.next()
                self.next()
                self.parse_expr()
                self.skip_semi()
            elif p.kind == "IDENT" and self.peek(1).kind == "{" and p.text not in FLOW_KEYWORDS:
                self.parse_ui_node()
            else:
                self.parse_stmt(ui=True)
        self.expect("}", f"'}}' to close UI node {t.text!r}")

    def parse_stmt(self, ui):
        t = self.peek()
        if t.kind == "IDENT":
            if t.text == "if":
                self.parse_if(ui)
                return
            if t.text == "while":
                self.next()
                self.parse_expr()
                self.parse_block(ui)
                return
            if t.text == "for":
                self.next()
                self.expect_ident("loop variable")
                kw = self.expect_ident("'in'")
                if kw != "in":
                    raise LolsError(f"line {t.line}: expected 'in'")
                self.parse_expr()
                self.parse_block(ui)
                return
            if t.text == "return":
                self.next()
                if self.peek().kind not in (";", "}") and self.same_line():
                    self.parse_expr()
                self.skip_semi()
                return
            if t.text == "break":
                self.next()
                self.skip_semi()
                return
            if t.text == "continue":
                self.next()
                self.skip_semi()
                return
        # assign or expr
        start = self.i
        start_line = self.last_line
        self.parse_expr()
        if self.peek().kind == "=" or \
           (self.peek().kind in ("+", "-") and self.peek(1).kind == "=" and self.same_line()):
            self.i = start
            self.last_line = start_line
            self.parse_postfix()
            if self.peek().kind in ("+", "-"):
                self.next()
            self.expect("=", "'='")
            self.parse_expr()
            self.skip_semi()
        else:
            self.skip_semi()

    def parse_assign(self):
        self.parse_postfix()
        if self.peek().kind in ("+", "-"):
            self.next()
        self.expect("=", "'=' in assignment")
        self.parse_expr()
        self.skip_semi()

    def parse_if(self, ui):
        self.next()  # if
        self.parse_expr()
        self.parse_block(ui)
        while self.peek().kind == "IDENT" and self.peek().text == "else" and self.peek().line == self.last_line:
            self.next()
            if self.peek().kind == "IDENT" and self.peek().text == "if":
                self.next()
                self.parse_expr()
                self.parse_block(ui)
            else:
                self.parse_block(ui)
                break

    # ---- expressions ----

    def parse_expr(self):
        self.parse_or()

    def parse_or(self):
        self.parse_and()
        while self.peek().kind == "||" and self.same_line():
            self.next()
            self.parse_and()

    def parse_and(self):
        self.parse_eq()
        while self.peek().kind == "&&" and self.same_line():
            self.next()
            self.parse_eq()

    def parse_eq(self):
        self.parse_cmp()
        while self.same_line() and self.peek().kind in ("==", "!="):
            self.next()
            self.parse_cmp()

    def parse_cmp(self):
        self.parse_add()
        while self.same_line() and self.peek().kind in ("<", "<=", ">", ">="):
            self.next()
            self.parse_add()

    def parse_add(self):
        self.parse_mul()
        while self.same_line() and self.peek().kind in ("+", "-"):
            if self.peek(1).kind == "=":
                break
            self.next()
            self.parse_mul()

    def parse_mul(self):
        self.parse_unary()
        while self.same_line() and self.peek().kind in ("*", "/", "%"):
            self.next()
            self.parse_unary()

    def parse_unary(self):
        if self.peek().kind in ("-", "!"):
            self.next()
            self.parse_unary()
        else:
            self.parse_postfix()

    def parse_postfix(self):
        self.parse_primary()
        while True:
            t = self.peek()
            if t.kind == "." and self.same_line():
                self.next()
                self.expect_ident("member name")
            elif t.kind == "[" and self.same_line():
                self.next()
                self.parse_expr()
                self.expect("]", "']'")
            elif t.kind == "(" and self.same_line():
                self.next()
                if self.peek().kind != ")":
                    self.parse_expr()
                    while self.peek().kind == ",":
                        self.next()
                        self.parse_expr()
                self.expect(")", "')'")
            else:
                break

    def parse_primary(self):
        t = self.peek()
        if t.kind == "NUM":
            self.next()
        elif t.kind == "STR":
            self.next()
        elif t.kind == "(":
            self.next()
            self.parse_expr()
            self.expect(")", "')'")
        elif t.kind == "[":
            self.parse_list_or_map()
        elif t.kind == "{":
            self.parse_closure()
        elif t.kind == "IDENT":
            self.next()
            if t.text in FLOW_KEYWORDS - {"true", "false", "null"}:
                raise LolsError(f"line {t.line}: keyword {t.text!r} cannot be a value")
        else:
            raise LolsError(f"line {t.line}: unexpected {t.text or 'EOF'!r} in expression")

    def parse_list_or_map(self):
        self.expect("[", "'['")
        if self.peek().kind == "]":
            self.next()
            return
        if self.peek().kind in ("IDENT", "STR") and self.peek(1).kind == ":":
            while True:
                if self.peek().kind not in ("IDENT", "STR"):
                    raise LolsError(f"line {self.peek().line}: map key must be name or string")
                self.next()
                self.expect(":", "':' after map key")
                self.parse_expr()
                if self.peek().kind == ",":
                    self.next()
                    continue
                break
        else:
            while True:
                self.parse_expr()
                if self.peek().kind == ",":
                    self.next()
                    continue
                break
        self.expect("]", "']'")

    def parse_closure(self):
        self.expect("{", "'{'")
        if self.peek().kind == "IDENT" and self.peek(1).kind == "|":
            self.next()
            self.next()
        while self.peek().kind not in ("}", "EOF"):
            self.parse_ui_stmt()
        self.expect("}", "'}' to close closure")


def check_lols_files():
    files = sorted((REPO_ROOT / "lolsrc").rglob("*.lols"))
    errors = []
    for f in files:
        try:
            Parser(lex(f.read_text(encoding="utf-8"))).parse_program()
            print(f"  OK   {f.relative_to(REPO_ROOT)}")
        except LolsError as e:
            errors.append(f"{f.relative_to(REPO_ROOT)}: {e}")
            print(f"  FAIL {f.relative_to(REPO_ROOT)}: {e}")
    return errors


def check_xml():
    errors = []
    for f in sorted(REPO_ROOT.rglob("*.xml")):
        if "build" in f.parts:
            continue
        try:
            xml.dom.minidom.parse(str(f))
            print(f"  OK   {f.relative_to(REPO_ROOT)}")
        except Exception as e:
            errors.append(f"{f.relative_to(REPO_ROOT)}: {e}")
            print(f"  FAIL {f.relative_to(REPO_ROOT)}: {e}")
    return errors


def check_kotlin_braces():
    warnings = []
    for f in sorted((REPO_ROOT / "app").rglob("*.kt")):
        text = f.read_text(encoding="utf-8")
        # strip char literals first, then strings, then comments (rough heuristic)
        text = re.sub(r"'(\\.|[^'\\])'", "X", text)
        text = re.sub(r'"(\\.|[^"\\])*"', '""', text)
        text = re.sub(r"//[^\n]*", "", text)
        text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
        if text.count("{") != text.count("}"):
            warnings.append(f"{f.relative_to(REPO_ROOT)}: {{ = {text.count('{')} }} = {text.count('}')}")
            print(f"  WARN {f.relative_to(REPO_ROOT)}: unbalanced braces")
    return warnings


def check_packages():
    errors = []
    pkg_dir = REPO_ROOT / "app" / "src" / "main" / "assets" / "repo"
    if not pkg_dir.exists():
        print("  (no packed packages yet - run tools/build_lols.py first)")
        return errors
    for f in sorted(pkg_dir.glob("*.lols")):
        try:
            with zipfile.ZipFile(f) as z:
                names = z.namelist()
                if "manifest.lols" not in names or "main.lols" not in names:
                    raise LolsError(f"missing entries: {names}")
                for entry in ("manifest.lols", "main.lols"):
                    Parser(lex(z.read(entry).decode("utf-8"))).parse_program()
            print(f"  OK   {f.relative_to(REPO_ROOT)}")
        except Exception as e:
            errors.append(f"{f.relative_to(REPO_ROOT)}: {e}")
            print(f"  FAIL {f.relative_to(REPO_ROOT)}: {e}")
    return errors


def main():
    print("== LOLS source files ==")
    errs = check_lols_files()
    print("\n== XML resources ==")
    errs += check_xml()
    print("\n== Kotlin brace balance (heuristic) ==")
    check_kotlin_braces()
    print("\n== packed .lols packages ==")
    errs += check_packages()
    if errs:
        print(f"\nFAILED with {len(errs)} error(s)")
        return 1
    print("\nALL CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
