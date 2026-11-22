#!/usr/bin/env python3
"""
[37099] Ceviri kapisi — "her metin iki dilde" kuralini olcen arac.

NEDEN VAR
    `.17`'de 194 yeni anahtar eklendi ve 71'inin Turkce karsiligi yoktu
    (konsey ekrani, gorev tahtasi, ekip modlari, saglik gostergesi, Termux
    satiri). Uc kapi da yesil dedi: derleme eksik ceviriyi gormez (Android
    Ingilizceye duser), `kotlin-denetim.py` kaynak dizeleriyle ilgilenmez,
    `risk-corpus.py` yalniz komut siniflandirir. Yani ihlal SESSIZDI.

    Bu betik onu gurultulu yapar ve iki sinifi birden yakalar:

      1. `values/` icinde olup `values-tr/` icinde olmayan ANAHTAR (taban
         dosyasinda yoksa) -> kirmizi. Taban, upstream'den devralinan ve
         bilerek cevrilmemis 521 anahtari tasir (fork kurali #4: eksik ceviri
         Ingilizceye duser). Taban bir "ratchet": yeni eksik eklenemez,
         ceviri yapildikca taban kucultulur.
      2. Ayni dosyada AYNI ADLA iki kez tanimlanan string -> kirmizi. Android
         sonuncuyu sessizce kazanir; ilki olu kalir ve `rg` ile "bu metin
         nerede" sorusu yanlis yeri gosterir.

KULLANIM
    python3 scripts/ozel/ceviri-denetim.py               # rapor
    python3 scripts/ozel/ceviri-denetim.py --fail-on-new # yeni eksikte kirmizi
    python3 scripts/ozel/ceviri-denetim.py --fix-baseline # tabani guncelle
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "src/android/app/src/main/res"
EN = RES / "values/strings.xml"
TR = RES / "values-tr/strings.xml"
BASELINE = ROOT / ".ozel/ceviri/taban.json"

STRING_RE = re.compile(r'<string\s+name="([^"]+)"')


def read(path: Path) -> tuple[dict[str, int], str]:
    """Anahtar -> kac kez tanimlandigi + ham metin."""
    text = path.read_text(encoding="utf-8")
    counts: dict[str, int] = {}
    for name in STRING_RE.findall(text):
        counts[name] = counts.get(name, 0) + 1
    return counts, text


def load_baseline() -> set[str]:
    if not BASELINE.exists():
        return set()
    data = json.loads(BASELINE.read_text(encoding="utf-8"))
    return set(data.get("keys", []))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fail-on-new", action="store_true")
    ap.add_argument("--fix-baseline", action="store_true")
    args = ap.parse_args()

    if not EN.exists() or not TR.exists():
        print(f"HATA: kaynak dosya yok: {EN} / {TR}", file=sys.stderr)
        return 2

    en, _ = read(EN)
    tr, _ = read(TR)
    baseline = load_baseline()

    missing = sorted(set(en) - set(tr))
    live = [k for k in missing if k not in baseline]
    duplicated = sorted(k for k, c in list(en.items()) + list(tr.items()) if c > 1)

    # Ayni anahtar iki dosyada: birlesik sozlukte sayim 2'den buyuk olur.
    combined: dict[str, int] = {}
    for k, c in en.items():
        combined[k] = combined.get(k, 0) + c
    for k, c in tr.items():
        combined[k] = combined.get(k, 0) + c
    dup_any = sorted(k for k, c in combined.items() if c > 2)

    print("=== CEVIRI (de/en) ===")
    print(f"values/      : {len(en)} anahtar")
    print(f"values-tr/   : {len(tr)} anahtar")
    print(f"taban        : {len(baseline)} anahtar (upstream devralimli)")
    print(f"eksik        : {len(missing)} ({len(live)} tanesi YENI)")

    if live:
        print("\nYENI EKSIK (taban dosyasinda yok):")
        for k in live[:60]:
            print(f"  - {k}")
        if len(live) > 60:
            print(f"  ... +{len(live) - 60}")

    if dup_any:
        print("\nAYNI DOSYADA COK KEZ TANIMLI (sonuncusu kazanir):")
        for k in dup_any:
            print(f"  ! {k}")

    if args.fix_baseline:
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text(
            json.dumps(
                {"not": "upstream devralimli, bilerek cevrilmemis", "keys": missing},
                ensure_ascii=False, indent=1,
            ) + "\n",
            encoding="utf-8",
        )
        print(f"\ntaban guncellendi: {len(missing)} anahtar")

    bad = bool(dup_any) or (args.fail_on_new and bool(live))
    print("\nSONUC:", "KIRMIZI" if bad else "YESIL")
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
