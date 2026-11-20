#!/usr/bin/env python3
"""Yükleme öncesi log temizliği — sır sızıntısını dosyada kes.

## Neden

37094 logları kalıcı yaptı: her `run:` bloğu `build/logs/*.log` dosyasına
yazıyor ve o dosyalar `if: always()` ile artifact olarak yükleniyor.

GitHub'ın sır maskelemesi **log akışında** çalışır: bir sır ekrana basılırsa
`***` olur. Ama `tee` ile **diske** yazılan kopya maskelenmez; artifact
indirildiğinde ham değer oradadır. Yani "her şeyi logla" kararı, kendi başına
yeni bir sızıntı yüzeyi açar. Bu betik o yüzeyi kapatır: yüklemeden hemen önce
dosyaları tarar, bulduğunu siler.

İki katman:

1. **Literal sırlar.** Ortamda duran gizli değerler (imza parolası, alias,
   keystore base64) birebir aranır. Değer buraya parametre olarak gelir, dosyaya
   yazılmaz.
2. **Biçim desenleri.** Değeri bilmediğimiz ama tanıdığımız sırlar: GitHub
   token'ları, PEM blokları, uzun base64 yığınları.

## Neden yalnız "uzun base64" değil de ayrımlı

sha256 özetleri (64 karakter hex) loglarda **kalmalı**: APK doğrulaması onlara
bakıyor. Bu yüzden desen en az bir büyük harf ya da `+`/`/` istiyor — hex özet
buna uymaz, base64 anahtar uyar.

## Kullanım

    python3 scripts/scrub_logs.py build/logs
    python3 scripts/scrub_logs.py build/logs --env ANDROID_KEYSTORE_PASSWORD

Çıkış: 0 temiz, 1 sır bulundu ve temizlendi (adım kırmızı olmaz, uyarı basar).
"""
from __future__ import annotations

import argparse
import os
import pathlib
import re
import sys

#: Değeri bilinmeyen ama biçimi tanınan sırlar.
PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("github-token", re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}")),
    ("github-pat", re.compile(r"github_pat_[A-Za-z0-9_]{20,}")),
    ("pem", re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----")),
    ("aws", re.compile(r"AKIA[0-9A-Z]{16}")),
    # En az bir büyük harf veya +/ istiyor: sha256 hex özeti buraya düşmesin.
    ("base64-blob", re.compile(r"(?=[A-Za-z0-9+/]*[A-Z+/])[A-Za-z0-9+/]{120,}={0,2}")),
]

#: Adsız da olsa asla logda görünmemesi gereken ortam değişkenleri.
SECRET_ENV = (
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_PASSWORD",
    "ANDROID_KEYSTORE_BASE64",
    "GITHUB_TOKEN",
    "GH_TOKEN",
)


def scrub_text(text: str, literals: list[str]) -> tuple[str, dict[str, int]]:
    hits: dict[str, int] = {}
    for name, pat in PATTERNS:
        text, n = pat.subn(f"***REDACTED:{name}***", text)
        if n:
            hits[name] = hits.get(name, 0) + n
    for i, lit in enumerate(literals):
        if not lit or len(lit) < 6:
            continue
        if lit in text:
            hits[f"env#{i}"] = text.count(lit)
            text = text.replace(lit, "***REDACTED:secret***")
    return text, hits


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("dirs", nargs="+")
    ap.add_argument("--env", action="append", default=[],
                    help="değeri literal aranacak ortam değişkeni adı")
    args = ap.parse_args()

    literals = []
    for name in args.env:
        v = os.environ.get(name, "")
        if v:
            literals.append(v)
    # Adı verilmese de bilinen sırlar ortamda varsa aranır.
    for name in SECRET_ENV:
        v = os.environ.get(name, "")
        if v and v not in literals:
            literals.append(v)

    total = 0
    files = 0
    for d in args.dirs:
        p = pathlib.Path(d)
        if not p.exists():
            continue
        targets = [p] if p.is_file() else sorted(p.rglob("*"))
        for f in targets:
            if not f.is_file():
                continue
            files += 1
            try:
                text = f.read_text(encoding="utf-8", errors="replace")
            except OSError as e:
                print(f"! okunamadı {f}: {e}", file=sys.stderr)
                continue
            new, hits = scrub_text(text, literals)
            if hits:
                f.write_text(new, encoding="utf-8")
                total += sum(hits.values())
                detail = ", ".join(f"{k}×{v}" for k, v in sorted(hits.items()))
                print(f"::warning file={f}::log temizlendi ({detail})")

    print(f"scrub: {files} dosya, {total} sır bulundu"
          if total else f"scrub: {files} dosya, sır yok")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
