#!/usr/bin/env python3
"""
[37099] Yerel kapı koşucusu — CI'ya gitmeden hepsini koş, saniyeler içinde.

NEDEN VAR
    Bugün üç tur CI harcandı ve ikisi **yerelde yakalanabilirdi**:
      · harita deterministik değildi (yerelde yeşil, CI'da kırmızı)
      · `ThinkingTranslator` iki sınıfı import etmiyordu (audit yakalar)
    Her tur ~4 dakika + log indirme + artefakt indirme demekti. Sıralı
    koşulduğunda bu sekiz kapı ~9 saniye; paralel koşulunca ~4.

    Yani bu betiğin işi hız değil **tur sayısını azaltmak**: bir kez koş, hepsi
    yeşilse push et. Kırmızıysa hangisi olduğunu adıyla ve çıktısıyla söyler.

NEDEN PARALEL
    Kapılar birbirinden bağımsız (biri Kotlin okur, biri XML, biri git).
    Sıralı koşmak, en yavaş olanı beklemek demek. `ThreadPoolExecutor` yeterli:
    hepsi alt süreç, GIL sorun değil.

KULLANIM
    python3 scripts/ozel/kapi.py              # hepsi, paralel
    python3 scripts/ozel/kapi.py --seri       # sırayla (hata ayıklarken)
    python3 scripts/ozel/kapi.py -v           # başarılıların da çıktısı
    python3 scripts/ozel/kapi.py korpus harita        # yalnız seçilenler

ÇIKIŞ KODU
    0 hepsi geçti · 1 en az bir kapı kırmızı
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# ad -> (komut, zorunlu mu)
#  "zorunlu" False ise kırmızı olsa da genel sonucu düşürmez (uyarı kapısı).
GATES: dict[str, tuple[list[str], bool]] = {
    "korpus": ([sys.executable, "scripts/ozel/risk-corpus.py", "--fail-on-mismatch"], True),
    "yapisal": ([sys.executable, "scripts/ozel/kotlin-denetim.py", "--no-warn"], True),
    "ceviri": ([sys.executable, "scripts/ozel/ceviri-denetim.py", "--fail-on-new"], True),
    "apk-butce": ([sys.executable, "scripts/ozel/apk-butce.py", "--fail-on-embed"], True),
    "harita": ([sys.executable, "scripts/ozel/harita.py", "--check", "--quiet"], True),
    "entegrasyon": ([sys.executable, "scripts/integration_audit.py"], True),
    "denetim": ([sys.executable, "scripts/audit/audit_all.py"], True),
    "etiket": ([sys.executable, "scripts/ozel/tag-denetim.py"], False),
    # [37099e · §7.2] Derlemesiz doğrulama: bu cihazda gradle yok, derleme
    # hataları yalnız CI'da çıkıyordu. Yalnız kesin kurallar (yabancı harf,
    # kullanılmayan import); yanlış-pozitif üreten denetimler bilgi olarak
    # basılır, kapıyı kırmızı yapmaz.
    "kotlin": ([sys.executable, "scripts/ozel/kotlin-hizli.py"], True),
}


def run_one(name: str) -> tuple[str, int, float, str]:
    cmd, _required = GATES[name]
    started = time.time()
    try:
        p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=300)
        out = (p.stdout or "") + (p.stderr or "")
        return name, p.returncode, time.time() - started, out
    except subprocess.TimeoutExpired:
        return name, 124, time.time() - started, "zaman aşımı (300 sn)"
    except FileNotFoundError as e:
        return name, 127, time.time() - started, f"betik yok: {e}"


def tail(text: str, n: int = 12) -> str:
    lines = [l for l in text.strip().splitlines() if l.strip()]
    return "\n".join(lines[-n:])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("names", nargs="*", help="yalnız bu kapılar")
    ap.add_argument("--seri", action="store_true", help="sırayla koş")
    ap.add_argument("-v", "--verbose", action="store_true", help="başarılı çıktıyı da bas")
    args = ap.parse_args()

    names = args.names or list(GATES)
    unknown = [n for n in names if n not in GATES]
    if unknown:
        print(f"bilinmeyen kapı: {', '.join(unknown)}", file=sys.stderr)
        print(f"geçerli: {', '.join(GATES)}", file=sys.stderr)
        return 2

    started = time.time()
    if args.seri:
        results = [run_one(n) for n in names]
    else:
        with ThreadPoolExecutor(max_workers=min(8, len(names))) as pool:
            results = list(pool.map(run_one, names))
    elapsed = time.time() - started

    red = 0
    warn = 0
    for name, code, secs, out in sorted(results, key=lambda r: r[0]):
        required = GATES[name][1]
        if code == 0:
            mark, color = "OK  ", ""
        elif not required:
            mark, color = "UYARI", ""
            warn += 1
        else:
            mark, color = "KIRMIZI", ""
            red += 1
        print(f"[{mark:>7}] {name:<12} {secs:5.1f}s")
        if code != 0 or args.verbose:
            body = tail(out)
            if body:
                for line in body.splitlines():
                    print(f"          {line}")

    print()
    total = len(results)
    if red:
        print(f"{total - red - warn}/{total} geçti · {red} KIRMIZI · {warn} uyarı · {elapsed:.1f}s")
        print("Push etme. Yukarıdaki kırmızı kapıyı düzelt, sonra tekrar koş.")
        return 1
    print(f"{total - warn}/{total} geçti · {warn} uyarı · {elapsed:.1f}s — push edilebilir.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
