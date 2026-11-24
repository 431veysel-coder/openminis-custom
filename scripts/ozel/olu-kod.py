#!/usr/bin/env python3
"""Ölü kod denetimi — tanımlı ama **üretimde hiç çağrılmayan** fonksiyonlar.

## Neden var (ölçülen sorun)

Bu depoda iki kez aynı sınıf hata çıktı:

  * `VirtualScreenHost.brief()` yazıldı, hiçbir yerden çağrılmadı — bir tur
    "ajan nerede çalıştığını biliyor" sanıldı.
  * `needsCapture` / `markCaptured*` bölme bütçesi düzeltmesi yapıldı, ama
    **yakalama hattı yok**; yani kural doğru, çağıran yok.

Her ikisinde de kapılar yeşildi. Sebep: kapılar *kodu* denetliyor, kodun
**bağlı olup olmadığını** denetlemiyor.

## Ne yapar

`src/main` içindeki her `fun` tanımını toplar, aynı ağaçta çağrı sayar.
Çağrısı sıfır olanları basar. Yalnız **bilgi** verir, kırmızı yapmaz: arayüz
uygulamaları (`override`), Compose `@Composable`'ları, `main()`, operatör
fonksiyonlar ve yansımayla çağrılanlar bu kuralın dışında kalır — bu yüzden
kesin hüküm veremez.

Kullanım:
    python3 scripts/ozel/olu-kod.py            # tüm ağaç
    python3 scripts/ozel/olu-kod.py --sadece-yeni   # son commit'te eklenenler
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "src" / "android" / "app" / "src" / "main"

# Yansımayla/çerçeveyle çağrılan veya tanımı gereği çağrı gerektirmeyen adlar.
EXEMPT = {
    "main", "onCreate", "onDestroy", "onCleared", "onStart", "onStop", "onResume",
    "onPause", "onBind", "onUnbind", "onReceive", "onServiceConnected",
    "toString", "equals", "hashCode", "compareTo", "iterator", "invoke",
    "get", "set", "contains", "component1", "component2", "copy",
    "describeContents", "writeToParcel", "onSaveInstanceState",
}

FUN_RE = re.compile(
    r"^[ \t]*(?:@\w+(?:\([^)]*\))?\s*)*"
    r"(?:private\s+|internal\s+|public\s+|protected\s+|override\s+|suspend\s+|inline\s+|operator\s+|infix\s+)*"
    r"fun\s+(?:<[^>]+>\s*)?(?:[\w.]+\.)?(\w+)\s*\(",
    re.M,
)
PRIVATE_RE = re.compile(r"^[ \t]*private\s", re.M)


def production_files() -> list[Path]:
    return [p for p in MAIN.rglob("*.kt")]


def scan(files: list[Path]) -> list[tuple[str, str, int]]:
    """(dosya, fonksiyon, satır) — çağrısı olmayanlar.

    Tek geçiş: metin bir kez taranır, `ad(` geçişleri sayılır. Ada göre
    döngü kurmak (N ad × N dosya) bu depoda 300 saniyeyi aşıyordu.
    """
    texts = {p: p.read_text(errors="ignore") for p in files}
    defs: list[tuple[Path, str, int]] = []

    for p, text in texts.items():
        lines = text.splitlines()
        for m in FUN_RE.finditer(text):
            name = m.group(1)
            line_no = text[: m.start()].count("\n") + 1
            line = lines[line_no - 1] if line_no - 1 < len(lines) else ""
            if name in EXEMPT or "@Composable" in line or name.startswith("component"):
                continue
            # `override` arayüzü uygular; çağrı oradan gelir.
            if re.search(r"\boverride\b", line):
                continue
            defs.append((p, name, line_no))

    # Çağrı sayımı: tüm metinde `ad(` — tanım satırları dahil; bir tanım
    # kendini saydırırsa sayı 1 olur ve "çağrılıyor" görünür. Bunu önlemek
    # için tanım satırlarını önce maskele.
    counts: dict[str, int] = {}
    for p, text in texts.items():
        masked = FUN_RE.sub(lambda m: m.group(0).replace("fun ", "fun _def_", 1), text)
        for m in re.finditer(r"\b(\w+)\s*\(", masked):
            name = m.group(1)
            counts[name] = counts.get(name, 0) + 1

    hits: dict[str, int] = {}
    for _, name, _ in defs:
        hits[name] = hits.get(name, 0) + 1

    out = []
    for p, name, line_no in defs:
        # Ada göre çok tanım varsa çağrı sayımı belirsiz: atla.
        if hits.get(name, 0) > 1:
            continue
        if counts.get(name, 0) == 0:
            out.append((str(p.relative_to(ROOT)), name, line_no))
    return sorted(out)


def new_in_last_commit(files: list[Path]) -> list[Path]:
    try:
        out = subprocess.run(
            ["git", "-C", str(ROOT), "diff", "--name-only", "HEAD~1", "HEAD"],
            capture_output=True, text=True, timeout=30,
        ).stdout.split()
    except Exception:  # noqa: BLE001
        return files
    keep = [ROOT / f for f in out if f.endswith(".kt") and (ROOT / f).exists()]
    return keep or files


def main() -> int:
    files = new_in_last_commit(production_files()) if "--sadece-yeni" in sys.argv else production_files()
    rows = scan(files)
    if not rows:
        print(f"olu-kod: {len(files)} dosyada çağrısız fonksiyon yok")
        return 0
    print(f"olu-kod: {len(rows)} fonksiyonun üretimde çağrısı yok (bilgi, kapı değil)")
    for path, name, line in rows:
        print(f"  · {path}:{line}  {name}()")
    return 0


if __name__ == "__main__":
    sys.exit(main())
