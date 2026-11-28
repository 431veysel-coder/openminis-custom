"""Eksik import denetimi — derleyicinin "Unresolved reference" sınıfı.

## Neden var (ölçüldü)

`1.15-ozel.57` ve `.58` turlarında **üç CI turu** yalnız eksik import yüzünden
boşa gitti:

  · `.57`  `StopConfirmDialog` (silme aralığı hatası, ayrı sınıf)
  · `.58`  `Box` / `Spacer` → `Unresolved reference 'Box'`
  · `.58b` `AndroidManifest`/`formatElapsed` benzeri küçük eksikler

Yerel kapılar `kotlin-hizli` **kullanılmayan** importu görüyordu, **eksik**
importu görmüyordu. Eksik import bir sözdizimi hatası değil, bir **ad
çözümleme** hatası; ayrıştırıcı olmadan da yakalanabilir: kullanılan simge
tabloda tanımlıysa ve dosyada tanımı/importu yoksa, derleyici de bulamaz.

## Ne yapar

Sabit bir tablo: yaygın Compose/Android simgesi → beklenen import yolu.
Tablo bilerek **küçük ve kesin**: yalnız bu projede gerçekten kullanılan ve
adı başka bir yerde tanımlı olmayan simgeler. Yanlış pozitif, kapıyı susturur.

Muafiyet: dosya `// kapi-import-muaf:` satırı içeriyorsa atlanır.
"""

from __future__ import annotations

import re
from pathlib import Path

# simge -> zorunlu import (import satırı bu yolu içermeli)
REQUIRED: dict[str, str] = {
    # layout
    "Box": "androidx.compose.foundation.layout.Box",
    "Spacer": "androidx.compose.foundation.layout.Spacer",
    "Surface": "androidx.compose.material3.Surface",
    "PaddingValues": "androidx.compose.foundation.layout.PaddingValues",
    "BorderStroke": "androidx.compose.foundation.BorderStroke",
    "CircleShape": "androidx.compose.foundation.shape.CircleShape",
    "BoxWithConstraints": "androidx.compose.foundation.layout.BoxWithConstraints",
    # lazy/grid
    "LazyVerticalGrid": "androidx.compose.foundation.lazy.grid.LazyVerticalGrid",
    "GridCells": "androidx.compose.foundation.lazy.grid.GridCells",
    "GridItemSpan": "androidx.compose.foundation.lazy.grid.GridItemSpan",
    # graphics
    "alpha": "androidx.compose.ui.draw.alpha",
    "Color": "androidx.compose.ui.graphics.Color",
    "Alignment": "androidx.compose.ui.Alignment",
}

# `X(` ya da `X {` ya da `X.` biçiminde geçiyorsa kullanılıyor demektir.
def _used(text: str, name: str) -> bool:
    return re.search(r"(?<![\w.])" + re.escape(name) + r"\s*[({.]", text) is not None


def _imported(text: str, fq: str) -> bool:
    leaf = fq.rsplit(".", 1)[-1]
    for line in text.splitlines():
        s = line.strip()
        if not s.startswith("import "):
            continue
        path = s[len("import "):].strip()
        if path == fq or path.startswith(fq + " as "):
            return True
        # aynı paketten yıldız importu da yeter
        if path.endswith(".*") and fq.startswith(path[:-2] + "."):
            return True
        # `X.Y.Z` importu `Z` diye değil, dosyada tanımlı olabilir — aşağıdaki
        # "dosyada tanımlı mı" kontrolü bunu karşılar.
        if path.rsplit(".", 1)[-1] == leaf and path.startswith("androidx.compose"):
            return True
    return False


def _declared(text: str, name: str) -> bool:
    """Simge bu dosyada tanımlı mı (yerel class/fun/val)?"""
    pats = [
        rf"\b(?:fun|class|object|val|var|interface)\s+{re.escape(name)}\b",
        rf"\btypealias\s+{re.escape(name)}\b",
    ]
    return any(re.search(p, text) for p in pats)


def check_file(path: Path, problems: list[str], root: Path) -> None:
    text = path.read_text(errors="ignore")
    if "kapi-import-muaf:" in text:
        return
    # Yalnız Compose UI dosyaları: `androidx.compose` importu olanlar.
    if "import androidx.compose" not in text:
        return
    for name, fq in REQUIRED.items():
        if not _used(text, name):
            continue
        if _declared(text, name):
            continue
        if _imported(text, fq):
            continue
        try:
            rel = path.relative_to(root)
        except ValueError:
            rel = path
        problems.append(f"{rel}: eksik import {name} ({fq})")
