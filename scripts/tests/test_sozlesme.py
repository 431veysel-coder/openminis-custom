"""Sözleşme defteri testleri — saf Python, 14 vaka.

Kural: her test geçici bir kök dizinde çalışır (`tempfile`), gerçek `.ozel/`
defterine dokunmaz. Aksi halde test, gerçek kayıtları kirletir.
"""
from __future__ import annotations

import importlib.util
import json
import pathlib
import tempfile

import pytest

SPEC = importlib.util.spec_from_file_location(
    "sozlesme", pathlib.Path(__file__).resolve().parents[1] / "sozlesme.py")
sz = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sz)


@pytest.fixture()
def defter(tmp_path, monkeypatch):
    """Geçici defter kökü: DEFTER/INDEX/RAPOR yolları taşınır."""
    monkeypatch.setattr(sz, "DEFTER", tmp_path / ".ozel/sozlesmeler")
    monkeypatch.setattr(sz, "INDEX", tmp_path / ".ozel/sozlesmeler/index.jsonl")
    monkeypatch.setattr(sz, "RAPOR", tmp_path / "rapor")
    monkeypatch.setattr(sz, "taban", lambda: "abc1234")
    monkeypatch.setattr(sz, "ROOT", tmp_path)
    return tmp_path


def ac(tur, is_, kademe="K1", kapsam=("k",), disi=("d",)):
    return sz.cmd_ac(type("A", (), {
        "tur": tur, "is_": is_, "kademe": kademe, "kapsam": list(kapsam),
        "disi": list(disi), "oturum": "A", "durma": "komut", "butce": ""})())


def alan(rid, ad, deger):
    return sz.cmd_alan(type("A", (), {"id": rid, "alan": ad, "deger": deger})())


def kanit(rid, deger):
    return sz.cmd_kanit(type("A", (), {"id": rid, "deger": deger})())


def kapat(rid):
    return sz.cmd_kapat(type("A", (), {"id": rid, "sonuc": "TAMAM"})())


# ── 1-3: kanıt biçimi ──────────────────────────────────────────────────────

def test_kanaat_kanit_degildir():
    """'derlendi' bir sonuç değil adımdır; kapı reddeder."""
    sorun = []
    assert not sz.kanit_gecerli_mi("derlendi", sorun.append)
    assert "kanaat" in sorun[0]


def test_bilinmeyen_bicim_reddedilir():
    sorun = []
    assert not sz.kanit_gecerli_mi("bence iyi", sorun.append)
    assert "tanınmadı" in sorun[0]


def test_komut_bicimi_kabul():
    assert sz.kanit_gecerli_mi("komut:python3 scripts/preflight.py", lambda _: None)


def test_kosu_bicimi_kabul():
    assert sz.kanit_gecerli_mi("kosu:44622129907", lambda _: None)


def test_dosya_kaniti_bayat_sayilir(defter):
    """Kanıt hash'i dosyanın bugünkü haliyle uyuşmazsa bayattır."""
    f = defter / "a.txt"
    f.write_text("bir")
    sorun = []
    assert not sz.kanit_gecerli_mi(f"dosya:a.txt#{'0' * 12}", sorun.append)
    assert "bayat" in sorun[0]


def test_uydurma_test_reddedilir():
    """Repoda olmayan test adı kanıt sayılmaz — uydurmayı yakalar."""
    sorun = []
    assert not sz.kanit_gecerli_mi("test:YokTest#yok_metot", sorun.append)
    assert "bulunamadı" in sorun[0]


def test_gercek_test_kabul():
    """Var olan test ASCII yazılsa da eşleşir (Türkçe harf sadeleştirmesi)."""
    assert sz.kanit_gecerli_mi(
        "test:ChatHealthTrackerTest#p50 medyandir ortalama degil", lambda _: None)


# ── 4-6: kapsam ve alanlar ─────────────────────────────────────────────────

def test_kapsam_uc_madde_tavani(defter):
    """G1: dördüncü kapsam maddesi yeni turdur."""
    with pytest.raises(SystemExit):
        ac("G", "x", kapsam=("1", "2", "3", "4"))


def test_kapsamsiz_acilmaz(defter):
    with pytest.raises(SystemExit):
        sz.cmd_ac(type("A", (), {
            "tur": "G", "is_": "y", "kademe": "K1", "kapsam": [],
            "disi": [], "oturum": "A", "durma": "", "butce": ""})())


def test_ayni_id_iki_kez_acilmaz(defter):
    ac("G", "z")
    with pytest.raises(SystemExit):
        ac("G", "z")


# ── 7-9: bağ grafiği ───────────────────────────────────────────────────────

def test_k1_eksik_kardes_kapatmayi_engeller(defter):
    """K1 işte P/C/T/D/M yoksa kapanış reddedilir (bağ grafiği)."""
    ac("G", "j")
    kanit("G-j", "komut:true")
    assert kapat("G-j") == 1


def test_k1_tam_zincir_kapanir(defter):
    """G+P+C+T+D+M birlikte açıkken G kapanabilir."""
    for tur in ("G", "P", "C", "T", "D", "M"):
        ac(tur, "j", kademe="K1")
    kanit("G-j", "komut:true")
    assert kapat("G-j") == 0


def test_zorunlu_bag_uyarisi(defter):
    """S (sürüm) açıkken V (veri) yoksa uyarı çıkar — gerekçesiyle."""
    ac("S", "v", kademe="K2")
    ac("K", "v", kademe="K2")
    _, uyari = sz.denetle(sz.tum_idler())
    assert any("V" in u and "Veri" in u for u in uyari), uyari


def test_celiski_karar_zorunlu(defter):
    """R ile Z çelişir; K yoksa bu HATA'dır, uyarı değil."""
    ac("R", "c", kademe="K2")
    ac("Z", "c", kademe="K2")
    hata, _ = sz.denetle(sz.tum_idler())
    assert any("çelişiyor" in h for h in hata), hata


# ── 10-12: defter davranışı ────────────────────────────────────────────────

def test_defter_append_only(defter):
    """Yeni olay eskisini silmez; satır sayısı artar."""
    ac("G", "a")
    once = sz.kayit_yolu("G-a").read_text().count("\n")
    alan("G-a", "kilit", "f.kt:1-9")
    sonra = sz.kayit_yolu("G-a").read_text().count("\n")
    assert sonra == once + 1


def test_gecersiz_kaydi_yeniden_acar(defter):
    """`gecersiz` kapanışı geri alır ama olayı silmez (ikisi de görünür)."""
    for tur in ("G", "P", "C", "M"):
        ac(tur, "g", kademe="K0")
    kanit("G-g", "komut:true")
    assert kapat("G-g") == 0
    assert sz.kayit_durumu("G-g")["closed"] is True
    sz.cmd_gecersiz(type("A", (), {"id": "G-g", "neden": "yanlis"})())
    d = sz.kayit_durumu("G-g")
    assert d["closed"] is False
    assert d["gecersiz"] == ["yanlis"]


def test_kanitsiz_kapatma_reddedilir(defter):
    for tur in ("G", "P", "C", "M"):
        ac(tur, "n", kademe="K0")
    assert kapat("G-n") == 1


# ── 13-14: belge/kayıt tutarlılığı ─────────────────────────────────────────

def test_kayit_ile_belge_ayrismaz():
    """sozlesmeler.json ile üretilmiş belgeler aynı veriden doğmuş olmalı."""
    import subprocess
    root = pathlib.Path(__file__).resolve().parents[2]
    r = subprocess.run(["python3", "scripts/sozlesme_uret.py", "--check"],
                       cwd=root, capture_output=True, text=True)
    assert r.returncode == 0, r.stdout + r.stderr


def test_bag_grafiginde_dongu_yok():
    """A→B→A döngüsü 'önce hangisi' sorusunu cevapsız bırakır."""
    import sys
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
    from sozlesme_baglar import ZORUNLU

    def yol_derinlik(baslangic, hedef, gorulen):
        if baslangic == hedef:
            return True
        if baslangic in gorulen:
            return False
        gorulen.add(baslangic)
        for nxt in ZORUNLU.get(baslangic, []):
            if yol_derinlik(nxt, hedef, gorulen):
                return True
        return False

    dongu = [(a, b) for a, bs in ZORUNLU.items() for b in bs
             if yol_derinlik(b, a, set())]
    assert not dongu, f"döngü: {dongu}"


def test_her_bagin_gerekcesi_var():
    """Gerekçesiz bağ, kaldırılması gereken bağdır (B3)."""
    import sys
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
    from sozlesme_baglar import CELISKI, GEREKCE, TAMAMLAYICI, ZORUNLU

    eksik = []
    for a, bs in ZORUNLU.items():
        for b in bs:
            if f"{a}→{b}" not in GEREKCE:
                eksik.append(f"{a}→{b}")
    for a, bs in TAMAMLAYICI.items():
        for b in bs:
            if f"{a}⇄{b}" not in GEREKCE:
                eksik.append(f"{a}⇄{b}")
    for a, bs in CELISKI.items():
        for b in bs:
            if f"{a}✗{b}" not in GEREKCE:
                eksik.append(f"{a}✗{b}")
    assert not eksik, f"gerekçesiz bağ: {eksik}"


def test_her_tur_en_az_bir_baga_gecir():
    """Bağsız sözleşme ölü ağırlıktır (B5)."""
    import sys
    sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
    from sozlesme_baglar import CELISKI, TAMAMLAYICI, ZORUNLU

    bagli = set()
    for d in (ZORUNLU, TAMAMLAYICI, CELISKI):
        for a, bs in d.items():
            bagli.add(a)
            bagli.update(bs)
    tumu = set(sz.turler())
    assert not (tumu - bagli), f"bağsız: {sorted(tumu - bagli)}"
