"""İç içe kaydırma çökmesi (37127).

## Neden bu kapı var

`TaskHallScreen` **görev listesi boşken değil, doluyken** çöküyordu:

    IllegalStateException: Vertically scrollable component was measured with an
    infinity maximum height constraints, which is disallowed.

Sebep: `SettingsScaffold` içeriği varsayılan olarak `verticalScroll` içine sarar;
ekranın gövdesine konan bir `LazyColumn` yükseklik sınırı almamıştı. Compose iki
dikey kaydırılabiliri ölçemez ve fırlatır.

## Neden kolay gizlendi

Boş liste dalı yalnız bir `SettingsRow` çiziyor → sınırsız yükseklik sorunu yok →
ekran "çalışıyor gibi" görünüyor. Hata yalnız **veri varken** ortaya çıkıyor.
Yani geliştirme sırasında boş bir cihazda hiç görünmez, kullanıcıda ilk görevle
birlikte patlar.

## Kural

Bir dosyada `SettingsScaffold(` ve `LazyColumn(` varsa, ikisinden biri olmalı:
  - `scrollable = false` (kaydırmayı scaffold'tan al), veya
  - LazyColumn'a sınırlı yükseklik: `heightIn(`, `height(`, `weight(`

İkisi de yoksa KIRMIZI. Yanlış pozitif riski düşük: kod tabanındaki diğer altı
kullanım da bu iki kalıptan birini izliyor.
"""

import re
from pathlib import Path


def check_file(path: Path, problems: list[str], root: Path) -> None:
    text = path.read_text(errors="ignore")
    if "SettingsScaffold(" not in text or "LazyColumn(" not in text:
        return

    # Scaffold'ta kaydırma kapatılmış mı?
    if re.search(r"scrollable\s*=\s*false", text):
        return

    # LazyColumn çağrılarına bak: her biri sınırlı yükseklik almalı.
    for m in re.finditer(r"LazyColumn\s*\(", text):
        # Çağrı gövdesini dengeli parantezle al (ilk argüman bloğu yeter).
        depth = 0
        i = m.end() - 1
        start = i
        while i < len(text):
            c = text[i]
            if c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        call = text[start:i + 1]
        # Modifier zincirini de gör: `LazyColumn(modifier = Modifier.weight(1f))`
        if re.search(r"heightIn\s*\(|\.height\s*\(|weight\s*\(", call):
            continue
        line = text[:m.start()].count("\n") + 1
        problems.append(
            f"{path.relative_to(root)}:{line}: LazyColumn sınırsız yükseklikte + "
            "SettingsScaffold kaydırılabilir — iç içe dikey kaydırma çöker "
            "(`scrollable = false` ya da `weight(1f)`/`heightIn(...)` ekle)"
        )
