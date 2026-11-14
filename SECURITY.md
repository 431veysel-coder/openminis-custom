# Güvenlik Politikası

Bu depo `com.veysel.minis` paket adıyla dağıtılan **Minis Özel** uygulamasının
kaynağıdır — [OpenMinis](https://github.com/OpenMinis/OpenMinis)'in kişisel bir
çatalı. Uygulama telefonunda gerçek bir Linux kabuğu çalıştırır, dosyalarına
erişir ve yapay zekâ sağlayıcılarına ağ istekleri yapar. Bu yüzden güvenlik
bildirimleri ciddiye alınır.

## Desteklenen sürümler

Yalnızca **en son yayınlanan sürüm** güvenlik düzeltmesi alır.

| Sürüm | Destek |
|---|---|
| En son `1.15-ozel.x` | ✅ |
| Daha eski `ozel` sürümleri | ❌ — önce güncelle |
| `1.15` düz upstream | Bu depo değil — [upstream'e bildir](https://github.com/OpenMinis/OpenMinis/issues) |

## Bildirim kanalı

**Herkese açık bir issue açma.** Bunun yerine:

1. Deponun **Security** sekmesi → **Report a vulnerability**
   (GitHub Private Vulnerability Reporting).
2. Ya da doğrudan bakımcıya: `431veysel-coder` (GitHub profili üzerinden).

Şunları ekle:

- Etkilenen sürüm (`Ayarlar → Hakkında` içinde `versionName` / `versionCode`)
- Ne olduğu, nasıl tetiklendiği
- Mümkünse yeniden üretme adımları
- Etkinin ne olduğunu düşündüğün

## Yanıt süresi

| Aşama | Hedef |
|---|---|
| Alındı bildirimi | 7 gün içinde |
| İlk değerlendirme | 14 gün içinde |
| Düzeltme veya plan | 30 gün içinde (önem derecesine göre) |

Tek kişilik bir çatal olduğu için bunlar **hedef**, garanti değil. Ciddi bir
açıksa elinden gelen en hızlı şekilde dönülür.

## Kapsam

### Kapsam içi

| Alan | Örnek |
|---|---|
| **Güncelleme yolu** | İmza doğrulamasını atlatma, sahte release, indirme zinciri |
| **Sandbox** | PRoot kök dosya sisteminden kaçış, Kabuk komutu enjeksiyonu, araç zinciri kurulumunda ayrıcalık yükselmesi |
| **Sağlayıcı kimlik bilgileri** | API anahtarlarının düz metin sızması, loglara düşmesi, yedeklemeye girmesi |
| **Dışa aktarma** | `minis://` deep-link ile niyetsiz eylem tetikleme |
| **Ajan araçları** | Dosya sistemi sınırlarını aşan `file_read`/`file_write`, izin verilmeyen dizine erişim |
| **Ağ** | TLS doğrulamasını devre dışı bırakan yol, proxy üzerinden kimlik bilgisi sızması |

### Kapsam dışı

- **Upstream'den miras alınan ve bilinen hatalar** — [FORK.md §8](FORK.md)
  listesinde. Bunlar upstream'de de var; bildirimi oraya yap.
- **Kurulmuş cihaza fiziksel erişim gerektiren senaryolar** (root, ADB ile
  yedek çekme).
- **Üçüncü taraf sağlayıcıların kendi güvenliği** (OpenAI, Anthropic, Evren
  API…). Bu uygulama onların istemcisi.
- **Sosyal mühendislik**.
- **Kendi girdiğin API anahtarının cihazda durması** — bu tasarım gereği;
  anahtarı sen veriyorsun ve cihazda şifrelenmemiş olarak yaşıyor.

## Tehdit modeli — neyi koruyoruz, neyi korumuyoruz

Dürüst olmak gerekirse:

**Korunuyor:**
- **Güncelleme tedarik zinciri** — bir APK yalnızca çalışan uygulamayla **aynı
  imza anahtarıyla** imzalanmışsa kurulur (`UpdateSignatureGuard`). GitHub
  hesabı ele geçse bile farklı anahtarla imzalanmış bir APK reddedilir.
- **Yedekleme şifreleme** — upstream'in yedekleme şifrelemesi olduğu gibi duruyor.

**Korunmuyor / korunamıyor:**
- **Ele geçirilmiş imza anahtarı.** Anahtar da çalınırsa güncelleme yolunda
  kontrol edilecek bir şey kalmaz. Çözüm anahtar rotasyonu ve iptaldir, kod
  içindeki bir kontrol değil.
- **Root'lu cihaz.** Root, uygulama sandbox'ının tüm garantilerini geçersiz kılar.
- **Zararlı model çıktısı.** Model, ajan araçları aracılığıyla komut çalıştırır.
  Ne çalıştırdığını görmek için **Canlı Görevler** panelini kullan (§3.3).

## Rapor ederken lütfen

- **Gerçek kullanıcı verisi kullanma.** Kendi test hesaplarınla çoğalt.
- **Herkese açık paylaşma.** Düzeltme yayınlanana kadar bekle.
- **Yapıcı ol.** Tek kişilik bir çatal; iyi bir rapor, yamadan daha değerli.

## Teşekkür

Geçerli bir güvenlik açığı bildiren herkes — isterse — `FORK.md` §11'deki
katkı listesinde anılır.
