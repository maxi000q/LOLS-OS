#!/usr/bin/env python3
"""
build_lols.py - packs lolsrc/<app>/{manifest.lols, main.lols} into real
.lols ZIP packages under app/src/main/assets/repo/.

A .lols package is a ZIP container:
  manifest.lols  (app id, name, version, icon, color, permissions)
  main.lols      (the program executed by the LOLS runtime)

Run from the repo root:  python3 tools/build_lols.py
"""
import re
import sys
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SRC_DIR = REPO_ROOT / "lolsrc"
OUT_DIR = REPO_ROOT / "app" / "src" / "main" / "assets" / "repo"


def package_id(manifest_text: str) -> str:
    m = re.search(r'\bid\s+"([^"]+)"', manifest_text)
    if not m:
        raise SystemExit("manifest missing id")
    return m.group(1)


def build() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    count = 0
    for app_dir in sorted(SRC_DIR.iterdir()):
        if not app_dir.is_dir():
            continue
        manifest = app_dir / "manifest.lols"
        main = app_dir / "main.lols"
        if not manifest.exists() or not main.exists():
            print(f"  skip {app_dir.name}: missing manifest.lols or main.lols")
            continue
        pid = package_id(manifest.read_text(encoding="utf-8"))
        out = OUT_DIR / f"{pid}.lols"
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            # deterministic timestamps for reproducible builds
            for name in ("manifest.lols", "main.lols"):
                src = app_dir / name
                info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o644 << 16
                z.writestr(info, src.read_text(encoding="utf-8"))
        print(f"  packed {out.name} ({out.stat().st_size} bytes)")
        count += 1
    print(f"done: {count} package(s) -> {OUT_DIR}")


if __name__ == "__main__":
    sys.exit(build())
