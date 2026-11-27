# HATA DEFTERİ — Minis Özel

> **Bu dosya bir özür listesi değil, bir kapı listesidir.** Her kayıt şu soruya
> cevap vermek zorunda: *bu hata bir daha olursa hangi otomatik kontrol
> yakalar?* Cevabı "insan dikkat ederse" olan kayıt, kayıt değildir.
>
> Kural (bu depoda bağlayıcı): **yeni bir hata sınıfı keşfedildiğinde, önce
> buraya kayıt, sonra o kaydı yakalayan kapı.** Kapısı yazılamayan bir kayıt
> `KAPSIZ` olarak işaretlenir ve `YAPILACAKLAR.md`'ye taşınır — sessizce
> kabul edilmiş sayılmaz.
>
> Denetim: `python3 scripts/ozel/hata-defteri.py` (Ozel Guard içinde çalışır).
> Her kayıtta `Kapı:` satırı zorunlu; işaret ettiği dosya gerçekten yoksa kapı
> kırmızı olur. Böylece defter zamanla çürüyemez.

---

## Kayıt biçimi

```
## H-XXX · Kısa ad
- **Sürüm / tarih:** hangi sürümde ortaya çıktı
- **Belirti:** kullanıcının gördüğü şey
- **Kök neden:** nedensel zinciri, tahmin değil kanıt
- **Kanıt:** dosya:satır, CI çalışması, ekran görüntüsü
- **Neden hiçbir kapı görmedi:** asıl ders burada
- **Düzeltme:** ne değişti
- **Kapı:** kontrol eden betik/adım
- **Kural:** gelecekte bu sınıfa girmemek için tek cümle
```

---

## H-001 · Veritabanı sürümü iki yerde yazılıydı

- **Sürüm / tarih:** `1.15-ozel.53` (37133), 2026-11-26
- **Belirti:** Uygulama kuruluyor, ilk açılış çalışıyor; **ikinci** açılışta
  "Verileriniz daha yeni bir sürümden geliyor" ekranı. "Kapat" dışında hiçbir
  şey yapılamıyor. Kullanıcı için görüntü: bütün sohbetler kaybolmuş.
- **Kök neden:** `AppDatabase`'in `@Database(version = 16)` değeri ile
  `DatabaseVersionGuard.CODE_DB_VERSION = 15` birbirinden ayrışmıştı.
  İlk açılış v16 dosyası yazıyor, koruma `16 > 15` görüp Room'u hiç
  açmıyordu. Veri kaybı yoktu — dosya hiç dokunulmadan reddediliyordu.
- **Kanıt:** `DatabaseVersionGuard.kt` (tek kaynak öncesi) `CODE_DB_VERSION = 15`;
  `AppDatabase.kt` `version = 16`. Kapı ekranındaki sayı da `15`.
- **Neden hiçbir kapı görmedi:** İki taraf da geçerli Kotlin'di; sayılar
  derleyici için anlamsız. `DatabaseVersionGuard`'daki yorum
  "`AppDatabaseVersionTest` ikisinin aynı olduğunu doğrular" diyordu ama
  **o dosya depoda hiç yoktu** — güvenlik ağı bir cümleydi, test değildi.
  Bu, deponun en pahalı dersi: *yorumda vaat edilen test, olmayan testtir.*
- **Düzeltme:** `DB_SCHEMA_VERSION` tek sabit; hem `@Database` hem muhafız onu
  okuyor. `AppDatabaseVersionTest.kt` gerçekten yazıldı (annotation ↔ sabit,
  muhafız ↔ sabit, `DOWNGRADE_PAIRS` ↔ migration alanları).
- **Kapı:** `scripts/ozel/db-surum.py` (+ Ozel Guard adımı "Veritabanı sürümü")
- **Kural:** Aynı bilgi iki dosyada yazılıysa, biri diğerini **okumak**
  zorunda; iki literal asla.

---

## H-002 · Commit edilmemiş şema json'ları

- **Sürüm / tarih:** `.15`'ten beri, 2026-11-27'de tespit
- **Belirti:** Doğrudan kullanıcı hatası değil — sessiz yetenek kaybı.
- **Kök neden:** `exportSchema = true` olmasına rağmen depoda yalnız
  `12.json`, `13.json`, `14.json` var. `15` ve `16` yok. `MigrationTestHelper`
  olmayan bir baseline'a karşı hiçbir şeyi doğrulayamaz: 15→16 ve 16→15
  zinciri bugün **hiçbir otomatik testin kapsamında değil**.
- **Kanıt:** `src/android/app/schemas/com.openminis.app.data.db.AppDatabase/`
  içeriği `[12, 13, 14]`; `DB_SCHEMA_VERSION = 16`.
  `apk.yml`'daki "Upload Room schemas" adımının yorumu da bunu itiraf ediyor
  ("it is not in the repo for version 15").
- **Neden hiçbir kapı görmedi:** Şema json'u bir *derleme çıktısı*; artefakt
  olarak yükleniyordu ama kimse indirip commit etmiyordu. "Sonra bakarız"
  dosyası hâline gelmişti.
- **Düzeltme:** `db-surum.py` eksikleri sayıyor ve listeliyor (uyarı; `--strict`
  ile hata). Artefakt `room-schemas` zaten üretiliyor → indirip commit etmek
  tek seferlik iş. Ayrıntı: `YAPILACAKLAR.md` → şema devri.
- **Kapı:** `scripts/ozel/db-surum.py` (uyarı seviyesi)
- **Kural:** `exportSchema = true` yazan bir depo, şema json'larını da
  sürümle birlikte taşımak zorunda; yoksa o bayrak bir yalandır.

---

## H-003 · `DROP COLUMN` — Android 8'de çalışmayan downgrade

- **Sürüm / tarih:** `.53`'te yazıldı, 2026-11-27'de tespit (henüz kimseye
  çarpmadı: kimse downgrade denemedi)
- **Belirti:** Sürüm düşüren kullanıcıda açılışta
  `SQLiteException: near "DROP": syntax error`.
- **Kök neden:** `MIGRATION_16_15` → `ALTER TABLE sessions DROP COLUMN team_id`.
  `DROP COLUMN` SQLite **3.35.0** (2021-03) ile geldi. `minSdk = 26` →
  Android 8.0 → **SQLite 3.18.2**.
- **Kanıt:** `minSdk = 26` (`app/build.gradle.kts`); migration gövdesi.
  Aynı depoda `MIGRATION_15_14` bu tuzağı *doğru* çözmüş (gölge tablo) —
  yani bilgi vardı, uygulanmamıştı.
- **Neden hiçbir kapı görmedi:** SQL geçerli *metin*; 3.35 desteği
  derlemede görünmez. Migration testi de 16.json olmadığı için (H-002)
  zinciri çalıştıramıyor.
- **Düzeltme:** Gölge tablo: `CREATE TABLE sessions_v15` → `INSERT SELECT` →
  `DROP TABLE` → `RENAME` + indeksler. `CREATE TABLE` metni `14.json`'dan
  birebir alındı (Room canlı şemayı entity ile karşılaştırır).
- **Kapı:** `scripts/ozel/db-surum.py` (SQL taraması) + H-002 çözülünce
  `MigrationTestHelper`
- **Kural:** `minSdk 26` bir SQLite sürümü kısıtıdır. `DROP COLUMN`, `RENAME
  COLUMN`, `STRICT` tablolar, `RETURNING` — hiçbiri güvenli değil.

---

## H-004 · Muhafızın bildiği downgrade yolları, kayıtlı olanlardan azdı

- **Sürüm / tarih:** `.16`–`.53`, 2026-11-27'de tespit
- **Belirti:** Sürüm düşüren kullanıcı, elinde **çalışan** bir downgrade
  migration'ı varken rehber ekranını görüyordu.
- **Kök neden:** `isHandledDowngrade` elle yazılmış iki karşılaştırmaydı
  (12→11, 13→12) ve orada donmuştu. `MIGRATION_14_13`, `15_14`, `16_15`
  `addMigrations`'a kayıtlıydı ama muhafız onları bilmiyordu → `evaluate()`
  `SHOW_NEWER_DB_GUIDANCE` dönüyordu.
- **Kanıt:** `AppDatabase.getInstance()` migration listesi vs
  `isHandledDowngrade` gövdesi.
- **Neden hiçbir kapı görmedi:** İki liste arasında hiçbir bağ yoktu;
  ikisi de tek tek doğru görünüyordu.
- **Düzeltme:** `AppDatabase.DOWNGRADE_PAIRS` tek kaynak; muhafız onu okuyor;
  `AppDatabaseVersionTest` her çifti karşılık gelen `MIGRATION_x_y` alanıyla
  eşleştiriyor.
- **Kapı:** `src/android/app/src/test/java/com/openminis/app/data/db/AppDatabaseVersionTest.kt`, `scripts/ozel/db-surum.py`
- **Kural:** "Bildiğim yollar" listesi, "kayıtlı yollar" listesinden
  türetilmeli — ikinci bir liste yazmak bir gün ayrışır.

---

## H-005 · Vaadedilen test dosyası yoktu (sınıf dersi)

- **Sürüm / tarih:** yorum `.16`'da yazıldı, dosya hiç oluşmadı
- **Belirti:** H-001'in doğrudan sebebi.
- **Kök neden:** Bir yorum `[AppDatabaseVersionTest]` diye bir sınıfa atıf
  yaptı; kimse dosyanın varlığını kontrol etmedi. IDE'de kırık referans
  uyarısı vermez çünkü yorum içinde.
- **Kanıt:** `rg 'AppDatabaseVersionTest'` → yalnız yorumlar (düzeltme öncesi).
- **Neden hiçbir kapı görmedi:** Yorum, derleyicinin kör noktası.
- **Düzeltme:** Dosya yazıldı.
- **Kapı:** `scripts/ozel/hata-defteri.py` — `[XxxTest]` biçiminde atıf yapan
  her yorumun karşılığı olan bir `XxxTest.kt` aranıyor.
- **Kural:** Kod içinde `[SomeTest]` / `SomeTest.kt` diye atıf yapıyorsan,
  o dosya aynı commit'te var olmalı.

---

## H-006 · `.35`–`.43` hiç derlenmedi

- **Sürüm / tarih:** `.35`–`.43`, 2026-11-24
- **Belirti:** `.41`'de silinen `private fun DecisionTab` dokuz sürüm boyunca
  fark edilmedi; çağrısı duruyordu → o sürümler derlenemez hâldeydi.
- **Kök neden:** `apk.yml` push filtresi `ozel-*` (tire) idi, çalışma dalı
  `ozel/16-duzeltmeler` (**slash**). `Slice Gate` yalnız dilim dosyalarını
  derliyor, `ui/**` orada yok.
- **Kanıt:** `apk.yml` yorumu (37124 notu) olayı birebir anlatıyor.
- **Neden hiçbir kapı görmedi:** Desen ile gerçek dal adı uyuşmuyordu ve
  kimse "bu dalda CI koşuyor mu?" diye sormadı. Yeşil görünen bir depo,
  hiç koşmamış bir CI'dır.
- **Düzeltme:** Filtreye `ozel/**` eklendi.
- **Kapı:** `scripts/ozel/kapi.py` (dal ↔ workflow filtresi eşleşmesi)
- **Kural:** Yeni bir çalışma dalı açtığın gün, o dalın CI'da koştuğunu
  *çalışma listesinde* gör.

---

## H-007 · "Manuel indir" bağlantısı yanlış depoya gidiyordu

- **Sürüm / tarih:** `.12`'den beri, 2026-11-16'da tespit, hâlâ açık
- **Belirti:** Otomatik güncelleme başarısız olduğunda gösterilen bağlantı
  resmî `OpenMinis/OpenMinis` sürümlerine gidiyor; indirilen APK farklı
  imzayla imzalı olduğu için **kurulamıyor** ("Uygulama yüklenemedi").
- **Kök neden:** `UpdateChecker.RELEASES_URL` sabiti elle yazılmış upstream
  adresi; `BuildConfig.RELEASE_REPO` ise doğru (fork). İki kaynak yine.
- **Kanıt:** `UpdateChecker.kt` — `RELEASES_URL = "https://github.com/OpenMinis/OpenMinis/releases"`.
- **Neden hiçbir kapı görmedi:** Kimlik kapısı (`check_identity.sh`) yalnız
  `applicationId`/`app_name`/`RELEASE_REPO` sabitlerine bakıyor; serbest metin
  içindeki URL'yi görmüyor.
- **Düzeltme:** `RELEASES_URL` `BuildConfig.RELEASE_REPO`'dan türetilmeli.
- **Kapı:** `scripts/ozel/kimlik-url.py` (yeni — H-001 turunda eklendi)
- **Kural:** Depo adı bir yerde yazılır; her yerde `BuildConfig`'ten okunur.

---

## H-008 · `main` dalı 143 commit geride kaldı

- **Sürüm / tarih:** 2026-11-16'dan beri
- **Belirti:** Depoya GitHub'dan bakan biri (veya yeni bir klon) 16 Kasım
  hâlini görüyor. Release notlarındaki "FORK.md §10" bağlantısı `main`'e
  gidiyor, orada `.53` yok.
- **Kök neden:** Çalışma dalı `ozel/16-duzeltmeler`; birleştirme elle
  yapılıyor ve yapılmamış.
- **Kanıt:** `compare/main...ozel/16-duzeltmeler` → ahead 143, dosya 300.
- **Neden hiçbir kapı görmedi:** `upstream-sync.yml` yalnız *upstream* ile
  farkı izliyor; kendi dallarımız arasındaki sapmayı izleyen yok.
- **Düzeltme:** Otomatik ilerletme (aşağıdaki "Sürekli senkron" bölümü).
- **Kapı:** `.github/workflows/main-sync.yml` (yeni)
- **Kural:** `main` her zaman yayınlanabilir ve güncel olmalı; çalışma dalı
  bir günden fazla ileriyse bu bir arıza sayılır.

---

## H-009 · `applicationId` sessizce değişti

- **Sürüm / tarih:** `.44`–`.53` arasında bir yerde
- **Belirti:** `com.veysel.minis` → `com.minis.x`. Eski paket kurulu bir
  cihazda `.53` **güncelleme olarak kurulamaz**, ayrı uygulama olur; sohbetler
  yeni pakete taşınmaz.
- **Kök neden:** Tespit edilmedi; kimlik kapısı yeni değeri "doğru" kabul
  ediyor çünkü sabitler güncellenmiş.
- **Kanıt:** `app/build.gradle.kts` → `applicationId = "com.minis.x"`;
  `.53` release notu "Kurulum: com.minis.x".
- **Neden hiçbir kapı görmedi:** Kapı "kimlik = sabit" diyor, "kimlik =
  *yayınlanmış* kimlik" demiyor. Sabit değişince kapı da değişiyor.
- **Düzeltme:** Yayınlanmış kimliğin kaydı (`docs/ozel/KIMLIK.md`) ve
  kapının ona bakması. Karar kullanıcıya ait — bilinçli değişiklikse kayıt
  altına alınır.
- **Kapı:** `scripts/check_identity.sh` genişletmesi (yayın kimliği dosyası)
- **Kural:** `applicationId` bir *sözleşmedir*; değişikliği bir sürüm notu ve
  veri taşıma planı gerektirir.

---

## H-010 · Açıklanmamış sürüm atlamaları

- **Sürüm / tarih:** `.12`–`.53`
- **Belirti:** Release yok ama sürüm numarası ilerlemiş (1, 4, 9, 10, 11 gibi
  boşluklar). Kullanıcı "bu sürümler neydi" diye soramıyor.
- **Kök neden:** Her başarısız deneme de sürüm numarasını yakıyor; kaydı
  tutulmuyor.
- **Kanıt:** `gh release list` ile `versionCode` dizisi karşılaştırması.
- **Neden hiçbir kapı görmedi:** `tag-denetim.py` etiket ↔ sürüm eşleşmesine
  bakıyor, *boşluklara* bakmıyor.
- **Düzeltme:** `docs/ozel/SURUM-DEFTERI.md` — her versionCode tek satır
  (yayınlandı / yayınlanmadı / neden).
- **Kapı:** `scripts/ozel/tag-denetim.py` genişletmesi
- **Kural:** Numara yakmak serbest, **açıklamasız** yakmak değil.

---

## H-011 · 441 çeviri anahtarı Türkçede yok, kapı "yeşil" diyor

- **Sürüm / tarih:** `.34`'ten beri, 2026-11-27'de ölçüldü
- **Belirti:** Uygulama Türkçe'de 441 metni **İngilizce** gösteriyor
  (`agent_callback_result`, `agent_detail_current_tool`, `agent_settings_allow`…).
  Kullanıcı Türkçe kullandığı için bu doğrudan görünür.
- **Kök neden:** `values/` 3112 anahtar, `values-tr/` 2671 anahtar.
  `ceviri-denetim.py` farkı `taban.json`'daki 441 "upstream devralımı"
  anahtarıyla eşleştirip **hepsini affediyor** → `SONUC: YESIL`.
- **Kanıt:** `python3 scripts/ozel/ceviri-denetim.py` →
  "eksik: 441 (0 tanesi YENI)" / "SONUC: YESIL".
- **Neden hiçbir kapı görmedi:** Taban dosyası "upstream'den devralınan
  eksikler" için yazılmıştı; amacı yeni regresyonu yakalamaktı, birikmiş
  borcu değil. Ama 441 kalıcı borç hâline geldi ve sayı hiç düşmüyor.
- **Düzeltme:** Taban borcu eritilmeli (aşağıdaki plan); kapı bir "borç
  tavanı" tutmalı — sayı artarsa kırmızı.
- **Kapı:** `scripts/ozel/ceviri-denetim.py` (`.ozel/ceviri/taban.json` borç tavanı)
- **Kural:** Affedilen bir eksik listesi, **sayısı sabit** olduğu sürece
  affedilebilir; sayı sessizce artıyorsa affetme bitmiştir.

---

## H-012 · Beş yayın etiketi yanlış commit'i gösteriyor

- **Sürüm / tarih:** 2026-11-27'de tespit
- **Belirti:** `v1.15-ozel.13`, `.14`, `.15`, `11.3.1`, `11.4` etiketleri
  kendi sürümlerini içermeyen commit'lere işaret ediyor. Güncelleme kontrolü
  `RELEASE_REPO`'dan release okuduğu için kullanıcıyı doğrudan etkilemiyor,
  ama "bu sürümde ne değişti" sorusunun cevabı yanlış commit'te.
- **Kök neden:** Etiketler elle atılmış; CI'ın ürettiği etiketle çakışmış.
- **Kanıt:** `python3 scripts/ozel/tag-denetim.py` → "5 etiket yanlış
  commit'i gösteriyor".
- **Neden hiçbir kapı görmedi:** `tag-denetim.py` bunu **rapor ediyor** ama
  Ozel Guard'da `--strict` olmadan çağrılıyor ("yanlış etiket bir push
  problemidir, PR'ı engellememeli"). Yani kırmızı hiç olmuyor.
- **Düzeltme:** Beş etiket silinip doğru commit'e yeniden atanmalı; kapı
  yayın dalında `--strict` ile koşmalı.
- **Kapı:** `scripts/ozel/tag-denetim.py` (`--strict` yayın adımında)
- **Kural:** Rapor eden ama kırmızı olmayan kontrol, kapı değil günlüktür.

---

## H-013 · Geçici ağ hatası (Maven 429) "blocking test" olarak okundu

- **Sürüm / tarih:** `.54` derlemesi, 2026-11-27
- **Belirti:** `:app:kspGithubDebugUnitTestKotlin FAILED` → CI kırmızı.
  Oysa kod sağlamdı: `Could not resolve junit:junit:4.13.2 … status code 429
  from server: Too Many Requests`.
- **Kök neden:** Gradle bağımlılık indirmesi tek denemeli. Maven Central
  hız sınırı, test derlemesini kod hatası gibi gösteriyor.
- **Kanıt:** CI logu 45919073382, "Unit tests → Fork tests (blocking)".
- **Neden hiçbir kapı görmedi:** Bu bir kapı sorunu değil, kapının *yanlış
  renk* vermesi. Kırmızı gördüğünde insan "kod bozuk" diye bakmaya başlıyor;
  birkaç kez yaşanınca kırmızı ciddiye alınmıyor.
- **Düzeltme:** Gradle'a `--refresh-dependencies` yerine retry: depo
  tanımına `mavenCentral()` yanında bir mirror, ya da iş adımına
  `continue-on-error` **değil** 3 denemeli `retry` sarmalayıcı. Yeniden
  koşum (re-run failed jobs) ilk çözüm.
- **Kapı:** `scripts/ozel/ci-hata-ayir.py` — 429/5xx'i ayırt eder;
  çıkış 2 = "yeniden dene", 1 = "gerçekten bozuk"
- **Kural:** "Kırmızı" iki anlama gelebiliyorsa (kod bozuk / ağ),
  ikisini ayırmadan kimse kırmızıya bakmaz.

---

## Sürekli senkron (H-008'in çözümü)

`main` dalının elle ilerletilmesi bir alışkanlık değil, bir borçtur.
Hedef: **`main` her zaman son yayınlanan sürüme eşit.**

- `.github/workflows/main-sync.yml` — çalışma dalı yeşil bir yayın
  ürettiğinde `main`'i fast-forward eder; edemiyorsa (yani gerçekten
  çatallanmışsa) issue açar, zorla yazmaz.
- Kural: `main`'e doğrudan commit yok; `main` yalnız yayınlanmış bir
  commit'e hızlı ileri sarılır.
- Ölçüm: `git rev-list --count main..<çalışma dalı>` her zaman 0 olmalı.

---

## Kapı → kayıt eşlemesi (özet)

| Kapı | Yakaladığı kayıtlar |
|---|---|
| `scripts/ozel/db-surum.py` | H-001, H-002, H-003 |
| `AppDatabaseVersionTest` | H-001, H-004 |
| `scripts/ozel/hata-defteri.py` | H-005 (+ defterin kendisi) |
| `scripts/ozel/kapi.py` | H-006 |
| `scripts/ozel/kimlik-url.py` | H-007 |
| `.github/workflows/main-sync.yml` | H-008 |
| `scripts/check_identity.sh` + `KIMLIK.md` | H-009 |
| `scripts/ozel/tag-denetim.py` | H-010 |
