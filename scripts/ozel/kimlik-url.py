#!/usr/bin/env python3
"""[H-007] Kimlik URL ve paket adı kapısı.

## Neden var

`1.15-ozel.12`'den `.53`'e kadar `UpdateChecker.RELEASES_URL` sabiti hâlâ
`https://github.com/OpenMinis/OpenMinis/releases` idi. Otomatik güncelleme
doğru çalışıyordu (`BuildConfig.RELEASE_REPO` fork'u gösteriyor), ama
güncelleme *başarısız* olduğunda gösterilen "manuel indir" düğmesi resmî
APK'ya götürüyordu — farklı imza, kurulamıyor. Kullanıcı için "güncelleme
bozuk"; teknik olarak "yanlış depo".

`check_identity.sh` bunu görmedi çünkü yalnız `applicationId`, `app_name` ve
`RELEASE_REPO` sabitlerine bakıyor; Kotlin kaynağının *içindeki* serbest
metin URL'lerini taramıyor.

Bu betik iki şeyi kontrol eder:

1. `src/android/app/src/main/**/*.kt` içinde `github.com/OpenMinis` geçen bir
   string literal varsa kırmızı — depo adı tek yerde (`BuildConfig.RELEASE_REPO`)
   yazılır. Meşru istisnalar dosya başına `kimlik-url: izinli` yorumuyla
   işaretlenir ve gerekçesi yazılır.
2. `applicationId`, `docs/ozel/KIMLIK.md` içinde kayıtlı yayın kimliğiyle
   aynı olmalı. Kimlik değişikliği bir sürüm notu ve veri taşıma planı
   gerektirir (H-009).

Çıkış: 0 = temiz, 1 = ihlal.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "src/android/app/src/main"
GRADLE = ROOT / "src/android/app/build.gradle.kts"
KIMLIK = ROOT / "docs/ozel/KIMLIK.md"

hatalar = []
uyarilar = []

# ---- 1. Kaynak içinde gömülü upstream URL'i -------------------------------
# Yalnız GÜNCELLEME/İNDİRME URL'leri. `HTTP-Referer`, issue linki,
# `MinisSkills` gibi adresler meşru: onlar kimlik değil, köken bilgisi.
desen = re.compile(r'"[^"\n]*(github\.com/OpenMinis[^"\n]*(releases|download))[^"\n]*"')
for p in sorted(SRC.rglob("*.kt")):
    try:
        metin = p.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        continue
    if "kimlik-url: izinli" in metin:
        continue
    for i, satir in enumerate(metin.splitlines(), 1):
        if satir.lstrip().startswith("//") or satir.lstrip().startswith("*"):
            continue  # yorum serbest, kod değil
        if desen.search(satir):
            hatalar.append(
                f"{p.relative_to(ROOT).as_posix()}:{i} — gömülü upstream URL: "
                f"{satir.strip()[:110]}\n      → `BuildConfig.RELEASE_REPO` kullan "
                f"(bkz. H-007). Bilinçli istisnaysa dosyaya "
                f"`kimlik-url: izinli — gerekçe` yorumu ekle."
            )

# ---- 2. applicationId ↔ kayıtlı kimlik -----------------------------------
if GRADLE.exists() and KIMLIK.exists():
    g = GRADLE.read_text(encoding="utf-8", errors="ignore")
    m = re.search(r'applicationId\s*=\s*"([^"]+)"', g)
    k = KIMLIK.read_text(encoding="utf-8", errors="ignore")
    km = re.search(r"\|\s*applicationId\s*\|\s*`([a-z][a-z0-9_.]+)`", k)
    if m and km and m.group(1) != km.group(1):
        hatalar.append(
            f"applicationId = {m.group(1)} ama KIMLIK.md kaydı {km.group(1)}. "
            "Kimlik değişikliği güncelleme olarak KURULAMAZ (ayrı uygulama olur, "
            "veri taşınmaz) — bkz. H-009."
        )
    elif m and not km:
        uyarilar.append("KIMLIK.md var ama applicationId satırı okunamadı.")

if hatalar:
    print("KİMLİK KAPISI KIRMIZI:")
    for h in hatalar:
        print(f"  ✗ {h}")
    sys.exit(1)

for u in uyarilar:
    print(f"  ! {u}")
print("Kimlik kapısı yeşil ✓")
