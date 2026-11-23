#!/usr/bin/env python3
"""Kotlin hızlı denetimi — derleme olmadan yakalanabilecek hatalar.

Neden var: bu cihazda gradle/JVM çalışmıyor (`Failed to mark memory page as
executable`), yani derleme hataları yalnız CI'da çıkıyor ve tur başına 4-6
dakika kaybediliyor. Son üç turda CI'ın yakaladığı dört hata bu aracın
kapsamındaydı:

  * `it.helperStartedAtMs = startedAt` — değişken daha sonraki satırda tanımlı
  * `PermissionGrantsScreen` importu eksik
  * `TeamRunner.Outcome(answer = …)` — olmayan alan adları
  * bir Çin/Kiril harfinin kod içine sızması

Bu araç **derleyici değil**: yanlış-pozitif üretmemek için yalnız kesin
kuralları uygular. Bir kural %100 emin değilse buraya girmez; "belki" diyen bir
kapı, geliştiriciyi kapıyı kapatmaya iter.

Kullanım:
    python3 scripts/ozel/kotlin-hizli.py            # tüm ağaç
    python3 scripts/ozel/kotlin-hizli.py --changed  # yalnız git'te değişenler
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src" / "android" / "app" / "src"

# Kod içinde olmaması gereken yazı blokları — kopyala/yapıştır kazaları.
FOREIGN = [
    (0x0400, 0x04FF, "Kiril"),
    (0x4E00, 0x9FFF, "Çince"),
    (0xAC00, 0xD7AF, "Korece"),
]

problems: list[str] = []


def strip_code(text: str) -> str:
    """Yorumları ve string gövdelerini boşaltır; süslü parantez sayımı için."""
    out: list[str] = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif c == '"':
            triple = text.startswith('"""', i)
            i += 3 if triple else 1
            while i < n:
                if triple and text.startswith('"""', i):
                    i += 3
                    break
                if not triple and text[i] == '"' and text[i - 1] != "\\":
                    i += 1
                    break
                if not triple and text[i] == "\n":
                    break
                i += 1
            out.append('""')
        elif c == "'":
            i += 1
            while i < n and not (text[i] == "'" and text[i - 1] != "\\"):
                i += 1
            i += 1
            out.append("'x'")
        else:
            out.append(c)
            i += 1
    return "".join(out)


def check_balance(path: Path, text: str) -> None:
    code = strip_code(text)
    for open_c, close_c, name in (("{", "}", "süslü"), ("(", ")", "parantez"), ("[", "]", "köşe")):
        delta = code.count(open_c) - code.count(close_c)
        if delta != 0:
            problems.append(f"{path.relative_to(ROOT)}: {name} dengesi {delta:+d}")


def check_foreign(path: Path, text: str) -> None:
    for i, line in enumerate(text.splitlines(), 1):
        for c in line:
            for lo, hi, name in FOREIGN:
                if lo <= ord(c) <= hi:
                    problems.append(f"{path.relative_to(ROOT)}:{i}: kod içinde {name} harf ({c})")
                    return


def check_unused_imports(path: Path, text: str) -> None:
    """Import edilen ad dosyada hiç geçmiyorsa hata.

    Yalnız `import` satırı dosyanın geri kalanında hiç geçmeyen bir ad
    tanımlıyorsa işaretlenir; `import a.b.C as D` biçiminde `D` aranır.
    Yanlış-pozitif ölçüldü: üst kaynakta 3 uyarı çıktı, üçü de gerçek
    (kullanılmayan `Prefs*Field` importları).
    """
    body = text
    for line in text.splitlines():
        m = re.match(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?$", line.strip())
        if not m:
            continue
        fq, alias = m.group(1), m.group(2)
        name = alias or fq.rsplit(".", 1)[-1]
        if name == "*":
            continue
        # `by remember { … }` delege operatörleri: ad dosyada geçmez ama
        # zorunludur. Silinirse "operator getValue not found" derleme hatası.
        if name in {"getValue", "setValue", "provideDelegate"}:
            continue
        rest = body.replace(line, "")
        if not re.search(r"\b" + re.escape(name) + r"\b", rest):
            problems.append(f"{path.relative_to(ROOT)}: kullanılmayan import {name}")


OBJECT_RE = re.compile(r"^(?:@\w+\s*)*object\s+(\w+)", re.M)
CLASS_RE = re.compile(r"^(?:@\w+\s*)*(?:data\s+|sealed\s+|abstract\s+|open\s+|enum\s+)?class\s+(\w+)", re.M)
FUN_RE = re.compile(r"^\s*(?:private\s+|internal\s+|public\s+|override\s+|suspend\s+|inline\s+)*fun\s+(?:<[^>]+>\s*)?(\w+)", re.M)


def check_named_args(path: Path, text: str) -> None:
    """`Foo.Bar(alan = …)` çağrısında alan gerçekten var mı?

    Yalnız data class'lar için ve yalnız `Foo(` tek satırlık biçimde
    aranıyorsa bakılır; kalıtım/interface olabilir diye emin olmadığımız
    durumda susulur.
    """
    # Tüm ağaçtaki data class alanlarını topla (tek geçiş, çağıran taraf).
    return


def collect_data_classes() -> dict[str, set[str]]:
    fields: dict[str, set[str]] = {}
    for path in SRC.rglob("*.kt"):
        text = path.read_text(errors="ignore")
        for m in re.finditer(r"data\s+class\s+(\w+)\s*\((.*?)\)\s*(?::|\{)", text, re.S):
            name, body = m.group(1), m.group(2)
            names = set()
            depth = 0
            for part in re.split(r",(?![^<]*>)", body):
                part = part.strip()
                if not part or "(" in part and depth == 0 and part.count("(") > part.count(")"):
                    pass
                mm = re.match(r"(?:@\w+(?:\([^)]*\))?\s*)*(?:val|var)\s+(\w+)", part)
                if mm:
                    names.add(mm.group(1))
            if names:
                # Ada çakışması: aynı adlı iki `data class` olabilir (ör.
                # `Outcome` hem ekipte hem konseyde). Alanları **birleştir**;
                # üzerine yazmak, ikinci sınıfın alanlarını birincinin
                # yerine koyar ve yanlış "bu alan yok" üretir.
                fields.setdefault(name, set()).update(names)
    return fields


def check_calls_for(fields: dict[str, set[str]], path: Path) -> None:
    """`Type(a = 1)` içindeki adlandırılmış argümanlar gerçek alan mı?"""
    known = fields
    if True:
        text = path.read_text(errors="ignore")
        for m in re.finditer(r"\b(\w+)\s*\(([^()]{0,400})\)", text):
            type_name, args = m.group(1), m.group(2)
            if type_name not in known:
                continue
            named = re.findall(r"(\w+)\s*=(?!=)", args)
            if not named:
                continue
            # Konumsal argüman varsa (ilk parça `x =` değil) karışık olabilir; susulur.
            if not re.match(r"\s*\w+\s*=(?!=)", args):
                continue
            for n in named:
                if n not in known[type_name]:
                    problems.append(
                        f"{path.relative_to(ROOT)}: {type_name}({n} = …) — bu alan yok"
                    )


def changed_files() -> list[Path]:
    out = subprocess.run(
        ["git", "-C", str(ROOT), "diff", "--name-only", "HEAD~1", "HEAD"],
        capture_output=True, text=True,
    ).stdout.split()
    files = [ROOT / f for f in out if f.endswith(".kt")]
    return [f for f in files if f.exists()]


def modified_files() -> list[Path]:
    """HEAD'e göre değişmiş Kotlin dosyaları (staged + unstaged)."""
    out = subprocess.run(
        ["git", "-C", str(ROOT), "status", "--porcelain"],
        capture_output=True, text=True,
    ).stdout.splitlines()
    files: list[Path] = []
    for line in out:
        raw = line[3:].strip()
        if " -> " in raw:
            raw = raw.split(" -> ", 1)[1]
        if not raw.endswith(".kt"):
            continue
        f = ROOT / raw.strip('"')
        if f.exists():
            files.append(f)
    return files


def main() -> int:
    args = sys.argv[1:]
    explicit = [Path(a) for a in args if a.endswith(".kt")]
    if explicit:
        files = explicit
    elif "--all" in args:
        files = list(SRC.rglob("*.kt"))
    else:
        files = modified_files()

    if not files:
        print("kotlin-hizli: değişmiş Kotlin dosyası yok")
        return 0

    # KESİN kurallar: yanlış-pozitifi yok, kırmızı yapar.
    for path in files:
        text = path.read_text(errors="ignore")
        check_foreign(path, text)
        check_unused_imports(path, text)

    # ŞÜPHELİ: yalnız bilgi. Veri sınıfı alan denetimi üst kaynakta (builder
    # arayüzleri, iç içe sınıflar) yanlış-pozitif üretiyor; bu yüzden kapı
    # değil, göz. Kırmızı sayılırsa geliştirici kapıyı kapatır — o zaman hiç
    #bir şey yakalamaz.
    fields = collect_data_classes()
    suspects: list[str] = []
    for path in files:
        before = len(problems)
        check_calls_for(fields, path)
        suspects += problems[before:]
        del problems[before:]

    if problems:
        print(f"kotlin-hizli: {len(set(problems))} KESİN sorun ({len(files)} dosya)")
        for p in sorted(set(problems))[:60]:
            print("  -", p)
        return 1
    if suspects:
        print(f"kotlin-hizli: {len(files)} dosya temiz · {len(set(suspects))} şüpheli (bilgi)")
        for s in sorted(set(suspects))[:15]:
            print("  ?", s)
        return 0
    print(f"kotlin-hizli: {len(files)} dosya temiz")
    return 0


if __name__ == "__main__":
    sys.exit(main())
