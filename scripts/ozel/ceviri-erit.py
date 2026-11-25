#!/usr/bin/env python3
"""[37124] Ceviri tabanini **eritme** araci — batch basina 40 anahtar.

## Neden ayri arac, neden `--fix-baseline` degil

`ceviri-denetim.py --fix-baseline` tabani **bugunku gercege** esitler: yani
eksigi gorunmez yapar. Bu arac tersini yapar — eksigi **gercekten cevirir** ve
tabani o kadar daraltir. Ikisi ayni dosyaya yazar ama biri borcu saklar,
digeri oder.

## Neden batch ve neden sinirli

521 devralinmis anahtar var. Hepsini tek turda cevirmek (a) turu bitirmez,
(b) yanlis ceviriyi 500 anahtara yayar. Batch = 40: her turda olculebilir,
geri alinabilir bir adim.

## Cevrilmeyecek anahtarlar

Gercek ceviri gerektirmeyenler (yalniz sembol/sayi, ya da Turkce'de ayni yazilan
teknik kisaltmalar) tabandan **dusulur** ama dosyaya yazilmaz: ayni metni iki
dile kopyalamak "ceviri var" gostergesini yalan soyler.

Kullanim:
    python3 scripts/ozel/ceviri-erit.py --liste          # adaylari goster
    python3 scripts/ozel/ceviri-erit.py --uygula <dosya> # JSON eşlemesini uygula
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "src/android/app/src/main/res"
EN = RES / "values/strings.xml"
TR = RES / "values-tr/strings.xml"
BASELINE = ROOT / ".ozel/ceviri/taban.json"

STR_RE = re.compile(r'<string\s+name="([^"]+)"\s*>(.*?)</string>', re.S)

BATCH = 40


def read(path: Path) -> dict[str, str]:
    return {m.group(1): m.group(2) for m in STR_RE.finditer(path.read_text(encoding="utf-8"))}


def translatable(value: str) -> bool:
    stripped = re.sub(r"%\d+\$?[sd]|%s|%d", "", value).strip()
    return bool(re.search(r"[A-Za-z]{3,}", stripped))


def candidates() -> list[str]:
    en, tr = read(EN), read(TR)
    missing = set(en) - set(tr)
    items = [k for k in missing if translatable(en[k])]
    return sorted(items, key=lambda k: len(en[k]))


def apply(mapping: dict[str, str]) -> int:
    text = TR.read_text(encoding="utf-8")
    added = 0
    for key, value in mapping.items():
        if re.search(r'<string\s+name="%s"' % re.escape(key), text):
            continue
        escaped = (
            value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', '\\"').replace("'", "\\'")
        )
        text = text.replace(
            "</resources>",
            '    <string name="%s">%s</string>\n</resources>' % (key, escaped),
        )
        added += 1
    TR.write_text(text, encoding="utf-8")

    # Taban: yalnizca gercekten cozulen anahtarlar daralir.
    data = json.loads(BASELINE.read_text(encoding="utf-8"))
    keys = set(data.get("keys", []))
    before = len(keys)
    keys -= set(mapping.keys())
    data["keys"] = sorted(keys)
    BASELINE.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"eklenen: {added} · taban: {before} -> {len(keys)}")
    return added


def main() -> int:
    if "--liste" in sys.argv:
        cand = candidates()
        en = read(EN)
        print(f"cevrilebilir eksik: {len(cand)}")
        for k in cand[:BATCH]:
            print(f"  {k} :: {en[k]}")
        return 0
    if "--uygula" in sys.argv:
        path = Path(sys.argv[sys.argv.index("--uygula") + 1])
        mapping = json.loads(path.read_text(encoding="utf-8"))
        apply(mapping)
        return 0
    print(__doc__)
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
