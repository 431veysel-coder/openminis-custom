# DENETİM 37098 — Termux kodunun kendi incelemesi

**Tarih:** 2026-11-20 · **Kapsam:** `37098` paketinde yazılan Termux kodu
**Yöntem:** kodu yazdıktan sonra satır satır yeniden okuma + gerçek bootstrap'ı
indirip yapısını doğrulama (`unzip -l`) + korpus koşumu

Bu belge "ne yaptım" değil. **Yazdığım kodda bulduğum hatalar** ve her birinin
nasıl kapandığı. Sıfır hata iddiası yok; ölçülebilir olanlar burada.

---

## 1. Kritik: bootstrap yanlış dizine açılıyordu

**Hata.** `TermuxInstaller.unzip(zip, root)` — zip kökün altına açılıyordu.

**Neden kritik.** Bootstrap girişleri **öneğe göre göreli**. Gerçek arşiv
ölçüldü:

```
$ unzip -l bootstrap-aarch64.zip | cut -d/ -f1 | sort -u
SYMLINKS.txt   bin   etc   include   lib   libexec   share   tmp   var
```

Hiçbiri `data/` ile başlamıyor. Köke açılsaydı:

- `rootfs/bin/bash` → **Alpine'in `/bin` dizini ezilir** (busybox, sh, apk…)
- `rootfs/etc/apt/…` → Alpine'in `apk` yapılandırmasıyla karışır
- `rootfs/lib/` → musl kütüphaneleri bionic'lerle dolar

Sonuç: kök kullanılamaz, **Alpine katmanı da gider**. Kullanıcı 31 MB indirip
çalışan bir sandbox'ı bozardı.

**Düzeltme.** `TermuxLayer.extractTarget(rootfsDir, entryName)` eklendi — hedef
hesabı tek yerde ve her zaman öneğin altında. `unzip` artık buradan geçiyor.

**Test.** `TermuxLayerTest.bootstrap onek dizinine acilir kok dizinine degil`:
`extractTarget(root,"bin/bash") == prefix/bin/bash` **ve**
`!= root/bin/bash`. Yani hata geri gelirse test kırmızı olur — yorum satırı
değil, sözleşme.

---

## 2. Kritik: kurulum `boot()` yapmadan proot çağırıyordu

**Hata.** `runInGuest` doğrudan `PRootKernel.buildProotCommand(...)` çağırıyordu.

**Neden kritik.** `buildProotCommand` ilk satırda `check(isBooted)` yapıyor.
Kurulum, uygulama açılışında **hiç kabuk açılmamışken** çalışabilir — o anda
`IllegalStateException` atar ve son adım (smoke) çöker. Sonuç: katman aslında
açılmış olmasına rağmen "kuruldu ama doğrulanamadı" raporu.

Ayrıca `boot()` `nativeLibDir` / `prootLoaderPath`'i dolduruyor; bunlar olmadan
proot `execve`'de SELinux tarafından öldürülür.

**Düzeltme.** `runInGuest` artık `suspend` ve ilk işi `PRootKernel.boot(ctx)`.

---

## 3. Yüksek: kurulum sonrası PATH eski kalıyordu

**Hata.** `customEnvironment` `boot()` sırasında **bir kez** dolduruluyor.
Katman kurulumu neredeyse her zaman boot'tan **sonra** olur.

**Etkisi.** Kullanıcı "Kur" der, kurulum başarılı olur, sonra ajan `apt` yazar →
`apt: not found`. Kullanıcı için "kurdum ama çalışmıyor"; kodda hata yok.

**Düzeltme.** `PRootKernel.refreshTermuxPath()`:
- eski `$PREFIX/bin` girdisini söker (tekrar eklemeyi önler),
- katman kuruluysa başına ekler, `PREFIX`/`TERMUX_PREFIX` yazar,
- kaldırıldıysa ikisini de temizler.

Çağrı yerleri: `TermuxInstaller.install` (başarı), `ToolchainManager`
(kurulum **ve** kaldırma). Çağırana bırakmak "hatırlamayı" gerektirir; bu
sınıf hata hatırlamaya bırakılmaz.

---

## 4. Yüksek: `LD_LIBRARY_PATH` global enjeksiyonu musl'u kırardı

**Hata (tasarım).** İlk niyet `TermuxLayer.environment()`'ı kabuk oturumuna
olduğu gibi enjekte etmekti. O metot `LD_LIBRARY_PATH = $PREFIX/lib` ve
`HOME = $PREFIX/../home` yazıyor.

**Neden yanlış.**
- `LD_LIBRARY_PATH` global olarak bionic dizinini gösterirse **musl ikilileri**
  (`apk`, `busybox`, `sh`) bionic `libc` yüklemeye çalışır → sessiz çökme, üstelik
  hata mesajı "permission denied" gibi görünür.
- `HOME` değişirse `~`, `/var/minis/workspace` alışkanlığı ve mevcut yollar
  anlam değiştirir.

**Düzeltme.** Kabuk oturumuna **yalnız PATH** ekleniyor. Gerekçe: Termux
paketleri kendi kütüphanelerini `DT_RUNPATH` ile buluyor (paketleyici öyle
derliyor), yani `LD_LIBRARY_PATH` gerekmiyor. Tam ortam (`LD_LIBRARY_PATH`
dahil) yalnız **tek seferlik smoke testinde** kullanılıyor
(`TermuxInstaller.runInGuest`), orada da süreç kısa ömürlü.

Bu, `TermuxLayer.environment()`'ın belgesinde duran uyarının kodda karşılığı.

---

## 5. Orta: `pm`/probe yanlış "kurulu" diyebilirdi

**Hata.** `termuxGroup.requiredBinaries = listOf("bash", "apt")` — `probe`
bunları `command -v` ile arıyor.

**Neden yanlış.** `bash` Alpine'de de olabilir (C grubu `git` ile birlikte
gelmez ama kullanıcı kurabilir). O durumda katman hiç kurulmamışken prob
"bash var, apt yok → Partial" der; kullanıcı yarım kurulum görür.

**Düzeltme.** Mutlak yollar: `$PREFIX/bin/bash`, `$PREFIX/bin/apt`. `command -v
/mutlak/yol` yalnız o dosyayı sayar.

---

## 6. Orta: kaldırma yolu kırılgan ve tehlikeliydi

**Hata.** `uninstall` hedefi `guestPrefixDir(root).parentFile.parentFile` ile
türetiliyordu (`usr` → `files` → `com.termux`).

**Neden tehlikeli.** Üç adımlık türetme; biri değişirse (`usr` adı değişir,
`files` seviyesi kalkar) **yanlış dizin** silinir. Örneğin `files` silinse
Termux ev dizini ve `apt` durumu da gider; daha kötüsü, `data/data` seviyesine
kayan bir hata kökteki tüm `data` ağacını siler.

**Düzeltme.** Sabit yol: `File(root, "data/data/com.termux")` + ek kapı:
`target.canonicalPath` kökün **altında** olmak zorunda, değilse reddedilir ve
loglanır.

---

## 7. Orta: `apk del` Termux grubunda anlamsızdı

**Hata.** `ToolchainManager.uninstall` her grup için `apk del ${group.packages}`
çalıştırıyor. Termux grubunun `packages` listesi **boş** (bootstrap `apk` ile
kurulmuyor).

**Etki.** `apk del` (argümansız) yardım metni basıp döner; kullanıcı "Kaldır"
der, hiçbir şey silinmez, ekran "kurulu"da kalır.

**Düzeltme.** `uninstall` içinde `TERMUX_GROUP_ID` ayrı yol: `TermuxInstaller.uninstall`
+ `InstallState.Absent` + PATH tazeleme.

---

## 8. Düşük: istem anahtara bağlıydı, gerçeğe değil

**Hata.** `TermuxPrompt.isReady` `OzelFeatures.TOOLCHAINS` anahtarını da
koşul sayıyordu.

**Neden yanlış.** O anahtar yalnız **kurulum ekranını** gizler; kurulu katmanı
silmez. Anahtar kapatılırsa `apt` kabukta hâlâ çalışır ama istem "katman yok"
der → model olmayan bir kısıt varsayar ya da tam tersi, komut çalıştığı hâlde
"yapamam" der.

**Düzeltme.** Tek gerçek: `TermuxLayer.isInstalled(...)`. İstem gerçeği söyler;
hangi ekranın görüneceğini ayar belirler.

---

## 9. Düşük: belge kodun yapmadığını yazıyordu

**Hata.** Tasarım belgesinde `home/ (→ /var/minis/shared/termux-home sembolik
bağı)` yazıyordu. Kod ev dizinini kökün içinde oluşturuyor, bağ kurmuyor.

**Düzeltme.** Belge koda çekildi: `home/ (Termux $HOME — kökün içinde kalır,
yedeklenmez)`.

**Neden kök içinde kalsın (kod değişmedi):** `/var/minis/shared`'a sembolik bağ
kurmak, yedeklemeye 110 MB'lık bir ağacın kullanıcı dosyası gibi girmesi
demekti. Ayrıca bağ, `USER_TREES` taşımasında iki yönlü kopyalama doğurur
(aynı veriyi iki kez). Ev dizini kökün içinde; kalıcı olması gereken dosya
`/var/minis/shared`'a yazılır ve istem bunu modele söylüyor.

---

## 10. Düşük: paketleyicide ölü kod

**Hata.** `paket.py` içinde `for sub in (...): pass` — hiçbir şey yapmayan,
kopyala-yapıştırmadan kalmış bir döngü.

**Düzeltme.** Silindi. Altı klasör zaten oluşturuluyor (`mkdir` + yazma).

---

## 11. Korpus koşusunun yakaladığı iki hata (önceki turdan)

Bunlar bu turda değil, Termux kuralları eklenirken çıktı; kayda değer çünkü
ikisi de **sessiz** olurdu:

1. **`apt install -y sshpass` sessizce geçiyordu.** Kural sırası ters olduğu
   için `pkg:install` (LOW) önce eşleşiyordu. Tablo sıralı; Termux kuralları
   artık önde.
2. **`risk-corpus.py` kalıbı yüklemiyordu.** Ayrıştırıcı `Regex(` ile string
   arasına yorum girince kalıbı **sessizce atlıyordu**, yani gölge koşucu eksik
   kuralı görmeyip "temiz" diyordu. Düzeltildi; ders: **testin yeşil olması,
   kuralın var olduğunu kanıtlamıyor** — bu yüzden `load_regexes` artık sayı
   raporluyor ve korpusta kalıp sayısı çıktıda yazıyor.

---

## 12. Kalan bilinen sınırlar (düzeltilmedi — bilinçli)

| # | Sınır | Neden şimdi düzeltilmedi |
|---|---|---|
| 1 | Smoke testi `apt update` gerektiriyor; ağ yoksa atlanıyor | `apt-update` adımı `optional`; başarısızlık kurulumu düşürmüyor. Ağsız cihazda `bash --version` yine kanıt. |
| 2 | `SYMLINKS.txt` hatası ölümcül değil | Bazı imajlarda `Os.symlink` kısıtlı; smoke bunu yakalar ve "kuruldu ama kullanılamaz" der. Sert hata vermek, çalışan bir kurulumu çöpe atardı. |
| 3 | Zip-slip kontrolü `canonicalPath` ile | Symlink üzerinden kaçış (`dest` içinde önceden var olan bir symlink) kapsanmıyor. Bootstrap güvenilir kaynak; yine de not. |
| 4 | `refreshTermuxPath` süreç-içi global | Çoklu oturum paralel kurulum yaparsa yarış olabilir. Kurulum ekranı tek iş parçacığından çağırıyor; kapatmak için mutex gerekir. |
| 5 | Termux katmanı **kurulamıyor** henüz | P3 arayüz bağlanmadı: `ToolchainScreen` grup kartı `install()` çağırıyor (hazır) ama ilerleme satırı `Installing` etiketiyle sınırlı. |

---

## 13. Bu turdan sonra ölçüm

```
risk korpusu        57 komut · 0 sapma · 37 kural
yapısal denetim     1 646 dosya · yapısal hata yok
entegrasyon         10/11 dilim üretimden çağrılıyor
TermuxLayerTest     15 test (2 yeni: açma hedefi, data altında arama)
```

Derleme koşulmadı: bu cihazda JVM başlamıyor (*"Failed to mark memory page as
executable"*). Yukarıdaki üç kapı statik; `gradlew` CI'da.
