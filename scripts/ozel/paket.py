#!/usr/bin/env python3
"""
[37098] Paket — her şeyi **tek düzenli klasörde** toplar.

NEDEN VAR
    Çalışma tek bir dalda birikiyor ve yayın tek seferde yapılacak. Kullanıcı
    "her şey tek klasörde, içinde alt klasörler olabilir, düzenli olsun" dedi.
    Bu betik o klasörü üretir: kod, belge, araç, arşiv ve damga — hangi dosyanın
    ne olduğu bakınca anlaşılır.

YAPI
    minis-x-paket-<damga>/
      00-BASLA.md              okuma sırası, tek sayfalık özet
      01-DAMGA/                tarih-saat, commit, push durumu, kapı sonuçları
      02-KAYNAK/               çalışma ağacının tamamı (zip'siz, açık)
      03-BELGE/                yalnız belgeler: tasarım, denetim, sürüm notu
      04-ARAC/                 denetim/kurulum betikleri + nasıl koşulur
      05-GECMIS/               git bundle (klonlanabilir)
      06-KANIT/                kapı çıktıları (log dosyaları)

    Aynı klasörün `.zip`'i de yazılır (paylaşmak için), ama **klasör asıl
    çıktıdır**: zip'i açmadan da her şey okunur.

KULLANIM
    python3 scripts/ozel/paket.py                    # üret
    python3 scripts/ozel/paket.py --no-zip           # yalnız klasör
    python3 scripts/ozel/paket.py --out /yol
"""

from __future__ import annotations

import argparse
import hashlib
import re
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "src/android/app/build.gradle.kts"
DEFAULT_OUT = Path("/var/minis/shared/minis-x-paket")

EXCLUDE_DIRS = {
    ".git", "build", ".gradle", ".idea", "DerivedData", "node_modules",
    "__pycache__", ".kotlin", "intermediates", "transforms",
}

# Belge olarak kopyalanacaklar (03-BELGE).
DOC_FILES = [
    "docs/ozel/TERMUX-ENTEGRASYONU.md",
    "docs/ozel/DENETIM-37096.md",
    "docs/ozel/DEGISIKLIK-GUNLUGU-11-16.md",
    "docs/ozel/SIRADAKI-SURUM.md",
    "docs/ozel/ENTEGRASYON-DURUMU.md",
    "FORK.md",
    "SECURITY.md",
    "CONTRIBUTING.md",
    "PRIVACY.md",
    "BUILDING.md",
]

# Araç olarak kopyalanacaklar (04-ARAC).
TOOL_FILES = [
    "scripts/ozel/risk-corpus.py",
    "scripts/ozel/kotlin-denetim.py",
    "scripts/ozel/tag-denetim.py",
    "scripts/ozel/snapshot.py",
    "scripts/ozel/paket.py",
    "scripts/integration_audit.py",
    "scripts/slice_gate.py",
    "scripts/slices.json",
    ".github/workflows/ozel-guard.yml",
]


def run(*args: str) -> str:
    return subprocess.run(
        list(args), cwd=ROOT, capture_output=True, text=True, check=False
    ).stdout.strip()


def version() -> tuple[str, int]:
    t = GRADLE.read_text(encoding="utf-8")
    n = re.search(r'versionName\s*=\s*"([^"]+)"', t)
    c = re.search(r"versionCode\s*=\s*(\d+)", t)
    return (n.group(1) if n else "?"), (int(c.group(1)) if c else 0)


def push_state() -> dict:
    branch = run("git", "rev-parse", "--abbrev-ref", "HEAD")
    upstream = run("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
    if not upstream:
        return {"dal": branch, "uzak": "—", "durum": "PUSH EDİLMEDİ", "ileride": 0, "geride": 0}
    ahead = int(run("git", "rev-list", "--count", f"{upstream}..HEAD") or 0)
    behind = int(run("git", "rev-list", "--count", f"HEAD..{upstream}") or 0)
    dirty = bool(run("git", "status", "--porcelain"))
    durum = "PUSH EDİLDİ" if ahead == 0 and not dirty else (
        "UZAKLA AYNI (yerel değişiklik var)" if ahead == 0 else "PUSH EDİLMEDİ"
    )
    return {"dal": branch, "uzak": upstream, "durum": durum, "ileride": ahead, "geride": behind}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def copy_tree(dst: Path) -> tuple[int, int]:
    count = 0
    total = 0
    for p in sorted(ROOT.rglob("*")):
        if not p.is_file():
            continue
        rel = p.relative_to(ROOT)
        if any(part in EXCLUDE_DIRS for part in rel.parts):
            continue
        if rel.suffix in (".apk", ".aab", ".dex"):
            continue
        out = dst / rel
        out.parent.mkdir(parents=True, exist_ok=True)
        try:
            shutil.copy2(p, out)
            count += 1
            total += p.stat().st_size
        except OSError:
            continue
    return count, total


def gate(name: str, cmd: list[str]) -> dict:
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, check=False)
    out = (p.stdout or "") + (p.stderr or "")
    # `tag-denetim.py` çıkış kodu 0 döner (yayın kararı insanın), ama
    # uyuşmazlık varsa bunu **YEŞİL** diye göstermek yalan olur. Kapı durumu
    # çıktıdan okunur; "0 döndü" tek başına sağlık işareti değil.
    mismatch = "yanlış commit" in out or "SAPMA" in out
    durum = "KIRMIZI" if p.returncode != 0 else ("UYARI" if mismatch else "YEŞİL")
    return {
        "ad": name,
        "kod": p.returncode,
        "durum": durum,
        "cikti": out,
    }


def write_start(pkg: Path, ver: tuple[str, int], push: dict, gates: list[dict],
                commit: str, subject: str, counts: tuple[int, int]) -> None:
    name, code = ver
    gate_rows = "\n".join(
        f"| {g['ad']} | **{g['durum']}** | `{(g['cikti'].strip().splitlines() or [''])[-1][:90]}` |"
        for g in gates
    )
    text = f"""# Minis X — paket `{name}` / {code}

Bu klasör bir çalışma anlık görüntüsüdür: kaynak, belge, araç ve kanıt bir arada.
Zip açmadan da okunur.

| | |
|---|---|
| Sürüm | `{name}` / versionCode **{code}** |
| Commit | `{commit[:12]}` — {subject} |
| Dal | `{push['dal']}` |
| **Push durumu** | **{push['durum']}** |
| Uzak | `{push['uzak']}` |
| Zaman | {time.strftime('%Y-%m-%d %H:%M:%S %z')} |
| Kaynak | {counts[0]} dosya, {counts[1] / 1048576:.1f} MB |

## Kapılar

| Kapı | Durum | Son satır |
|---|---|---|
{gate_rows}

## Klasörler — okuma sırası

1. **`01-DAMGA/`** — bu paketin kimliği: `DAMGA.md` (tarih, commit, push, kapılar).
2. **`03-BELGE/`** — ne yapıldı, neden, sırada ne var:
   - `TERMUX-ENTEGRASYONU.md` — **asıl tasarım**: mimari, ölçümler, güvenlik,
     ön/arka yüz, T1–T10 test matrisi, reddedilen seçenekler
   - `SIRADAKI-SURUM.md` — P1→P5 fazları, açık işler
   - `DENETIM-37096.md` — `.15` incelemesinde bulunan 10 bulgu ve kanıtları
   - `DEGISIKLIK-GUNLUGU-11-16.md` — sürüm sürüm ne değişti
3. **`04-ARAC/`** — denetim ve paketleme betikleri (`KULLANIM.md` ile).
4. **`06-KANIT/`** — kapıların ham çıktısı. "Geçti" demek yetmez, çıktı durur.
5. **`02-KAYNAK/`** — çalışma ağacının tamamı.
6. **`05-GECMIS/`** — git bundle: `git clone <dosya>.bundle minis-x-full`.

## Hızlı doğrulama

```bash
cd 02-KAYNAK
python3 scripts/ozel/risk-corpus.py --fail-on-mismatch   # 57 komut, 0 sapma
python3 scripts/ozel/kotlin-denetim.py --no-warn         # 1 646 dosya
python3 scripts/integration_audit.py                     # 10/11 dilim
python3 scripts/ozel/tag-denetim.py                      # etiket ↔ sürüm
```

## Bu pakette NE VAR / NE YOK

**Var:** Termux katmanı P1+P2 kodu (`TermuxLayer`, `TermuxInstaller`,
`TermuxPrompt`), PRoot bağları, `USER_TREES` koruması, risk kuralları + korpus,
sağlık köprüsü, onay kutusu, çeviri önbelleği, 4 denetim aracı, 2 CI iş akışı.

**Yok (dürüstçe):** derlenmiş APK. Bu cihazda JVM başlamıyor
(*"Failed to mark memory page as executable"*), yani `gradlew` koşmuyor.
Derleme CI'da; `apk.yml`'deki `needs: tests` kapısı testler kırmızıysa sürüm
çıkarmıyor.
"""
    (pkg / "00-BASLA.md").write_text(text, encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description="Minis X paketi — tek düzenli klasör")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--no-zip", action="store_true")
    ap.add_argument("--no-gates", action="store_true")
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    name, code = version()
    pkg = out / f"minis-x-paket-{name}-{stamp}"
    pkg.mkdir(parents=True, exist_ok=True)
    print(f"paket: {pkg.name}")

    # 02 KAYNAK
    print("  02-KAYNAK kopyalanıyor…")
    counts = copy_tree(pkg / "02-KAYNAK")
    print(f"    {counts[0]} dosya, {counts[1] / 1048576:.1f} MB")

    # 03 BELGE
    belge = pkg / "03-BELGE"
    belge.mkdir(exist_ok=True)
    for rel in DOC_FILES:
        src = ROOT / rel
        if src.exists():
            shutil.copy2(src, belge / Path(rel).name)
    # sürüm notları
    for p in sorted((ROOT / "docs/ozel").glob("SURUM-NOTU-*.md")):
        shutil.copy2(p, belge / p.name)
    (belge / "DIZIN.md").write_text(
        "# Belgeler\n\n"
        "| Dosya | Ne |\n|---|---|\n"
        "| `TERMUX-ENTEGRASYONU.md` | Termux katmanı tasarımı: mimari, ölçüm, güvenlik, T1–T10 |\n"
        "| `SIRADAKI-SURUM.md` | Fazlar P1→P5 ve açık işler |\n"
        "| `DENETIM-37096.md` | `.15` incelemesi: 10 bulgu, kanıtlarıyla |\n"
        "| `DEGISIKLIK-GUNLUGU-11-16.md` | `.11.3.1` → bugün |\n"
        "| `ENTEGRASYON-DURUMU.md` | Hangi dilim üretimden çağrılıyor (10/11) |\n"
        "| `SURUM-NOTU-*.md` | Release gövdeleri (çalıştırınca metin basar) |\n"
        "| `FORK.md` | Çatalın neden var olduğu |\n"
        "| `SECURITY.md` · `PRIVACY.md` · `CONTRIBUTING.md` · `BUILDING.md` | Depo politikaları |\n",
        encoding="utf-8",
    )

    # 04 ARAC
    arac = pkg / "04-ARAC"
    arac.mkdir(exist_ok=True)
    for rel in TOOL_FILES:
        src = ROOT / rel
        if src.exists():
            dst = arac / Path(rel).name
            shutil.copy2(src, dst)
            dst.chmod(0o755)
    (arac / "KULLANIM.md").write_text(
        """# Araçlar

Hepsi `02-KAYNAK/` içinden çalıştırılır (yollar repoya göre).

| Betik | Ne yapar | Çıkış kodu |
|---|---|---|
| `risk-corpus.py` | `RiskRules.kt` + `RiskClassifier.kt`'yi okur, 57 komutun kararını basar | `--fail-on-mismatch` ile 1 |
| `kotlin-denetim.py` | Denge, çift imza, kapanmamış `\"\"\"`, kullanılmayan import | hata varsa 1 |
| `tag-denetim.py` | Her `v*` etiketini çözer, o commit'in sürümüyle karşılaştırır | `--strict` ile 1 |
| `snapshot.py` | zip + git bundle + `DAMGA.md` + `DIZIN.md` | 0 |
| `paket.py` | bu klasörü üretir | 0 |
| `integration_audit.py` | Hangi dilim üretim kodundan çağrılıyor | 0 |
| `slice_gate.py` | Dilimleri ayrı ayrı derler (Gradle'sız, kotlinc ile) | kırmızı dilimde 1 |

## Neden bu araçlar var

Derleme bu telefonda koşamıyor (JVM: *"Failed to mark memory page as
executable"*). Bu dördü, push'tan önce yakalanabilecek hataları saniyeler
içinde yakalar. Hiçbiri derleyicinin yerine geçmez — `ozel-guard.yml` bunu CI'da
her push'ta koşuyor.
""",
        encoding="utf-8",
    )

    # 06 KANIT
    kanit = pkg / "06-KANIT"
    kanit.mkdir(exist_ok=True)
    gates: list[dict] = []
    if not args.no_gates:
        checks = [
            ("risk-korpusu", [sys.executable, "scripts/ozel/risk-corpus.py", "--fail-on-mismatch"]),
            ("yapisal-denetim", [sys.executable, "scripts/ozel/kotlin-denetim.py", "--no-warn"]),
            ("entegrasyon", [sys.executable, "scripts/integration_audit.py"]),
            ("etiket", [sys.executable, "scripts/ozel/tag-denetim.py"]),
        ]
        for ad, cmd in checks:
            g = gate(ad, cmd)
            gates.append(g)
            (kanit / f"{ad}.txt").write_text(
                f"$ {' '.join(cmd)}\n$ exit={g['kod']}\n\n{g['cikti']}", encoding="utf-8"
            )
            print(f"  kapı {ad}: {g['durum']}")

    # 01 DAMGA
    damga = pkg / "01-DAMGA"
    damga.mkdir(exist_ok=True)
    commit = run("git", "rev-parse", "HEAD")
    subject = run("git", "log", "-1", "--format=%s")
    push = push_state()
    write_start(pkg, (name, code), push, gates, commit, subject, counts)
    (damga / "DAMGA.md").write_text(
        f"""# DAMGA — `{name}` / {code}

| | |
|---|---|
| Zaman | {time.strftime('%Y-%m-%d %H:%M:%S %z')} (yerel) · {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} (UTC) |
| Damga | `{stamp}` |
| Sürüm | `{name}` / versionCode **{code}** |
| Dal | `{push['dal']}` |
| Commit | `{commit}` |
| Konu | {subject} |
| **Push durumu** | **{push['durum']}** ({push['ileride']} ileride, {push['geride']} geride) |
| Uzak | `{push['uzak']}` |
| Değişiklik | {run('git', 'status', '--porcelain').count(chr(10)) + (1 if run('git', 'status', '--porcelain') else 0)} dosya |
""",
        encoding="utf-8",
    )

    # 05 GECMIS
    bundle = pkg / "05-GECMIS" / f"minis-x-{name}-{stamp}.bundle"
    bundle.parent.mkdir(exist_ok=True)
    r = subprocess.run(["git", "bundle", "create", str(bundle), "--all"],
                       cwd=ROOT, capture_output=True, text=True, check=False)
    if r.returncode == 0:
        print(f"  bundle: {bundle.stat().st_size / 1048576:.1f} MB")
    else:
        bundle.unlink(missing_ok=True)
        print(f"  bundle ATLANDI: {r.stderr.strip()[:100]}")

    # ZIP
    if not args.no_zip:
        zpath = out / f"{pkg.name}.zip"
        print("  zip yazılıyor…")
        with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
            for p in sorted(pkg.rglob("*")):
                if p.is_file():
                    z.write(p, (Path(pkg.name) / p.relative_to(pkg)).as_posix())
        print(f"  zip: {zpath.stat().st_size / 1048576:.1f} MB · sha256 {sha256(zpath)[:16]}…")

    print(f"\ntamam: {pkg}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
