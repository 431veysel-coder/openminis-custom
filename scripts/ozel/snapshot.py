#!/usr/bin/env python3
"""
[37098] Anlık görüntü — "ne zaman, ne durumda, push edildi mi?"

NEDEN VAR
    Bu depoda çalışma tek bir dalda birikiyor ve yayın tek seferde yapılacak.
    Arada "şu an elimizde ne var, hangi commit, push edildi mi" sorusunun tek
    cevabı olmalı. Git bunu söyler ama **dosya olarak** söylemez; kullanıcı
    arşivi açtığında hangi sürüm olduğunu görmeli.

NE ÜRETİR
    <out>/minis-x-<sürüm>-<damga>.zip      çalışma ağacı (build çıktısı hariç)
    <out>/minis-x-<sürüm>-<damga>.bundle   git geçmişi (tek dosya, klonlanabilir)
    <out>/minis-x-<sürüm>-<damga>/DAMGA.md bu anlık görüntünün kimliği
    <out>/DIZIN.md                         bütün anlık görüntülerin dizini

DAMGA İÇERİĞİ
    zaman (yerel + UTC) · dal · commit · commit konusu · değişiklik var mı ·
    push durumu (kaç commit ileride/geride) · versionCode/versionName ·
    kapı sonuçları · dosya sayısı · sha256 · boyut

KULLANIM
    python3 scripts/ozel/snapshot.py                  # yeni anlık görüntü
    python3 scripts/ozel/snapshot.py --note "termux planı eklendi"
    python3 scripts/ozel/snapshot.py --list           # dizini göster
    python3 scripts/ozel/snapshot.py --no-zip         # yalnız damga (hızlı)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE = ROOT / "src/android/app/build.gradle.kts"
DEFAULT_OUT = Path("/var/minis/shared/minis-x-snapshots")

# Build çıktısı, IDE artıkları ve büyük ikili dosyalar arşive girmez: zip'in
# işi kaynağı taşımak, derleme önbelleğini değil.
EXCLUDE_DIRS = {
    ".git", "build", ".gradle", ".idea", "DerivedData", "node_modules",
    "__pycache__", ".kotlin", "intermediates", "transforms",
}
EXCLUDE_SUFFIX = {
    ".apk", ".aab", ".dex", ".class", ".jar", ".so.aar", ".zip", ".bundle",
}
# Zip'in içine girmesi gereken ama genelde büyük olan uzantılar: kaynak ağacında
# yok, olsa da girmeli (assets, json). Yalnız yukarıdakiler dışlanıyor.


def run(*args: str, cwd: Path | None = None) -> str:
    return subprocess.run(
        list(args), cwd=cwd or ROOT, capture_output=True, text=True, check=False
    ).stdout.strip()


def version() -> tuple[str, int]:
    text = GRADLE.read_text(encoding="utf-8")
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    return (name.group(1) if name else "?"), (int(code.group(1)) if code else 0)


def gate(name: str, cmd: list[str], tail: int = 1) -> dict:
    """Kapıyı koşar, son satırı saklar. Kırmızı da bir sonuçtur — gizlenmez."""
    p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, check=False)
    out = (p.stdout or p.stderr).strip().splitlines()
    return {
        "ad": name,
        "kod": p.returncode,
        "durum": "YESIL" if p.returncode == 0 else "KIRMIZI",
        "son": out[-tail:] if out else [],
    }


def push_state() -> dict:
    branch = run("git", "rev-parse", "--abbrev-ref", "HEAD")
    upstream = run("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
    if not upstream:
        return {"dal": branch, "uzak": None, "durum": "YAYINLANMADI (uzak dal yok)",
                "ileride": None, "geride": None}
    ahead = run("git", "rev-list", "--count", f"{upstream}..HEAD")
    behind = run("git", "rev-list", "--count", f"HEAD..{upstream}")
    dirty = run("git", "status", "--porcelain")
    if ahead == "0" and not dirty:
        durum = "PUSH EDİLDİ"
    elif ahead == "0":
        durum = "UZAKLA AYNI (yerel değişiklik var)"
    else:
        durum = "PUSH EDİLMEDİ"
    return {
        "dal": branch,
        "uzak": upstream,
        "durum": durum,
        "ileride": int(ahead or 0),
        "geride": int(behind or 0),
    }


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def should_skip(rel: Path) -> bool:
    if any(part in EXCLUDE_DIRS for part in rel.parts):
        return True
    if rel.name.endswith(".tar.gz") and rel.stat().st_size > 40 * 1024 * 1024:
        return True
    return False


def build_zip(target: Path) -> tuple[int, int]:
    count = 0
    raw = 0
    with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for path in sorted(ROOT.rglob("*")):
            if not path.is_file():
                continue
            rel = path.relative_to(ROOT)
            if should_skip(rel):
                continue
            try:
                z.write(path, rel.as_posix())
                count += 1
                raw += path.stat().st_size
            except (OSError, ValueError):
                continue
    return count, raw


def make_label(out: Path, stamp: str, ver: tuple[str, int], note: str,
               gates: list[dict], zip_info: dict | None, bundle: Path | None) -> Path:
    name, code = ver
    push = push_state()
    commit = run("git", "rev-parse", "HEAD")
    subject = run("git", "log", "-1", "--format=%s")
    local = time.strftime("%Y-%m-%d %H:%M:%S %z")
    utc = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    lines = [
        "# DAMGA — Minis X anlık görüntüsü",
        "",
        f"| | |",
        f"|---|---|",
        f"| **Zaman** | {local} (yerel) · {utc} (UTC) |",
        f"| **Damga** | `{stamp}` |",
        f"| **Sürüm** | `{name}` / versionCode **{code}** |",
        f"| **Dal** | `{push['dal']}` |",
        f"| **Commit** | `{commit[:12]}` — {subject} |",
        f"| **Push durumu** | **{push['durum']}**"
        + (f" · {push['ileride']} ileride, {push['geride']} geride" if push["uzak"] else "")
        + " |",
        f"| **Uzak** | `{push['uzak'] or '—'}` |",
        "",
    ]
    if note:
        lines += ["## Not", "", note, ""]

    lines += ["## Kapılar", "", "| Kapı | Durum | Son satır |", "|---|---|---|"]
    for g in gates:
        last = " · ".join(g["son"]).replace("|", "\\|")[:120]
        lines.append(f"| {g['ad']} | **{g['durum']}** | `{last}` |")
    lines.append("")

    if zip_info:
        lines += [
            "## Arşiv",
            "",
            f"- `{zip_info['ad']}` — {zip_info['dosya']} dosya, "
            f"{zip_info['ham_mb']:.1f} MB ham → {zip_info['zip_mb']:.1f} MB zip",
            f"- sha256 `{zip_info['sha256']}`",
        ]
    if bundle:
        lines.append(f"- `{bundle.name}` — git geçmişi (klonlanabilir, `git clone <bundle>`)")
    lines.append("")

    lines += [
        "## Geri yükleme",
        "",
        "```bash",
        "# yalnız dosyalar:",
        f"unzip {zip_info['ad'] if zip_info else '<arşiv>.zip'} -d minis-x-restore",
        "",
        "# tam geçmiş dahil:",
        f"git clone {bundle.name if bundle else '<arşiv>.bundle'} minis-x-full",
        "```",
        "",
    ]

    path = out / f"DAMGA.md"
    path.write_text("\n".join(lines), encoding="utf-8")
    return path


def update_index(out: Path) -> None:
    rows = []
    for d in sorted(out.glob("minis-x-*")):
        if not d.is_dir():
            continue
        label = d / "DAMGA.md"
        if not label.exists():
            continue
        text = label.read_text(encoding="utf-8")
        def cell(key: str) -> str:
            m = re.search(rf"\|\s*\*\*{key}\*\*\s*\|\s*(.+?)\s*\|", text)
            if not m:
                return "—"
            # DAMGA.md değerleri kendi kalın işaretlerini taşıyor
            # (`**PUSH EDİLMEDİ**`); tabloda tekrar sarılınca `****metin****`
            # oluyor ve Markdown onu düz metin gösteriyor. Temizle.
            return m.group(1).replace("**", "").strip()
        rows.append(
            f"| `{d.name}` | {cell('Zaman').split(' (')[0]} | {cell('Sürüm')} | "
            f"{cell('Commit')} | **{cell('Push durumu').split(' ·')[0]}** | "
            f"[DAMGA]({d.name}/DAMGA.md) |"
        )

    body = [
        "# Minis X — anlık görüntü dizini",
        "",
        "Her satır bir arşiv. **Push durumu** sütunu o anki gerçeği söyler;",
        "`PUSH EDİLMEDİ` ise o kod yalnız bu cihazda.",
        "",
        "| Arşiv | Zaman | Sürüm | Commit | Push | Damga |",
        "|---|---|---|---|---|---|",
        *rows,
        "",
        f"_Güncellendi: {time.strftime('%Y-%m-%d %H:%M:%S %z')}_",
        "",
        "Yeni anlık görüntü: `python3 scripts/ozel/snapshot.py --note \"...\"`",
        "",
    ]
    (out / "DIZIN.md").write_text("\n".join(body), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser(description="Minis X anlık görüntüsü")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--note", default="")
    ap.add_argument("--no-zip", action="store_true", help="yalnız damga üret (hızlı)")
    ap.add_argument("--no-gates", action="store_true", help="kapıları koşma")
    ap.add_argument("--list", action="store_true", help="dizini göster ve çık")
    args = ap.parse_args()

    out = Path(args.out)
    if args.list:
        idx = out / "DIZIN.md"
        print(idx.read_text(encoding="utf-8") if idx.exists() else "henüz anlık görüntü yok")
        return 0

    out.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    name, code = version()
    snap_dir = out / f"minis-x-{name}-{stamp}"
    snap_dir.mkdir(parents=True, exist_ok=True)

    print(f"anlık görüntü: {snap_dir.name}")
    print(f"sürüm {name} / {code}")

    gates = []
    if not args.no_gates:
        corpus = ROOT / "scripts/ozel/risk-corpus.py"
        checks = ROOT / "scripts/ozel/kotlin-denetim.py"
        audit = ROOT / "scripts/integration_audit.py"
        if corpus.exists():
            gates.append(gate("risk korpusu", [sys.executable, str(corpus), "--fail-on-mismatch"]))
        if checks.exists():
            gates.append(gate("yapısal denetim", [sys.executable, str(checks), "--no-warn"], tail=1))
        if audit.exists():
            gates.append(gate("entegrasyon", [sys.executable, str(audit)], tail=1))
        for g in gates:
            print(f"  kapı {g['ad']}: {g['durum']}")

    zip_info = None
    bundle = None
    if not args.no_zip:
        zpath = snap_dir / f"minis-x-{name}-{stamp}.zip"
        print("  zip yazılıyor…")
        count, raw = build_zip(zpath)
        zip_info = {
            "ad": zpath.name,
            "dosya": count,
            "ham_mb": raw / 1048576,
            "zip_mb": zpath.stat().st_size / 1048576,
            "sha256": sha256_of(zpath),
        }
        print(f"  zip: {zip_info['dosya']} dosya, {zip_info['zip_mb']:.1f} MB")

        bpath = snap_dir / f"minis-x-{name}-{stamp}.bundle"
        r = subprocess.run(
            ["git", "bundle", "create", str(bpath), "--all"],
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
        if r.returncode == 0 and bpath.exists():
            bundle = bpath
            print(f"  bundle: {bpath.stat().st_size / 1048576:.1f} MB")
        else:
            print(f"  bundle ATLANDI: {r.stderr.strip()[:120]}")

    label = make_label(snap_dir, stamp, (name, code), args.note, gates, zip_info, bundle)
    update_index(out)
    print(f"  damga: {label}")
    print(f"  dizin: {out / 'DIZIN.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
