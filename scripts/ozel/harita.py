#!/usr/bin/env python3
"""
[37099] Harita üreteci — belgeleri **elle değil, kaynaktan** üretir.

NEDEN VAR
    `docs/ozel/HARITA.md` elle yazıldı ve içindeki her sayı (73 ekran,
    6 517 test, 42 kural) ikinci günde bayatlayabilir. Bayat belge, yanlış
    belgeden daha kötüdür: insan ona güvenip yanlış yere bakar.

    Bu betik haritayı **kaynaktan** üretir: dosya ağacı, `scripts/slices.json`,
    `RiskRules.kt`, `OzelFeatures.kt`, `AndroidManifest.xml`, sözleşme defteri,
    `values*/strings.xml`. Sayılar tahmin değil, okuma.

    `--check` ile üretilen ile diskteki karşılaştırılır: bir şey değişip harita
    güncellenmediyse çıkış kodu 1. Yani "harita bayat" bir CI hatasıdır.

ÇIKTI (docs/harita/)
    00-GIRIS.md        yönlendirme: "ne arıyorsan -> şu dosya" (en kısa yol)
    10-KOD.md          paket ağacı: dosya sayısı, satır, ekranlar
    20-DILIMLER.md     13 dilim + bağımlılık
    30-KURALLAR.md     42 risk kuralı, seviye/kapsam/geri alınabilirlik
    40-OZELLIKLER.md   27 özellik anahtarı, açık/kapalı
    50-IZINLER.md      27 Android + 2 özel izin
    60-CEVIRI.md       en/tr anahtar sayıları + eksik tabanı
    70-SOZLESMELER.md  13 sözleşme, 72 kayıt
    80-TESTLER.md      test dosyası dağılımı
    90-BELGELER.md     docs/ altındaki belgeler: başlık + ilk satır

KULLANIM
    python3 scripts/ozel/harita.py            # üret
    python3 scripts/ozel/harita.py --check    # bayat mı? (CI kapısı)
    python3 scripts/ozel/harita.py --check --quiet
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "docs/harita"
SRC = ROOT / "src/android/app/src"

# Elle yazılan, üretecin dokunmadığı dosyalar (docs/harita/ içinde).
ELLE = {"99-NE-NEREYE.md"}

BAYAT = "<!-- ÜRETİLDİ: scripts/ozel/harita.py — elle düzenlemeyin. -->"


def n(v: int) -> str:
    """Sayıyı binlik ayraçla yaz — PROSE'u değil, yalnız sayıyı biçimler.

    Önce `.replace(",", " ")` tüm metne uygulanıyordu ve cümlelerdeki virgülleri
    de siliyordu ("Ne arıyorsan  şuraya bak"). Bu fonksiyon tek iş yapıyor.
    """
    return f"{v:,}".replace(",", " ")


def rel(p: Path) -> str:
    return str(p.relative_to(ROOT))


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8", errors="replace")


# ── kaynak okuyucular ───────────────────────────────────────────────────────

def kod_agaci() -> dict:
    """Java/Kotlin ağacı: paket başına dosya ve satır."""
    base = SRC / "main/java/com/openminis/app"
    rows = []
    total_files = total_lines = 0
    for d in sorted(p for p in base.iterdir() if p.is_dir()):
        files = list(d.rglob("*.kt"))
        if not files:
            continue
        lines = sum(len(read(f).splitlines()) for f in files)
        total_files += len(files)
        total_lines += lines
        rows.append((d.name, len(files), lines))
    screens = sorted(SRC.glob("main/java/com/openminis/app/**/*Screen.kt"))
    return {
        "rows": rows,
        "total_files": total_files,
        "total_lines": total_lines,
        "screens": len(screens),
        "ui_packages": len([p for p in (base / "ui").iterdir() if p.is_dir()]) if (base / "ui").is_dir() else 0,
    }


def dilimler() -> list[dict]:
    data = json.loads(read(ROOT / "scripts/slices.json"))
    out = []
    for code, v in data["slices"].items():
        out.append({
            "code": code,
            "title": v.get("title", ""),
            "note": v.get("issue") or v.get("not") or "",
            "deps": len(v.get("deps", [])),
            "files": len(v.get("files", [])),
        })
    return sorted(out, key=lambda x: x["code"])


def risk_kurallari() -> list[dict]:
    """RiskRules.kt -> [(grup, id, seviye, kapsam, geri)] — regex, ayrıştırıcı değil."""
    src = read(SRC / "main/java/com/openminis/app/policy/RiskRules.kt")
    rows = []
    # Rule( id = "x", ... level = RiskLevel.Y, reach = Reach.Z, recovery = Recovery.W )
    for m in re.finditer(
        r'\bRule\(\s*(?:id\s*=\s*)?"([a-z][\w:-]+)"(.*?)(?=\bRule\(|\Z)', src, re.S,
    ):
        rid, body = m.group(1), m.group(2)
        # İki biçim var: adlandırılmış (`level = RiskLevel.X`) ve konumsal
        # (`Rule("id", Regex(...), RiskLevel.X, Reach.Y, Recovery.Z, "not")`).
        # Yalnız adlandırılmışı arayan sürüm konumsal kuralları "—" gösteriyordu
        # (shell:dd, shell:mkfs) — yani eksik veri, boş satır.
        lvl = re.search(r"RiskLevel\.(\w+)", body)
        rch = re.search(r"Reach\.(\w+)", body)
        rec = re.search(r"Recovery\.(\w+)", body)
        rows.append({
            "id": rid,
            "level": lvl.group(1) if lvl else "—",
            "reach": rch.group(1) if rch else "—",
            "recovery": rec.group(1) if rec else "—",
            "deny": rid.startswith("deny:"),
        })
    return rows


def ozellikler() -> list[dict]:
    src = read(SRC / "main/java/com/openminis/app/data/OzelFeatures.kt")
    return [
        {"name": m.group(1), "key": m.group(2), "default": m.group(3) == "true"}
        for m in re.finditer(r'(\w+)\("([a-z0-9_]+)",\s*(true|false)\)', src)
    ]


def izinler() -> tuple[list[str], list[str]]:
    src = read(SRC / "main/AndroidManifest.xml")
    # Yalnız gerçek `<uses-permission>` etiketleri. Önce bütün dosyada
    # `android.permission.X` aranıyordu ve YORUMLARDAKİ izin adları da
    # sayılıyordu (31 çıkıyordu, gerçek 25).
    declared = re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', src)
    std = sorted({m.split(".")[-1] for m in declared if m.startswith("android.permission.")})
    ozel = sorted({m for m in declared if not m.startswith("android.permission.")})
    return std, ozel


def ceviri() -> dict:
    res = SRC / "main/res"
    en = read(res / "values/strings.xml")
    tr = read(res / "values-tr/strings.xml")
    E = set(re.findall(r'<string name="([^"]+)"', en))
    T = set(re.findall(r'<string name="([^"]+)"', tr))
    base_file = ROOT / ".ozel/ceviri/taban.json"
    baseline = set()
    if base_file.exists():
        baseline = set(json.loads(read(base_file)).get("keys", []))
    missing = sorted(E - T)
    return {
        "en": len(E), "tr": len(T),
        "missing": len(missing),
        "new_missing": [k for k in missing if k not in baseline],
        "baseline": len(baseline),
    }


def sozlesmeler() -> dict:
    f = ROOT / ".ozel/sozlesmeler/index.jsonl"
    ops = Counter()
    ids = set()
    tur = Counter()
    if f.exists():
        for line in read(f).splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                d = json.loads(line)
            except Exception:
                continue
            ops[d.get("op", "?")] += 1
            ids.add(d.get("id", ""))
            if d.get("tur"):
                tur[d["tur"]] += 1
    return {"ops": dict(ops), "count": len(ids), "tur": dict(tur)}


def testler() -> dict:
    files = list((SRC / "test").rglob("*.kt"))
    per = Counter()
    root_base = SRC / "test/java/com/openminis/app"
    for f in files:
        # Bazı test dosyaları `com/openminis/app` dışında yaşıyor (ör.
        # `src/test/java/android/net/CtxFakeUri.kt` — Android sınıfının sahte
        # karşılığı). `relative_to` orada patlar; onları ayrı sayıyoruz.
        try:
            parts = f.relative_to(root_base).parts
            per[parts[0] if len(parts) > 1 else "(kök)"] += 1
        except ValueError:
            per["(android sahteleri)"] += 1
    tests = sum(
        len(re.findall(r"@Test", read(f))) for f in files
    )
    device = list((SRC / "androidTest").rglob("*.kt"))
    device_tests = sum(len(re.findall(r"@Test", read(f))) for f in device)
    return {"files": len(files), "tests": tests, "per": per.most_common(12),
            "device_files": len(device), "device_tests": device_tests}


def belgeler() -> list[dict]:
    out = []
    for f in sorted(ROOT.glob("docs/**/*.md")):
        if "harita" in f.parts:
            continue
        text = read(f)
        title = next((l.lstrip("# ").strip() for l in text.splitlines() if l.startswith("# ")), f.name)
        first = next(
            (l.strip() for l in text.splitlines()
             if l.strip() and not l.startswith(("#", "|", ">", "```", "-", "*"))),
            "",
        )
        out.append({"path": rel(f), "title": title[:80], "first": first[:110]})
    return out


# ── yazıcılar ──────────────────────────────────────────────────────────────

def yaz(dir_: Path, name: str, title: str, body: str) -> None:
    (dir_ / name).write_text(f"{BAYAT}\n\n# {title}\n\n{body}", encoding="utf-8")


def uret(dir_: Path) -> None:
    dir_.mkdir(parents=True, exist_ok=True)
    k, dl, rk, oz, (iz, ozel_iz), cv, sz, ts, bg = (
        kod_agaci(), dilimler(), risk_kurallari(), ozellikler(), izinler(),
        ceviri(), sozlesmeler(), testler(), belgeler(),
    )

    deny = [r for r in rk if r["deny"]]
    ask = [r for r in rk if not r["deny"]]

    yaz(dir_, "00-GIRIS.md", "Harita — giriş ve yönlendirme", f"""\
**Ne arıyorsan, şuraya bak.** Her satır tek bir soruya cevap veriyor.

| Arıyorum | Yer |
|---|---|
| Bir ekranın kodu | `ui/` → `10-KOD.md` |
| Bir özelliğin açma/kapama anahtarı | `40-OZELLIKLER.md` |
| "Bu komut kart açar mı?" | `30-KURALLAR.md` + `scripts/ozel/risk-corpus.py -v "<komut>"` |
| Hangi izin var, neden | `50-IZINLER.md` |
| Bir dilimin kodu/bağımlılığı | `20-DILIMLER.md` + `scripts/slices.json` |
| Test nerede | `80-TESTLER.md` |
| Bir sözleşme ne vaat ediyor | `70-SOZLESMELER.md` + `.ozel/sozlesmeler/` |
| Çeviri eksiği | `60-CEVIRI.md` + `scripts/ozel/ceviri-denetim.py` |
| "Bu belge ne anlatıyor" | `90-BELGELER.md` |
| **Yeni bir şey ekledim, ne güncellenir** | `99-NE-NEREYE.md` |
| Neden bu çatal var | `../ozel/HARITA.md` (insan yazımı, sabit) |

## Bu klasör otomatik

Her dosya `scripts/ozel/harita.py` tarafından **kaynaktan** üretilir. Elle
düzenleme bir sonraki üretimde silinir. Sayılar kaynaktan okunur; bayatlamaz,
çünkü bayatlarsa CI kırmızı olur (`--check`).

```
python3 scripts/ozel/harita.py          # üret
python3 scripts/ozel/harita.py --check  # bayat mı?
```

## Bugünkü ölçüm

| | |
|---|---|
| Kotlin dosyası (main) | {k['total_files']} · {n(k['total_lines'])} satır |
| Ekran | {k['screens']} · UI paketi {k['ui_packages']} |
| Dilim | {len(dl)} |
| Risk kuralı | {len(rk)} (DENY {len(deny)} · karta düşen {len(ask)}) |
| Özellik anahtarı | {len(oz)} (açık {sum(1 for o in oz if o['default'])}) |
| İzin | {len(iz)} Android + {len(ozel_iz)} özel |
| Test | {n(ts['tests'])} birim ({ts['files']} dosya) + {ts['device_tests']} cihaz |
| Çeviri | en {cv['en']} · tr {cv['tr']} · eksik {cv['missing']} (yeni {len(cv['new_missing'])}) |
| Sözleşme | {sz['count']} · kayıt {sum(sz['ops'].values())} |
""")

    kod = ["| Paket | Dosya | Satır |", "|---|---|---|"]
    for name, files, lines in k["rows"]:
        kod.append(f"| `{name}/` | {files} | {lines} |")
    yaz(dir_, "10-KOD.md", f"Kod ağacı — {k['total_files']} dosya, {k['total_lines']} satır", "\n".join(kod) + f"""

Ekran (`*Screen.kt`): **{k['screens']}** · UI paketi: **{k['ui_packages']}**

## Nerede ne var (sabit sınırlar)

| Dizin | Sorumluluk | Kural |
|---|---|---|
| `ozel/` | çatala ait karar mantığı | Android'siz çekirdek `ozel/health/` içinde (dilim 37095) |
| `ozel/PolicyGate.kt` | onay kapısının Android tarafı | çekirdek `ApprovalInbox` ayrı, test edilebilir |
| `policy/` | risk tablosu (veri) + sınıflandırıcı (mantık) | tablo ile kod ayrı dosyada |
| `sandbox/` | PRoot, kök, kabuk, araç zinciri | en riskli alan; her dosya kendi başlığında "neden" taşır |
| `ui/` | Compose ekranları | iş mantığı yok, yalnız sunum |
| `data/` | Room, prefs, model | şema değişimi göç ister |
| `provider/` | LLM adaptörleri | sağlayıcıya özel tuhaflık burada izole |
""")

    rows = ["| Dilim | Ad | Not | Dep |", "|---|---|---|---|"]
    for d in dl:
        rows.append(f"| `{d['code']}` | {d['title']} | {d['note'][:70]} | {d['deps']} |")
    yaz(dir_, "20-DILIMLER.md", f"Dilimler — {len(dl)} birim", "\n".join(rows) + """

Kural: bir dilim kendi dosyaları + `deps` ile **Android'siz** derlenebilmeli.
Kapı `android.*` importunu hata sayar. Kaynak: `scripts/slices.json`.
""")

    rows = ["| Kural | Seviye | Kapsam | Geri |", "|---|---|---|---|"]
    for r in ask:
        rows.append(f"| `{r['id']}` | {r['level']} | {r['reach']} | {r['recovery']} |")
    rows.append("")
    rows.append("### DENY — kart bile açılmaz")
    rows.append("")
    rows.append("| Kural | Kapsam | Geri |")
    rows.append("|---|---|---|")
    for r in deny:
        rows.append(f"| `{r['id']}` | {r['reach']} | {r['recovery']} |")
    yaz(dir_, "30-KURALLAR.md", f"Risk kuralları — {len(rk)}", "\n".join(rows) + """

Sınıf: **DENY** farz yasak · **ASK** caiz izinle · **SILENT** serbest.
Seviye ≠ sınıf: MEDIUM ve üstü karta düşer, ama hedef kuralı yükseltir
(ajanın kendi çöpünde LOW, `/var/minis/shared`'de HIGH, kökte DENY).

Tek tek sormak için: `python3 scripts/ozel/risk-corpus.py -v "<komut>"`
""")

    rows = ["| Anahtar | Ayar adı | Varsayılan |", "|---|---|---|"]
    for o in oz:
        rows.append(f"| `{o['name']}` | `{o['key']}` | {'açık' if o['default'] else 'kapalı'} |")
    yaz(dir_, "40-OZELLIKLER.md", f"Özellik anahtarları — {len(oz)}", "\n".join(rows) + """

Kural: **açık** bir anahtarın üretim kodunda okuyucusu olmak zorunda.
`.14`'te `STATION` bu kuralı çiğnedi (açık, okuyucu yok) ve kapatıldı.
""")

    rows = ["| İzin |", "|---|"] + [f"| `{p}` |" for p in iz]
    rows += ["", "### Özel", "", "| İzin |", "|---|"] + [f"| `{p}` |" for p in ozel_iz]
    yaz(dir_, "50-IZINLER.md", f"İzinler — {len(iz)} + {len(ozel_iz)}", "\n".join(rows) + """

Kural (FORK.md §1.1/5): bir özellik ekstra izin gerektiriyorsa **izni isteme
akışı da o özelliğin parçasıdır**. İzin isteyip kullanmamak da bir hatadır.
""")

    yeni = "\n".join(f"- `{k2}`" for k2 in cv["new_missing"][:40]) or "- yok"
    yaz(dir_, "60-CEVIRI.md", "Çeviri durumu", f"""\
| | |
|---|---|
| `values/` anahtar | {cv['en']} |
| `values-tr/` anahtar | {cv['tr']} |
| Eksik | {cv['missing']} |
| Bunlardan **yeni** (taban dışı) | {len(cv['new_missing'])} |
| Taban (upstream devralınmış, bilerek çevrilmemiş) | {cv['baseline']} |

Yeni eksikler (kırmızı olanlar):

{yeni}

Kapı: `python3 scripts/ozel/ceviri-denetim.py --fail-on-new`
Tabanı güncelleme: `--fix-baseline` (yalnız bilinçli kararla).
""")

    ops = " · ".join(f"`{k2}` {v}" for k2, v in sorted(sz["ops"].items()))
    rows = ["| Tür | Sözleşme |", "|---|---|"] + [f"| `{t}` | {n} |" for t, n in sorted(sz["tur"].items())]
    yaz(dir_, "70-SOZLESMELER.md", f"Sözleşmeler — {sz['count']} · {sum(sz['ops'].values())} kayıt", f"""\
İşlemler: {ops}

{chr(10).join(rows)}

Her sözleşmenin değeri **`disi`** alanında: "yapmayacağım" da yazılı.
Sessiz kapsam kayması burada yakalanıyor. Kaynak: `.ozel/sozlesmeler/index.jsonl`.
""")

    rows = ["| Paket | Test dosyası |", "|---|---|"] + [f"| `{p}` | {n} |" for p, n in ts["per"]]
    yaz(dir_, "80-TESTLER.md", f"Testler — {ts['tests']} birim testi", f"""\
| | |
|---|---|
| Birim test dosyası | {ts['files']} |
| Birim `@Test` | {ts['tests']} |
| Cihaz testi dosyası | {ts['device_files']} |
| Cihaz `@Test` | {ts['device_tests']} |

{chr(10).join(rows)}

**Bu cihazda koşmuyor** (JVM/PaX: "Failed to mark memory page as executable").
Doğrulama CI'da; burada yalnız JVM'siz aynalar (`risk-corpus.py` vb.) koşar —
onlar **gölgedir**, ayrışırsa doğru olan Kotlin'dir.
""")

    rows = ["| Belge | Başlık | İlk satır |", "|---|---|---|"]
    for b in bg:
        rows.append(f"| `{b['path']}` | {b['title']} | {b['first']} |")
    yaz(dir_, "90-BELGELER.md", f"Belgeler — {len(bg)} dosya", "\n".join(rows) + "\n")



def harita_md_guncelle(k, dl, rk, oz, iz, ozel_iz, cv, sz, ts) -> bool:
    """`docs/ozel/HARITA.md` içindeki OTO bloğunu ölçümle değiştirir.

    Neden: o belge "neden var" sorusunu cevaplıyor ve elle yazılmalı; ama
    içindeki SAYILAR elle yazıldığında ikinci günde bayatlıyor. Bu yüzden sayı
    taşıyan tek bölüm işaretli ve ölçümden üretiliyor.
    """
    f = ROOT / "docs/ozel/HARITA.md"
    if not f.exists():
        return False
    text = read(f)
    start, end = "<!-- OTO:BASLA -->", "<!-- OTO:SON -->"
    if start not in text or end not in text:
        return False
    rows = [
        "| Ölçüm | Değer |",
        "|---|---|",
        f"| Kotlin dosyası (main) | {k['total_files']} · {n(k['total_lines'])} satır |",
        f"| Ekran | {k['screens']} · UI paketi {k['ui_packages']} |",
        f"| Dilim | {len(dl)} (bağımlılığı olan: {sum(1 for d in dl if d['deps'])}) |",
        f"| Risk kuralı | {len(rk)} — DENY {sum(1 for r in rk if r['deny'])} · karta düşen {sum(1 for r in rk if not r['deny'])} |",
        f"| Özellik anahtarı | {len(oz)} — açık {sum(1 for o in oz if o['default'])} · kapalı {sum(1 for o in oz if not o['default'])} |",
        f"| İzin | {len(iz)} Android + {len(ozel_iz)} özel |",
        f"| Test | {n(ts['tests'])} birim ({ts['files']} dosya) + {ts['device_tests']} cihaz |",
        f"| Çeviri | en {cv['en']} · tr {cv['tr']} · eksik {cv['missing']} (yeni {len(cv['new_missing'])}) |",
        f"| Sözleşme | {sz['count']} · kayıt {sum(sz['ops'].values())} |",
        "",
        "Ayrıntı ve yönlendirme: [`docs/harita/00-GIRIS.md`](../harita/00-GIRIS.md)",
    ]
    yeni = start + "\n" + "\n".join(rows) + "\n" + end
    import re as _re
    text2 = _re.sub(_re.escape(start) + r".*?" + _re.escape(end), lambda _: yeni, text, flags=_re.S)
    if text2 != text:
        f.write_text(text2, encoding="utf-8")
        return True
    return False


# ── ana akış ───────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="bayat mı? (0 = güncel)")
    ap.add_argument("--quiet", action="store_true")
    args = ap.parse_args()

    if args.check:
        tmp = ROOT / ".harita-tmp"
        if tmp.exists():
            import shutil
            shutil.rmtree(tmp)
        uret(tmp)
        diffs = []
        for f in sorted(tmp.glob("*.md")):
            target = OUT / f.name
            if not target.exists():
                diffs.append((f.name, "yok"))
            elif read(target) != read(f):
                diffs.append((f.name, "farklı"))
        for extra in sorted(OUT.glob("*.md")):
            if extra.name in ELLE:
                continue
            if not (tmp / extra.name).exists():
                diffs.append((extra.name, "artık üretilmiyor"))
        import shutil
        shutil.rmtree(tmp)
        if diffs:
            print("HARITA BAYAT — `python3 scripts/ozel/harita.py` çalıştır:")
            for name, why in diffs:
                print(f"  · {name}: {why}")
            return 1
        if not args.quiet:
            print("harita güncel")
        return 0

    uret(OUT)
    k, dl, rk, oz = kod_agaci(), dilimler(), risk_kurallari(), ozellikler()
    iz, ozel_iz = izinler()
    degisti = harita_md_guncelle(k, dl, rk, oz, iz, ozel_iz, ceviri(), sozlesmeler(), testler())
    cnt = len(list(OUT.glob("*.md")))
    print(f"harita üretildi: {rel(OUT)} ({cnt} dosya)" + (" · docs/ozel/HARITA.md ölçümü tazelendi" if degisti else ""))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
