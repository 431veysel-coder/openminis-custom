#!/usr/bin/env python3
"""Sürüm notu üreticisi — 1.15-ozel.15 için.

`apk.yml` içindeki otomatik not üreticisi commit başlıklarından bir taslak
kuruyor; bu sürümde kullanıcıya görünen değişiklikler commit başlıklarından
anlaşılmıyor. Bu dosya notu elle yazar, release adımı gövdeyi buradan alır.

Kullanım: metin olarak bas (release gövdesine ver).
"""

METIN = """\
## Ne değişti

**Sohbet sağlık göstergesinin çekirdeği** (arayüzü henüz yok — sıradaki sürümde
üst çubukta görünecek). Ölçüm artık kayıt altında: ilk parça gecikmesinin
medyanı (son 20 ölçüm), tekrar deneme sayısı, hata sınıfı. `ChatHealthTracker`
saat dışarıdan alır; 9 test gerçek zaman beklemeden koşar. Ağ hatası "hata"
değil **ulaşılamıyor** olarak ayrıldı.

**Yayın hattında 8 gerçek hata düzeltildi.** En önemlisi: sürüm ve yayın
kapıları **sessizce açıktı** — `gh release view ... || true` bir 401/403'ü
"yayın yok" sayıyordu, yani yakılmış bir versionCode tekrar yayınlanabilirdi.
Artık 404 izin, diğer her hata **ret**. Ayrıca `bash -eo pipefail` altında
`| head`/`| grep -q` boruları geçen kontrolü kırmızıya çevirebiliyordu (43 MB
APK üzerinde üreme olasılığı yüksekti) — hepsi kapatıldı.

**Derleme logları artık kaybolmuyor.** Her iş log dosyası üretiyor
(`tee` + `pipefail`), her koşulda 90 günlük artifact olarak yükleniyor
(`if: always()` — kırılan işte de). Koşu sayfasında sürüm/boyut/sha256 özeti var.
Artifact kopyası maskelenmediği için yüklemeden önce sır temizliği çalışıyor;
sha256 özetleri bilerek korunuyor.

**Güvenlik:** `contents: write` akış genelindeydi, altı işin dördü yalnız kod
okuyordu — yazma izni yalnız yayın işine indirildi. Tek üçüncü taraf action
(`softprops/action-gh-release`) commit SHA'sına sabitlendi.

**Sözleşme ailesi (24 sözleşme).** Bir işin sınırı, kanıtı, kararı, riski,
bütçesi ve geri alınışı artık yazılı. Kanıt kuralı makineye bağlı: "derlendi /
çalışıyor / testler geçti" **kabul edilmiyor** — `test:Sınıf#metot`,
`komut:...`, `dosya:yol#sha256` veya koşu kimliği gerekiyor. Uydurma test adı
kapıdan geçmiyor. `preflight` ve CI bunu denetliyor.

## Bu sürümde yayınlanan özellik paketi

37080–37090 dilimleri de bu sürümle birlikte geliyor: onay kapısı, bütçe tavanı,
otomatik yedek politikası, plan önizleme, tur özeti diff'i, araç yönlendirme,
ders defteri, kanıtlı "bitti", yazma niyeti, yeniden oynatma.

## Bilinen açıklar

- Sağlık göstergesinin **arayüzü yok**; ölçüm toplanıyor, chip 37096'da geliyor.
- `schemas/…/15.json` (Room göç şeması) repoda değil — CI artifact'ından indirilip
  commit edilecek. Göçün kendisi var ve çalışıyor.
- `TeamMode.FANOUT` artık gerçekten bağımsız yanıt üretiyor ama `verified=false`:
  hiçbir tur bunu canlı doğrulamadı.
- `remote/RemotePolicy.kt` (uzaktan konsol jeton modeli) bağlı değil, anahtarı
  kapalı — kaldırılması öneriliyor.

## Doğrulama

- `preflight` temiz · `bash -n` 30/30 blok · `actionlint` 0 hata
- `pytest scripts/tests/test_sozlesme.py` → 21/21
- Slice Gate: dilimler tek tek derlendi ve testleri geçti
- Debug APK koşusu: 6 iş yeşil, 4 log artifact'ı dolu

_Derleme logları: `run-logs-*` artifact'ları (90 gün)._
"""

if __name__ == "__main__":
    print(METIN)
