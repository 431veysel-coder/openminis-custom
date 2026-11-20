# Gizlilik — hangi veri nereye gidiyor

Bu belge **Minis X** (`com.minis.x`) uygulamasının veri akışını
açıklar. Kısa cevap: **uygulamanın kendi sunucusu yok.** Hiçbir veri bu depoya
veya bakımcısına gitmez; her şey ya cihazında kalır ya da **senin seçtiğin**
yapay zekâ sağlayıcısına gider.

Upstream [OpenMinis](https://github.com/OpenMinis/OpenMinis) ile aynı mimari.
Bu belge, çatalın eklediği özellikler için ek şeffaflık sağlar.

---

## 1. Cihazda kalan veriler

Bunlar **hiçbir yere gönderilmez**:

| Veri | Nerede |
|---|---|
| Sohbet geçmişi, mesajlar | Uygulama veritabanı (`filesDir`) |
| API anahtarları | Cihazdaki uygulama deposu |
| Ayarlar, tercihler | SharedPreferences |
| Sandbox kök dosya sistemi + kurulan araç zincirleri | `filesDir/alpine-rootfs` |
| Çeviri önbelleği (düşünme blokları) | `filesDir` altında, dil+model+metin anahtarıyla |
| Loglar | Cihazda, 24 saat sonra silinir |
| Ekran görüntüleri, indirilen dosyalar | `filesDir` / paylaşılan klasörler |

**Yedekleme** açıksa sohbetler ve ayarlar upstream'in şifreleme şemasıyla
şifrelenip senin seçtiğin hedefe (yerel dosya ya da kendi bulut hesabın)
gider. Sunucumuz yok.

**Sandbox ağ erişimi:** Kabuk, cihazın ağına senin izninle çıkar. Ajan bir
komut çalıştırdığında o komutun ne yaptığı sana bağlıdır — bu yüzden
**Canlı Görevler** paneli var.

---

## 2. Dışarı giden veriler

### 2.1 Yapay zekâ sağlayıcıları — sen seçersin

Her sohbet isteği, **senin yapılandırdığın** sağlayıcıya gider. Biz aracı
değiliz:

| Sağlayıcı | Veri |
|---|---|
| OpenAI, Anthropic, Google, xAI, OpenRouter… | Mesajlar, sistem istemi, araç tanımları, ekli görseller, araç çağrısı sonuçları |
| Kendi barındırdığın (Ollama, LM Studio, vLLM…) | Yalnız senin sunucuna |

Gönderilen: **konuşma içeriği**, **araç tanımları**, **araç sonuçları**
(dosya içerikleri, kabuk çıktısı dahil — çünkü model bunları görmek zorunda)
ve **görseller**.

Gönderilmeyen: cihaz kimliği, konum, kişiler, diğer uygulamaların verisi.

Her sağlayıcının kendi gizlilik politikası vardır ve **onlara** tabisin.
Uygulama bunu değiştiremez.

### 2.2 Evren API — çatalın eklediği sağlayıcı

> **Bu bir üçüncü taraf servistir.** `https://evren-llmapi.ssyz.org.tr`
> adresinde çalışır, bu deponun bakımcısına ait **değildir** ve bu depo
> tarafından denetlenmez.

"Sağlayıcı Ekle" ekranındaki bir ön ayardır — kolaylık için var, **zorunlu
değil**. Seçersen mesajların o servise gider ve o servis onları kendi model
sağlayıcılarına iletir. Hangi veriyi ne kadar tuttuğu, nereye ilettiği
**onların** politikasına bağlıdır; buradan garanti edilemez.

**Kullanmak zorunda değilsin.** Kendi OpenAI/Anthropic/Gemini anahtarını
girebilir ya da kendi sunucunu bağlayabilirsin. Evren ön ayarını
kullanmıyorsan o servise tek bir bayt bile gitmez.

Servis çökerse bu **çatalın hatası değildir** — ama bunu sana söylemek
çatalın sorumluluğu.

### 2.3 Düşünme bloğu çevirisi — ek istek, ek ücret

Açıkken (varsayılan **açık**, ama **kendiliğinden çalışmaz** — çeviri
düğmesine sen basarsın) bir düşünce bloğunu çevirmek için **seçtiğin modele
ikinci bir istek** gönderilir.

| | |
|---|---|
| Ne gönderilir | Düşünce bloğunun metni + dil talimatı |
| Nereye | Sohbeti yaptığın sağlayıcı |
| Ücret | **Evet** — sağlayıcın bunu ek girdi/çıktı olarak faturalandırır |
| Önbellek | Evet, `filesDir`'de; aynı blok ikinci kez çevrilmez |

Ayarlar → Düşünme → Çeviri'den kapatılabilir. Kapalıyken hiçbir ek istek
gitmez.

### 2.4 Güncelleme kontrolü — GitHub

Uygulama güncellemeleri **`431veysel-coder/openminis-custom`** deposunun
GitHub Releases API'sinden okur.

| | |
|---|---|
| Ne gönderilir | Sıradan bir HTTPS isteği (IP adresin GitHub'a görünür) |
| Ne gönderilmez | Sohbetler, anahtarlar, cihaz kimliği |
| Ne indirilir | APK + sürüm bilgisi |

**İmza doğrulaması:** İndirilen APK, çalışan uygulamayla **aynı imza
anahtarıyla** imzalanmamışsa kurulmaz (`UpdateSignatureGuard`). GitHub hesabı
ele geçse bile farklı anahtarlı bir APK reddedilir.

### 2.5 Dil modeli listesi / model meta verisi

Bazı ekranlar model başına bağlam penceresi ve fiyat bilgisini gösterir.
Bu bilgi `models.dev` gibi herkese açık kaynaklardan çekilebilir — yalnızca
**model kimliği** sorulur, kişisel veri gitmez.

### 2.6 Tarayıcı aracı

Ajan tarayıcıyı kullandığında ziyaret ettiği siteler normal bir tarayıcı
gibi görünür (IP, user-agent, çerezler). Site sahibi seni görür. Bu kaçınılmaz.

---

## 3. Toplamadığımız şeyler

Açıkça, hiçbiri:

- ❌ Telemetri / kullanım istatistiği
- ❌ Çökme raporlarının otomatik gönderimi (loglar cihazda kalır)
- ❌ Reklam kimliği, cihaz kimliği, konum
- ❌ Kişiler, takvim, fotoğraflar (izin verip bir ajan görevi vermedikçe)
- ❌ Bizim sunucumuz — **yok**

---

## 4. İzinler ve neden istendiği

| İzin | Ne için | Zorunlu mu |
|---|---|---|
| `INTERNET` | Sağlayıcı çağrıları, güncelleme | Evet |
| Bildirimler | Uzun görevlerin bitişi, sorular | Hayır |
| Pil optimizasyonu muafiyeti | Arka planda uzun görev | Hayır |
| Bildirim erişimi | Bildirimleri okuma aracı | Hayır |
| Erişilebilirlik servisi | Ekran okuma / UI otomasyonu | Hayır |
| Mikrofon | Sesli komut | Hayır |
| Kamera / fotoğraflar | Görsel ekleme | Hayır |
| Kesin alarm | Zamanlanmış görevler | Hayır |

Her biri **istenmeden önce** ne işe yaradığı gösterilir
(`diagnostics/BackgroundGates.kt`). Vermezsen yalnız o özellik çalışmaz.

---

## 5. Verini silmek

| Ne | Nasıl |
|---|---|
| Tek sohbet | Sohbeti sil |
| Tüm veriler | Ayarlar → Uygulamayı sıfırla, ya da uygulamayı kaldır |
| Sandbox + araç zincirleri | Kaldırma ile birlikte silinir (uygulama güncellemesinde **korunur**) |
| Sağlayıcıda kalan veri | O sağlayıcının hesabından — bizde kopyası yok |

---

## 6. Çocuklar

Uygulama 13 yaş altına yönelik değildir ve bilerek veri toplamaz.

## 7. Değişiklikler

Bu belge değişirse commit geçmişinde görünür (`git log -- PRIVACY.md`).

## 8. İletişim

Gizlilikle ilgili soru: depoda issue aç ya da `431veysel-coder` profiline bak.
Güvenlik açığı için [SECURITY.md](SECURITY.md) — **herkese açık issue açma**.

---

_Upstream mimarisi ve izin listesi için: [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)._
