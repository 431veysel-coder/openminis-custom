"""Oda sözleşmesi (37131).

## Neden bu kapı

Altı oda yalnız sekme etiketiydi; amacı ve **tüketicisi** yazılı değildi. İki
kez aynı hata oldu:

1. `.22` — "İletişim" odası açıldı ama gösterilecek verisi yoktu. Veri modelde
   vardı (`Position.questions`), hiçbir yere yazılmıyordu.
2. `.46` — "Karşılaştırma" odası karar göstermiyor, yalnız duruş. Yazılı
   olmadığı için kullanıcı tablodan karar bekledi.

Bu kapı ikisini de yapısal olarak imkânsız kılar:
- Her `CouncilEvent` alt tipi **en az bir odaya** bağlı olmalı (yoksa veri
  üretilip atılıyor demektir).
- Her odanın `question` ve `claimsNot` alanı dolu olmalı (amaç ve sınır yazılı).

## Neden Kotlin ayrıştırmıyor

Bu kapı yalnız **ad listelerini** karşılaştırıyor: `RoomContract.EVENT_TYPES` ile
`RoomContract.ROOMS[].feeds`. İkisi de aynı dosyada, düz metin olarak
okunabiliyor. Kotlin ayrıştırmaya girişmek, kırılgan bir yarım-ayrıştırıcı
yazmak olurdu.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACT = ROOT / "src/android/app/src/main/java/com/openminis/app/ui/team/RoomContract.kt"
LOOP = ROOT / "src/android/app/src/main/java/com/openminis/app/agent/team/CouncilLoop.kt"


def check() -> list[str]:
    problems: list[str] = []
    if not CONTRACT.is_file():
        return [f"RoomContract yok: {CONTRACT.relative_to(ROOT)}"]

    src = CONTRACT.read_text(errors="ignore")

    # EVENT_TYPES listesi
    m = re.search(r"EVENT_TYPES[^=]*=\s*listOf\((.*?)\)", src, re.S)
    if not m:
        return ["EVENT_TYPES bulunamadi — kapi korlesir"]
    event_types = set(re.findall(r'"([^"]+)"', m.group(1)))

    # feeds listeleri: her Room blogundan
    feeds: set[str] = set()
    for fm in re.finditer(r"feeds\s*=\s*listOf\((.*?)\)", src, re.S):
        feeds |= set(re.findall(r'"([^"]+)"', fm.group(1)))

    orphans = sorted(event_types - feeds)
    if orphans:
        problems.append(
            "bu olaylar HICBIR odaya bagli degil (veri uretilip atiliyor): "
            + ", ".join(orphans)
        )

    # Her odada question + claimsNot dolu mu
    # Her oda: `id = Id.X` bulunan blok, bir sonraki `id = Id.` ya da liste sonu.
    # Onceki surum `\n    ),` ariyordu; ic ice `listOf(...)` yuzunden uc odayi
    # atliyordu (kapi kendi kodumda sahte kirmizi verdi). Indeks tabanli bu
    # ayirma ic ice parantezden etkilenmiyor: alan adlarini ariyor, yapiyi degil.
    id_positions = [(m.start(), m.group(1)) for m in re.finditer(r"id\s*=\s*Id\.(\w+)", src)]
    for i, (pos, room_id) in enumerate(id_positions):
        end = id_positions[i + 1][0] if i + 1 < len(id_positions) else len(src)
        block = src[pos:end]
        for field in ("question", "claimsNot"):
            fm2 = re.search(field + r'\s*=\s*"([^"]*)"', block)
            if not fm2 or not fm2.group(1).strip():
                problems.append(f"oda {room_id}: `{field}` bos — amac/sinir yazilmamis")

    # Loop'ta uretilen her alt tip EVENT_TYPES'ta mi? (yeni olay eklenip
    # sozlesmeye yazilmazsa yakalanir)
    if LOOP.is_file():
        emitted = set(
            "CouncilEvent." + n
            for n in re.findall(r"emit\(\s*CouncilEvent\.(\w+)", LOOP.read_text(errors="ignore"))
        )
        missing = sorted(emitted - event_types)
        if missing:
            problems.append(
                "CouncilLoop bu olaylari uretiyor ama EVENT_TYPES'ta yok: "
                + ", ".join(missing)
            )
    return problems


if __name__ == "__main__":
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    probs = check()
    if probs:
        print("ODA SOZLESMESI: KIRMIZI")
        for p in probs:
            print("  -", p)
        raise SystemExit(1)
    print("oda sozlesmesi: temiz")
