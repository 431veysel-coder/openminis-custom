# YAPILACAKLAR — geniş kapsamlı, kategori kategori

> **Durum:** 2026-11-25 · sürüm `1.15-ozel.36` / 37117 (derlenmedi, yalnız kaynak)
> Tur planı: [`PLAN-35-45.md`](PLAN-35-45.md)
> Bu dosya **plan**dır, ölçüm değil. Ölçüm için `HARITA.md` ve `harita/`.
> Her madde: ne · neden · nerede · nasıl anlaşılır (kabul ölçütü).
> Sıra: **P0** = kullanıcıyı bugün engelliyor, **P1** = sürüm hedefi,
> **P2** = iyileştirme, **P3** = fikir.

---

## 0 · Bu turda kapananlar (kayıt)

| # | İş | Kanıt |
|---|---|---|
| K1 | Termux bionic bağları dört PRoot kurucusunda | `TermuxLayer.prootBindArgs` + `ProotFlagParityTest` |
| K2 | Termux apt kök reddi (`-i 10000:10000`) | cihazda `apt --version` yanıt verdi |
| K3 | Havuzlu kabukta apt: taze sürece yönlendirme | `ExecutionCoordinator.requiresFreshProcess` |
| K4 | Sanal ekran istemi (A/B, ayar yolu, örnekler, durum) | `VirtualScreenPrompt` + wiring testi |
| K5 | Çeviri "model yok" hatası | `effectiveModelEntryId` üçüncü halka + modalite süzgeci |
| K6 | Alt ajan saat bütçesi + son 3 dk uyarısı | `HelperConfig.minutes`, `timeBudgetWarning` |
| K7 | Toplu izin ekranı (kategori, geri alınamaz hariç) | `GrantCatalog` + `PermissionGrantsScreen` |
| K8 | Konsey altı oda | `CouncilScreens` 6 sekme |
| K9 | Karşılaştırma/iletişim/bağımlılık gerçek veri | `ClaimLine.stances`, `QuestionLine`, `NeedLine` |
| K10 | Konsey "hiç pozisyon yok" arıza kaydı | `CouncilLoop.synthesize` boş dal |
| K11 | Soru → cevap eşleme | `CouncilLiveRegistry.matchAnswers` |
| K12 | Görev odası eylemleri (ekle/ata/düşür) | `RunTasksSection` |
| K13 | Dalga bilgisi (5 üye / 2 dalga) | `LiveRun.waveSize` |
| K14 | İzin kullanım sayacı ("7 kez kullanıldı") | `GrantCatalog.usageCounts` |
| K15 | Onay modu: sohbet bazlı kart yerine uygulama geneli izin (`SETTINGS_ONLY` varsayılan) | `policy/ApprovalMode.kt` + `ApprovalModeTest` (7) |
| K16 | Sesli onay uyarısı (TTS + titreşim + bildirim + tekrar) | `ozel/ApprovalVoice*.kt` + `ApprovalVoiceTextTest` (10) |
| K17 | Görünürlük kapısı (11. kapı): açık anahtar `ui/` tüketicisi ister | `scripts/ozel/gorunurluk.py` + `--kendini-sina` 4/4 |
| K18 | `CHAT_HEALTH` ölçümle kapatıldı (0 çağrı) | `OzelFeatures.kt` KDoc + kapı çıktısı |
| K19 | `olu-kod` yanlış pozitifi: `::ref`, trailing lambda, yorum | 250 → 313 (dürüst sayı) |

---

## 1 · Konsey (P0 — asıl kullanım burada)

### 1.1 Üye pozisyon üretemiyor / koşu boş dönüyor
- **Ne:** `no_positions` durumunda artık arıza kaydı yazılıyor (K10), ama **sebep
  hâlâ üye başına tek satır**. Sağlayıcı hatası, JSON bozukluğu, kota, model yok
  — hepsi aynı satırda görünüyor.
- **Neden:** kullanıcı "konsey çalışmadı" diyor; hangi üyenin hangi sağlayıcıyla
  neden düştüğünü bilmiyor. Bu, özelliğin güvenilirliğinin tek gerçek engeli.
- **Nerede:** `PositionParser.absent` (gerekçe üretimi), `CouncilLoop.runRound`
  (`reply.failure`), `AndroidCouncilRuntime` (sağlayıcı seçimi).
- **Kabul:** Bir üye düştüğünde `absentWhy` içinde sağlayıcı adı + hata sınıfı
  (`kota`, `401`, `json`, `boş cevap`) geçer; sonuç ekranı bunu üye üye listeler.

### 1.2 Konsey ön kontrolü (preflight)
- **Ne:** Koşu başlamadan üye başına model/seçenek doğrulaması: model var mı,
  sağlayıcı anahtarı geçerli mi, çıktı modalitesi metin mi.
- **Neden:** Bugün 0. turda düşüyor ve token/para harcanmış oluyor.
- **Nerede:** `CouncilPolicy`, `MemberConfig`, `ModelTierResolver`.
- **Kabul:** Eksik üye varsa koşu **başlamaz**, "şu üye yüzünden başlamadı" der.

### 1.3 Ekip/konsey kapısı (plan:long-run onayı)
- **Ne:** `team_run` çağrısı "plan:long-run onaylanmadı" ile reddediliyor;
  kullanıcı her koşuda ayrıca onay kartı görüyor.
- **Neden:** Toplu izin ekranı (K7) bunu kapsamıyor: kapı `ApprovalPolicy`
  değil, ayrı bir plan kapısı.
- **Nerede:** `PolicyGate.kt:177` çevresi, `team_run`/`council` kapısı.
- **Kabul:** Ayarlar › İzinler'de "ekip koşusu" satırı; açıkken kart çıkmaz,
  kapalıyken bugünkü davranış.

### 1.4 Oda içi eylem derinliği
- **Ne:** Görev odası ekle/ata/düşür yapıyor (K12). Eksik: görev **detayı**
  (kanıt ekleme), kanıt görüntüleme, "DONE" işaretleme akışı.
- **Neden:** `TeamTask.canBeDone` kuralı var ama kullanıcı kanıt ekleyemiyor;
  görev sonsuza kadar "kanıt yok" kalıyor.
- **Nerede:** `TaskHallScreen`, `TaskBoard.addEvidence`.
- **Kabul:** Bir göreve dosya yolu/URL/output eklenebilir; eklenince DONE açılır.

### 1.5 Karşılaştırma odası: matris görünümü
- **Ne:** Bugün satır başına bir metin bloğu. Gerçek tablo (dar ekranda yatay
  kaydırma) daha okunur.
- **Neden:** 5 üye × 8 iddia'da dikey liste okunmuyor.
- **Kabul:** Sütun başlıkları sabit, satırlar kayar; 320 dp'de taşma yok.

### 1.6 İletişim hattı: cevap yaklaşıklığını işaretle
- **Ne:** Cevap, hedefin sonraki pozisyonundan eşleniyor (K11) — kesin değil.
- **Neden:** Kullanıcı bunu resmî cevap sanabilir.
- **Kabul:** Satırda "≈ eşleme" işareti ve tek satır açıklama.

---

## 2 · Termux katmanı (P1)

### 2.1 Havuzda hâlâ reddedilen yollar
- **Ne:** `requiresFreshProcess` (K3) `shell_execute` yolunu çözdü. Ama
  **interaktif Terminal** (`TerminalSession`) hâlâ tek süreç + `-0`; orada
  `apt` reddedilir.
- **Neden:** Kullanıcı Terminal ekranını açıp `apt install` yazarsa aynı
  mesajı görür: "kurdum, çalışmıyor".
- **Nerede:** `TerminalSession.buildInteractiveCommand`.
- **Kabul:** Terminal'de `apt install` çalışır ya da ekran **baştan** "Termux
  komutları bu sekmede kök kimliğiyle çalışır" der. Sessiz kalmaz.

### 2.2 Termux paket listesi / arama
- **Ne:** Kullanıcı "Termux'ta şu paket var mı" diye sorduğunda ajan `apt-cache
  search` çalıştırmak zorunda; ağ yoksa cevap yok.
- **Kabul:** `apt-cache` çağrısı 2 sn içinde döner; ağ yoksa "depo erişilemedi"
  der, boş liste döndürmez.

### 2.3 Kaldırma yolu
- **Ne:** `uninstall()` rootfs dışına taşma koruması var; ama işaret dosyası
  (`.minis-extract-complete`) sonrası yarım kalan kurulum senaryosu test
  edilmedi.
- **Kabul:** "yarıda kes → tekrar kur" testi: ikinci deneme tam kurulum yapar.

### 2.4 `apt update` süresi
- **Ne:** Depo ilk güncellemede onlarca MB indeks indiriyor; komut zaman
  aşımına düşebilir.
- **Kabul:** İlk `apt update` için ayrı, uzun zaman aşımı (ya da ilerleme
  satırı) var; kullanıcı "takıldı" sanmıyor.

---

## 3 · İzinler ve onay (P1)

### 3.1 ~~Onay kartı yorgunluğu — kalan kısım~~ ✅ .35

> `.35`te mod eklendi: `SETTINGS_ONLY` varsayılan, kart yalnız verilemeyen
> kurallar için. "Karttan kural aç" bağlantısı yerine tek karar yeri (Ayarlar).
> Eski metin aşağıda kayıt olarak duruyor.
- **Ne:** K7 toplu izni getirdi. Kalan: kart **hangi kural** için çıktığını
  kısa bir "bu kuralı hep aç" bağlantısıyla göstermiyor.
- **Kabul:** Kartta "bu kuralı Ayarlar'da aç" satırı; tıklayınca ilgili
  kategoriye gider.

### 3.2 ~~Kapsam yükseltme (oturum → proje → cihaz)~~ ✅ .35

> `.35`te `SETTINGS_ONLY` zaten `Global` kapsam yazıyor; kullanıcı kart görmeden
> uygulama geneli izne sahip. Eski metin kayıt olarak duruyor.

### 3.2b Kapsam yükseltme (eski kayıt)
- **Ne:** `ApprovalScope` üç kapsamı biliyor; kart yalnız dar olanı öneriyor.
- **Neden:** Güvenlik kuralı gereği doğru; ama kullanıcı "bu projede hep"i
  seçemiyor.
- **Kabul:** `offeredScopes` zaten proje/global'i bazı durumlarda veriyor —
  kartta **görünür** olmalı, gizli kalmamalı.

### 3.3 Ret gerekçesi geri bildirimi
- **Ne:** Kullanıcı "hayır" dediğinde ajan gerekçeyi alıyor; ama kullanıcı
  aynı kuralı tekrar sorulunca "ben bunu reddetmiştim" diyemiyor.
- **Kabul:** İzinler ekranında reddedilen kurallar da listelenir (şu an yalnız
  ALLOW sayılıyor).

### 3.4 `deny:` kurallarının görünürlüğü
- **Ne:** `deny:root`, `deny:pm-all` vb. listede **yok** (kart da yok).
- **Neden:** Doğru — ama kullanıcı "neden bunu hiç sorulmadan reddetti"
  sorusunu cevaplayamıyor.
- **Kabul:** İzinler ekranında **salt-okunur** "hiç sorulmadan reddedilir"
  bölümü, nedeniyle.

---

## 4 · Alt ajan, ekip, süre (P1)

### 4.1 Süre bütçesi görünürlüğü
- **Ne:** K6 istemi ve uyarıyı ekledi. Kalan: **ekranda** kalan süre yok.
- **Kabul:** Alt ajan kartında "7/10 dk" ilerleme.

### 4.2 Alt ajan araç yüzeyi
- **Ne:** Kullanıcı "araç sıkıntıları" diyor: çocuk `ask_teammate`,
  `assign_task` gibi araçları görmüyor olabilir.
- **Kabul:** `MemberToolBelt` ile çocuğun gerçek araç listesi **yazılır**;
  istemde "görebileceğin araçlar" listesi şemayla birebir.

### 4.3 Ekip turunda zaman aşımı zinciri
- **Ne:** `TeamLoop` bütçesi var; üye düşünce tur devam ediyor ama
  `no_positions` gibi sessiz durumlar ekip tarafında da var.
- **Kabul:** Ekip sonucu "kaç üye cevap verdi / kim düştü" satırı taşır.

### 4.4 Eşzamanlı alt ajan sınırı
- **Ne:** 3 eşzamanlı + kuyruk var; kuyrukta bekleyen görünmüyor.
- **Kabul:** Kuyruk ekranı: "2 çalışıyor, 3 sırada".

---

## 5 · Sanal ekran (P2)

### 5.1 Tek yüzey çizimi
- **Ne:** `brief()` artık bağlı (K4) ama bölmeler tek tuvalde çizilmiyor.
- **Neden:** Kullanıcı "sanal ekran" derken görsel bir yüzey bekliyor.
- **Kabul:** `HeadlessRenderHost` + bölme durumu aynı yüzeyde; ajan yakalama
  yapabiliyor.

### 5.2 Bölme içi eylem (tıklama)
- **Ne:** `VirtualScreenHost.Op` var, hiçbir araçtan çağrılmıyor.
- **Kabul:** `screen` aracı: `open/click/type/scroll` — A modunda kartsız,
  B modunda kartlı (cost tablosu hazır).

### 5.3 Bölme başına yakalama bütçesi
- **Ne:** Yalnız global 250 ms var; belge "bölme başına" diyor.
- **Kabul:** Belge ile kod aynı şeyi söyler (belgeyi düzeltmek de kabul).

---

## 6 · Çeviri (P2)

### 6.1 Kalite: paket önerisi
- **Ne:** Dil çiftine göre hangi paketin iyi olduğu bilinmiyor.
- **Kabul:** Ayarlar'da "bu dil çifti için önerilen paket" etiketi.

### 6.2 Kısmi çeviri eylemi
- **Ne:** Kesilen/eksik çeviri "kısmi" diye gösteriliyor; "devam et" yok.
- **Kabul:** Kısmi çeviride "kalanı çevir" düğmesi, önbellekten devam.

### 6.3 Sözlük motorunun dürüstlüğü
- **Ne:** `OfflineGlossary` cümle çevirmiyor, `degraded=true` — arayüz bunu
  söylüyor mu, her çağrı yerinde?
- **Kabul:** Her yerde turuncu "sözlük" etiketi; hiçbir yerde "çevrildi" demez.

---

## 7 · Altyapı ve dayanıklılık (P1)

### 7.1 DNS
- **Ne:** Sandbox `getent`/python `getaddrinfo` github.com'u çözemiyor; curl
  çözüyor. Şu an `/etc/hosts` yamasıyla çalışıyor — **oturumluk**.
- **Neden:** Her yeni oturumda push/indirme kırılır; kullanıcı "yine bozuldu"
  der.
- **Kabul:** `minis-config dns` ile kalıcı resolver; ya da uygulama açılışında
  hosts satırını garanti eden tek yer.

### 7.2 Derleme doğrulaması olmadan geliştirme
- **Ne:** Bu cihazda gradle/JVM çalışmıyor → derleme hataları yalnız CI'da
  çıkıyor (bu turda 2 tane yakalandı: `startedAt`, eksik import).
- **Kabul:** `scripts/ozel/kapi.py`'ye "kaba derleme" adımı: eksik import,
  tanımsız sembol, parantez dengesi (Kotlin parser olmadan, regex + AST-lite).
  Ya da CI'da `compileOnly` hedefi ayrı, hızlı.

### 7.3 Çeviri kapısı tabanı
- **Ne:** 521 devralınmış eksik çeviri var; yeni eksik eklemek kırmızı.
- **Kabul:** Taban eritme planı: sürüm başına N anahtar.

### 7.4 Harita tazeliği
- **Ne:** `harita.py` her kaynak değişiminde koşmalı; unutulursa kapı kırmızı.
- **Kabul:** `kapi.py` haritayı **kendi** tazeler (şu an ayrı adım).

---

## 8 · Ürün ve deneyim (P2)

### 8.1 Konsey ekranında boş durumlar
- **Ne:** Altı odanın hepsi artık "neden boş" yazıyor (K8/K9/K12).
- **Kalan:** Boş durumdan **eylem**: "koşuyu başlat", "üye ekle".

### 8.2 Uzun rapor okuma
- **Ne:** Sonuç odası düz metin. Başlık, madde, atıf vurgusu yok.
- **Kabul:** Markdown-lite render: `#`, `-`, `[E1]` bağlantısı.

### 8.3 Dil tutarlılığı
- **Ne:** Ekranlarda TR/EN karışık (ör. "Görev ekle" + "Add a task" aynı
  projede, ayara göre).
- **Kabul:** Tüm yeni dizeler iki dilde; `ceviri-denetim` yeşil (şu an yeşil).

### 8.4 Erişilebilirlik
- **Ne:** Konuşmacı etiketleri, dokunma hedefleri 48 dp.
- **Kabul:** TalkBack ile altı oda gezilebilir.

---

## 9 · Fikirler (P3)

- **9.1** Konsey koşularını **karşılaştır**: aynı görev iki farklı üye
  yapısıyla koşsun, raporlar yan yana.
- **9.2** Görev panosundan **doğrudan alt ajan** başlat: "bu görevi bir ajana ver".
- **9.3** İzin kullanımından **öneri**: "bu kuralı 20 kez kullandın, toplu izne
  alalım mı".
- **9.4** Termux katmanında **hazır tarif**: `pip install` için gerekli
  paketleri tek dokunuşla kur.
- **9.5** Sanal ekranda **kayıt/oynatma**: ajanın ekranda yaptığı adımları
  tekrar oynat.

---

## 10 · Yol haritası `.23` → `.30` (tek bakışta)

Kural: her sürüm **en az bir P0/P1** kapatır, `YAPILACAKLAR.md` güncellenir,
kapanmayan madde gerekçesiyle bir alt sürüme taşınır. Derleme **yalnız kullanıcı
isterse**.

| Sürüm | Tema | Kapanacak maddeler | Kanıt |
|---|---|---|---|
| **.22** ✅ | Konsey odaları + iskelet | K10–K14, `2.1` | 8/8 kapı, `.22` kaynak |
| **.23** | Konsey güvenilirliği | `1.1` üye arıza sınıfı · `1.2` preflight · `1.4` görev kanıtı | birim test + koşu |
| **.25** ✅ | İzin/onay tamamlanması | `3.1` karttan kural aç · `3.2` kapsam görünürlüğü · `3.3` ret listesi · `3.4` deny okunur | cihaz testi |
| **.26** ✅ | Altyapı | `7.1` DNS doktoru · `7.2` derlemesiz doğrulama · `7.4` harita kendini tazeler | CI + yerel |
| **.27** | Sanal ekran yüzeyi | `5.1` tek tuval · `5.2` bölme içi eylem | yakalama kanıtı |
| **.28** | Alt ajan/ekip | `4.1` kalan süre · `4.2` araç yüzeyi · `4.4` kuyruk | kart ekran görüntüsü |
| **.29** | Çeviri | `6.1` paket önerisi · `6.2` devam et · `6.3` sözlük dürüstlüğü | çeviri testleri |
| **.30** ◐ | Ürün kapanışı (boş durum ✓, render/erişilebilirlik açık) | `8.1` boş durum eylemleri · `8.2` rapor render · `8.4` erişilebilirlik | TalkBack |
| **.30** | Kapanış | kalan P2'ler + `9.x`'ten seçilen 2 fikir · tam denetim | tüm kapılar + cihaz |

### Neden bu sıra

1. **`.23` önce**, çünkü konsey bugün "çalışmadı" dediğinde sebebi söylemiyor;
   diğer her şey bunun üstüne oturuyor.
2. **`.24` ikinci**, çünkü onay kartı yorgunluğu günlük kullanımı kesiyor ve
   `.22`'de kurulan toplu izin ekranı yarım (karttan oraya köprü yok).
3. **`.25` üçüncü**, çünkü DNS her oturumda push'u kırıyor ve derlemesiz
   doğrulama olmadan her tur CI'ya bağımlı.
4. **`.26`–`.28`** görünür özellikler; altyapı sağlamken yapılmalı, yoksa
   hata ayıklama iki kat zorlaşır.

### `.31` denetim turunda bulunanlar (kayıt)

| Ne | Sınıf | Durum |
|---|---|---|
| `AgentCallback(budget = …)` — alan `AgentCallback`'ta yoktu (`.27`'den beri) | **derleme hatası** | düzeltildi + kapı 3 yerden güçlendirildi |
| `needsCapture` / `markCaptured` üretimde **çağrılmıyor** (yakalama hattı yok) | ölü kural | belgelendi "latent"; `olu-kod.py` artık listeliyor |
| `queueSummaryOf` Türkçe parça, İngilizce cümle içinde | dil karışması | İngilizceye çevrildi (metin modele gidiyor) |
| `denialRows` kapsam metni Kotlin'de sabit "oturum/proje/cihaz" | i18n | saf çekirdek anahtar (`session`/`project`/`device`), çeviri arayüzde |
| `.22`–`.30` sürüm notları **hiç oluşmadı** (tek dosya `mv` ile taşındı) | belge kaybı | `SURUM-GECMISI-1.15-ozel.18-31.md` commit geçmişinden yeniden kuruldu |
| `olu-kod.py` ilk sürümü 300 sn'de kesildi (N ad × N dosya) | araç hatası | tek geçişe indirildi (0.7–1.9 s) |

**Yeni kapı: `olu-kod`** (bilgi, zorunlu değil) — yalnız son commit'te eklenen
fonksiyonlara bakar. Tüm ağaçta **250 devralınmış** çağrısız fonksiyon var;
hepsini kırmızı yapmak kapıyı susturur, o yüzden kapsam dar tutuldu.

### Bu turda (`.22`) gerçekten kapananlar

`.22`'ye `1.1`, `1.2`, `3.4`, `7.2`'nin **çekirdeği** de girdi (derleme yok):

| Madde | Ne yapıldı | Sınır |
|---|---|---|
| `1.1` | `MemberFailure` sınıflandırması (kota/kimlik/JSON/zama/y uva/model yok) + öneri satırı | ham gerekçe hâlâ sağlayıcıdan geliyor; HTTP kodu ayrıştırması `AndroidCouncilRuntime`'da kısmi |
| `1.2` | `CouncilPreflight` — hiç üye hazır değilse koşu **başlamaz** | "bazıları hazır" durumunda onay **sorulmuyor**, yalnız günlüğe yazılıyor; kart ekranı `.23` |
| `3.4` | `GrantCatalog.denyRows()` + İzinler ekranında salt-okunur bölüm | — |
| `7.2` | `scripts/ozel/kotlin-hizli.py` + `kapi.py`'de 9. kapı | yalnız kesin kurallar; tanımsız sembol denetimi yok |

---

## 11 · Riskler ve kararlar

| Risk | Neden | Karar |
|---|---|---|
| 5 üye / 4 yuva | cihaz RAM'i; 5. üye ikinci dalgada | **söyle**, gizleme (`LiveRun.waveSize`) |
| Konsey token maliyeti | her turda N üye tam prompt | preflight + `maxTokens` bütçesi |
| `deny:` listesi büyürse | kural eklemek kolay, silmek zor | her yeni kural korpusa satır + test |
| Derlemesiz geliştirme | cihazda JVM yok | `kotlin-hizli` + CI compile adımı |
| Çeviri kapsamı | 521 devralınmış eksik | sürüm başına N eritme, taban ratchet |
| Sanal ekran beklentisi | "ekran" kelimesi görsel yüzey vaat ediyor | istemde ve ekranda sınır yazılı |
