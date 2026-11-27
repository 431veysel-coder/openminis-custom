#!/usr/bin/env python3
"""[H-014 / H-015] Çökme-eğilimli iki deseni yakalar.

## Neden var

`.55`'te iki farklı çökme sınıfı **aynı turda** kullanıcıya ulaştı:

1. **H-014:** `GLES20.glGetString` bağlam yokken istisna değil **SIGSEGV**
   üretiyor. `runCatching` bir sinyali yakalayamaz; kod doğru görünür,
   ekran açılınca süreç ölür.
2. **H-015:** `lateinit` alan, sistemin süreç ölümünden sonra **yeniden
   kurduğu** bir bileşenden okunuyor (`AgentForegroundService.onCreate`).
   Danışma sırası garanti değil; ilk okuma `UninitializedPropertyAccess…`
   fırlatıyor ve süreç ikinci kez çöküyor.

İkisinin ortak yanı: derleyici susuyor, testler susuyor, yalnız **cihazda
çalışırken** patlıyor. Yani kapı gerekiyor, dikkat yetmiyor.

## Ne yapar

Tarama iki kural:

  A. `GLES20.glGetString(` / `GLES10.glGetString(` çağrısı, aynı dosyada
     `GpuQuery` kullanılmıyorsa **kırmızı** (H-014).
  B. `Service`/`Receiver`/`Worker` dosyasından `MinisApp`'in `lateinit`
     alanına erişim (`as MinisApp).alan` ya da `app.alan`), alan `by lazy`
     değilse **kırmızı** (H-015).

Muafiyet dosya başına tek satırdır ve **gerekçe yazmak zorundadır**:

    // kapi-muaf: <gerekçe>

Gerekçesiz muafiyet kırmızı. Bu depoda "sessizce affet" sınıfı dört kez
kapıyı boşalttı; burada affın kendisi denetleniyor.

## Kullanım

    python3 scripts/ozel/kapi-cokme.py            # ağaç taraması
    python3 scripts/ozel/kapi-cokme.py --kendini-sina   # kural kendini test eder
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID_SRC = ROOT / "src" / "android" / "app" / "src"

GL_CALL = re.compile(r"\bGLES(?:10|20|30)\.glGetString\s*\(")
GL_SAFE = re.compile(r"\bGpuQuery\.renderer\s*\(")
MUAF = re.compile(r"kapi-muaf:\s*(\S.*)")

SERVICE_DIRS = ("service", "offload", "worker", "receiver")

# MinisApp'te `lateinit` yerine `by lazy` olmayan, dışarıdan okunan alanlar.
# Liste elle tutulmuyor: MinisApp.kt'den okunuyor.
MINIS_APP = ANDROID_SRC / "main" / "java" / "com" / "openminis" / "app" / "MinisApp.kt"
LATEINIT_DECL = re.compile(r"^\s*lateinit\s+var\s+(\w+)\s*:", re.M)


def lateinit_fields() -> set[str]:
    if not MINIS_APP.exists():
        return set()
    return set(LATEINIT_DECL.findall(MINIS_APP.read_text(encoding="utf-8")))


def has_muaf(text: str) -> bool:
    for line in text.splitlines():
        m = MUAF.search(line)
        if m and m.group(1).strip():
            return True
    return False


def scan_gl(path: Path, text: str) -> str | None:
    if not GL_CALL.search(text):
        return None
    if GL_SAFE.search(text):
        return None
    if has_muaf(text):
        return None
    return "glGetString çağrısı bağlamsız (H-014) — GpuQuery.renderer() kullan"


def scan_lateinit(path: Path, text: str, fields: set[str]) -> str | None:
    # Yalnız yaşam döngüsü garantisi olmayan dosyalar: servis/alıcı/işçi.
    if not any(f"/{d}/" in path.as_posix() for d in SERVICE_DIRS):
        return None
    # Yalnız **MinisApp üzerinden** erişim sayılır. Çıplak kelime araması
    # yanlış pozitif üretiyordu: `android.database.Cursor`, yorumdaki
    # "namespace database" ifadesi ve `providerRepository` adlı yerel
    # değişkenler de eşleşiyordu — kapıyı gürültüye boğan tam bu sınıf.
    for f in fields:
        pat = rf"(?:as\s+MinisApp\)|\bminisApp\b|\bapp)\s*\??\.\s*{re.escape(f)}\b"
        if re.search(pat, text):
            return f"lateinit `{f}` servis yolundan okunuyor (H-015) — `by lazy` yap"
    return None


def kendini_sina() -> int:
    """Kural, bilerek bozuk iki örnekte kırmızı vermeli."""
    cases = [
        ("orta", lambda: scan_gl(Path("/x/A.kt"), "val s = GLES20.glGetString(1)")),
        ("temiz", lambda: scan_gl(Path("/x/B.kt"), "val s = GpuQuery.renderer()")),
        ("muaf", lambda: scan_gl(Path("/x/C.kt"),
                                 "// kapi-muaf: GL bağlamı çağıran tarafta kurulu\nGLES20.glGetString(1)")),
        ("gerekçesiz-muaf", lambda: scan_gl(Path("/x/D.kt"),
                                            "// kapi-muaf:\nGLES20.glGetString(1)")),
        ("lateinit", lambda: scan_lateinit(Path("/y/service/S.kt"), "x = app.foo", {"foo"})),
        ("lateinit-dışı", lambda: scan_lateinit(Path("/y/ui/U.kt"), "x = app.foo", {"foo"})),
    ]
    beklenen = {
        "orta": "kırmızı", "temiz": None, "muaf": None,
        "gerekçesiz-muaf": "kırmızı", "lateinit": "kırmızı", "lateinit-dışı": None,
    }
    fail = 0
    for ad, fn in cases:
        got = fn()
        want = beklenen[ad]
        ok = (got is None and want is None) or (got is not None and want == "kırmızı")
        print(f"  {'PASS' if ok else 'FAIL'}  {ad}: {'temiz' if got is None else 'kırmızı'}")
        if not ok:
            fail += 1
    print(f"kendini-sina: {len(cases) - fail}/{len(cases)}")
    return 1 if fail else 0


def main() -> int:
    if "--kendini-sina" in sys.argv:
        return kendini_sina()

    fields = lateinit_fields()
    findings: list[tuple[str, str, str]] = []
    scanned = 0
    for path in sorted(ANDROID_SRC.rglob("*.kt")):
        if "/test/" in path.as_posix():
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        scanned += 1
        for fn in (lambda: scan_gl(path, text), lambda: scan_lateinit(path, text, fields)):
            msg = fn()
            if msg:
                findings.append((path.relative_to(ROOT).as_posix(), msg, ""))

    if findings:
        for path, msg, _ in findings:
            print(f"[KIRMIZI] {path}: {msg}")
        print(f"\nkapi-cokme: {len(findings)} sorun ({scanned} dosya)")
        return 1
    print(f"kapi-cokme: temiz ({scanned} dosya, {len(fields)} lateinit alan)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
