#!/usr/bin/env python3
"""
[37096] Yayın etiketi denetimi — "bu etiket gerçekten o sürümü mü gösteriyor?"

NEDEN VAR
    2026-11-20'de ölçüldü:

        v1.15-ozel.13  ->  ffeb948  (release: 1.15-ozel.11.2, versionCode 37029)
        v1.15-ozel.14  ->  ffeb948  (aynı)
        v1.15-ozel.15  ->  ffeb948  (aynı)

    Yani üç sürüm etiketinin üçü de **altı sürüm eski** bir commit'i gösteriyor.
    Uygulama güncellemeyi bu etiketlerden okuyor (`RELEASE_REPO` bu çatala
    yönlendirilmiş), dolayısıyla bu, kendi kendini güncellemenin sessizce
    yanlış çalışması demek: ya "yeni sürüm yok" der ya da 37029'u indirmeye
    çalışır — Android düşük versionCode'u reddeder ve kullanıcı açıklamasız bir
    "Uygulama yüklenemedi" görür.

    ### [37099] KÖK SEBEP BULUNDU

    Etiketler `gh release create --target` ile yanlış ref'e basılmamış: etiket
    **hiç hedeflenmemiş**. `softprops/action-gh-release` çağrısında
    `target_commitish` yoktu, o da etiketi deponun **varsayılan dalına**
    (`main`) yaratır. Bizim `main` upstream'de takılı (`ffeb948` = 11.2), yani
    BÜTÜN yayın etiketleri oraya işaret etti — altı tanesi, `.11.2`'den
    `.18`'e kadar.

    Bu yüzden "etiketleri sil, yeniden hedefle" planı yanlış tedaviydi: silinen
    etiket bir sonraki yayında yine `main`'e basılırdı. Gerçek düzeltme
    `.github/workflows/apk.yml` içinde `target_commitish: ${{ github.sha }}`;
    bu dosya yalnız **ölçer**.

    Release NESNESİ doğru APK'yı taşıyor (sha256 ve derleme kimliği uyumlu);
    yanlış olan yalnız etiketin gösterdiği commit.

NE YAPAR
    Her `v*` etiketini çözer, o commit'teki `build.gradle.kts` sürümünü ve
    etiket adındaki sürümü karşılaştırır. Uyuşmazlık varsa **düzeltme komutunu**
    yazar (çalıştırmaz — yayın geri dönüşsüz, kararı insan verir).

KULLANIM
    python3 scripts/ozel/tag-denetim.py            # tablo
    python3 scripts/ozel/tag-denetim.py --fix-plan # düzeltme komutları
    python3 scripts/ozel/tag-denetim.py --strict   # uyuşmazlıkta çıkış kodu 1
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = "src/android/app/build.gradle.kts"


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=ROOT, capture_output=True, text=True, check=False
    ).stdout.strip()


def tags() -> list[str]:
    out = git("tag", "--list", "v*")
    return sorted(out.splitlines()) if out else []


def peel(tag: str) -> str:
    return git("rev-list", "-n", "1", tag)


def version_at(commit: str) -> tuple[str, int] | None:
    blob = git("show", f"{commit}:{GRADLE}")
    if not blob:
        return None
    code = re.search(r"versionCode\s*=\s*(\d+)", blob)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', blob)
    if not code or not name:
        return None
    return name.group(1), int(code.group(1))


def tag_version(tag: str) -> str:
    """`v1.15-ozel.15` -> `1.15-ozel.15`."""
    return tag[1:] if tag.startswith("v") else tag


def main() -> int:
    ap = argparse.ArgumentParser(description="Yayın etiketi ↔ sürüm denetimi")
    ap.add_argument("--fix-plan", action="store_true", help="düzeltme komutlarını yaz")
    ap.add_argument("--strict", action="store_true", help="uyuşmazlıkta 1 dön")
    args = ap.parse_args()

    rows = []
    bad = []
    for tag in tags():
        commit = peel(tag)
        ver = version_at(commit)
        subject = git("log", "-1", "--format=%s", commit)
        expected = tag_version(tag)
        if ver is None:
            rows.append((tag, commit[:7], "?", expected, subject, "OKUNAMADI"))
            bad.append(tag)
            continue
        name, code = ver
        ok = name == expected
        rows.append((tag, commit[:7], f"{name}/{code}", expected, subject[:46], "OK" if ok else "SAPMA"))
        if not ok:
            bad.append(tag)

    width = max((len(r[0]) for r in rows), default=10)
    print(f"{'ETİKET':<{width}}  {'COMMIT':7}  {'COMMIT SÜRÜMÜ':16} {'ETİKET SÜRÜMÜ':16} DURUM")
    print("-" * (width + 70))
    for tag, commit, ver, expected, subject, state in rows:
        print(f"{tag:<{width}}  {commit:7}  {ver:16} {expected:16} {state}")

    print()
    if bad:
        print(f"{len(bad)} etiket yanlış commit'i gösteriyor:")
        for tag in bad:
            print(f"  · {tag}")
        if args.fix_plan:
            print("\nDÜZELTME PLANI (çalıştırılmadı — yayın geri dönüşsüz, karar insanın):")
            print("  1) Doğru commit'i bul:  git log --all --oneline --grep 'ozel.15'")
            print("  2) Etiketi yeniden hedefle (yalnız uzak etiket silinebilir;")
            print("     yerel etiket zaten yanlış, önce silinir):")
            for tag in bad:
                print(f"       git tag -d {tag}")
                print(f"       git push origin :refs/tags/{tag}")
                print(f"       git tag {tag} <DOĞRU_COMMIT>")
                print(f"       git push origin {tag}")
            print("  3) Release nesnesi APK'yı taşıyor; etiket değişince release")
            print("     sayfası doğru commit'i gösterir, indirme bağlantısı değişmez.")
            print("  4) Doğrulama: bu betiği yeniden koş.")
        if args.strict:
            return 1
    else:
        print("Bütün etiketler doğru commit'i gösteriyor.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
