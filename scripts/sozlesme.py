#!/usr/bin/env python3
"""Sözleşme defteri — aç / alan / kanıt / kapat / denetle / rapor.

Tasarım kararı: defter **append-only JSONL**. Satır silinmez; yanlış kayıt
`gecersiz` olayıyla iptal edilir. Böylece hem hata hem düzeltme görünür kalır
ve iki yazar aynı dosyaya farklı satırlara eklediği için git birleşmesi temiz
olur (Markdown tablo her yazmada tüm dosyayı yeniden yazar → çakışma üretir).

Kullanım:
    sozlesme.py ac      --tur G --is health-37096 --kademe K1 \
                        --kapsam "a" --kapsam "b" --disi "chip"
    sozlesme.py alan    --id G-health-37096 --alan kilit --deger "f.kt:1-9"
    sozlesme.py kanit   --id G-health-37096 --deger "test:FooTest#bar"
    sozlesme.py kapat   --id G-health-37096 --sonuc TAMAM
    sozlesme.py gecersiz --id G-health-37096 --neden "yanlis kapsam"
    sozlesme.py denetle [--json]
    sozlesme.py rapor   --id G-health-37096
    sozlesme.py liste

Kademe eşlemesi, zorunlu alanlar, bağ grafiği: `sozlesmeler.json`
(`scripts/sozlesme_uret.py` üretir; tek kaynak `sozlesme_tanimlari.py`).
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys
from datetime import datetime, timezone

ROOT = pathlib.Path(__file__).resolve().parents[1]
REG = ROOT / "docs/ozel/sozlesmeler/sozlesmeler.json"
DEFTER = ROOT / ".ozel/sozlesmeler"
INDEX = DEFTER / "index.jsonl"
RAPOR = ROOT / "docs/ozel/sozlesmeler/rapor"

KANIT_BICIM = {
    "komut": re.compile(r"^komut:.+$"),
    "test": re.compile(r"^test:[A-Za-z_][\w.]*#[^#]+$"),
    "dosya": re.compile(r"^dosya:[^#]+#[0-9a-f]{12}$"),
    "kosu": re.compile(r"^kosu:\d+.*$"),
}

#: Kanaat — kanıt değil. Kayıt bunlardan biriyle kapanmaya çalışırsa reddedilir.
REDDEDILEN = {"derlendi", "testler gecti", "testler geçti", "calisiyor",
              "çalışıyor", "kontrol ettim", "ok", "tamam", "+", ""}


def now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def taban() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"],
                              cwd=ROOT, capture_output=True, text=True).stdout.strip() or "?"
    except OSError:
        return "?"


def load_reg() -> dict:
    if not REG.exists():
        sys.exit(f"kayıt yok: {REG}\nönce: python3 scripts/sozlesme_uret.py")
    return json.loads(REG.read_text(encoding="utf-8"))


def turler() -> dict:
    return load_reg()["turler"]


def kayit_yolu(rid: str) -> pathlib.Path:
    tur = rid.split("-", 1)[0]
    return DEFTER / tur / f"{rid}.jsonl"


def ekle_olay(rid: str, olay: dict) -> None:
    """Deftere bir satır ekler. İki dosyaya yazar: tür dosyası + index."""
    olay = {"t": now(), "id": rid, **olay}
    line = json.dumps(olay, ensure_ascii=False)
    for path in (kayit_yolu(rid), INDEX):
        path.parent.mkdir(parents=True, exist_ok=True)
        with path.open("a", encoding="utf-8") as fh:
            fh.write(line + "\n")


def kayit_durumu(rid: str) -> dict:
    """Bir sözleşmenin olaylarını indirgeyerek son hâlini üretir."""
    path = kayit_yolu(rid)
    if not path.exists():
        return {}
    durum: dict = {"id": rid, "alanlar": {}, "kanitlar": [], "gecersiz": [],
                   "opened": False, "closed": False, "sonuc": None}
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip():
            continue
        o = json.loads(raw)
        op = o.get("op")
        if op == "ac":
            durum.update(opened=True, tur=o.get("tur"), is_=o.get("is"),
                         oturum=o.get("oturum"), taban=o.get("taban"),
                         kademe=o.get("kademe"), zaman=o.get("t"))
            durum["alanlar"]["kapsam"] = o.get("kapsam", [])
            durum["alanlar"]["disi"] = o.get("disi", [])
            # kademe/durma/butce `ac` ile gelir; alan denetimi onları da görsün
            for k in ("kademe", "durma", "butce"):
                if o.get(k):
                    durum["alanlar"][k] = o[k]
        elif op == "alan":
            durum["alanlar"][o.get("alan")] = o.get("deger")
        elif op == "kanit":
            durum["kanitlar"].append(o.get("deger"))
        elif op == "kapat":
            durum.update(closed=True, sonuc=o.get("sonuc"))
        elif op == "gecersiz":
            durum["gecersiz"].append(o.get("neden"))
            durum["closed"] = False
    return durum


def tum_idler() -> list[str]:
    if not DEFTER.exists():
        return []
    return sorted(p.stem for p in DEFTER.glob("*/*.jsonl"))


def kanit_gecerli_mi(deger: str, problemi_ekle) -> bool:
    d = (deger or "").strip()
    if d.lower() in REDDEDILEN:
        problemi_ekle(f"kanıt reddedildi: '{d}' kanaat, kanıt değil")
        return False
    for ad, pat in KANIT_BICIM.items():
        if d.startswith(ad + ":"):
            if not pat.match(d):
                problemi_ekle(f"kanıt biçimi bozuk ({ad}): {d}")
                return False
            if ad == "test":
                sinif_metot = d[5:]
                sinif = sinif_metot.split("#")[0].split(".")[-1]
                metot = sinif_metot.split("#", 1)[1]
                if not _test_var(sinif, metot):
                    problemi_ekle(f"test bulunamadı: {sinif}#{metot} (uydurma kanıt?)")
                    return False
            if ad == "dosya":
                yol, sha = d[6:].rsplit("#", 1)
                f = ROOT / yol
                if not f.exists():
                    problemi_ekle(f"dosya yok: {yol}")
                    return False
                import hashlib
                gercek = hashlib.sha256(f.read_bytes()).hexdigest()[:12]
                if gercek != sha:
                    problemi_ekle(f"kanıt bayat: {yol} sha={sha}, şimdi {gercek}")
                    return False
            return True
    problemi_ekle(f"kanıt biçimi tanınmadı (komut:/test:/dosya:/kosu:): {d}")
    return False


def _norm(s: str) -> str:
    """Karşılaştırma için sadeleştir: Türkçe harf, boşluk, backtick farkı kalksın.

    Neden: Kotlin test adları backtick içinde ve Türkçe karakterlerle yazılıyor
    (`p50 medyandır ortalama değil`). Kanıt metni ASCII yazıldığında eşleşme
    kopuyordu — kanıt kuralı doğru, karşılaştırma yanlıştı.
    """
    tr = str.maketrans("ıİşŞğĞüÜöÖçÇâÂîÎ", "iIsSgGuUoOcCaAiI")
    return "".join(ch for ch in s.translate(tr).lower() if ch.isalnum())


def _test_var(sinif: str, metot: str) -> bool:
    """Test adı gerçekten repoda var mı — uydurma kanıtı yakalar."""
    try:
        r = subprocess.run(["rg", "-l", "-F", sinif, str(ROOT / "src")],
                           capture_output=True, text=True)
        if r.returncode != 0:
            return False
        dosyalar = [l for l in r.stdout.splitlines() if l.strip()]
        if not dosyalar:
            return False
        if metot.strip() in ("*", ""):
            return True
        hedef = _norm(metot)
        for d in dosyalar:
            try:
                if hedef in _norm(pathlib.Path(d).read_text(encoding="utf-8", errors="ignore")):
                    return True
            except OSError:
                continue
        return False
    except OSError:
        return True  # rg yoksa kanıtı engellemeyiz; yanlış pozitif kapıyı kapatır


def denetle(rids: list[str], kapanis: bool = False) -> tuple[list[str], list[str]]:
    """(hatalar, uyarilar) — bağ grafiği dahil.

    `kapanis=True` sıkı moddur: `kapat` bunu kullanır. Fark bilinçli —
    ilerleme denetimi uyarı üretmeli (gürültülü kapıyı insan kapatır), ama
    kapanış denetimi üretmemeli. Aksi halde "K1 işte test kaydı yok" uyarısı
    görünüp iş yine de kapanıyordu; bu kapı değil, süs.
    """
    reg = load_reg()
    T = reg["turler"]
    B = reg["baglar"]
    hata: list[str] = []
    uyari: list[str] = []

    durumlar = {r: kayit_durumu(r) for r in rids}
    # Bağ denetimi TÜM deftere bakar: `kapat --id G-x` yalnız G-x'i yüklediğinde
    # kardeş sözleşmeler (P, C, T …) görünmüyordu ve her kapanış "P yok" diyordu.
    # Tek kaydı denetlemek için `denetle([rid])` yeter; bağ grafiği için bütün.
    bag_durumlar = {r: kayit_durumu(r) for r in tum_idler()}
    acik = {r: d for r, d in bag_durumlar.items() if d.get("opened") and not d.get("closed")}

    for rid, d in durumlar.items():
        tur = d.get("tur") or rid.split("-", 1)[0]
        if tur not in T:
            hata.append(f"{rid}: bilinmeyen tür '{tur}'")
            continue
        spec = T[tur]
        if not d.get("opened"):
            hata.append(f"{rid}: 'ac' olayı yok — kayıt boş")
            continue

        # 1) zorunlu alanlar
        eksik = [a for a in spec["required"] if not d["alanlar"].get(a)]
        if eksik:
            (hata if (d.get("closed") or kapanis) else uyari).append(
                f"{rid}: zorunlu alan(lar) boş: {', '.join(eksik)}")

        # 2) kapanış kanıtı
        if d.get("closed"):
            if not d["kanitlar"]:
                hata.append(f"{rid}: kapandı ama hiç kanıt yok (Ç1)")
            else:
                for k in d["kanitlar"]:
                    sorun: list[str] = []
                    kanit_gecerli_mi(k, sorun.append)
                    hata.extend(f"{rid}: {s}" for s in sorun)

        # 3) kademe ↔ açılan sözleşmeler (bağ grafiği)
        kademe = d.get("kademe") or "K1"
        is_ = d.get("is_")
        # "Var mı" sorusuna KAPANMIŞ kayıt da cevap verir: G'yi kapatıp P'yi
        # açık bırakmak meşru; aksi halde her kapanış kardeşlerini uyarıya boğar
        # ve gürültülü kapıyı insan kapatır.
        ayni_is = [r for r, x in bag_durumlar.items()
                   if x.get("is_") == is_ and r != rid and x.get("opened")]
        turler_acik = {tur} | {(bag_durumlar[r].get("tur") or r.split("-", 1)[0])
                               for r in ayni_is}
        for gerekli in B["kademe_asgari"].get(kademe, []):
            if gerekli not in turler_acik:
                (hata if (d.get("closed") or kapanis) else uyari).append(
                    f"{rid}: {kademe} işte '{gerekli}' ({T[gerekli]['ad']}) yok")

        # 4) zorunlu bağlar
        for a, bs in B["zorunlu"].items():
            if a not in turler_acik:
                continue
            for b in bs:
                if b not in turler_acik:
                    gerek = B["gerekce"].get(f"{a}→{b}", "")
                    (hata if (d.get("closed") or kapanis) else uyari).append(
                        f"{rid}: {a} açık ama {b} yok — {gerek}")

        # 5) çelişki → K zorunlu
        for a, bs in B["celiski"].items():
            if a not in turler_acik:
                continue
            for b in bs:
                if b in turler_acik and "K" not in turler_acik:
                    hata.append(f"{rid}: {a} ile {b} çelişiyor, K (karar) kaydı yok")

        # 6) yaş
        if not d.get("closed") and d.get("zaman"):
            try:
                t0 = datetime.strptime(d["zaman"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
                gun = (datetime.now(timezone.utc) - t0).days
                if gun >= 7:
                    uyari.append(f"{rid}: {gun} gündür açık (borç)")
            except ValueError:
                pass

    return hata, uyari


# ── komutlar ────────────────────────────────────────────────────────────────

def cmd_ac(a) -> int:
    T = turler()
    if a.tur not in T:
        sys.exit(f"bilinmeyen tür: {a.tur} (geçerli: {', '.join(T)})")
    rid = f"{a.tur}-{a.is_}"
    if kayit_yolu(rid).exists():
        sys.exit(f"kayıt zaten var: {rid} — yeni tur için yeni `--is`")
    if a.kademe not in load_reg()["kademeler"]:
        sys.exit(f"kademe K0/K1/K2 olmalı: {a.kademe}")
    if not a.kapsam:
        sys.exit("--kapsam en az bir kez verilmeli (kanıtlanabilir madde)")
    if len(a.kapsam) > 3:
        sys.exit("kapsam en çok 3 madde (G1): fazlası yeni turdur")
    ekle_olay(rid, {"op": "ac", "tur": a.tur, "is": a.is_, "oturum": a.oturum,
                    "taban": taban(), "kademe": a.kademe,
                    "durma": a.durma, "butce": a.butce,
                    "kapsam": a.kapsam, "disi": a.disi or []})
    print(f"açıldı: {rid} ({a.kademe}) · taban {taban()}")
    return 0


def cmd_alan(a) -> int:
    rid = a.id
    if not kayit_yolu(rid).exists():
        sys.exit(f"kayıt yok: {rid}")
    ekle_olay(rid, {"op": "alan", "alan": a.alan, "deger": a.deger})
    print(f"{rid}.{a.alan} = {a.deger}")
    return 0


def cmd_kanit(a) -> int:
    rid = a.id
    if not kayit_yolu(rid).exists():
        sys.exit(f"kayıt yok: {rid}")
    sorun: list[str] = []
    if not kanit_gecerli_mi(a.deger, sorun.append):
        for s in sorun:
            print(f"RED: {s}", file=sys.stderr)
        return 1
    ekle_olay(rid, {"op": "kanit", "deger": a.deger})
    print(f"{rid} kanıt: {a.deger}")
    return 0


def cmd_kapat(a) -> int:
    rid = a.id
    d = kayit_durumu(rid)
    if not d.get("opened"):
        sys.exit(f"kayıt yok ya da açılmamış: {rid}")
    if a.sonuc == "TAMAM":
        if not d["kanitlar"]:
            print("RED: kanıtsız kapatma (Ç1) — önce `kanit` ekle", file=sys.stderr)
            return 1
        hata, _ = denetle([rid], kapanis=True)
        if hata:
            for h in hata:
                print(f"RED: {h}", file=sys.stderr)
            return 1
    ekle_olay(rid, {"op": "kapat", "sonuc": a.sonuc})
    print(f"kapandı: {rid} → {a.sonuc}")
    return 0


def cmd_gecersiz(a) -> int:
    rid = a.id
    if not kayit_yolu(rid).exists():
        sys.exit(f"kayıt yok: {rid}")
    ekle_olay(rid, {"op": "gecersiz", "neden": a.neden})
    print(f"geçersiz: {rid} — {a.neden}")
    return 0


def cmd_denetle(a) -> int:
    rids = tum_idler()
    hata, uyari = denetle(rids)
    if a.json:
        print(json.dumps({"hata": hata, "uyari": uyari, "kayit": len(rids)},
                         ensure_ascii=False, indent=2))
        return 1 if hata else 0
    if not rids:
        print("defter boş — açık sözleşme yok")
        return 0
    print(f"{len(rids)} kayıt denetlendi")
    for u in uyari:
        print(f"  uyarı: {u}")
    for h in hata:
        print(f"  HATA: {h}")
    print("temiz" if not hata else f"{len(hata)} hata")
    return 1 if hata else 0


def cmd_rapor(a) -> int:
    d = kayit_durumu(a.id)
    if not d.get("opened"):
        sys.exit(f"kayıt yok: {a.id}")
    T = turler()
    tur = d.get("tur")
    spec = T.get(tur, {})
    L = [f"# {a.id}", "",
         f"- tür: **{tur} · {spec.get('ad','?')}** · kademe {d.get('kademe')}",
         f"- iş: `{d.get('is_')}` · oturum {d.get('oturum')} · taban `{d.get('taban')}`",
         f"- açılış: {d.get('zaman')} · durum: {'KAPALI ' + str(d.get('sonuc')) if d.get('closed') else 'AÇIK'}",
         ""]
    L.append("## Alanlar\n")
    L.append("| Alan | Değer |")
    L.append("|---|---|")
    for k, v in sorted(d["alanlar"].items()):
        val = ", ".join(v) if isinstance(v, list) else str(v)
        L.append(f"| `{k}` | {val or '—'} |")
    L.append("")
    L.append("## Kanıtlar\n")
    if d["kanitlar"]:
        for k in d["kanitlar"]:
            L.append(f"- `{k}`")
    else:
        L.append("- **yok** — kanıtsız kayıt")
    L.append("")
    if d["gecersiz"]:
        L.append("## Geçersiz kılınan\n")
        for g in d["gecersiz"]:
            L.append(f"- {g}")
        L.append("")
    RAPOR.mkdir(parents=True, exist_ok=True)
    out = RAPOR / f"{a.id}.md"
    out.write_text("\n".join(L), encoding="utf-8")
    print(f"yazıldı: {out.relative_to(ROOT)}")
    return 0


def cmd_liste(a) -> int:
    rids = tum_idler()
    if not rids:
        print("defter boş")
        return 0
    for rid in rids:
        d = kayit_durumu(rid)
        durum = "KAPALI" if d.get("closed") else "AÇIK"
        print(f"{durum:7} {rid:32} kademe={d.get('kademe','?')} kanıt={len(d['kanitlar'])}")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Sözleşme defteri")
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("ac"); p.add_argument("--tur", required=True)
    p.add_argument("--is", dest="is_", required=True)
    p.add_argument("--kademe", default="K1")
    p.add_argument("--kapsam", action="append", default=[])
    p.add_argument("--disi", action="append", default=[])
    p.add_argument("--oturum", default="A")
    p.add_argument("--durma", default="", help="hangi komut yeşil olunca durulur")
    p.add_argument("--butce", default="", help="süre/tur bütçesi")
    p.set_defaults(fn=cmd_ac)

    p = sub.add_parser("alan"); p.add_argument("--id", required=True)
    p.add_argument("--alan", required=True); p.add_argument("--deger", required=True)
    p.set_defaults(fn=cmd_alan)

    p = sub.add_parser("kanit"); p.add_argument("--id", required=True)
    p.add_argument("--deger", required=True); p.set_defaults(fn=cmd_kanit)

    p = sub.add_parser("kapat"); p.add_argument("--id", required=True)
    p.add_argument("--sonuc", default="TAMAM", choices=["TAMAM", "IPTAL", "DEVIR"])
    p.set_defaults(fn=cmd_kapat)

    p = sub.add_parser("gecersiz"); p.add_argument("--id", required=True)
    p.add_argument("--neden", required=True); p.set_defaults(fn=cmd_gecersiz)

    p = sub.add_parser("denetle"); p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_denetle)

    p = sub.add_parser("rapor"); p.add_argument("--id", required=True)
    p.set_defaults(fn=cmd_rapor)

    p = sub.add_parser("liste"); p.set_defaults(fn=cmd_liste)

    a = ap.parse_args()
    return a.fn(a)


if __name__ == "__main__":
    sys.exit(main())
