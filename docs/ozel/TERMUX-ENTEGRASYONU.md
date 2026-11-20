# TERMUX ENTEGRASYONU — tasarım, güvenlik, ön/arka yüz, etkiler

**Sürüm hedefi:** `37098` / `1.15-ozel.17` · **Durum:** tasarım + çekirdek kod hazır, kurulum akışı bağlanmadı
**Ölçüm tarihi:** 2026-11-20 · **Cihaz:** Infinix X6873, Android 16 (SDK 36), arm64-v8a

Bu belge bir niyet beyanı değil. Her bölüm ya **ölçülmüş** bir gerçeğe, ya
**yazılmış** bir koda, ya da **reddedilmiş** bir seçeneğe dayanıyor. Üçü
karışırsa "yapıldı" ile "düşünüldü" ayrımı kaybolur ve okuyan kandırılır.

---

## 1 · Sorun, doğru cümleyle

Kullanıcıların istediği şey "Termux'u kuralım" değil. İstedikleri şu:

> *Ajanın, telefonumda Termux'un erişebildiği şeylere erişebilsin — ve bunun
> için ayrı bir uygulamaya geçip elle komut yazmak zorunda kalmayayım.*

Termux'un iki ayrı hediyesi var ve karıştırılırsa yanlış iş yapılır:

| Hediye | Ne | Bizde bugün | Alınır mı |
|---|---|---|---|
| **Paket evreni** | `apt` + ~2500 paket: `openssh`, `python`, `node`, `clang`, `ffmpeg`, `imagemagick`, `ruby`, `go`… | Alpine `apk` + 11 grup + serbest paket | Kısmen zaten var |
| **Android'e bionic'ten bağlanma** | `termux-api` (sensör, kamera, SMS, TTS), `am`/`termux-open` köprüleri, Android'e özel derlenmiş araçlar | **Yok** | Asıl eksik bu |

Yani: saf paket ihtiyacı için Termux gerekmiyor (Alpine'de `apk add openssh`
çalışıyor, ölçüldü). Termux'un **gerçekten** eklediği şey bionic tarafı —
`termux-api` yardımcıları ve Android'e özel derlenmiş ikililer.

**Bu belge o ayrımı kabul ederek yazıldı:** mod A (Alpine) varsayılan kalır,
mod B (Termux katmanı) yalnız bionic'in gerçekten gerektiği yerde açılır.

---

## 2 · Ölçülen teknik gerçekler

Bunlar tahmin değil; bu cihazda koşuldu.

### 2.1 PRoot konuğu kendi kökünden başka bir şey görmüyor

```
$ ls /system                       → No such file or directory
$ ls /system/bin/linker64          → No such file or directory
$ ls /apex                         → boş
```

PRoot yalnız `-r <rootfs>` + `-b /dev -b /proc -b /sys` + oturum/global
dizinleri bağlıyor (`PRootKernel.buildProotCommand`, satır ~683). Yani
**bionic yükleyici bugünkü yapılandırmada görünmüyor** → Termux ikilileri
bugün çalışmaz.

### 2.2 Termux ikililerinin sabit öneki

Bootstrap `bln/apt`, `bin/bash`, `bin/dpkg` taşıyor; hepsi bionic'e bağlı ve
ELF yorumlayıcısı `/system/bin/linker64`. Derleme öneki de sabit:
`/data/data/com.termux/files/usr`.

Bizim PRoot kökümüzde **gerçek Android `/data` bağlı değil**. Dolayısıyla
`/data/data/com.termux/files/usr` konuğun içinde sıradan bir dizindir. Bootstrap
tam o yola açılırsa ikililer kendi önekini bulur — **proot yaması, symlink
hilesi veya ikili düzenleme gerekmez**. Tasarımın tamamı bu tek gerçeğe dayanıyor.

### 2.3 Bootstrap gerçekleri (resmî release, 2026-11-15)

| | |
|---|---|
| Asset | `bootstrap-aarch64.zip` |
| Boyut | **31,6 MB** zip → **~110 MB** açık, **3 779 dosya** |
| İçerik | `bin/bash`, `bin/apt`, `bin/dpkg`, `bin/pkg`, `SYMLINKS.txt` (66 KB) |
| Kaynak | `github.com/termux/termux-packages/releases` |
| Lisans | GPL-3.0 uyumlu (depo ile aynı) |

`SYMLINKS.txt` ayrı bir adım: zip symlink taşımıyor, bağlar `hedef←kaynak`
biçiminde metin olarak geliyor (`libncursesw.so.6.5←./lib/libtic.so`). Açma
adımı bunu uygulamazsa `bash` "libncursesw bulunamadı" der.

---

## 3 · Mimari — iki katman, tek kök

```
alpine-rootfs/                      ← PRoot kökü (-r), .image = alpine-3.21.3
├── bin/ usr/ etc/ …                ← KATMAN A: Alpine (musl, apk)   [varsayılan]
└── data/data/com.termux/files/     ← KATMAN B: Termux (bionic, apt) [isteğe bağlı]
    ├── usr/  (PREFIX — 3 779 dosya, ~110 MB)
    └── home/ (→ /var/minis/shared/termux-home sembolik bağı)
```

PRoot komut satırına iki ek:

```
-b /system          (bionic yükleyici + /system/lib64)
-b /apex            (Android 14+ runtime: libc, libdl…)
```

`/system` ve `/apex` **salt-okunur bölümlerdir** (dm-verity), `-b` ile
yazılabilir bağlansalar bile yazma EROFS döner. Yani bağlama yeni bir yazma
yüzeyi açmıyor.

**`/data` asla bağlanmaz.** Bağlanırsa ajan gerçek uygulama verisine (kendi
API anahtarları, veritabanı, kullanıcının diğer uygulama verileri) erişir ve
onay kapımızın "sandbox" sınırı anlamsızlaşır.

### Neden ayrı bir rootfs değil de aynı kök?

Ayrı kök (proot-distro tarzı) iki kabuk, iki `PATH`, iki dosya görünümü
demek. Kullanıcı "dosyam nerede" sorusunu iki kez sorar ve ajan her komutta
hangi katmanda olduğunu bilmek zorunda kalır. Aynı kök + `PATH` önceliği tek
görünüm veriyor: `/var/minis/*` her iki katmanda da aynı.

**Bedeli:** bionic ve musl `lib` dizinleri aynı ağaçta. Bu yüzden
`LD_LIBRARY_PATH` **yalnız** Termux `lib`'ini gösterir (`TermuxLayer.environment`);
karışırsa bionic ikilisi musl `libc` yüklemeye çalışıp sessizce çöker.

---

## 4 · Güvenlik — bu işin en riskli kısmı

Termux katmanı, uygulamanın bugün sahip olmadığı üç yeni yetki getiriyor.
Üçü de **onay kapısına bağlandı** (`RiskRules`, ölçüldü: 57/57 korpus).

### 4.1 Yeni riskler ve karşılıkları

| # | Yeni yetki | Neden tehlikeli | Kural | Seviye / ömür |
|---|---|---|---|---|
| 1 | `apt`/`pkg`/`dpkg` | Yeni kod indirir, **konuk root** olarak kurar; `postinst` betikleri çalışır | `termux:pkg` | MEDIUM · UNDOABLE |
| 2 | Kaynak değişikliği (`sources.list`) | Bundan sonraki **her** paket yeni kaynaktan gelir; imza zinciri kopar | `termux:sources` | **HIGH · ACCOUNT** |
| 3 | `sshd` | Konuk kabuğunu **ağa** açar; açık olduğu sürece dışarıdan giriş denenir | `termux:sshd` | **HIGH · PERMANENT** |

**Kural sırası bir güvenlik kararıdır.** `apt install` hem `pkg:install`'a
(LOW, sessiz) hem `termux:pkg`'ye (MEDIUM, kart) uyar. Tablo sıralı — ilk
eşleşen kazanır. Termux kuralları **önce** yazıldı; ilk korpus koşusunda
sıra ters olduğu için `apt install -y sshpass` **sessizce geçti**. Bu, testin
yakaladığı gerçek bir hataydı, tasarım hatası değil.

### 4.2 Onay kapısıyla ilişki — ve sınırı

Kapı komut **metnine** bakıyor (`.16`'da tırnak farkındalığı eklendi). Termux
katmanında bu yetmiyor çünkü:

```bash
apt install ./indirilen.deb      # yerel paket: apt hiçbir depo görmüyor
```

Bu satır `termux:pkg` ile kart açar (doğru), ama **paketin içinde ne olduğu**
denetlenemez. Bu yüzden:

- **Varsayılan kapalı.** Katman kurulmadan `apt` yok; kurulum kullanıcının
  açık isteği (`ToolchainScreen` → "Termux katmanı" grubu).
- **Kaynak sabit.** `TermuxLayer.defaultSources()` resmî mirror'ı yazar;
  değiştiren her komut `termux:sources` ile HIGH kart alır.
- **`/data` bağlı değil.** En kötü durumda bile katman, gerçek uygulama
  verisine ulaşamaz.
- **`sshd` varsayılan kapalı**, yalnız anahtar tabanlı; parola ile açmak ayrı
  bir kart konusu (yol haritası §9).

**Dürüst sınır:** konuk root olarak çalışan bir `postinst` betiği, kökün
içindeki her şeyi değiştirebilir — Alpine katmanının ikilileri dahil. Bunu
`apt`'ın kendisi engelleyemez. Gerçek çözüm ayrıcalık ayrımı (konuk root ≠
konuk kullanıcı) ve o, §9'daki mimari iş.

### 4.3 Uygulama kimliği ve Android izinleri

Termux katmanı **ayrı bir uygulama değil** — bizim uid'imiz altında çalışır.
Yani:

- Yeni Android izni **gerekmez** (ağ, dosya zaten var).
- Bizim iznimiz **genişlemez**; tersine, kullanıcının bize verdiği izinler
  artık konuk root'tan da erişilebilir olur. `termux-api` yardımcıları
  Termux uygulaması kurulu değilse **çalışmaz** (ayrı APK ister) — bu yüzden
  ilk sürümde `termux-api` **dışarıda**: yanıltıcı bir "çalışıyor" görüntüsü
  vermektense hiç sunmamak.

---

## 5 · Arka yüz — kod

### 5.1 Yazıldı (bu turda)

| Dosya | Satır | Ne |
|---|---|---|
| `sandbox/TermuxLayer.kt` | 258 | `probe`, `spaceProblem`, yol haritası, `environment`, `layerFor`, `installPlan`, `defaultSources`, `smokeCommands` |
| `policy/RiskRules.kt` | +3 kural | `termux:pkg`, `termux:sources`, `termux:sshd` |
| `test/.../TermuxLayerTest.kt` | 13 test | sahte dosya sistemiyle uygunluk, ortam, katman seçimi, plan sırası |
| `test/resources/ozel/risk-corpus.txt` | +9 satır | Termux kararları korpusa girdi |

`TermuxLayer` **`android.*` import etmiyor**: karar mantığı saf, test cihazsız
koşuyor (`TermuxLayerTest`).

### 5.2 Bağlanacak (sıradaki adım — kod yeri belli)

| Dosya | Değişiklik | Risk |
|---|---|---|
| `sandbox/PRootKernel.kt` | `buildProotCommand`: uygunluk varsa `-b /system -b /apex` ekle + `TermuxLayer.environment` enjekte et | Düşük — mevcut bağların yanına ekleme |
| `sandbox/RootfsManager.kt` | `USER_TREES`'e `data/data/com.termux` ekle | **Kritik** — eklenmezse yeni Alpine imajı 110 MB katmanı siler |
| `sandbox/ToolchainManager.kt` | Yeni grup: "Termux katmanı" (boyut 110 MB + prob sonucu) | Orta |
| `ui/settings/ToolchainScreen.kt` | Grup kartında prob sonucu; uygun değilse düğme pasif + sebep | Düşük |
| `terminal/TerminalSession.kt` | Katman seçici (Alpine / Termux) oturum başlığında | Orta |

**`USER_TREES` maddesi en kolay atlanan ve en pahalı olan.** `.10.1`'de aynı
hata yaşandı: rootfs yeniden çıkarılınca 1,4 GB'lık araç zinciri silinmişti.
Çözüm `USER_TREES` taşıma listesiydi; Termux katmanı da aynı listeye girmeli.

---

## 6 · Ön yüz — kullanıcı ne görür

### 6.1 Araç zincirleri ekranında yeni kart

```
┌──────────────────────────────────────────────┐
│ Termux katmanı            [Kurulmadı]        │
│ Termux paket evreni (apt): openssh, python,  │
│ node, clang… Android'e bionic'ten bağlanır.  │
│                                              │
│ 110 MB · 3 779 dosya · bu cihazda kullanılabilir │
│                              [ Kur (110 MB) ] │
└──────────────────────────────────────────────┘
```

Uygun değilse:

```
│ ⚠ Bu cihazda çalışmıyor                   │
│ bionic yükleyici bulunamadı               │
│ (/system/bin/linker64 veya …/apex/…)      │
│                          [ Ayrıntı ]      │
```

**Kural:** sebep her zaman yazılır. "Kur" düğmesi pasifse, neden pasif
olduğu aynı kartta. Sessiz devre dışı düğme, kullanıcıya "bozuk" der.

### 6.2 Kurulum sırasında

Adımlar `TermuxLayer.installPlan()`'dan geliyor ve **sırayla** gösterilir:

```
1/10  cihaz uygunluğu        ✓
2/10  boş alan (400 MB)      ✓
3/10  indirme 12,4/31,6 MB   ▓▓▓▓░░░░
4/10  sha256 doğrulama       …
```

Her adım tekrar başlatılabilir: indirme koparsa 3'ten devam, baştan değil.
`smoke` adımı `bash --version` **çıktısını** gösterir — "kuruldu" yazısı
değil, gerçek sürüm satırı.

### 6.3 Kurulumdan sonra

- **Terminal:** oturum başlığında katman rozeti (`ALPINE` / `TERMUX`). Ajanın
  hangi katmanda olduğu görünür olmalı; "komut çalışmadı" şikayetlerinin
  yarısı yanlış katman.
- **Ayarlar › Minis X › Özellikler:** "Termux katmanı" anahtarı (varsayılan
  **kapalı**), alt yazısı boyutu ve riski yazıyor.
- **Onay kartı:** `apt`/`sshd` komutlarında mevcut beş satırlık kart; `YETKİ`
  satırı "bu oturumun dosyaları" / "ağ" der.

### 6.4 Kullanıcının "kullanacağı şekli"

Üç kullanım biçimi, üçü de bugünkü akışın içinde:

1. **Sohbette doğal dil:** *"openssh kur ve kendine bir ssh anahtarı üret"* →
   ajan `apt install openssh` çağırır → kart → kurulum → çıktı.
2. **Terminal:** kullanıcı doğrudan `pkg install` yazar (katman rozeti görünür).
3. **Araç zincirleri ekranı:** tek dokunuşla hazır gruplar
   (`ssh istemcisi`, `python + pip`, `node`), elle `apt` yazmadan.

---

## 7 · Etkiler — ölçülmüş ve öngörülen

### 7.1 Uygulamaya etki

| Ölçüt | Bugün | Termux katmanı açıkken | Not |
|---|---|---|---|
| APK boyutu | 43 MB | **43 MB (değişmez)** | Bootstrap çalışma anında indirilir; APK'ya gömülmez |
| Disk (kök) | ~110 MB Alpine | **+110 MB** (+ `apt` listeleri ~40 MB) | 400 MB öneri bu yüzden |
| İlk açılış | değişmez | değişmez | Katman tembel: kurulmadan hiçbir maliyet yok |
| Kabuk başına RAM | ~40 MB | ~55–70 MB | bionic + musl aynı süreçte; ölçüm §8.3'te |
| `shell_execute` gecikmesi | ~120 ms | ~150 ms (PATH daha uzun) | Kabul edilebilir |
| Pil | — | `apt install` sırasında belirgin; boşta yok | Kurulum kullanıcı isteğiyle |

### 7.2 Ortama etki

- **Ağ:** ilk kurulum 31,6 MB indirir; `apt update` ~40 MB liste. Hücresel
  veride kullanıcı uyarılmalı (kurulum kartı "Wi-Fi önerilir" der).
- **Depolama:** 150 MB kalıcı + paketler. Kullanıcı `apt clean` ile temizler.
- **Yedek:** `USER_TREES`'e girerse yedekler **150 MB büyür**. Karar: yedek
  **katmanı dışlar**, yalnız `home` (kullanıcı dosyaları) alınır — geri
  yüklemede katman yeniden kurulur. Bu, yedek boyutunu şişirmemenin tek yolu.

### 7.3 Diğer uygulamalara etki

- **Termux kuruluysa:** çakışma yok — ayrı uid, ayrı önek (`/data/data/
  com.termux` onların, bizim kökümüzdeki aynı isimli dizin bizim).
- **`termux-api`:** bizim çağrılarımız onlara ulaşmaz (ayrı uygulama, amacı
  dışında). İlk sürümde `termux-api` desteği **yok** (§4.3).
- **Pil optimizasyonu:** `sshd` açıksa Android arka planı kısar; `sshd`
  başına `termux-wake-lock` gerekir → o da `termux-*` kuralıyla kart açar.

---

## 8 · Test ve denetim planı

İlke: **her iddia ya test edilir ya ölçülür.** "Çalışıyor" demek yetmez.

### 8.1 Birim (cihazsız, CI'da koşar)

| Dosya | Ne çiviliyor | Adet |
|---|---|---|
| `TermuxLayerTest` | uygunluk (linker yok/var/apex), yer, önek haritası, ortam ayrımı, katman seçimi, plan sırası, sabit sürüm | 13 |
| `RiskCorpusTest` | `termux:pkg` MEDIUM, `termux:sources` HIGH, `termux:sshd` HIGH, sıra | 57 satır (9'u Termux) |

### 8.2 Cihaz testi (instrumented / elle checklist)

| # | Senaryo | Beklenen | Nasıl |
|---|---|---|---|
| T1 | Uygun olmayan cihazda kur | düğme pasif + **sebep yazılı** | `probe` sahte fs |
| T2 | İndirme yarıda kes | 3. adımdan devam, baştan değil | uçak modu |
| T3 | Bozuk zip | sha256 reddi, kök dokunulmamış | zip'i boz |
| T4 | Kurulum sonrası `bash --version` | sürüm satırı görünür | ekran |
| T5 | `apk add` (Alpine) hâlâ çalışıyor | çalışır | terminal |
| T6 | `apt install openssh` | kart açılır, "Bir kez izin ver" ile kurulur | sohbet |
| T7 | `sshd` | HIGH kart, PERMANENT (yalnız sohbette hatırlanır) | sohbet |
| T8 | Rootfs yeniden çıkar (Alpine güncellemesi) | **Termux katmanı korunur** | `.image` değiştir |
| T9 | Yedek al → geri yükle | katman yedeğe girmez, `home` girer | yedek ekranı |
| T10 | `/data` görünür mü | **hayır** (`ls /data` boş) | terminal |

T8 ve T10 en kritik ikisi: biri veri kaybı, diğeri sandbox sızıntısı.

### 8.3 Ölçüm (tahmin değil, sayı)

| Ölçüm | Yöntem | Şu an |
|---|---|---|
| Kurulum süresi | kronometre, Wi-Fi | — |
| Kabuk RAM farkı | `free -m` iki katmanda | — |
| `apt update` süresi/boyut | `du -sh /var/lib/apt` | — |
| Soğuk `shell_execute` | 10 koşu ortancası | — |

Boş hücreler **ölçülmedi** demek; doldurulmadan "performans etkisi yok"
denmez.

### 8.4 Denetim kapıları

- `risk-corpus.py` → **57/57** (Termux satırları dahil) — CI'da `ozel-guard.yml`
- `kotlin-denetim.py` → 1644 dosya, yapısal hata yok
- `integration_audit.py` → Termux katmanı **bağlanana kadar** `BEKLİYOR`
  sayılır; bağlandığında 11/12 olmalı

---

## 9 · Yol haritası ve **reddedilen** seçenekler

### Fazlar

| Faz | İş | Neden bu sırada |
|---|---|---|
| **P1** ✅ | `TermuxLayer` + 3 kural + 13 test + korpus | Karar mantığı önce: kurulum yazmadan önce "çalışır mı" belli olsun |
| **P2** | `PRootKernel` bind ekleme + `USER_TREES` + kurulum akışı | Gerçek kurulum; T1–T10 burada |
| **P3** | `ToolchainScreen` kartı + terminal katman rozeti | Ön yüz; kurulum çalışmadan UI yazmak boş vaat |
| **P4** | `sshd` anahtar tabanlı, parola kapalı, otomatik kapanma (30 dk) | En riskli yüzey en sona |
| **P5** | Ölçümler + `apt` önbellek temizliği + yedek dışlama | Maliyet kontrolü |

### Reddedilenler — ve neden

| Seçenek | Neden reddedildi |
|---|---|
| **Termux APK'sını indirtip kurmak** | Ayrı uygulama, ayrı uid; ajan oraya erişemez. "Entegre" iddiası yalan olurdu |
| **Bootstrap'ı APK'ya gömmek** | +32 MB APK, kullanmayan herkese maliyet. Çalışma anında indirme + sha256 daha dürüst |
| **Ayrı rootfs (proot-distro tarzı)** | İki dosya görünümü, iki `PATH`, kullanıcıya iki kez "dosyam nerede" sorusu |
| **Bionic ikililerini musl köke kopyalamak** | Yorumlayıcı yolu sabit; `patchelf` ikilileri bozar ve güncellemede geri gelir |
| **`/data` bağlamak** | Sandbox sınırını kendi elimizle kaldırmak; onay kapısı anlamsızlaşır |
| **`termux-api` desteğini ilk sürüme koymak** | Termux APK'sı kurulu değilse çalışmaz; "var" görünen ama çalışmayan özellik, `.14`'te kınanan sınıfın ta kendisi |
| **`sshd` varsayılan açık** | Telefonda ağa açık kabuk; parola denemesi kaba kuvvete açık |

---

## 10 · Açık sorular (karar bekliyor)

1. **Yedek politikası:** katman yedeğe girsin mi? Önerim **hayır** (150 MB);
   geri yüklemede yeniden kurulur. Ama çevrimdışı geri yükleme isteyen
   kullanıcı için "katmanı da al" seçeneği gerekebilir.
2. **`sshd` portu:** sabit 8022 (Termux geleneği) mi, rastgele mi? Sabit port
   taramaya açık; rastgele port kullanıcıya "port nerede" derdini verir.
3. **`apt` otomatik güncelleme:** ajanın kendi `apt update` çalıştırması
   kart açmalı mı? Şu an `termux:pkg` kapsamında **evet** (kart açıyor);
   `apt update` salt-okunur sayılıp sessize alınabilir.
4. **Katman iki sürüm arasında:** Alpine imajı değişince katman korunacak
   (`USER_TREES`), ama `apt` listeleri eski kalabilir. İlk açılışta
   `apt update` arka planda mı?
