<div align="center">

# LOLS OS

**A real operating system environment inside an Android app — with zero permissions.**

`v1.0.0` · Kotlin + Jetpack Compose · MIT License

![CI](https://img.shields.io/badge/build-GitHub_Actions-2088FF) ![license](https://img.shields.io/badge/license-MIT-6EE7D8) ![permissions](https://img.shields.io/badge/android%20permissions-0-4ADE80)

</div>

---

## What is LOLS OS?

LOLS OS is a **real, original, MIT-licensed OS environment** that runs entirely inside one Android app — the same philosophy as Termux: nothing is faked.

- Its apps are real packages (`.lols` ZIP files) executed by a **real interpreter** written from scratch.
- Its filesystem (**LolsFS**) is real: persistent, quota-limited, browsable.
- Its processes are real: PIDs, coroutine scopes, a watchdog that kills runaway apps, and an **OOM killer** that terminates the largest process when the memory pool is exceeded.
- Its settings control real behavior: the RAM slider changes the actual allocation pool; the scheduler quantum changes real preemption; graphics tiers change real rendering.
- Its device panel shows **real host telemetry** (battery, RAM, CPU) read through public APIs — with **zero Android permissions**.

> LOLS OS is an independent original project. It is not affiliated with, endorsed by, or derived from any other operating system. All design, code, icons, and the LOLS language are original works.

## What "real" means here

| | Fake "OS" apps | LOLS OS |
|---|---|---|
| Apps | Screens drawn in the app | `.lols` packages parsed & executed by a real interpreter at runtime |
| Memory | A picture of a RAM bar | Byte-accounted heap per process + a real OOM killer |
| Processes | A task-switcher animation | PIDs, states, heap/steps stats, real kills (coroutine cancellation) |
| Filesystem | — | Persistent VFS (64MB quota) with real read/write/list/delete |
| Security | — | Salted SHA-256 PIN, per-app FS sandboxing, permissions |
| Terminal | — | 15 real commands against the live kernel (`ps`, `kill`, `free`, `cat`…) |

## Features

- **Boot sequence** — staged init with a real kernel console (real timings, real file counts)
- **Setup wizard & lock screen** — language choice + salted-hash PIN
- **Home screen** — app grid with live notification badges, dock, long-press actions
- **Notification shade + control center** — pull-down from the status bar; real brightness, DND, language flip (instant RTL), graphics tier
- **Recents** — the real process table: live PID/heap/uptime, switch or kill
- **Store** — install / uninstall / reinstall real `.lols` packages
- **Settings** — hardware control (RAM pool 4–64MB, watchdog, scheduler quantum), storage, real device info, security, restart OS
- **Terminal** — a real shell into the kernel
- **5 bundled `.lols` apps** — Calculator, Notes (persistent), Clock (world + stopwatch), Paint (canvas + save), Files (browses the actual VFS)
- **Bilingual** — English / العربية with instant switching and full RTL

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                        MainActivity                       │
│                    (single Activity)                     │
│  ┌────────────────────────────────────────────────────┐  │
│  │                    OsRoot (shell)                   │  │
│  │  Boot → Setup/Lock → Desktop                        │  │
│  │  StatusBar · Shade · ControlCenter · Recents        │  │
│  │  System apps: Store · Settings · Terminal           │  │
│  │  ┌──────────────────────────────────────────────┐   │  │
│  │ │        UiRenderer (Compose) ← RNode trees     │   │  │
│  │ └──────────────────────▲────────────────────────┘   │  │
│  │                        │ events (fire)               │  │
│  │  ┌────────────────────┴────────────────────────┐   │  │
│  │ │  LOLS Runtime · Interpreter · Builtins       │   │  │
│  │ │  (lexer → parser → tree-walking execution)   │   │  │
│  │ └──────────────────────▲────────────────────────┘   │  │
│  └───────────────────────│──────────────────────────────┘  │
│                          │ syscalls                          │
│  ┌───────────────────────┴──────────────────────────────┐  │
│  │                    Kernel                            │  │
│  │  ProcessManager (PIDs, watchdog, kills)              │  │
│  │  MemoryManager (heap accounting, OOM killer)         │  │
│  │  LolsFS (persistent VFS, 64MB quota)                 │  │
│  │  PackageManager (.lols ZIPs, registry)                │  │
│  │  MessageBus (notifications) · PowerBridge (host)      │  │
│  └──────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

## The LOLS language

A `.lols` package is a ZIP containing `manifest.lols` + `main.lols`.

```lols
app "Calculator" {
  id "com.lols.calculator"
  version "1.0.0"
  icon "±"
  color "#6EE7D8"

  state { count = 0 }                 // reactive globals

  on start { log("hello") }           // real boot hook
  on tick(1000) { count = count + 1 } // real timer event

  func double(x) { return x * 2 }

  ui {
    Screen {
      Column {
        padding = 16
        spacing = 10
        Text { value = "Count: " + count; style = "title" }
        Button { label = "+1"; onClick = { count = count + 1 }; style = "primary" }
      }
    }
  }
}
```

**Rules**: one statement per line (binary operators must stay on the same line) · calls always use `()` · maps are `[key: value]`, lists `[a, b]` · closures are `{ stmts }` or `{ param | stmts }` (locals captured by value, globals live).

**Builtins**: `str num len push pop keys floor round abs min max sqrt random now timeParts dateParts split join trim upper lower replace find sub sysinfo notify dataDir lang t log` and the sandboxed `fs.` namespace: `fs.read fs.write fs.append fs.list fs.delete fs.mkdir fs.exists`.

**UI nodes**: `Screen Column Row Grid Scroll Card Text Button Field Canvas Divider Spacer` — `if`/`while`/`for` flow control is allowed inside the `ui` block. `Canvas` delivers normalized touch events (`0..1000` virtual space) through `onPoint = { p | … }` with `p["x"]`, `p["y"]`, `p["phase"]`.

**Permissions** (manifest): `fs.full` = full filesystem access (used by Files). Default sandbox: own `/data/<id>/` + shared `/home`.

The full source of all five apps lives in [`lolsrc/`](lolsrc) — they double as the SDK examples. Repack with `python3 tools/build_lols.py`, validate with `python3 tools/validate.py`.

## Hardware control (Settings → Hardware)

| Control | Real effect |
|---|---|
| RAM pool (4–64MB) | The kernel's allocation pool. Exceed it and the **OOM killer terminates the largest process** — try 4MB and open many apps |
| Watchdog (1–10s) | Max wall-clock time per event handler; runaway apps are killed |
| Scheduler quantum (128–2048 steps) | How often interpreter execution yields to the UI thread |
| Graphics (High/Balanced/Lite) | Real blur, wallpaper animation, glass effects |
| Device panel | Real battery/RAM/CPU/model via public APIs, zero permissions |

## Build & install

### Option A — GitHub Releases (easiest)
1. Open the repo's **Releases** page → **v1.0.0**.
2. Download **LOLS-OS-v1.0.0-release.apk** (or the debug build).
3. Install it on your phone (allow "unknown sources" when asked).

CI builds both APKs on every push and attaches them to the release automatically.

### Option B — GitHub Actions artifacts
Open the **Actions** tab → **Build LOLS OS APK** → download the **LOLS-OS-release-APK** artifact from the latest run.

### Option C — Android Studio / CLI
Open the project, let Gradle sync, press **Run**. Or from the CLI:

```bash
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease # app/build/outputs/apk/release/app-release.apk
```

Requires JDK 17. The Gradle wrapper (8.7) is included — no local Gradle install needed.

## Pushing to GitHub (do this securely)

```bash
git init
git add .
git commit -m "LOLS OS v1.0.0"
git branch -M main
git remote add origin https://github.com/<your-user>/LOLS-OS.git
git push -u origin main
```

When git asks for a password, **paste a personal access token** (Settings → Developer settings → Tokens). **Never put the token in commands, URLs, or chats** — if a token is ever exposed, revoke it immediately at github.com/settings/tokens.

## Project layout

```
├── app/src/main/java/os/lols/
│   ├── kernel/     Kernel, BootSequence, LolsFS, MemoryManager,
│   │               ProcessManager, PackageManager, MessageBus, PowerBridge
│   ├── lols/       Lexer, Parser, Ast, Interpreter, Builtins, Runtime, UiRenderer
│   ├── shell/      OsRoot, BootScreen, SetupWizard, LockScreen, StatusBar,
│   │               NotificationShade, ControlCenter, HomeScreen, Recents
│   ├── sysapps/    StoreApp, SettingsApp, TerminalApp
│   └── ui/         Theme, Glass components, Wallpaper
├── lolsrc/         .lols app sources (SDK examples)
├── tools/          build_lols.py (packer), validate.py (offline checks)
└── .github/        CI workflow that builds the APK
```

## Roadmap

- [ ] Sideload external `.lols` files (file picker)
- [ ] LOLS SDK docs site + more builtins (`net.*` behind explicit permission)
- [ ] App-to-app IPC channels
- [ ] Landscape & tablet layout
- [ ] Proper release signing (current release APK is debug-signed for easy CI installs)

## License

MIT — see [LICENSE](LICENSE). You are free to use, modify, and distribute LOLS OS, including commercially. Attribution is appreciated.

---

<div dir="rtl">

## ما هو LOLS OS؟ (عربي)

**LOLS OS** بيئة نظام تشغيل **حقيقية وأصلية** برخصة MIT تعمل بالكامل داخل تطبيق أندرويد واحد — بنفس فلسفة Termux: لا يوجد أي شيء مزيف.

- تطبيقاته حزم حقيقية (ملفات `.lols` بصيغة ZIP) ينفذها **مفسّر حقيقي** كُتب من الصفر.
- نظام ملفاته (LolsFS) حقيقي: دائم، بحصة تخزين، قابل للتصفح.
- عملياته حقيقية: أرقام PID، حارس نظام يُنهي التطبيقات الجامحة، و**قاتل OOM** يُنهي أكبر عملية عند تجاوز الذاكرة.
- إعدادات الهاردوير تتحكم في سلوك حقيقي: شريط الرام يغيّر تجمع الذاكرة الفعلي، والمجدول يغيّر الاستباق الحقيقي.
- لوحة الجهاز تعرض قياسات **حقيقية** (البطارية، الرام، المعالج) بدون أي أذونات أندرويد.

> LOLS OS مشروع أصلي مستقل، غير مرتبط بأي نظام تشغيل آخر. كل التصميم والكود والأيقونات ولغة LOLS أعمال أصلية.

### البناء
افتح صفحة **Releases** في المستودع وحمّل ملف **LOLS-OS-v1.0.0-release.apk** مباشرة، أو من تبويب **Actions**، أو افتح المشروع في Android Studio واضغط Run.

</div>
