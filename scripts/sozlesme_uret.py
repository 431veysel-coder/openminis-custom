#!/usr/bin/env python3
"""Sözleşme belgelerini ve makine kaydını üretir.

Tek kaynak: `scripts/sozlesme_tanimlari.py`. Belge ile kapı kuralı ayrışırsa
belge yalan söyler; bu yüzden ikisi de aynı veriden doğar.

Kullanım:
    python3 scripts/sozlesme_uret.py            # yaz
    python3 scripts/sozlesme_uret.py --check    # üretilmiş dosyalar güncel mi
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from sozlesme_tanimlari import EKLENEN, KULLANICI_13, TURLER  # noqa: E402
from sozlesme_baglar import (CELISKI, GEREKCE, KADEME_ASGARI,  # noqa: E402
                             TAMAMLAYICI, ZORUNLU)

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/ozel/sozlesmeler"
REG = OUT / "sozlesmeler.json"


def _ascii(s: str) -> str:
    """Dosya adı için ASCII slug — Türkçe karakterli adlar link ve CLI'da kırılır."""
    import re as _re
    import unicodedata as _ud
    tr = str.maketrans("ıİşŞğĞüÜöÖçÇ", "iIsSgGuUoOcC")
    s = _ud.normalize("NFKD", s.translate(tr)).encode("ascii", "ignore").decode()
    return _re.sub(r"[^A-Za-z0-9]+", "-", s).strip("-")


BASLIK = "<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->\n"


def tur_md(t: dict) -> str:
    L: list[str] = [BASLIK]
    L.append(f"# {t['kod']} · {t['ad']} sözleşmesi\n")
    L.append(f"> **{t['kisa']}**\n")
    L.append(f"Kademe: **{t['kademe']}** · Öncelik: **{t['oncelik']}**\n")
    L.append("## Amaç\n")
    L.append(t["amac"] + "\n")
    L.append("## Ne zaman açılır\n")
    L.append(t["ne_zaman"] + "\n")
    L.append("## Zorunlu ve isteğe bağlı alanlar\n")
    L.append("| Alan | Ne | Zorunlu |")
    L.append("|---|---|---|")
    for ad, aciklama, zorunlu in t["alanlar"]:
        L.append(f"| `{ad}` | {aciklama} | {'✅' if zorunlu else '—'} |")
    L.append("")
    L.append("## Alt bölümler\n")
    L.append("| Kod | Ad | Kural |")
    L.append("|---|---|---|")
    for kod, ad, kural in t["alt"]:
        L.append(f"| {kod} | {ad} | {kural} |")
    L.append("")
    L.append("## Kapı — makine ne denetler\n")
    L.append(t["kapi"] + "\n")
    L.append("## Bu depodan gerçek örnek\n")
    L.append(t["ornek"] + "\n")
    L.append("## Yanlış kullanım\n")
    L.append(t["yanlis"] + "\n")
    L.append("---\n")
    L.append("Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: ")
    L.append("[`00-CEKIRDEK.md`](00-CEKIRDEK.md)\n")
    return "\n".join(L)


def baglanti_md() -> str:
    """Bağımlılık grafiği — 24 sözleşme arası ilişkiler."""
    ad = {t["kod"]: t["ad"] for t in TURLER}
    L: list[str] = [BASLIK]
    L.append("# Sözleşmeler arası bağlar\n")
    L.append("24 sözleşme bağımsız değil. Bu dosya **hangi sözleşmenin onsuz "
             "anlamsız ya da yanlış sonuç ürettiğini** söyler. `denetle` bu "
             "grafiği okur: zorunlu bağ açılmamışsa iş kapanamaz.\n")

    L.append("## 1 · ZORUNLU bağlar (A → B: A açıksa B de açık olmalı)\n")
    L.append("| Açılan | Gerektirir | Neden (bu depoda yaşanmış) |")
    L.append("|---|---|---|")
    for a, bs in ZORUNLU.items():
        for b in bs:
            gerek = GEREKCE.get(f"{a}→{b}", "—")
            L.append(f"| **{a}** {ad[a]} | **{b}** {ad[b]} | {gerek} |")
    L.append("")

    L.append("## 2 · TAMAMLAYICI bağlar (⇄ biri olmadan diğeri yarım)\n")
    L.append("| A | B | Neden |")
    L.append("|---|---|---|")
    for a, bs in TAMAMLAYICI.items():
        for b in bs:
            gerek = GEREKCE.get(f"{a}⇄{b}", "—")
            L.append(f"| **{a}** {ad[a]} | **{b}** {ad[b]} | {gerek} |")
    L.append("")

    L.append("## 3 · ÇELİŞKİ bağları (✗ karar zorunlu)\n")
    L.append("İkisi aynı işte açıksa zıt şey söyleyebilirler; `K` kaydı olmadan "
             "iş kapanamaz.\n")
    L.append("| A | B | Neden |")
    L.append("|---|---|---|")
    for a, bs in CELISKI.items():
        for b in bs:
            gerek = GEREKCE.get(f"{a}✗{b}", "—")
            L.append(f"| **{a}** {ad[a]} | **{b}** {ad[b]} | {gerek} |")
    L.append("")

    L.append("## 4 · Kademe → asgari küme\n")
    L.append("Bir iş başlarken hangi sözleşmelerin **zorunlu** açılacağı:\n")
    for k, kodlar in KADEME_ASGARI.items():
        L.append(f"- **{k}**: " + " · ".join(f"`{c}` {ad[c]}" for c in kodlar))
    L.append("")

    L.append("## 5 · Zincir örneği — \"sürüm çıkar\" işi\n")
    L.append("```")
    L.append("G  görev:        kapsam 'sürüm 37092', dışı 'yeni özellik'")
    L.append("  → K  karar:     versionCode 37091→37092, gerekçe + geri alma")
    L.append("  → V  veri:      şema 15 göçü + schemas/15.json commit")
    L.append("    → T test:     MigrationTestHelper 14→15")
    L.append("  → İ  izin:      contents: write yalnız build işinde")
    L.append("    → E emeklilik: artifact ömrü 90 gün")
    L.append("  → R  risk:      'onaysız yayın yok' freni yazılı")
    L.append("    ✗ Z zaman:    bütçe artırımı risk freniyle çelişebilir → K")
    L.append("  → S  sürüm:     imza SHA, önceki yayın, geri alma = YENİ numara")
    L.append("  → D  derleme:   assemble + log artifact")
    L.append("  → M  mesaj:     'yayınlandı' iddiasının kanıtı: run id + APK sha256")
    L.append("```")
    L.append("")
    L.append("Bu zincirde `V` atlanırsa `denetle` şunu der: ")
    L.append("`S açık ama V yok → göç test edilmemiş APK yayınlanabilir`.\n")

    L.append("## 6 · Bakım kuralları\n")
    L.append("| # | Kural | Neden |")
    L.append("|---|---|---|")
    L.append("| B1 | Bağ eklendiğinde `sozlesme_baglar.py` + `sozlesme_uret.py --check` | belge ile kapı ayrışmasın |")
    L.append("| B2 | Döngü yasak (A→B→A), `denetle` reddeder | döngüde 'önce hangisi' cevapsız kalır |")
    L.append("| B3 | Her bağın `GEREKCE` satırı olmalı | gerekçesiz bağ, kaldırılması gereken bağdır |")
    L.append("| B4 | Sözleşme silinmez, `emekli` işaretlenir | bağı olan sözleşmeyi silmek zinciri kırar |")
    L.append("| B5 | Yeni sözleşme eklerken en az bir bağ kurulur | bağsız sözleşme ölü ağırlıktır |")
    L.append("")
    return "\n".join(L)


def index_md() -> str:
    L: list[str] = [BASLIK]
    L.append("# Sözleşme ailesi — dizin\n")
    L.append("24 sözleşme, 4 kademe eşlemesi, her biri kendi makine kapısıyla.\n")
    L.append("**Önce [`00-CEKIRDEK.md`](00-CEKIRDEK.md) okunur** — kanıt biçimi, ")
    L.append("defter (JSONL) ve bitiş kapısı orada; tek tek dosyalar onu tekrar etmez.\n")

    L.append("## Kullanıcının saydığı 13 madde\n")
    L.append("| İstenen | Sözleşme | Kapsıyor mu |")
    L.append("|---|---|---|")
    ad_map = {t["kod"]: t for t in TURLER}
    for ad, kod in KULLANICI_13.items():
        t = ad_map[kod]
        L.append(f"| {ad} | **{kod} · {t['ad']}** | {t['kisa']} |")
    L.append("")

    L.append("## Sonradan eklenen 11 sözleşme\n")
    L.append("Her biri bu depoda **gerçekten yaşanmış** bir kaybın karşılığı:\n")
    L.append("| Kod | Sözleşme | Neden eklendi |")
    L.append("|---|---|---|")
    for kod, neden in EKLENEN.items():
        t = ad_map[kod]
        L.append(f"| **{kod}** | {t['ad']} | {neden} |")
    L.append("")

    L.append("## Tam tablo (öncelik sırası)\n")
    L.append("| Öncelik | Kod | Sözleşme | Kademe | Kapı |")
    L.append("|---|---|---|---|---|")
    for t in sorted(TURLER, key=lambda x: (x["oncelik"], x["kod"])):
        L.append(f"| {t['oncelik']} | [{t['kod']}]({t['kod']}-{_ascii(t['ad'])}.md) "
                 f"| {t['ad']} | {t['kademe']} | {t['kapi'][:70]}{'…' if len(t['kapi']) > 70 else ''} |")
    L.append("")

    L.append("## Kademe eşlemesi\n")
    L.append("| Kademe | Ne zaman | Açılan sözleşmeler |")
    L.append("|---|---|---|")
    k0 = [t["kod"] for t in TURLER if t["kademe"] == "K0"]
    k1 = [t["kod"] for t in TURLER if t["kademe"] == "K1"]
    k2 = [t["kod"] for t in TURLER if t["kademe"] == "K2"]
    L.append(f"| **K0** küçük | tek dosya, geri alınabilir | {' · '.join(k0)} |")
    L.append(f"| **K1** normal | çok dosya / test / davranış | {' · '.join(k0)} + {' · '.join(k1)} |")
    L.append(f"| **K2** sürüm | `versionCode` / yayın / göç | hepsi + {' · '.join(k2)} |")
    L.append("")

    L.append("## Bir turda hangi sözleşmeler açılır (pratik)\n")
    L.append("```")
    L.append("K0  → G (görev) + P (kod) + M (mesaj)")
    L.append("K1  → G + P + C (kilit) + T (test) + D (derleme) + M")
    L.append("K2  → K1 + K (karar) + S (sürüm) + V (veri) + İ (izin) + R (risk) + Z (bütçe)")
    L.append("hata → F (geri bildirim) her zaman, kademeden bağımsız")
    L.append("devir → N (nakil) her zaman, alt ajan/oturum varsa")
    L.append("```\n")

    L.append("## Komutlar\n")
    L.append("```bash")
    L.append("python3 scripts/sozlesme.py ac    --tur G --is health-37096 --kademe K1 \\")
    L.append("    --kapsam \"ChatViewModel 5 kanca\" --disi \"chip, ayar satırı\"")
    L.append("python3 scripts/sozlesme.py alan  --id G-health-37096 --alan kilit --deger \"ChatViewModel.kt:12224-12240\"")
    L.append("python3 scripts/sozlesme.py kanit --id G-health-37096 --deger \"test:HealthBridgeTest#maps429\"")
    L.append("python3 scripts/sozlesme.py kapat --id G-health-37096 --sonuc TAMAM")
    L.append("python3 scripts/sozlesme.py denetle")
    L.append("python3 scripts/sozlesme.py rapor   --id G-health-37096")
    L.append("```\n")
    return "\n".join(L)


def registry() -> dict:
    return {
        "_comment": [
            "Sözleşme kayıtları — kaynak: scripts/sozlesme_tanimlari.py",
            "Bu dosya kapılar tarafından okunur; belgelerle aynı veriden üretilir.",
            "required: kapatmadan önce dolu olması gereken alanlar.",
        ],
        "version": 1,
        "kademeler": {"K0": "kucuk", "K1": "normal", "K2": "surum"},
        "kanit_bicimleri": ["komut:", "test:", "dosya:", "kosu:"],
        "reddedilen_kanitlar": ["derlendi", "testler gecti", "calisiyor",
                                "kontrol ettim", "ok", "tamam"],
        "baglar": {
            "_comment": "denetle bu grafiği okur; döngü ve gerekçesiz bağ hata",
            "zorunlu": ZORUNLU,
            "tamamlayici": TAMAMLAYICI,
            "celiski": CELISKI,
            "kademe_asgari": KADEME_ASGARI,
            "gerekce": GEREKCE,
        },
        "turler": {
            t["kod"]: {
                "ad": t["ad"],
                "kisa": t["kisa"],
                "kademe": t["kademe"],
                "oncelik": t["oncelik"],
                "required": [a for a, _, z in t["alanlar"] if z],
                "optional": [a for a, _, z in t["alanlar"] if not z],
                "alert": [k for k, _, _ in t["alt"]],
                "kapi": t["kapi"],
                "belge": f"docs/ozel/sozlesmeler/{t['kod']}-{t['ad']}.md",
            }
            for t in TURLER
        },
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--check", action="store_true", help="üretilmiş dosyalar güncel mi")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    targets: dict[pathlib.Path, str] = {}
    for t in TURLER:
        targets[OUT / f"{t['kod']}-{_ascii(t['ad'])}.md"] = tur_md(t)
    targets[OUT / "DIZIN.md"] = index_md()
    targets[OUT / "BAGLANTI.md"] = baglanti_md()
    targets[REG] = json.dumps(registry(), ensure_ascii=False, indent=2) + "\n"

    stale = []
    for path, content in targets.items():
        if args.check:
            if not path.exists() or path.read_text(encoding="utf-8") != content:
                stale.append(path)
        else:
            path.write_text(content, encoding="utf-8")

    if args.check:
        if stale:
            for p in stale:
                print(f"BAYAT: {p.relative_to(ROOT)}")
            print("düzeltme: python3 scripts/sozlesme_uret.py")
            return 1
        print(f"sözleşme belgeleri güncel ({len(targets)} dosya)")
        return 0

    print(f"yazıldı: {len(targets)} dosya → {OUT.relative_to(ROOT)}/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
