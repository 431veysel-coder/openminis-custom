#!/usr/bin/env python3
"""
[37099] APK boyut butcesi — "gomulu yuk yok" iddiasini olcen arac.

NEDEN VAR
    `TermuxLayer.MAX_EMBEDDED_ASSET_KB` ve `APK_BUDGET_KB` tanimliydi ve
    yorumlari "TermuxLayerTest ve CI'daki boyut kapisi bunu okuyor" diyordu.
    Olcum: **ikisini de hicbir sey okumuyordu.** Yani "bootstrap gomulmuyor"
    iddiasi bir sabitten ibaretti, kapidan degil.

    Bu betik iddiayi olcuye ceviriyor: `assets/` altinda isletim sistemi
    yuku (bootstrap, rootfs, arsiv) aranir; bulunursa kirmizi. Normal buyuk
    dosyalar (sozluk, model listesi) bilgi olarak raporlanir, engellenmez —
    cunku kural "buyuk dosya yok" degil, **"gomulu isletim sistemi yok"**.

KULLANIM
    python3 scripts/ozel/apk-butce.py
    python3 scripts/ozel/apk-butce.py --fail-on-embed
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "src/android/app/src/main/assets"
LAYER_KT = ROOT / "src/android/app/src/main/java/com/openminis/app/sandbox/TermuxLayer.kt"

# Isletim sistemi yuku sayilan adlar. Bir gun biri "hazir olsun" diye
# bootstrap'i assets'e koyarsa burada yakalanir.
EMBED_NAME = re.compile(
    r"(bootstrap|rootfs|alpine|termux|busybox-static|initramfs)", re.IGNORECASE
)
EMBED_EXT = {".tar", ".gz", ".tgz", ".xz", ".zst", ".zip", ".img", ".iso", ".apk"}


def const(name: str):
    """TermuxLayer'daki `const val NAME: Int = ...` degerini okur."""
    if not LAYER_KT.exists():
        return None
    text = LAYER_KT.read_text(encoding="utf-8")
    m = re.search(r"const val " + name + r"\s*:\s*Int\s*=\s*([0-9_*\s]+)", text)
    if not m:
        return None
    expr = m.group(1).replace("_", "").strip()
    try:
        return int(eval(expr, {"__builtins__": {}}, {}))
    except Exception:
        return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fail-on-embed", action="store_true")
    args = ap.parse_args()

    max_kb = const("MAX_EMBEDDED_ASSET_KB")
    budget_kb = const("APK_BUDGET_KB")
    print("=== APK BUTCE ===")
    print("MAX_EMBEDDED_ASSET_KB : " + (str(max_kb) + " KB" if max_kb else "okunamadi"))
    print("APK_BUDGET_KB         : " + (str(budget_kb) + " KB" if budget_kb else "okunamadi"))

    if not ASSETS.is_dir():
        print("")
        print("assets/ yok: " + str(ASSETS))
        print("SONUC: ATLANDI")
        return 0

    files = [p for p in ASSETS.rglob("*") if p.is_file()]
    total = sum(p.stat().st_size for p in files)
    print("")
    print("assets/: " + str(len(files)) + " dosya, " + format(total / 1048576, ".1f") + " MB")

    embedded = [
        p for p in files
        if EMBED_NAME.search(p.name) or p.suffix.lower() in EMBED_EXT
    ]
    limit = (max_kb or 4096) * 1024
    big = sorted((p for p in files if p.stat().st_size > limit), key=lambda p: -p.stat().st_size)

    if big:
        print("")
        print("BUYUK DOSYALAR (yuk degilse sorun degil):")
        for p in big[:10]:
            print("  - " + str(p.relative_to(ASSETS)) + " - " + format(p.stat().st_size / 1048576, ".2f") + " MB")

    if embedded:
        print("")
        print("GOMULU ISLETIM SISTEMI YUKU (olmamali):")
        for p in embedded[:20]:
            print("  ! " + str(p.relative_to(ASSETS)) + " - " + format(p.stat().st_size / 1048576, ".2f") + " MB")

    bad = bool(embedded)
    if bad:
        verdict = "KIRMIZI" if args.fail_on_embed else "UYARI"
    else:
        verdict = "YESIL"
    print("")
    print("SONUC: " + verdict)
    return 1 if (bad and args.fail_on_embed) else 0


if __name__ == "__main__":
    raise SystemExit(main())
