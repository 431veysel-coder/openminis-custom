# SÜRÜM NOTU — 1.15-ozel.37 / 37118

> **Yayındaki sürüm:** 1.15-ozel.34 / 37115 (`.35`, `.36` derlenmedi).
> **Kapılar:** 11/11. **Derleme yapılmadı.**

## 0 · Onay tamamen kalktı (kullanıcı isteği)

Kullanıcı: *"sen onay istesen ben onay versem de vermesem de onay vermişim gibi
hareket edeceksin… tüm yetki sende, tüm izinler sende"*.

- `ApprovalMode` **varsayılanı `ALLOW_ALL`** oldu (önce `SETTINGS_ONLY`).
  Bilinmeyen/bozuk tercih de `ALLOW_ALL`'a düşer — bozuk bir değer yüzünden
  kullanıcıyı kart yağmuruna döndürmek, verilmiş kararı görmezden gelmek olurdu.
- `deny:` sınıfı **hâlâ kapalı** ve kapalı kalacak: `rm -rf /`, `mkfs`,
  disk biçimlendirme bir onay değil, **sandbox sınırı**. Bir ayar anahtarına
  bağlanırsa, web sayfasından gelen bir komut kullanıcının kendi ayarını
  kullanarak sandbox'ı siler. `ApprovalModeTest` bunu 3 mod × bütün `deny:`
  kuralları üzerinde çiviliyor.
- **Kart kalktı, haber kalkmadı:** geri alınamaz (PERMANENT) ya da sandbox
  dışı (DEVICE/NETWORK/ACCOUNT) her iş `ApprovalVoice.notice()` ile söylenir —
  tek cümle, titreşim, bildirim. Beklemez, tekrarlamaz.
  Cümle: *"Şunu yapıyorum. Dizin ağacı silinecek. İşlem: rm -rf …. Geri gelmez.
  Ayarlar'dan onay isteğini geri açabilirsin."*
  Gerekçe: kullanıcının şikâyeti "onay veremiyorum"du; "ne olduğunu bilmiyorum"
  değil. Onayı kaldırırken görünürlüğü de kaldırmak, sessiz bir silme makinesi
  yapardı.

## 1 · `screen` aracı — sanal ekran artık gerçekten sürülebiliyor

`.34`'te `VirtualScreenHost.Op` yazıldı ve **0 çağrı**ydı; `markCaptured` de
öyleydi. Bu sürüm o API'nin tek çağıranını yazdı.

| Dosya | Ne |
|---|---|
| `tools/ScreenTool.kt` (yeni) | 8 eylem: state · open · focus · hide · scroll · type · click · capture |
| `screen/ScreenRequest.kt` (yeni) | saf çözümleyici: hangi eylem hangi argümanı ister |
| `screen/VirtualScreenHost.markCaptured` (yeni) | yakalama damgası — bölme başına |
| `tools/AgentTools.kt` | `screenEnabled` parametresi; şemaya kayıt |
| `ui/chat/ChatViewModel.kt` | şema parametresi + **sevk kapısı** (`ScreenTool.enabled`) |
| `agent/router/ToolRouter.kt` | `screen` ipuçları — `android-a11y-cli`'den bilerek ayrık |

**Dürüstlük kararları:**

1. **`click` "tıklandı" demiyor.** "click recorded at (x,y); the pane's own page
   does not receive it yet" diyor. Bölme içi DOM/PTY hattı yok (§5.1 tek tuval
   `.38`'de). Saklamak, modelin sonraki turda yanlış varsayım kurmasına ve
   kullanıcıya olmayan bir işi raporlamasına yol açardı.
2. **`capture` kanıt istiyor.** Kare üretilemezse `success=false` +
   "do not describe what is on the virtual screen". `.29`'da `needsCapture`
   "düzeltildi" sanılıp 5 sürüm latent kaldı; aynı sınıfı tekrarlamıyorum.
   Şu an **hiçbir kare kaynağı bağlı değil** (`frameSource = null`): bölmeler
   henüz çizilmiyor. `.38` bağlayacak. Araç bunu saklamıyor.
3. **B (gerçek ekran) bu araçta yok.** `click`/`type` gerçek telefonda
   `CONFIRM` ister ve o yol `android-a11y-cli`. İkisini birleştirmek A/B
   ayrımını tek yazım hatasıyla kaybettirirdi.
4. **`deny:`/risk kuralı eklenmedi.** `PolicyGate` yalnız `shell_execute`,
   `file_write`, `file_edit` sınıflandırıyor; `screen` A-modu bir araçtır ve
   kullanıcının ekranına dokunmaz. Kural eklemek ölü kod olurdu.

## 2 · Kapıda gerçek hata: `kotlin-hizli` yanlış "bu alan yok"

`ChatViewModel`'e dokunduğum anda kapı iki "kesin" hata verdi:
`Compaction(marker = …)`, `Compaction(cachedWarmUpDrop = …)` — **ikisi de var**.

Kök neden: iki ayrı argüman bölücü (`re.split` ve kendi döngüsü) `<`/`>` sayarken
`->` oku **kapanış** sanıyordu. Bir veri sınıfının alanı fonksiyon tipi olunca
(`cachedWarmUpDrop: (String) -> Int?`) derinlik negatife düşüyor, sonraki
virgüller hiç bölünmüyor, alan listesi **ilk alanda kesiliyordu**. Ölçüm:
`Compaction` alanları `{summary}` görünüyordu; doğrusu beş alan.

Düzeltme: bölme kuralı **tek yardımcıda** (`_split_top_level`), `->` ok olarak
tanınıyor. Ders: aynı kural iki yerde iki kez yazılırsa biri bozulur ve kapı
sahibi olmadığın borcu ödetir.

## 3 · Testler

- `policy/ApprovalModeTest` — **9** test (varsayılan `ALLOW_ALL`, `deny:` kilidi,
  `notifyOnly` duyuru sınıfı: geri gelmez + cihaz, sessiz: `pkg:install`).
- `screen/ScreenRequestTest` — **12** test (bölme zorunluluğu, eksik argüman
  mesajları, negatif kırpma, metinden sayı, boş metin).

## Kapılar

```
apk-butce · ceviri · denetim · entegrasyon · etiket · gorunurluk
harita · korpus · kotlin · olu-kod · yapisal
11/11 geçti · 0 uyarı · 49.9s
```

## Kapanan / açılan

| Madde | Durum |
|---|---|
| §5.2 bölme içi eylem (`screen` aracı) | **kapandı** — 8 eylem, sevk kapısı, testler |
| §5.3 bölme başına yakalama bütçesi | **kapandı** — `markCaptured` çağrılıyor |
| Onay kartı yorgunluğu (son kalan) | **kapandı** — `ALLOW_ALL` varsayılan |
| §5.1 tek tuval / gerçek kare | **açık** → `.38` (`frameSource` bağlanacak) |
| Cihazda ses sınaması | **açık** → `.45` |
