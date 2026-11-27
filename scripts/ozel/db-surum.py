#!/usr/bin/env python3
"""[T-db-version-single-source] Veritabanı şema sürümü kapısı.

## Neden var

`1.15-ozel.53`'te (build 37133) `AppDatabase`'in `@Database(version = 16)` ile
`DatabaseVersionGuard.CODE_DB_VERSION = 15` birbirinden ayrıştı. Sonuç: uygulama
açılışta v16 veritabanı yazıyor, bir sonraki açılışta aynı veritabanını "daha
yeni bir sürümden geliyor" diye reddediyordu. Hiçbir derleme, hiçbir test ve
hiçbir mevcut kapı bunu görmedi — iki dosya da geçerli Kotlin'di ve değerler
sadece *sayı* olduğu için derleyici için hiçbir anlam taşımıyordu.

Kod tarafı artık tek sabit (`DB_SCHEMA_VERSION`) kullanıyor; bu kapı üç şeyi
kanıtlar:

  1. `@Database(version = ...)` ile `DB_SCHEMA_VERSION` aynı.
  2. `DatabaseVersionGuard.CODE_DB_VERSION` literal bir sayıya dönmemiş.
  3. Depoda commit edilmiş en yüksek Room şema json'u `DB_SCHEMA_VERSION`'a eşit
     (sürüm artırıldı ama migration + şema json'u yazılmadıysa kırmızı).

## Çıkış kodları

0 = tutarlı, 1 = ayrışma (mesajlar stdout'ta), 2 = dosya bulunamadı.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DB = ROOT / "src/android/app/src/main/java/com/openminis/app/data/db/AppDatabase.kt"
GUARD = ROOT / "src/android/app/src/main/java/com/openminis/app/data/db/DatabaseVersionGuard.kt"
SCHEMAS = ROOT / "src/android/app/schemas/com.openminis.app.data.db.AppDatabase"

hata = []
uyari = []
bilgi = []
# `--strict`: eksik şema json'u da hata sayılır. Varsayılan olarak uyarıdır,
# çünkü eksik json bir *derleme çıktısı* sorunudur (KSP üretir) ve düzeltmesi
# ayrı bir iş kalemidir; sürüm ayrışması ise anında kullanıcıyı kilitler ve her
# zaman hatadır.
STRICT = "--strict" in sys.argv


def oku(yol):
    if not yol.exists():
        hata.append(f"yok: {yol.relative_to(ROOT)}")
        return None
    return yol.read_text(encoding="utf-8")


db = oku(DB)
guard = oku(GUARD)

if db is not None:
    m = re.search(r"const\s+val\s+DB_SCHEMA_VERSION\s*=\s*(\d+)", db)
    if not m:
        hata.append(
            "AppDatabase.kt: `const val DB_SCHEMA_VERSION = <sayı>` bulunamadı. "
            "Şema sürümünün tek kaynağı bu sabittir."
        )
        sabit = None
    else:
        sabit = int(m.group(1))
        bilgi.append(f"DB_SCHEMA_VERSION = {sabit}")

    # @Database(...) gövdesi içindeki version = ...
    ann = re.search(r"@Database\((.*?)\)\s*(?:abstract\s+)?class", db, re.S)
    if not ann:
        hata.append("AppDatabase.kt: `@Database(...)` gövdesi ayrıştırılamadı.")
    elif sabit is not None:
        vm = re.search(r"\bversion\s*=\s*([A-Za-z0-9_]+)", ann.group(1))
        if not vm:
            hata.append("@Database içinde `version = ...` yok.")
        else:
            deger = vm.group(1)
            if deger.strip() != "DB_SCHEMA_VERSION":
                hata.append(
                    f"@Database(version = {deger}) — sabit kullanılmıyor. "
                    "`version = DB_SCHEMA_VERSION` olmalı; literal sayı, muhafızla "
                    "ayrışmanın tam olarak nasıl oluştuğudur."
                )

if guard is not None:
    gm = re.search(r"const\s+val\s+CODE_DB_VERSION\s*=\s*([^\n/]+)", guard)
    if not gm:
        hata.append("DatabaseVersionGuard.kt: `CODE_DB_VERSION` bulunamadı.")
    else:
        if gm.group(1).strip() != "DB_SCHEMA_VERSION":
            hata.append(
                f"DatabaseVersionGuard.CODE_DB_VERSION = {gm.group(1).strip()} — "
                "literal değer. `= DB_SCHEMA_VERSION` olmalı. "
                "(1.15-ozel.53 tam olarak bu satırda 15 yazdığı için kilitlendi.)"
            )
        else:
            bilgi.append("CODE_DB_VERSION → DB_SCHEMA_VERSION (tek kaynak) ✓")

# Commit edilmiş şema json'ları
if sabit is not None:
    if not SCHEMAS.is_dir():
        hata.append(
            "şema dizini yok: src/android/app/schemas/com.openminis.app.data.db.AppDatabase — "
            "`exportSchema = true` iken her sürüm için json committed olmalı."
        )
    else:
        surumler = sorted(
            int(p.stem) for p in SCHEMAS.glob("*.json") if p.stem.isdigit()
        )
        if not surumler:
            hata.append("hiç şema json'u yok.")
        else:
            bilgi.append(f"şema json'ları: {surumler}")
            eksik = [s for s in range(min(surumler), sabit + 1) if s not in surumler]
            if eksik:
                mesaj = (
                    f"şema json'u eksik: {eksik} (mevcut en yüksek: {surumler[-1]}, "
                    f"DB_SCHEMA_VERSION: {sabit}). `exportSchema = true` olduğu için "
                    "her sürümün json'u depoda olmalı; MigrationTestHelper onsuz "
                    "15→16 zincirini doğrulayamaz. Üretim: derleme sonrası "
                    "`src/android/app/schemas/` çıktısını commit et "
                    "(CI 'şema json artefaktı' adımı bunu tek dosyada toplar)."
                )
                (hata if STRICT else uyari).append(mesaj)

for b in bilgi:
    print(f"  · {b}")

for u in uyari:
    print(f"  ! {u}")

if hata:
    print("\nDB SÜRÜM KAPISI KIRMIZI:")
    for h in hata:
        print(f"  ✗ {h}")
    print(
        "\nAyrıntı: docs/ozel/HATA-DEFTERI.md → H-001 (veritabanı sürüm ayrışması)"
    )
    sys.exit(1)

print("\nDB sürüm kapısı yeşil.")
