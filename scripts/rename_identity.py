#!/usr/bin/env python3
"""Kimlik değişikliği: Minis Özel -> Minis X, applicationId -> com.veysel.minisx.

Neden namespace DEĞİŞMİYOR:
  `namespace = "com.openminis.app"` sınıf adı önekidir. AppIconRepository
  activity-alias CLASS adlarını bu önekten kuruyor (PACKAGE_NAME sabiti);
  namespace değişirse ikon alias'ları çalışmaz ve bu derlemede görünmez,
  çalışma anında görünür. Bu yüzden yalnız applicationId değişiyor —
  PackageManager'ın gördüğü kimlik. Sonuç: yeni uygulama, cihazda eskisinin
  yanına kurulur, veri paylaşmaz, çakışmaz.

Kapsam:
  - src/android/app/**  (kotlin, xml, strings)
  - scripts/**
  - .github/workflows/**
  - *.md kök dosyaları (README/FORK) — docs/ hariç, tarihsel kayıt bozulmasın
"""
from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]

OLD_PKG = "com.veysel.minis"
NEW_PKG = "com.veysel.minisx"
OLD_NAME = "Minis Özel"
NEW_NAME = "Minis X"

SKIP_DIRS = {".git", "build", ".gradle", "node_modules", ".idea", "__pycache__"}
TEXT_SUFFIX = {".kt", ".kts", ".xml", ".py", ".sh", ".yml", ".yaml", ".json",
               ".pro", ".properties", ".md", ".txt", ".gradle"}


def targeted(path: pathlib.Path) -> bool:
    if path.suffix not in TEXT_SUFFIX:
        return False
    parts = path.relative_to(ROOT).parts
    if any(p in SKIP_DIRS for p in parts):
        return False
    # docs/ altındaki tarihsel belgeler dokunulmaz: içlerinde "Minis Özel 11.1
    # yayınlandı" gibi geçmiş kayıtlar var; adı değiştirmek kaydı bozar.
    if parts and parts[0] == "docs":
        return False
    # Betiğin kendisi: sabitleri ezilmesin, ikinci çalıştırma sessizce
    # hiçbir şey yapmaz hâle gelmesin.
    if path.name == "rename_identity.py":
        return False
    return True


def main(apply: bool) -> int:
    hits_name = hits_pkg = 0
    touched = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or not targeted(path):
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        new = text
        if OLD_NAME in new:
            hits_name += new.count(OLD_NAME)
            new = new.replace(OLD_NAME, NEW_NAME)
        if OLD_PKG in new:
            hits_pkg += new.count(OLD_PKG)
            new = new.replace(OLD_PKG, NEW_PKG)
        if new != text:
            touched.append(path.relative_to(ROOT))
            if apply:
                path.write_text(new, encoding="utf-8")

    print(f"isim: {hits_name} yerde  '{OLD_NAME}' -> '{NEW_NAME}'")
    print(f"paket: {hits_pkg} yerde  '{OLD_PKG}' -> '{NEW_PKG}'")
    print(f"dosya: {len(touched)}")
    for t in sorted(map(str, touched)):
        print(f"  {t}")
    if not apply:
        print("\n(kuru çalıştırma — yazmak için --apply)")
    return 0


if __name__ == "__main__":
    sys.exit(main("--apply" in sys.argv))
