#!/usr/bin/env python3
"""[37117] Görünürlük kapısı — "açık anahtar" ile "kullanıcının gördüğü şey" aynı mı?

NEDEN VAR (ölçülen arıza, üç kez tekrar etti)
    37091 STATION   : anahtar AÇIK, modül yok, üretimde 0 çağrı → kullanıcı
                      "iki-dört ajan" vaadini açtı, hiçbir şey olmadı.
    37095 sağlık    : çekirdek `.15`'te vardı, çağrısı yoktu; anahtar açıktı ve
                      ekranda tek piksel görünmüyordu.
    .34 sanal ekran : anahtar AÇIK, ayar ekranı var, ama yakalama hattı ve
                      eylem API'si 0 çağrı.

    Üçünde de `integration_audit.py` "BAĞLI" dedi ve haklıydı: kod çağrılıyordu.
    Yanlış olan, "BAĞLI" ile "GÖRÜNÜR"ün aynı sanılmasıydı. Bu betik o boşluğu
    ölçer: her anahtar için ayrı ayrı "üretimde çağrılıyor mu", "arayüzde var mı",
    "kapalıysa gerekçesi yazılı mı" sorusunu sorar.

ÜÇ SINIF
    AÇIK+GÖRÜNÜR   →  yeşil. Anahtar açık ve `ui/` altında tüketicisi var.
    AÇIK+GÖRÜNMEZ  →  KIRMIZI (taban dışındaysa). Anahtar açık ama kullanıcı
                      hiçbir şey görmüyor: ya kapalı olmalı ya yüzeyi yazılmalı.
    KAPALI         →  bilgi. Gerekçesi yazılıysa yeşil, yazılı değilse uyarı.

NEDEN TABAN (RATCHET)
    Bugünkü gerçek 8 kapalı anahtar + birkaç açık-görünmez satır içeriyor.
    Hepsini bir turda kırmızı yapmak, kapının ilk gün susturulması demek
    (`.23` dersi: yanlış pozitif üreten kapı kapatılır, o zaman hiçbir şey
    yakalamaz). Taban yalnız **daralır**: yeni bir açık-görünmez anahtar
    eklenirse kırmızı olur.

NEDEN `--taban-yaz` VAR VE NEDEN ELLE YAZILMIYOR
    Tabanı elle yazmak, kapının o günkü hatasını sabitler. Dosyayı **araç**
    üretir; gözle düzeltilmez.

KULLANIM
    python3 scripts/ozel/gorunurluk.py                  # rapor (çıkış 0/1)
    python3 scripts/ozel/gorunurluk.py --taban-yaz       # tabanı yeniden üret
    python3 scripts/ozel/gorunurluk.py --kendini-sina    # 4 sahte ağaç
    python3 scripts/ozel/gorunurluk.py --ayrintili       # her anahtar için yol
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "src/android/app/src/main/java"
TEST = ROOT / "src/android/app/src/test"
FEATURES = MAIN / "com/openminis/app/data/OzelFeatures.kt"
BASELINE = ROOT / ".ozel/gorunurluk/taban.json"

# `NAME("key", true),` — enum gövdesindeki tek satırlık girişler.
ENTRY_RE = re.compile(
    r'^\s*([A-Z][A-Z0-9_]*)\s*\(\s*"([a-z0-9_]+)"\s*,\s*(true|false)\s*\)\s*,',
    re.M,
)

# Gerekçe sayılan işaretler. Harf büyüklüğü önemsiz: depoda hem "OFF" hem
# "Off by default" yazılmış; biri gerekçe sayılıp diğeri sayılmazsa kapı
# keyfî görünür (ve `.36`'da tam bu oldu: DEV_SERVER/VAULT_AGENT_READ).
REASON_RE = re.compile(
    r"(\boff\b|\bkapal[iı]\b|uygulaması yok|0 call sites|no surface|urgent|"
    r"vatanda|kullanıcı istemeden)",
    re.I,
)


def has_reason(kdoc: str) -> bool:
    return bool(REASON_RE.search(kdoc))


@dataclass
class Row:
    name: str
    key: str
    enabled: bool
    wired: int          # üretimde, OzelFeatures.kt dışında kaç dosyada geçiyor
    visible: int        # ui/ altında kaç dosyada geçiyor
    reason: bool        # kapalıysa gerekçe yazılı mı
    ui_files: list[str]

    @property
    def state(self) -> str:
        if self.enabled:
            return "ACIK+GORUNUR" if self.visible > 0 else "ACIK+GORUNMEZ"
        return "KAPALI"


def strip_comments(text: str) -> str:
    """Yorumları siler — yorum satırı bir anahtarı 'kullanılmış' göstermemeli."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"//[^\n]*", " ", text)


def parse_entries(src: str) -> list[tuple[str, str, bool, str]]:
    """(ad, anahtar, varsayılan, kdoc) — kdoc, girişin hemen üstündeki blok."""
    out = []
    for m in ENTRY_RE.finditer(src):
        name, key, default = m.group(1), m.group(2), m.group(3) == "true"
        head = src[: m.start()]
        # Girişin üstündeki son yorum bloğu.
        blocks = re.findall(r"/\*\*(.*?)\*/", head, flags=re.S)
        out.append((name, key, default, blocks[-1] if blocks else ""))
    return out


_CACHE: dict[Path, str] = {}


def _text(p: Path) -> str:
    """Dosya metni — yorumlar silinmiş, süreç içinde bir kez okunur.

    Neden önbellek: ilk sürüm her anahtar için ağacı yeniden okuyordu
    (27 anahtar × 2 tarama ≈ 52 s). Kapı 50 saniyeden uzun sürerse kimse
    koşmaz; koşulmayan kapı yok sayılır.
    """
    if p not in _CACHE:
        _CACHE[p] = strip_comments(p.read_text(errors="ignore"))
    return _CACHE[p]


# [37128 · A3] Ağaç DİZİNİ bir kez yürünür, sonra desenler o listeye uygulanır.
#
# Önce: `count_refs` her anahtar için `sorted(root.rglob("*.kt"))` yapıyordu —
# 25 anahtar × 2 tarama (tüm ağaç + `ui/`) = **50 tam dizin yürüyüşü**, ölçülen
# 303 s. Şimdi: yürüyüş bir kez, 25 desen bellekteki listeye uygulanıyor.
# Dosya YÜKLEME maliyeti değişmiyor (`_CACHE` zaten vardı); kalkan şey
# dosya sistemi dolaşması.
_WALK: dict[Path, list[Path]] = {}

def _walk(root: Path) -> list[Path]:
    if root not in _WALK:
        _WALK[root] = sorted(root.rglob("*.kt"))
    return _WALK[root]

def count_refs(root: Path, name: str, exclude: Path | None = None) -> tuple[int, list[str]]:
    """`Feature.NAME` geçen dosyalar. Yorumlar silinir: yorum kullanım değildir."""
    pattern = re.compile(r"\bFeature\." + re.escape(name) + r"\b")
    files: list[str] = []
    for p in _walk(root):
        if exclude is not None and p == exclude:
            continue
        if pattern.search(_text(p)):
            files.append(str(p.relative_to(MAIN)) if MAIN in p.parents else str(p))
    return len(files), files


def audit(main: Path = MAIN, features: Path = FEATURES) -> list[Row]:
    src = features.read_text(errors="ignore")
    rows: list[Row] = []
    for name, key, default, kdoc in parse_entries(src):
        wired, _ = count_refs(main, name, exclude=features)
        visible, ui_files = count_refs(main / "com/openminis/app/ui", name)
        reason = has_reason(kdoc)
        rows.append(
            Row(
                name=name,
                key=key,
                enabled=default,
                wired=wired,
                visible=visible,
                reason=reason,
                ui_files=ui_files,
            )
        )
    return rows


def load_baseline() -> dict:
    if not BASELINE.exists():
        return {"invisible_enabled": [], "closed_without_reason": []}
    return json.loads(BASELINE.read_text(encoding="utf-8"))


def write_baseline(rows: list[Row]) -> None:
    BASELINE.parent.mkdir(parents=True, exist_ok=True)
    data = {
        "_not": "Araç üretir: python3 scripts/ozel/gorunurluk.py --taban-yaz. Elle düzeltme yok.",
        "invisible_enabled": sorted(r.name for r in rows if r.state == "ACIK+GORUNMEZ"),
        "closed_without_reason": sorted(
            r.name for r in rows if r.state == "KAPALI" and not r.reason
        ),
    }
    BASELINE.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def report(rows: list[Row], detailed: bool = False) -> int:
    base = load_baseline()
    known_invisible = set(base.get("invisible_enabled", []))
    known_reasonless = set(base.get("closed_without_reason", []))

    visible = [r for r in rows if r.state == "ACIK+GORUNUR"]
    invisible = [r for r in rows if r.state == "ACIK+GORUNMEZ"]
    closed = [r for r in rows if r.state == "KAPALI"]

    new_invisible = [r for r in invisible if r.name not in known_invisible]
    new_reasonless = [r for r in closed if not r.reason and r.name not in known_reasonless]

    print(f"gorunurluk: {len(rows)} anahtar · "
          f"{len(visible)} acik+gorunur · {len(invisible)} acik+gorunmez · {len(closed)} kapali")

    for r in invisible:
        tag = "YENI" if r.name in {x.name for x in new_invisible} else "taban"
        print(f"  [acik-gorunmez/{tag}] {r.name:<20} wired={r.wired} ui=0")
        if detailed:
            print(f"      anahtar: {r.key}")

    for r in closed:
        mark = "gerekce var" if r.reason else ("taban" if r.name in known_reasonless else "GEREKCE YOK")
        print(f"  [kapali] {r.name:<20} ui={r.visible}  {mark}")

    if detailed:
        for r in visible:
            print(f"  [acik+gorunur] {r.name:<20} ui={r.visible} {r.ui_files[:3]}")

    problems = []
    if new_invisible:
        problems.append(
            "yeni acik-gorunmez anahtar: " + ", ".join(r.name for r in new_invisible)
            + " — ya kapali olmali ya ui/ altinda tuketicisi olmali"
        )
    if new_reasonless:
        problems.append(
            "kapali ama gerekcesi yazilmamis: " + ", ".join(r.name for r in new_reasonless)
        )
    if problems:
        for p in problems:
            print("KIRMIZI: " + p)
        return 1
    print("gorunurluk: temiz")
    return 0


def self_test() -> int:
    """Kapının kendisini sına: 4 sahte ağaç, beklenen sonuçlar.

    Neden: bir kapı ancak kendi hatasını yakalayabiliyorsa kapıdır. `.24`'te
    `kotlin-hizli.py` iki yanlış pozitif üretti ve susturulması gerekti; bu
    sınamayı o gün yazsaydık görürdük.
    """
    cases = {
        "gorunur_acik": (
            'enum class Feature(val key: String, val defaultEnabled: Boolean) {\n'
            '    /** Settings row. */\n'
            '    ALPHA("alpha", true),\n'
            '    ;\n}\n',
            "enum class X { ; }\n",
            "package ui\nval a = OzelFeatures.isEnabled(ctx, Feature.ALPHA)\n",
            "ACIK+GORUNUR",
        ),
        "yalniz_testte": (
            'enum class Feature(val key: String, val defaultEnabled: Boolean) {\n'
            '    BETA("beta", true),\n'
            '    ;\n}\n',
            "enum class X { ; }\n",
            "package ui\nval a = 1\n",
            "ACIK+GORUNMEZ",
        ),
        "kapali_gerekceli": (
            'enum class Feature(val key: String, val defaultEnabled: Boolean) {\n'
            '    /** OFF: no surface exists yet. */\n'
            '    GAMMA("gamma", false),\n'
            '    ;\n}\n',
            "enum class X { ; }\n",
            "package ui\nval a = 1\n",
            "KAPALI",
        ),
        "kapali_gerekcesiz": (
            'enum class Feature(val key: String, val defaultEnabled: Boolean) {\n'
            '    DELTA("delta", false),\n'
            '    ;\n}\n',
            "enum class X { ; }\n",
            "package ui\n// Feature.DELTA yorumda gecer ama kullanim degil\nval a = 1\n",
            "KAPALI",
        ),
    }
    failures = 0
    for label, (features_src, _, ui_src, expected) in cases.items():
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "com/openminis/app"
            (base / "data").mkdir(parents=True)
            (base / "ui").mkdir(parents=True)
            feat = base / "data/OzelFeatures.kt"
            feat.write_text(features_src)
            (base / "ui/Sample.kt").write_text(ui_src)
            src = feat.read_text()
            row = None
            for name, key, default, kdoc in parse_entries(src):
                if kdoc:
                    pass
            name, key, default, kdoc = parse_entries(src)[0]
            wired, _ = count_refs(base, name, exclude=feat)
            visible, ui_files = count_refs(base / "ui", name)
            reason = has_reason(kdoc)
            row = Row(name, key, default, wired, visible, reason, ui_files)
            ok = row.state == expected
            if expected == "KAPALI":
                # Gerekçe sınaması ayrı: iki KAPALI vakası bunu ayırt eder.
                want_reason = label == "kapali_gerekceli"
                ok = ok and row.reason == want_reason
            print(f"  {label:<22} -> {row.state:<14} gerekce={row.reason}  {'OK' if ok else 'HATALI'}")
            if not ok:
                failures += 1
    print(f"kendini-sina: {len(cases) - failures}/{len(cases)} gecti")
    return 0 if failures == 0 else 1


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--taban-yaz", action="store_true")
    ap.add_argument("--kendini-sina", action="store_true")
    ap.add_argument("--ayrintili", action="store_true")
    args = ap.parse_args()

    if args.kendini_sina:
        return self_test()

    rows = audit()
    if not rows:
        print("gorunurluk: anahtar bulunamadi — OzelFeatures.kt bicimi degismis olabilir", file=sys.stderr)
        return 2
    if args.taban_yaz:
        write_baseline(rows)
        print(f"taban yazildi: {BASELINE.relative_to(ROOT)}")
        return 0
    return report(rows, detailed=args.ayrintili)


if __name__ == "__main__":
    raise SystemExit(main())
