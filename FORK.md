# FORK.md — Minis X

> Bu depo, [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)'in
> (GPLv3) kişisel bir çatalıdır. **Amaç upstream'i kopyalamak değil; sahip
> olduğumuz bir uygulamayı kendi ihtiyaçlarımıza göre büyütmek.**

| | |
|---|---|
| Çatal | `431veysel-coder/openminis-custom` |
| Upstream | `OpenMinis/OpenMinis` — v1.15 (2026-10-26, `a91ff5b`) |
| Lisans | GPL-3.0 (upstream ile aynı; türev çalışma da GPL-3.0'dır) |
| Platform | Yalnız Android (iOS macOS/Xcode ister, kapsam dışı) |
| Uygulama kimliği | `com.minis.x` — resmî uygulamayla **yan yana** kurulur |
| Görünen ad | Minis X |
| Mevcut sürüm | `1.15-ozel.17` / `versionCode 37098` |
| Durum | Upstream'e göre **73 commit ileride, 0 geride** (2026-11-21) |

---

## 1. Neden bu çatal var?

OpenMinis, telefonun içinde gerçek bir Linux kabuğu çalıştıran, açık kaynaklı
bir yapay zekâ ajanı uygulaması. Kutudan çıktığı hâliyle iyi; ama bir noktadan
sonra şu soru kaçınılmaz oluyor:

> *"Bu uygulama benim cihazımda çalışıyor, benim verimle çalışıyor — peki neden
> benim ihtiyacıma göre değişemiyor?"*

Bir uygulamayı yalnızca **kullanmak** ile **sahip olmak** arasındaki fark tam
olarak burada başlıyor. Upstream'in geliştirme döngüsü kendi yol haritasına
göre işliyor: bir özellik istiyorsun, issue açıyorsun, oy topluyorsun,
sırasını bekliyorsun. Bu doğru bir model — ama *benim* ihtiyacım *onların*
önceliği olmak zorunda değil.

Bu çatal, o bağımlılığı kesmek için var. Üç somut sebep:

**1. Özellik ekleme özgürlüğü.** İhtiyaç duyduğum bir şeyi bir issue'nun
oylanmasını beklemeden kendim yazabiliyorum. Bu depodaki her özellik (§3) bu
yüzden var — hiçbiri upstream'in yol haritasında değildi.

**2. Ajanın gerçekten yetenekli olması.** Uygulamanın vaadi "telefonunda bir
bilgisayar". Ama kutudan çıkan kök dosya sistemi 8 MB: busybox, `apk`, `curl`.
Ajan bir JSON'u işlemek için `jq`, bir sertifikaya bakmak için `openssl`, bir
dosyayı uzak sunucuya kopyalamak için `rsync` istiyor — ve her seferinde önce
kurulum izni soruyor. Bu, "bilgisayar" fikrinin yarısı. §3.5 bunu kapatıyor.

**3. Kendi dağıtım hattı.** Kendi APK'mı kendi CI'ımla, kendi imzamla
derliyorum ve kendi repomdan güncelliyorum. Ne zaman güncelleyeceğime ben
karar veriyorum; bir mağaza incelemesi ya da upstream'in yayın takvimi
beklemiyorum.

Bunların üstünde dördüncü bir sebep var: **burası bir deney alanı.** Bir
ajanın araç zincirini nasıl yönettiği, düşünme bloklarını nasıl çevirdiği,
arka plan kısıtlarını nasıl aştığı — bunları okumak ile değiştirip sonucu
görmek arasında çok fark var. Çatal bunu mümkün kılıyor.

### 1.1 Değişmez ilkeler

Bu depoya dokunan herkesin (gelecekteki ben dahil) uyduğu kurallar:

1. **Upstream'e sadakat, davranışta değil yapıda.** Mümkün olan her yerde
   upstream'in mimarisini, adlandırmasını ve desenlerini koruyoruz. Yeni
   dosyalar `[MINIS-OZEL]` etiketiyle işaretlenir; upstream dosyalarına
   dokunulduğunda yorum satırıyla *neden* dokunulduğu yazılır.
2. **Senkron kalınabilirlik.** Her değişiklik, upstream `git merge` edildiğinde
   çakışması kolay olacak şekilde dar tutulur. Büyük yeniden yazımlar yok.
3. **Sessiz bozulma yasak.** Bir sabit yanlış olduğunda derleme geçmemeli.
   `scripts/check_identity.sh` bunun için var (§5).
4. **Kullanıcıya görünen her metin iki dilde.** Türkçe ve İngilizce
   (`values-tr/` + `values/`). Diğer 21 dil upstream'den devralınır; eksik
   çeviri İngilizceye düşer, kırılmaz.
5. **Özellik, izin istemeden çalışmalı.** Bir özellik ekstra izin veya
   ekstra kurulum gerektiriyorsa, o izni *isteme* akışı da o özelliğin
   parçasıdır (bkz. `diagnostics/BackgroundGates.kt`, §3.4).
6. **GPL-3.0.** Kaynağı değiştirip dağıtıyorsan kaynağı da açıyorsun. Bu depo
   public kalır.

---

## 2. Upstream'den farklar — bir bakışta

| Alan | Upstream | Bu çatal |
|---|---|---|
| `applicationId` | `com.openminis.app` | `com.minis.x` |
| `namespace` | `com.openminis.app` | **aynı bırakıldı** (bkz. §5'teki tuzak) |
| Uygulama adı | Minis | Minis X |
| `versionCode` | 37 | 37098 |
| `versionName` | 1.15 | 1.15-ozel.17 |
| İmza | OpenMinis anahtarı | kendi 4096-bit RSA anahtarımız |
| `RELEASE_REPO` | `OpenMinis/OpenMinis` | bu çatal |
| `AUTO_UPDATE_CHECK` | true | true — **ama bu repoya bakıyor** |
| Provider presetleri | upstream listesi | + **Evren API** (§3.1) |
| Düşünme bloğu çevirisi | yok | **var** (§3.2) |
| Canlı Görevler paneli | yok | **var** (§3.3) |
| Araç zinciri ekranı | yok | **12 grup** — 11 `apk` grubu + Termux katmanı (§3.5) |
| Hızlı Ayarlar karosu | yok | **var** (§3.6) |
| Derleme hattı | özel ağaç (repoda yok) | **GitHub Actions** (§4) |
| Upstream test paketi | var | aynı, ama APK ondan önce yüklenir |

**Kod hacmi:** 19 commit · 30+ dosya · ~2.400 satır eklenmiş Kotlin.
Değiştirilen upstream dosyası sayısı: 9. Geri kalan her şey **yeni dosya** —
yani upstream bir sonraki sürümde bu dosyalara dokunmadığı sürece merge
temiz geçer.

---

## 3. Özellik envanteri

Aşağıdaki her özellik bu çatala özgüdür ve upstream'de **yoktur**.

### 3.1 Evren API — yerleşik sağlayıcı ön ayarı

`data/model/ChatProviderPreset.kt`

"Sağlayıcı Ekle" ekranında Evren API tek dokunuşla seçilebiliyor. OpenAI
uyumlu bir ağ geçidi (`https://evren-llmapi.ssyz.org.tr`), 15 model sunuyor.
Base URL, kimlik doğrulama biçimi ve model listesi önceden dolu geliyor —
kullanıcı yalnızca anahtarını yapıştırıyor.

*Neden:* OpenAI uyumlu her sağlayıcıyı elle kurmak üç alan doldurmak demek;
sık kullanılanı bir preset yapmak doğru olan.

### 3.2 Düşünme bloğu çevirisi

`ui/chat/ThinkingTranslator.kt` · `data/TranslationPrefs.kt` ·
`ui/settings/TranslationSettingsScreen.kt`

Modelin "düşünme" (reasoning) çıktısı İngilizce geliyor — modeller böyle
eğitilmiş. Türkçe konuşan biri için bu, yanıtın yarısının okunamaması demek.
Bu özellik düşünme bloğunu **ikinci bir model çağrısıyla** kullanıcının
diline çeviriyor.

Teknik ayrıntılar:
- Cümle sınırlarına saygılı parçalama (`sentenceBoundary`) — yarım cümle
  çevrilmiyor.
- Sonuç `filesDir` altında önbellekleniyor (`cacheKey = lang + model + text`);
  aynı blok ikinci kez çevrilmiyor.
- Çeviriyi yapacak model ayrı seçilebiliyor — asıl konuşmayı pahalı model,
  çeviriyi ucuz model yapabilir.
- Ayarlardan tamamen kapatılabilir (`enabled`, varsayılan açık).

*Neden:* Bir ajanın düşünme sürecini görmek, yanıtı görmekten daha öğretici —
ama yalnızca anladığın dilde.

### 3.3 Canlı Görevler paneli

`ui/settings/LiveTasksScreen.kt` · `LiveTaskCard.kt` ·
`ui/settings/AgentStepExtractor.kt`

Çalışan ajan oturumlarını tek ekranda gösteriyor: hangi oturum çalışıyor,
hangisi kuyrukta, kaç adım attı, ne kadar sürdü.

Asıl yenilik **adım çıkarımı**: bir göreve dokunduğunda o görevin gerçekte
yaptığı araç çağrılarını açıyor. Modelin "arama yapıyorum" demesi değil —
`shell_execute` çağrısının kendisi, süresi ve sonucu. `AgentStepExtractor`
mesaj geçmişini tarayıp araç çağrılarını eşleştiriyor, her birine okunabilir
bir başlık (`headline`) ve süre etiketi üretiyor.

*Neden:* Bir ajanın ne yaptığını görememek, ona güvenmeyi imkânsız kılıyor.
"Sonuç doğru geldi" ile "sonucu nasıl getirdiğini gördüm" aynı şey değil.

### 3.4 Arka plan kapıları

`diagnostics/BackgroundGates.kt`

Uygulamanın arka planda çalışması için gereken sistem izinlerini tek yerde
toplayıp eksik olanları gösteriyor — pil optimizasyonu, bildirim erişimi,
kesin alarm, veri tasarrufu. Her satır doğrudan ilgili sistem ayarına
götürüyor (`appDetails` intent'i).

*Neden:* Uzun görevler sessizce ölüyordu. Sebep izin değil, iznin **eksik
olduğunun görünmemesiydi**.

### 3.5 Araç zincirleri — 11 grup

`sandbox/ToolchainManager.kt` · `ui/settings/ToolchainScreen.kt`

APK bilerek yalın bir Alpine kök dosya sistemi taşıyor (8 MB). Bu ekran o
boşluğu dolduruyor: seç, kur, uygulamada kalsın. Kurulan paketler
`filesDir/alpine-rootfs` içine gider — **uygulama güncellemelerinde korunur**,
yalnızca kaldırınca silinir.

| # | Grup | İçerik | ~Boyut |
|---|---|---|---|
| 1 | C / C++ | `build-base`, `musl-dev`, `binutils`, `git`, `make`, **`clang19`** | 220 MB |
| 2 | Python | `python3`, `py3-pip`, `python3-dev`, **`uv`** | 70 MB |
| 3 | Node.js | `nodejs`, `npm` | 75 MB |
| 4 | Go | `go` | 190 MB |
| 5 | Rust | `rust`, `cargo` | 200 MB |
| 6 | Java | `openjdk17-jdk` | 260 MB |
| 7 | Medya | `ffmpeg`, `imagemagick` | 120 MB |
| 8 | **Kabuk araçları** | `jq`, `sqlite`, `openssl`, `ripgrep`, `less`, `tree`, `tmux`, `nano`, `vim` | 35 MB |
| 9 | **Uzak erişim** | `openssh-client`, `rsync`, `curl`, `wget` | 20 MB |
| 10 | **Betik dilleri** | `ruby-full`, `perl`, `php83`, `php83-phar`, `php83-openssl`, `php83-common` | 90 MB |
| 11 | **Android derleme** | `android-tools` (adb), `gradle` | 180 MB |

Kalın olanlar bu çatalda eklendi. 1–7 upstream'de vardı ama eksikti: `clang`
C grubunda, `uv` Python grubunda yoktu — yani "C toolchain" kurup
`clang` bulamıyordun.

*Neden 8–11:* Bunlar bir ajanın **ilk** başvurduğu komutlar. `jq` olmadan
JSON işlenmiyor, `openssl` olmadan sertifika kontrol edilmiyor, `rsync`
olmadan uzak kopya yok, `adb` olmadan cihaz yönetimi yok. Her biri için ayrı
kurulum sorusu sormak yerine tek grupla geliyorlar.

### 3.6 Hızlı Ayarlar karosu

`service/MinisTileService.kt` · `AndroidManifest.xml`

Bildirim panelinden tek dokunuşla yeni sohbet. Uygulama zaten
`minis://action/new_chat` bağlantısını tanıyor (uygulama simgesine uzun
basınca çıkan kısayolun aynısı) — karo o bağlantıyı ateşliyor, gezinme
mantığı `MainActivity`'de kalıyor.

Teknik not: Android 14+ `startActivityAndCollapse(PendingIntent)` istiyor,
altında `Intent` alan aşırı yükleme. İkisi de ele alındı. Karo **açma/kapama
düğmesi değil**: her zaman `STATE_INACTIVE` bildiriyor, çünkü "sohbet açık mı"
gibi bir durumu takip etmek kullanıcı uygulamada gezinmeye başladığı anda
bayatlar.

### 3.7 Markdown performans düzeltmesi

`ui/markdown/MarkdownText.kt`

Upstream'in bilinen **#426** hatası: `MarkdownParser` her satır için 8 regex
derliyor → uzun oturumlarda sanal bellek tükenmesi → `SIGABRT`. Bu çatalda
son kalan render-başına regex de kaldırıldı; desenler bir kez derleniyor.

*Neden:* Uzun bir oturumun ortasında uygulamanın çökmesi, özellik eksikliğinden
daha can sıkıcı.

### 3.8 Kendi kendini güncelleme

`RELEASE_REPO` bu çatala yönlendirildi. Uygulama güncellemeyi GitHub
Release'lerinden bu repodan kontrol ediyor — resmî OpenMinis sürümünü değil.
`versionCode` şeması (§6) sayesinde resmî uygulamayla çakışmıyor.

### 3.9 Güncelleme imza doğrulaması

`data/UpdateSignatureGuard.kt`

Güncelleme yolunda zaten üç kontrol vardı: dosyanın SHA-256'sı GitHub'ın
bildirdiği özetle, arşiv içindeki paket adı, ve `versionCode`'un daha yeni
olması. **Üçü de aynı GitHub hesabından geliyor** — yani "indirme bozulmadan
geldi mi" sorusunu yanıtlıyorlar, *"bunu yayınlayan bizim imza anahtarını
tutuyor muydu"* sorusunu değil.

Hesap ele geçirilirse, saldırgan özeti ve paket adı doğru olan bir release
yayınlayabilir. Android'in kendisi bunu kurmayı reddeder (imza
sertifikalarını karşılaştırır) — ama kullanıcı 39 MB indirdikten sonra
açıklamasız bir "Uygulama yüklenemedi" görür ve bunun bozuk bir release mi
yoksa saldırı mı olduğunu ayırt edemez.

Bu kontrol kararı **daha erken** ve **okunabilir** hâle getiriyor:

- İmza, çalışan uygulamanınkiyle karşılaştırılıyor. Sabit bir parmak izi
  gömülmüyor — öyle olsaydı anahtar döndüğünde elle güncellenmesi gerekir ve
  unutulması sessiz bir arızaya yol açardı. "Bu uygulama neyle imzalı" sorusu
  ise tanım gereği kayamaz.
- İmzası **okunamayan** arşiv de reddediliyor; atlanmıyor. Okunamayan imza ya
  bozuk ya da kasten bozulmuş demektir.
- Reddetme sebebi ayrı bir mesajla gösteriliyor: imza uyuşmazlığı ile hatalı
  paket adı aynı şey değil.

**Korumadığı şey:** hem GitHub hesabını hem imza anahtarını tutan bir
saldırgan. O noktada güncelleme yolunda kontrol edilecek bir şey kalmaz —
çözüm anahtar rotasyonudur, kod içindeki bir kontrol değil.

### 3.10 Belirli paket kurulumu

`sandbox/ToolchainManager.installPackages()` · `ui/settings/ToolchainScreen.kt`

Gruplar kaba birimler: `jq` ve `sqlite` için 35 MB'lık koca "kabuk araçları"
grubunu kurmak gerekiyordu. Artık serbest paket listesi girilebiliyor —
Alpine paket adları, boşlukla ayrılmış. Aynı sandbox'a kurulur, aynı şekilde
güncellemelerde korunur.

Ayrıca ekranın başında **toplam maliyet** görünüyor: bütün gruplar 1.4 GB.
Tek tek bakınca hiçbiri büyük görünmüyordu; toplam hiçbir yerde yazmıyordu.

### 3.11 Maliyet şeffaflığı

Çeviri varsayılan **açık** ve her çeviri seçili modele **ikinci bir istek**
gönderiyor — yani sağlayıcı bunu ek girdi/çıktı olarak faturalandırıyor. Bu
artık ayar ekranının alt bilgisinde açıkça yazıyor.

Çeviri kendiliğinden çalışmıyor (düğmeye kullanıcı basıyor), ama maliyetin
varlığı görünmüyordu. Sessiz ücret, ücretin kendisinden daha kötü.

---

## 4. Altyapı — kendi derleme hattımız

Upstream, Android APK'sını repoda olmayan özel bir ağaçtan derliyor. Bu çatal
o hattı **repoya taşıdı**: `.github/workflows/apk.yml`.

Tetikleyiciler:
- `main` dalına her push (yalnızca `**.md` ve `docs/**` dışındaki değişikliklerde)
- `v*` etiketi → APK'yı Release'e yükler
- Elle tetikleme (`workflow_dispatch`, `release`/`debug` seçimiyle)

Hattın çözdüğü problemler — her biri CI'da bir kez patladıktan sonra yazıldı:

| Problem | Çözüm |
|---|---|
| Kotlin derlemesi OOM | JVM `-Xmx4g`, Kotlin daemon `-Xmx6g` (repoda 2 GB varsayılan) |
| `libproot-loader*.so` eksik | İki NDK: **27.0.12077973** (AGP/CMake JNI) + **r28** (proot çapraz derleme) |
| Kimlik doğrulaması `rg` istiyordu | `check_identity.sh` saf `grep`/`sed`'e çevrildi (hosted runner'da `rg` yok) |
| Testler APK'yı düşürüyordu | APK **testlerden önce** yükleniyor; test hatası yayını engellemiyor |
| Sessiz kimlik kayması | Her derlemede `applicationId`, imza parmak izi, rootfs ve `.so` doğrulanıyor |

İmzalama: `own` signingConfig. CI secret'ından (`KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) ya da yerelde
`keystore.properties`'ten okur; ikisi de yoksa debug anahtarına düşer —
**yani derleme asla anahtar yüzünden durmaz**, yalnızca dağıtılamaz olur.

### 4.1 Upstream nöbeti

`.github/workflows/upstream-sync.yml` — her **pazartesi 06:00 UTC**.

Upstream'i çeker, bir deneme merge'i yapar ve **tek bir yuvarlanan issue**'yu
günceller:

- kaç commit gerideyiz, upstream hangi SHA'da
- deneme merge'i temiz mi, değilse hangi dosyalar çakışıyor
- upstream'in eklediği commit'ler (ilk 40)
- **hem bizim hem onların dokunduğu dosyalar** — asıl riskli liste

`main`'e hiçbir şey push etmez, hiçbir şey merge etmez. Kararı insan verir.
§1 ilkesi 2'nin ("merge edilebilir kal") alarmı: sapma sessizce büyümeden
görünür olur.

### 4.2 Sürüm notları

`apk.yml` → *Build the release notes* adımı, son sürüm etiketinden bu yana
geçen commit'leri `feat` / `fix` / `perf` başlıklarına göre gruplayıp Release
gövdesine yazıyor. GitHub'ın varsayılan "tüm merge commit'leri" listesi yerine
okunabilir bir *ne değişti*. `generate_release_notes` kullanılmıyor — `body`
verildiğinde zaten yok sayılıyor.

### 4.3 Belgeler

| Dosya | Ne |
|---|---|
| `FORK.md` | Bu belge — neden, ne, nasıl |
| `CONTRIBUTING.md` | Geliştirme ortamı, kod kuralları, commit/PR akışı, inceleme kriterleri |
| `SECURITY.md` | Tehdit modeli, kapsam, bildirim kanalı, yanıt süreleri |
| `PRIVACY.md` | Hangi veri nereye gidiyor — sağlayıcılar, Evren API, çeviri maliyeti, izinler |
| `.github/ISSUE_TEMPLATE/` | Hata ve özellik şablonları |
| `.github/PULL_REQUEST_TEMPLATE.md` | PR kontrol listesi |

---

## 5. Kimlik muhafızı ve o meşhur tuzak

`scripts/check_identity.sh` — her derlemede çalışır ve hangi sabitin hangi
değeri alması gerektiğini doğrular.

| Dosya | Sabit | Olması gereken |
|---|---|---|
| `accessibility/MinisAccessibilityService.kt` | `SERVICE_ID` | **applicationId** (`com.minis.x`) |
| `diagnostics/ResourceWatermark.kt` | `/data/user/0/<id>/` | **applicationId** |
| `res/xml/shortcuts.xml` (×3) | `targetPackage` | **applicationId** |
| `res/values/strings.xml` | `app_name` | Minis X |
| `data/repository/AppIconRepository.kt` | `PACKAGE_NAME` | **namespace** (`com.openminis.app`) |

> **Tuzak:** `AppIconRepository.PACKAGE_NAME` bir uygulama kimliği değil,
> **sınıf adı ön eki**. `ComponentName(ctx, "$PACKAGE_NAME.MainActivityIconAuto")`
> için kullanılıyor. Bir kez yanlışlıkla `applicationId` yapıldı: derleme
> **geçti**, uygulama açıldı, ama simge değiştirme `ClassNotFoundException`
> ile patladı. Bu yüzden muhafız var — bu tür hatalar derlemede görünmez.

---

## 6. Sürüm şeması

```
versionCode = upstream_versionCode * 1000 + özel_sürüm
versionName = "<upstream>-ozel.<özel_sürüm>"
```

| Upstream | Bizimki |
|---|---|
| 1.15 / 37 | `1.15-ozel.10.1` / `37016` |
| 1.16 / 38 | `1.16-ozel.1` / `38001` (planlanan) |

**Yakılmış numaralar: `37009`–`37013`.** Silinen `özel 12` / `özel 13` denemeleri
bu aralığı harcadı. Bir numara iki farklı APK'yı gösteremez; o numaralarla kurulu
bir cihaz varsa ve daha küçük/eşit bir sürüm yayınlanırsa o cihaz **sonsuza dek**
güncelleme alamaz. Bu yüzden 37014'ten sonrası `37015` (`1.15-ozel.10`) ve `37016` (`1.15-ozel.10.1`) oldu; geri dönüş yoktur.

Bu şema iki şeyi garanti ediyor: (a) kendi sürümlerimiz kendi içinde artıyor,
(b) upstream yeni bir taban yayınladığında bizim `versionCode`'umuz onunkinden
her zaman büyük — böylece güncelleme yönü asla ters dönmüyor.

---

## 7. Upstream güncellemesi alma

```sh
git remote add upstream https://github.com/OpenMinis/OpenMinis.git
git fetch upstream

# 1. Yeni tabanı al
git checkout -b sync-1.16 upstream/main

# 2. Kimlik yamasını uygula (§5 tablosu) + scripts/check_identity.sh çalıştır
bash scripts/check_identity.sh

# 3. Özellikleri sırayla taşı — commit'ler bağımsız, tek tek cherry-pick edilebilir
git checkout main
git cherry-pick <ozellik-commitleri>

# 4. Sürümü güncelle (§6), derle, doğrula
```

Özellik commit'leri bilinçli olarak **ayrı ve kendi kendine yeterli** tutuldu:
biri çakışırsa diğerleri etkilenmez. `git log --oneline` ile sıraları
görülebilir; her biri tek bir özelliğe karşılık gelir.

---

## 8. Bilinen sınırlar

- **Kaynak 1.15'te duruyor.** 1.16 beta'ları upstream'in özel ağacından
  yayınlanıyor; genel aynada 1.16 kaynağı yok. Resmî uygulaman daha yeniyse
  bu APK **ondan eski** görünecek — fark bizden değil, aynanın durumundan.
- **iOS derlenemiyor.** macOS/Xcode gerekir. Bu çatal yalnız Android.
- **`minis://` şema çakışması.** İki uygulama da aynı özel şemayı kaydediyor.
  Resmî uygulama da kuruluysa harici bir `minis://` bağlantısı seçim penceresi
  gösterir. Uygulama içi bağlantılar etkilenmez (paket adı hedeflenir).
- **OAuth geri dönüş portları** (`localhost:54545/1455/8085/3000-3002`) iki
  uygulamada aynı. Sağlayıcılar bu adresleri beyaz listede tuttuğu için keyfî
  değiştirilemez; oturum açarken diğer uygulama kuruluysa karışıklık olabilir.
- **`gradle` grubu büyük.** Android derleme grubu ~180 MB indiriyor ve
  `gradle` kendi JDK'sını getiriyor. Telefonda tam bir Android derlemesi
  pratik değil; grup daha çok `adb` ve `gradle --version` doğrulaması için.
- **CI süresi.** Her push ~10 dakika. Değişiklikleri toplayıp tek push'ta
  göndermek daha verimli.

---

## 9. Yol haritası

Yapılması düşünülenler — sıra ve kapsam kesin değil:

| Öncelik | İş | Neden |
|---|---|---|
| Yüksek | **Ana ekran widget'ı** (Glance) | Repoda `AppWidgetProvider`/Glance yok; ana ekrandan giriş imkânı hiç yok |
| Orta | **Provider seviyesinde özel HTTP başlıkları** | Upstream'in 1 numaralı talebi (#221, 7 👍). `customHeaders` şu an yalnız *model* seviyesinde var; *provider* seviyesinde yok |
| Orta | **Proje / mini uygulama üretimi** | Ajanın ürettiği kodu (web sitesi, mini app, script) tek yerde toplayıp çalıştırma. Şu an üretim var, *yönetim* yok |
| Orta | **Smoke test paketi** | CI 722 birim testi çalıştırıyor ama `continue-on-error`. Kritik yollar (sohbet, araç çağrısı, güncelleme) için engelleyici bir set yok |
| Düşük | **IzzyOnDroid yayını** | Mağazadan kurulabilir hâle gelmek. proot rootfs boyutu F-Droid kurallarını zorlar; IzzyOnDroid gerçekçi |
| Düşük | **Erişilebilirlik denetimi** | TalkBack, kontrast, font ölçekleme — hiçbiri sistematik test edilmedi |

Yapılmayacaklar: iOS, mağaza dışı dağıtım karmaşası, upstream'de zaten olan
özelliklerin yeniden yazımı.

**Tamamlananlar:** imza doğrulaması (§3.9), belirli paket kurulumu (§3.10),
maliyet şeffaflığı (§3.11), upstream nöbeti (§4.1), sürüm notu otomasyonu
(§4.2), güvenlik/katkı/gizlilik belgeleri (§4.3).

---

## 10. Değişiklik günlüğü

| Tarih | Sürüm | Ne |
|---|---|---|
| 2026-11-12 | `1.15-ozel.1` | Temiz 1.15 tabanı, kendi kimliği (`com.minis.x`), kendi imzası. CI hattı kuruldu (JVM heap, çift NDK 27+r28, kimlik muhafızı `rg`siz, APK testlerden önce yüklenir). `AppIconRepository.PACKAGE_NAME` tuzağı düzeltildi. |
| 2026-11-13 | `1.15-ozel.2` | Evren API preset'i. Düşünme bloğu çevirisi + Çeviri ayarları ekranı. Araç zinciri ekranı (7 grup). Kendi repodan güncelleme. İncelemesi yapılabilir release derlemeleri. Canlı Görevler paneli. Markdown'da son render-başına regex kaldırıldı (#426). Türkçe string kaçış hatası düzeltildi. |
| 2026-11-13 | `1.15-ozel.3` | Sürüm damgası (build 37003). |
| 2026-11-14 | `1.15-ozel.4` | Canlı Görevler: arka plan izin kapıları (`BackgroundGates`). |
| 2026-11-14 | `1.15-ozel.5` | Canlı Görevler: veri tasarrufu niyeti; özel stringler `strings.xml` dışına taşındı. |
| 2026-11-14 | `1.15-ozel.6` | Canlı Görevler: görevi gerçekte yaptığı araç çağrılarına açma (`AgentStepExtractor`). Delegasyon import düzeltmeleri. |
| 2026-11-14 | `1.15-ozel.7` | **Araç zincirleri 7 → 11 grup:** kabuk araçları, uzak erişim, betik dilleri, Android derleme. `clang` ve `uv` kendi gruplarına eklendi. **Hızlı Ayarlar karosu.** CI artık `main` push'unda da çalışıyor (yalnız belge değişiklikleri hariç). |
| 2026-11-14 | `1.15-ozel.8` | **Güncelleme imza doğrulaması** (`UpdateSignatureGuard`) — GitHub hesabı ele geçse bile farklı anahtarla imzalanmış APK reddedilir; imza uyuşmazlığı ayrı ve açık bir mesajla gösterilir. **Belirli paket kurulumu** + toplam boyut uyarısı (1.4 GB). **Maliyet şeffaflığı**: çeviri ayarının alt bilgisi ikinci isteğin ücretlendirildiğini söylüyor. **Haftalık upstream nöbeti** (`upstream-sync.yml`) — çakışma raporu tek issue'da. **Sürüm notları commit'lerden üretiliyor.** Yeni belgeler: `SECURITY.md`, `CONTRIBUTING.md`, `PRIVACY.md`, issue/PR şablonları. |
| 2026-11-16 | `1.15-ozel.9` | **Onarım sürümü.** GeckoView denemesi (12/13) tamamen geri alındı. **DebugServer artık `127.0.0.1`'e bağlanıyor ve loopback'te de jeton istiyor** — önce `0.0.0.0`'a bağlanıp loopback'i jeton'dan muaf tutuyordu, yani cihazdaki her uygulama `provider.export` ile API anahtarlarını okuyabiliyordu. Geliştirici sunucusu varsayılan **kapalı**. **Keystore yoksa release derlemesi kırılır.** `app_name` 22 dilde "Minis X". **Özellik anahtarları**: Ayarlar › Görünüm › Minis X — Canlı Görevler, Araç zincirleri, Çeviri, Arka plan denetimi, QS karosu, Evren ön ayarı, geliştirici sunucusu. **4 yeni sağlayıcı ön ayarı**: DeepSeek, Together AI, Fireworks AI, Hugging Face. CI: `tags: v*` tetikleyicisi kaldırıldı, aynı etikete düşük `versionCode` ile yayın yapan kapı eklendi, artefakt yolu düzeltildi (v4 ortak kökü buduyor). |
| 2026-11-16 | `1.15-ozel.10` | **Çeviri paketleri** (`TranslationPacks`): dil başına terim sözlüğü, üslup ve çevrilmeyecek tanımlayıcılar taşıyan küçük JSON; Ayarlar › Çeviri › Paketler'den katalogdan veya bağlantıdan kurulur, önbellek anahtarına katılır. **Sunucu yaşam döngüsü**: anahtar açılınca anında başlar, 30 dk sonra kendiliğinden kapanır, jeton ayar ekranında görünür (`DevServerController`). **Geri alma iş akışı** (`rollback.yml`): eski kodu daha yüksek `versionCode` ile yeniden yayınlar — Android düşük sürümü kabul etmediği için tek yol bu. |
| 2026-11-16 | `1.15-ozel.10.1` | **İki depo, tek APK.** `scripts/sync_upstream.sh` + `apply_identity.py` + yeniden yazılan `upstream-sync.yml`: upstream birleştirmesi artık dal üretiyor, kimliği otomatik geri uyguluyor ve muhafızı kanıt olarak çalıştırıyor; çakışma varsa kararı insana bırakıyor. **Rootfs sürüm işareti** (`.image`) — yeni Alpine tabanı eski kurulumlara ulaşabiliyor ve yeniden çıkarma 1,4 GB'lık araç zincirini artık silmiyor (`USER_TREES` taşınır). FORK.md sürüm tablosu ve çift `ozel.1` kaydı düzeltildi. |

> **Bu tablo `.10.1`'de duruyor.** `.11.3.1` → `.16` arası sürümler için
> tek kaynak: [`docs/ozel/DEGISIKLIK-GUNLUGU-11-16.md`](docs/ozel/DEGISIKLIK-GUNLUGU-11-16.md).
> Sebep: bu dosyadaki tabloyu elle sürdürmek beş sürüm boyunca atlandı; kod
> ilerlerken günlük geride kaldı ve "hangi sürümde ne vardı" sorusu cevapsız
> kaldı. Yeni günlük sürüm notundan türetiliyor, yani iki yerde tutulmuyor.

---

## 11. Lisans ve atıf

Bu depo GPL-3.0 altındadır, upstream ile aynı lisans. Orijinal çalışma
[OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) takımına aittir.
Buradaki eklemeler bu çatalın bakımına aittir ve aynı lisans altında
paylaşılır.

Değiştirilmiş bir sürümü dağıtıyorsan, GPL-3.0 gereği kaynağı da
dağıtmalısın. Bu depo bu yüzden public.
