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

## Kimlik bilgileri — nerede duruyor

Bu bölüm kodun ne yaptığını anlatır; iddia değil, dosya referansıyla.

| Ne | Nerede | Nasıl korunuyor |
|---|---|---|
| Sağlayıcı API anahtarları | `provider_secrets` | AES256-GCM, anahtar Android Keystore'da (`EncryptedPrefsFactory`) |
| Ortam değişkeni değerleri | `env_var_values` | aynı yol; metadata (`env-vars.json`) yalnızca ad ve not tutar |
| OAuth token'ları | `oauth_prefs`, `mcp_oauth_secrets` | aynı yol |
| rclone / ağ sırları | `backup_rclone_secrets`, `minis_network_secrets` | aynı yol |

Hepsi `res/xml/backup_rules.xml` ve `data_extraction_rules.xml` içinde **bulut
yedeğinden ve cihaz transferinden hariç tutulmuştur** — Keystore anahtarı hiçbir
zaman yedeklenmediği için yedeklenmiş bir kopya zaten açılamaz. Bu listeyi
`EncryptedPrefsBackupRulesTest` bekçiliyor: yeni bir şifreli store eklenip
buraya yazılmazsa test kırılır.

Araç çıktısında görünen sırlar `EnvVarRedactor` ile maskelenir.

**Bilinçli sınır:** bu değerler, kullanıldıkları süre boyunca sandbox'a ortam
değişkeni olarak **enjekte edilir**. Yani ajanın çalıştırdığı bir komut, o an
aktif olan bir sırra erişebilir. Bu tasarımın kaçınılmaz sonucu: ajanın
çalıştırdığı koda güvenmiyorsan, o oturumda aktif olan sırlara da güvenmiyorsun
demektir. Azaltma yolu, sırrı o oturum için **devre dışı bırakmaktır**
(Ortam Değişkenleri ekranındaki anahtar) — değer silinmez, enjekte edilmez.

## Depo kimlik bilgisi — iki token, iki yarıçap

Bu çatal kendi kendini günceller: uygulama güncellemeyi bu depodan okur, CI bu
depodan derler. Yani depoya yazma yetkisi olan bir kimlik bilgisi, aynı zamanda
**cihaza kurulacak APK'yı** etkiler. Yarıçapı dar tutmak bu yüzden tercih değil,
zorunluluk.

İki ayrı token kullanılır:

| Değişken | Kapsam | Ne için |
|---|---|---|
| `GITHUB_REPO_TOKEN` | **fine-grained**, yalnız `openminis-custom`: Contents R/W, Actions R/W, Workflows R/W, Metadata R | Bu depoya push, release, workflow |
| `GITHUB_TOKEN` | genel amaçlı (mevcut) | Diğer depolar, hesap işlemleri |

Dominant kural: **bu depoya yapılan her push `scripts/push.sh` üzerinden gider.**
O script (a) sır taraması yapar, (b) token'ın gerçekten bu depoyla sınırlı
olduğunu `check_token_scope.py` ile doğrular, (c) kimlik bilgisini `.git/config`'e
yazmadan, yalnız o çağrı için başlık olarak geçirir.

Bir kez kurulum:

```sh
sh scripts/install_hooks.sh        # .githooks/pre-push etkinleşir
python3 scripts/check_token_scope.py --expect-repo 431veysel-coder/openminis-custom
```

Token'ı fine-grained olarak oluşturmak: GitHub → Settings → Developer settings →
Fine-grained tokens → Generate new token → *Only select repositories:
openminis-custom* → izinler yukarıdaki tablo → süre 90 gün. Değeri uygulamanın
Ortam Değişkenleri ekranına `GITHUB_REPO_TOKEN` adıyla gir; ajan onu görmesin
diye sohbete yazma.

## Sır sızmasına karşı iki taraflı kapı

Tek taraflı kontrol yeterli değil: yalnız CI'da olursa sır **çoktan yayınlanmış**
olur (push edilmiş, indekslenmiş, fork'lanmış); yalnız yerelde olursa hook
kurulmamış tek bir klon bütün savunmayı boşa çıkarır.

| Taraf | Nerede | Ne zaman |
|---|---|---|
| Yerel | `.githooks/pre-push` | her push'tan önce — sır cihazdan çıkmadan |
| CI | `.github/workflows/security.yml` | her push/PR'da ağaç + gönderilen commit'ler; **haftalık tam geçmiş** taraması |

`scripts/secret_scan.py` kendi kurallarını taşır (GitHub PAT'leri, OpenAI/
Anthropic/Google/AWS/Slack/Stripe anahtarları, JWT, özel anahtar blokları,
base64 keystore, yüksek entropili `*_key`/`token` atamaları) ve `--self-test`
ile kendi kendini doğrular: kural sürüklenip sessizce hiçbir şey yakalamazsa
test kırılır. CI'da bu self-test ilk adımdır.

Önemli: bir sır commit edildiyse, sonraki bir commit'te silmek onu **gizlemez** —
geçmişte ve her fork'ta durmaya devam eder. Yapılacak şey değeri döndürmek
(rotate) ve geçmişten `git filter-repo` ile temizlemektir. Tarayıcı bu cümleyi
her bulguda yazar.


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
