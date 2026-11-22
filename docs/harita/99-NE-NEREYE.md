<!-- ELLE YAZILDI: docs/harita/99-NE-NEREYE.md depo kökünde (SURUM-NOTU-*.md gibi).
     `scripts/ozel/harita.py --check` bunu üretmez ve silmez — hariç tutulur. -->

# Ne nereye — "bir şey ekledim, ne güncellenir?"

Bu sayfa **yönlendirme**dir: uzun anlatım değil, kısa cevaplar. Çoğu iş zaten
hazır; buradaki tablo yalnız "hangi dosya" ve "hangi kapı" diyor.

Kural: **harita elle güncellenmez** (`python3 scripts/ozel/harita.py`). Bu
sayfa ve `docs/ozel/HARITA.md` elle yazılan iki belgedir; onlar da bu tabloya
göre güncellenir.

---

## 1. Hızlı tablo — ne ekledim → ne yapılır

| Eklediğin şey | Zorunlu | Sonra (otomatik) |
|---|---|---|
| Ekran (`*Screen.kt`) | `AppNavigation.kt`e rota + giriş | harita `10-KOD.md` kendini günceller |
| Özellik anahtarı | `OzelFeatures.kt` + **üretimde okuyucu** + `OzelFeaturesScreen` satırı | harita `40-OZELLIKLER.md` |
| Risk kuralı | `RiskRules.kt` + `risk-corpus.txt`e **satır** (önce kırmızı, sonra yeşil) | `risk-corpus.py`, harita `30-KURALLAR.md` |
| Dilim | `scripts/slices.json` + `docs/ozel/ENTEGRASYON-DURUMU.md` | harita `20-DILIMLER.md`, `integration_audit.py` |
| Android izni | `AndroidManifest.xml` + **isteme akışı** (§FORK 1.1/5) | harita `50-IZINLER.md` |
| Kullanıcıya görünen metin | `values/` **ve** `values-tr/` (aynı commit'te) | `ceviri-denetim.py`, harita `60-CEVIRI.md` |
| Araç zinciri grubu | `ToolchainManager.groups` + string'ler | harita `10-KOD.md` |
| Sözleşme | `.ozel/sozlesmeler/<T>/` + `index.jsonl` (`ac` → `alan` → `kanit`) | harita `70-SOZLESMELER.md` |
| Test | Test dosyası + **gerçekten koşan** bir kapı | harita `80-TESTLER.md` |
| Belge | `docs/ozel/` (glob ile pakete girer) | harita `90-BELGELER.md` |
| Serileştirme/format değişikliği | göç + geri alma yolu | — |
| Davranış değişikliği | **`docs/ozel/DEGISIKLIK-GUNLUGU-11-16.md`** satırı | — |
| Yeni denetim aracı | `scripts/ozel/` + `ozel-guard.yml` + `paket.py` TOOL_FILES | harita `00-GIRIS.md` |
| Sürüm çıkışı | `SIRADAKI-SURUM.md` yeniden yazılır (üstüne eklenmez) | `snapshot.py`/`paket.py` |

---

## 2. Tek komutla doğrulama

```bash
cd 02-KAYNAK            # ya da depo kökü
python3 scripts/ozel/harita.py --check     # harita bayat mı
python3 scripts/ozel/risk-corpus.py --fail-on-mismatch
python3 scripts/ozel/kotlin-denetim.py --no-warn
python3 scripts/ozel/ceviri-denetim.py --fail-on-new
python3 scripts/ozel/apk-butce.py --fail-on-embed
python3 scripts/integration_audit.py
```

Altı kapı, toplam ~8 saniye. Biri kırmızıysa **hangisi** olduğunu adıyla söyler.

---

## 3. Sık yapılan beş hata (hepsi bu depoda bir kez yaşandı)

| Hata | Nasıl yakalanır | Ders |
|---|---|---|
| Anahtar açık, üretimde okuyucu yok | `40-OZELLIKLER.md` + elle `rg` | `.14` `STATION` |
| Metin yalnız `values/`e eklendi | `ceviri-denetim.py` | `.17` 71 anahtar |
| Kural eklendi, korpus satırı yok | `risk-corpus.py` | kural test edilmemiş sayılır |
| Belge yazıldı, pakete girmedi | `paket.py` glob'ları | `DENETIM-37098/99` |
| İddia sabitte, kapıda değil | sabiti `rg` ile ara: okuyan var mı | `MAX_EMBEDDED_ASSET_KB` |

---

## 4. Otomatik güncellenen şeyler

| Ne | Nasıl | Ne zaman |
|---|---|---|
| `docs/harita/*.md` | `scripts/ozel/harita.py` (kaynaktan) | elle veya CI (`--check`) |
| Paket `03-BELGE/` | `paket.py` glob'ları | paket üretiminde |
| `01-DAMGA/DAMGA.md` | `paket.py` (commit + push aralığı) | paket üretiminde |
| `06-KANIT/*.txt` | `paket.py` kapı koşuları | paket üretiminde |
| Git bundle | `paket.py` `--all` | paket üretiminde |

---

## 5. Elle güncellenen iki şey (ve nedeni)

| Belge | Neden elle |
|---|---|
| `docs/ozel/HARITA.md` | "Neden var, kim için" — yargı içerir, kaynaktan okunamaz |
| bu sayfa (`docs/harita/99-NE-NEREYE.md`) | süreç rehberi, kodun kendisi değil |

İkisi de sayı taşıdığı yerde **iddia etmez**: sayıyı `00-GIRIS.md`den okur.
