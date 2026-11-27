#!/usr/bin/env python3
"""[H-005] Hata defterinin kendisini denetleyen kapı.

## Neden var

`docs/ozel/HATA-DEFTERI.md` bir kayıt tutuyor ve her kaydın bir `Kapı:` satırı
olması gerekiyor — *bu hata bir daha olursa hangi otomatik kontrol yakalar?*
Defter kendi kendini denetlemezse iki şekilde çürür:

  1. `Kapı:` satırı bir zamanlar var olan ama sonra silinen bir betiği
     gösterir. Kayıt "kapılı" görünür, gerçekte kapısız kalır.
  2. Bir kod yorumu `[BaziTest]` diye atıf yapar, o test hiç yazılmamıştır.
     H-001'in (veritabanı sürümü kilitlenmesi) doğrudan sebebi tam buydu:
     "AppDatabaseVersionTest doğrular" diyen bir yorum, olmayan bir test.

## Ne denetlenir

- Her `### H-NNN` kaydında zorunlu alanlar: Sürüm, Belirti, Kök neden,
  Kanıt, Kapı, Kural.
- `Kapı:` satırında geçen her yol (`scripts/...`, `.github/...`, `*.kt`,
  `*.yml`) gerçekten var mı. `KAPSIZ` işaretli kayıtlar muaf.
- Kotlin kaynaklarındaki `[XxxTest]` biçimli köşeli atıfların karşılığı olan
  `XxxTest.kt` var mı.
- Kayıt numaraları benzersiz mi ve artan mı.

Çıkış: 0 = defter tutarlı, 1 = tutarsız.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFTER = ROOT / "docs/ozel/HATA-DEFTERI.md"
KOD = ROOT / "src/android/app/src"

ZORUNLU = ["Sürüm", "Belirti", "Kök neden", "Kanıt", "Kapı", "Kural"]

if not DEFTER.exists():
    print(f"HATA: defter yok: {DEFTER.relative_to(ROOT)}")
    sys.exit(1)

metin = DEFTER.read_text(encoding="utf-8")
bloklar = re.split(r"^##+ (H-\d{3}) · ", metin, flags=re.M)

if len(bloklar) < 3:
    print("HATA: defterde hiç kayıt bulunamadı (### H-NNN biçimi bekleniyor).")
    sys.exit(1)

hatalar = []
kayitlar = []
numaralar = []

for i in range(1, len(bloklar), 2):
    no = bloklar[i]
    govde = bloklar[i + 1]
    numaralar.append(int(no[2:]))

    eksik = [a for a in ZORUNLU if f"**{a}" not in govde]
    if eksik:
        hatalar.append(f"{no}: eksik alan(lar): {', '.join(eksik)}")

    kapsiz = "KAPSIZ" in govde
    # Kapı satırı: "- **Kapı:** ..."
    m = re.search(r"\*\*Kapı:\*\*(.+)", govde)
    if not m:
        continue
    kapi_metni = m.group(1)

    if kapsiz:
        continue

    # Satırdaki `...` içindeki yollar
    yollar = re.findall(r"`([^`]+)`", kapi_metni)
    if not yollar:
        hatalar.append(f"{no}: Kapı satırında tek bir yol bile yok — neyi kontrol ettiği belli değil.")
        continue

    bulunan = 0
    for y in yollar:
        y = y.split("(")[0].strip()
        if not re.search(r"(\.|/)", y):
            continue  # sınıf adı (AppDatabaseVersionTest) — ayrı denetim
        if "*" in y:
            if list(ROOT.glob(y)):
                bulunan += 1
            else:
                hatalar.append(f"{no}: kapı yolu yok: {y}")
        elif (ROOT / y).exists():
            bulunan += 1
        else:
            hatalar.append(f"{no}: kapı yolu yok: {y} (silinmiş betik → kayıt kapısız)")
    if bulunan == 0 and yollar:
        hatalar.append(f"{no}: hiçbir kapı yolu doğrulanamadı.")

yineleme = {n for n in numaralar if numaralar.count(n) > 1}
if yineleme:
    hatalar.append(f"yinelenen kayıt numarası: {sorted(yineleme)}")
if numaralar != sorted(numaralar):
    hatalar.append("kayıtlar numara sırasına göre değil.")

# [XxxTest] atıfları ↔ gerçek dosyalar
if KOD.is_dir():
    dosya_adlari = {p.stem for p in KOD.rglob("*.kt")}
    atiflar = {}
    for p in KOD.rglob("*.kt"):
        try:
            s = p.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for ad in re.findall(r"\[([A-Z][A-Za-z0-9]*Test)\]", s):
            atiflar.setdefault(ad, set()).add(p.relative_to(ROOT).as_posix())
    for ad, yerler in sorted(atiflar.items()):
        if not any(d == ad or d.startswith(ad) for d in dosya_adlari):
            hatalar.append(
                f"[{ad}] diye atıf var ama {ad}.kt yok — atıf yapan: "
                + ", ".join(sorted(yerler)[:3])
            )

print(f"kayıt: {len(numaralar)} · denetlenen atıf: {len(atiflar) if KOD.is_dir() else 0}")

if hatalar:
    print("\nHATA DEFTERİ KAPISI KIRMIZI:")
    for h in hatalar:
        print(f"  ✗ {h}")
    sys.exit(1)

print("Hata defteri tutarlı ✓")
