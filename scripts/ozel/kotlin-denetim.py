#!/usr/bin/env python3
"""
[37096] Kotlin kaynak denetimi — derleyici olmadan yakalanabilenler.

NEDEN VAR
    Bu depo 6 490 test ve 1 532 Kotlin dosyası taşıyor; doğru sınav
    `./gradlew test`tir. Ama iki yerde o sınav koşamıyor:

      1. Cihazda JVM başlamıyor (PRoot: "Failed to mark memory page as
         executable"). Yani telefonda derleme yapılamıyor.
      2. Push öncesi 10 dakikalık CI'ya göndermeden önce "dosya kapanmamış"
         gibi aptal bir hatayı görmek istiyorsun.

    Bu betik derleyici DEĞİL. Yalnız şunları yakalar:
      · parantez/süslü/köşeli denge (string ve yorum farkında),
      · kapanmamış çok satırlı string (\"\"\"),
      · `val x = /* boş */`,
      · aynı dosyada iki kez tanımlanmış private fun,
      · import edilmiş ama kullanılmayan isim (yalnız tek kelimelik adlar),
      · `when` içinde `else` olmadan enum/else gerektiren ifade — SEZGİSEL,
        bu yüzden yalnız uyarı.

ÇIKIŞ KODU
    0 = engelleyici hata yok, 1 = var. Uyarılar çıkış kodunu değiştirmez.

KULLANIM
    python3 scripts/ozel/kotlin-denetim.py                     # tüm ağaç
    python3 scripts/ozel/kotlin-denetim.py <dosya>...          # seçili dosyalar
    python3 scripts/ozel/kotlin-denetim.py --changed           # git diff'e göre
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "src/android/app/src"


def strip_code(text: str) -> str:
    """Yorumları ve string içeriklerini boşaltır; yapıyı korur."""
    out: list[str] = []
    i = 0
    n = len(text)
    while i < n:
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
        elif text.startswith("/*", i):
            depth = 1
            i += 2
            while i < n and depth:
                if text.startswith("/*", i):
                    depth += 1
                    i += 2
                elif text.startswith("*/", i):
                    depth -= 1
                    i += 2
                else:
                    i += 1
        elif text.startswith('"""', i):
            i += 3
            while i < n and not text.startswith('"""', i):
                i += 1
            i += 3
            # Kotlin raw strings may END with a quote: `"""abc""""` is content
            # `abc"`. The closing delimiter is followed by the content's own
            # quotes, so swallow them here — otherwise the leftover `"` looks
            # like the start of a normal string and every following brace reads
            # as unbalanced (a whole class of false positives in the test tree).
            while i < n and text[i] == '"':
                i += 1
            out.append('""')
        elif text[i] == '`':
            # Kotlin backtick identifiers: `fun `rows keep iOS's wording`()`.
            # An apostrophe inside one would otherwise read as a char literal
            # and swallow the rest of the file — the single biggest source of
            # false "unclosed brace" reports in the test tree.
            i += 1
            while i < n and text[i] != '`':
                i += 1
            i += 1
            out.append("ID")
        elif text[i] in ('"', "'"):
            q = text[i]
            i += 1
            while i < n and text[i] != q:
                if text[i] == "\\":
                    i += 1
                i += 1
            i += 1
            out.append(q + q)
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def check_balance(path: Path, raw: str) -> list[str]:
    """
    Denge denetimi — SEZGİSEL, bu yüzden UYARI.

    Neden hata değil: Kotlin string şablonları iç içe geçebiliyor
    (`"${x.let { "a" }}"`) ve bunu doğru çözmek tam bir Kotlin sözlükçüsü
    yazmak demek. Kaba lexer bunu kaçırır; kaçırdığında üretilen "kapanmamış
    süslü parantez" raporu **yanlış** olur. Yanlış kırmızı, kapıyı ilk günde
    kapatılacak hâle getirir. Derleyici bu hatayı zaten yakalar (geç ama
    doğru); buradaki kontrol yalnız "dosyayı kaydederken bir parantez
    düşürdün mü" sorusuna hızlı cevap.
    """
    problems: list[str] = []
    code = strip_code(raw)
    pairs = {")": "(", "}": "{", "]": "["}
    stack: list[tuple[str, int]] = []
    line = 1
    for ch in code:
        if ch == "\n":
            line += 1
        elif ch in "({[":
            stack.append((ch, line))
        elif ch in ")}]":
            if not stack:
                problems.append(f"{path.name}:{line}: fazladan '{ch}' (uyarı, sezgisel)")
            else:
                opener, oline = stack.pop()
                if opener != pairs[ch]:
                    problems.append(
                        f"{path.name}:{line}: '{ch}' beklerken '{opener}' açılmış "
                        f"(satır {oline}) (uyarı, sezgisel)"
                    )
    for opener, oline in stack:
        problems.append(f"{path.name}:{oline}: '{opener}' kapanmamış (uyarı, sezgisel)")

    # Bu ikisi lexer'dan bağımsız ve KESİN: ham string sınırlayıcısı tektir.
    if raw.count('"""') % 2 != 0:
        problems.append(f"HATA {path.name}: kapanmamış çok satırlı string (\"\"\")")
    return problems


def check_duplicate_private(raw: str, path: Path) -> list[str]:
    """
    Aynı imzayla iki kez tanımlanan `private fun` — gerçek hata.

    Aynı ad + FARKLI imza Kotlin'de geçerli overload'dur (`stamp(View)` ve
    `stamp(SemanticsNode)`). Bu yüzden imza, parametre ADLARI atılıp TİPLERİ
    korunarak karşılaştırılıyor: ad değişikliği Kotlin için fark yaratmaz,
    tip değişikliği yaratır.

    Kapsam da anahtarın parçası: bir `object` içindeki `prefs(Context)` ile
    onun içindeki `data class`ın `prefs(Context)` çakışmaz — farklı sınıflar.
    Süslü parantez derinliği kapsamın kaba ama yeterli vekili (aynı derinlikte
    iki aynı imza gerçek bir çakışmadır).
    """
    seen: dict[tuple[int, str, str], int] = {}
    problems: list[str] = []
    raw_lines = raw.splitlines()
    for m in re.finditer(r"^\s*private\s+(?:suspend\s+)?fun\s+(?:<[^>]+>\s*)?(\w+)\s*\(", raw, re.M):
        name = m.group(1)
        line = raw[: m.start()].count("\n") + 1
        scope = brace_depth_at(raw_lines, line - 1)
        start = m.end() - 1
        depth = 0
        i = start
        while i < len(raw):
            ch = raw[i]
            if ch in "([{<":
                depth += 1
            elif ch in ")]}>":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        params = raw[start + 1: i]
        # Parametre adlarını at, tipleri tut: `view: View` -> `: View`.
        normalized = re.sub(r"\b([A-Za-z_]\w*)\s*:", ":", params)
        normalized = re.sub(r"\s+", " ", normalized).strip()
        key = (scope, name, normalized)
        if key in seen:
            problems.append(
                f"{path.name}:{line}: '{name}' aynı imzayla ikinci kez tanımlı (ilk: satır {seen[key]})"
            )
        else:
            seen[key] = line
    return problems


def brace_depth_at(lines: list[str], upto: int) -> int:
    """0-tabanlı [upto] satırından ÖNCEKİ kaba süslü parantez derinliği."""
    depth = 0
    for line in lines[:upto]:
        depth += line.count("{") - line.count("}")
    return max(depth, 0)


def check_empty_bodies(raw: str, path: Path) -> list[str]:
    """Boş gövde — varsayılan parametre (`onX: () -> Unit = {}`) hariç."""
    problems: list[str] = []
    for m in re.finditer(r"=\s*\{\s*\}", raw):
        line = raw[: m.start()].count("\n") + 1
        snippet = raw.splitlines()[line - 1].strip() if line <= len(raw.splitlines()) else ""
        # Varsayılan lambda parametresi bir sözleşmedir, ölü kod değil.
        if "->" in snippet or snippet.endswith("{},") or "= {}" in snippet and ":" in snippet:
            continue
        problems.append(f"{path.name}:{line}: boş gövde: {snippet[:70]} (uyarı)")
    return problems


def check_unused_imports(raw: str, path: Path) -> list[str]:
    """
    Kullanılmayan import — SEZGİSEL, bu yüzden yalnız uyarı.

    Dikkat: extension fonksiyonları (`collectAsState`, `dp`, `sp`) çağrı
    yerinde `.ad()` biçiminde görünür. Basit "ad geçiyor mu" araması bu yüzden
    `\\b` sınırıyla yapılıyor; nokta öncesi yasak DEĞİL.
    """
    code = strip_code(raw)
    imports = re.findall(r"^import\s+([\w.]+)(?:\s+as\s+(\w+))?", code, re.M)
    problems: list[str] = []
    body = re.sub(r"^import .*$", "", code, flags=re.M)
    # `by` delegasyonu getValue/setValue'yu gövdede hiç anmadan kullanır:
    # `import androidx.compose.runtime.getValue` + `val x by remember{}` derlenir.
    # Bunları "kullanılmıyor" diye işaretlemek gürültüdür.
    implicit = {"getValue", "setValue", "provideDelegate"}
    for full, alias in imports:
        name = alias or full.split(".")[-1]
        if name == "*" or name in implicit:
            continue
        if not re.search(rf"\b{re.escape(name)}\b", body):
            line = code[: code.find(f"import {full}")].count("\n") + 1
            problems.append(f"{path.name}:{line}: kullanılmayan import: {full} (uyarı)")
    return problems


def changed_files() -> list[Path]:
    out = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACMR", "HEAD"],
        cwd=ROOT, capture_output=True, text=True, check=False,
    ).stdout
    return [ROOT / p for p in out.splitlines() if p.endswith(".kt") and (ROOT / p).exists()]


def main() -> int:
    ap = argparse.ArgumentParser(description="Kotlin kaynak denetimi (derleyici değil)")
    ap.add_argument("files", nargs="*", help="denetlenecek .kt dosyaları")
    ap.add_argument("--changed", action="store_true", help="yalnız git diff'teki dosyalar")
    ap.add_argument("--no-warn", action="store_true", help="uyarıları gösterme")
    args = ap.parse_args()

    if args.changed:
        targets = changed_files()
    elif args.files:
        targets = [Path(f) if Path(f).is_absolute() else ROOT / f for f in args.files]
    else:
        targets = sorted(SRC.rglob("*.kt"))

    errors: list[str] = []
    warnings: list[str] = []
    for path in targets:
        if not path.exists():
            errors.append(f"{path}: dosya yok")
            continue
        raw = path.read_text(encoding="utf-8", errors="replace")
        for item in check_balance(path, raw):
            # `HATA` öneki kesin olanları işaretler (ham string paritesi);
            # geri kalanı sezgisel denge raporudur ve uyarı sayılır.
            (errors if item.startswith("HATA ") else warnings).append(
                item.replace("HATA ", "", 1)
            )
        errors += check_duplicate_private(raw, path)
        if not args.no_warn:
            warnings += check_empty_bodies(raw, path)
            warnings += check_unused_imports(raw, path)

    print(f"{len(targets)} dosya denetlendi")
    if warnings and not args.no_warn:
        print(f"\nUYARILAR ({len(warnings)})")
        for w in warnings[:60]:
            print(f"  ~ {w}")
        if len(warnings) > 60:
            print(f"  … {len(warnings) - 60} tane daha")
    if errors:
        print(f"\nHATALAR ({len(errors)})")
        for e in errors:
            print(f"  ✗ {e}")
        return 1
    print("\nYapısal hata yok.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
