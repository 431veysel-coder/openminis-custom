# İŞ LİSTESİ — 20 Kasım 2026

> **BU BELGE BAYAT.** Güncel plan: [`YAPILACAKLAR.md`](YAPILACAKLAR.md) (2026-11-23).
> Buradaki satırlar tarihsel kayıt olarak duruyor; açık maddelerin çoğu
> `YAPILACAKLAR.md` içine taşındı ve numaralandı.

> Bu liste uydurulmadı. Her satır bir **ölçümden** çıktı: `rg`, `integration_audit.py`,
> `preflight.py`, `git status`, GitHub API. "Olmalı" değil, "şu an şu".
> Eski plan `docs/ozel/SIRADAKI-SURUM.md` 37091 için yazılmıştı ve **bayat**:
> o sürüm çıktı, maddelerinin bir kısmı uygulandı, bir kısmı hâlâ açık — aşağıda
> hangisinin açık kaldığı tek tek işaretli.

## Durum (bugün, ölçülmüş)

| Şey | Değer |
|---|---|
| Yayındaki sürüm | **37091 / 1.15-ozel.14** (`com.minis.x`) |
| Sıradaki sürüm | **37092 / 1.15-ozel.15** (yakılmadı) |
| Çalışma dalı | `ci-37094` (yayın dalı değil — push release tetiklemez) |
| Son CI | run **44622129907** success (debug), 4 log artifact'ı dolu |
| Dilimler | 12 kayıtlı; entegrasyon **10/11** (37090 bağlı değil) |
| Sağlık göstergesi | çekirdek + test var, **chip yok** (ChatViewModel'e bağlı değil) |
| `schemas/…/15.json` | **YOK** (Room 15, dosya repoda değil) |
| Ağaçta yabancı iş | `scripts/slice_gate.py` değişik, `scripts/build_digest.py` izlenmiyor |

**Numara kuralı (netleştirme):** dilim etiketi (37095…) bir **iş birimi** adıdır;
`versionCode` yalnız **yayın anında** yakılır. 37095–37098 aynı sürümde (37092)
çıkabilir. Yakılan tek numara 37091'dir; 37092 serbest.

---

## DALGA 0 — bugün, 1–2 saat (blokaj değil ama sessiz bozulma)

### G1 · Dilim raporu iki farklı yere yazılıyor ⚠️
Başka bir oturum `scripts/slice_gate.py`'de `OUT`'u `build/slice-report` →
`docs/ozel/slice-report` yaptı ("build/ gitignore'da, her checkout'ta siliniyor" —
gerekçe doğru). Ama **iş akışı hâlâ eski yolu** okuyor:

| Yer | Yol |
|---|---|
| `.github/workflows/slice-gate.yml:99,121,131-132` | `build/slice-report/…` |
| `scripts/slice_gate.py` | `docs/ozel/slice-report/…` |

Sonuç: artifact **boş** yüklenir, `if-no-files-found: warn` olduğu için **sessiz**.
Yani "rapor kayboluyor" şikâyeti bu değişiklikle geri geldi.

**Dosyalar:** `.github/workflows/slice-gate.yml`, `scripts/slice_gate.py`,
`.gitignore` (gerekirse), `docs/ozel/slice-report/` (commit'lenecek)
**Kabul:** CI'da `slice-report` artifact'ı dolu; `SUMMARY.md` koşu özetinde görünür;
yeni bir dilim koşulunca rapor **git'te** duruyor.
**Boyut:** 20 dk · **Risk:** düşük

### G2 · `schemas/…/15.json` yok
Room `CODE_DB_VERSION = 15`, göç var, şema dosyası yok (`schemas/…/14.json` en yenisi).
Göçün doğruluğu CI'da doğrulanamıyor; bir sonraki DB değişikliğinde karşılaştırma
tabanı yok.
**Dosyalar:** `src/android/app/schemas/com.openminis.app.data.db.AppDatabase/15.json`
(CI'da KSP üretir → artifact'tan indirilip commit), artı bir Room şema testi.
**Kabul:** dosya repoda, `assemble` sonrası şema doğrulaması yeşil.
**Boyut:** 30 dk + bir CI turu · **Risk:** düşük · **Sahip:** ben (CI turu gerekir)

### G3 · Ağaçta sahipsiz iki dosya
`scripts/slice_gate.py` (M) ve `scripts/build_digest.py` (??) başka oturumun.
Sahiplenilmezse ya kaybolur ya yanlışlıkla commit'lenir.
**Karar:** G1 ile birlikte `slice_gate.py` sahiplenilsin; `build_digest.py`
G11'de ele alınacak (şu an akışa bağlı değil, ölü dosya).
**Boyut:** 5 dk · **Risk:** yok

### G4 · GitHub'a ait 8 action hâlâ `@v4/@v5`
Yalnız `softprops/action-gh-release` SHA'ya sabit. Karar bekliyor (senin: "şimdilik kalsın").
**Boyut:** 20 dk · **Risk:** düşük · **Not:** yapılırsa her action bump'ı elle iş olur.

---

## DALGA 1 — 37092 / 1.15-ozel.15 "sohbet sağlığı" (senin istediğin iş)

Sıra bağımlı: G5 → G6 → G7 → G8 → G9. Her biri ayrı commit; biri kırılırsa
diğerleri birleşebilir.

### G5 · (dilim 37096) Ölçümü ChatViewModel'e bağla
Bugün `ChatHealthTracker` var ama **hiç çağrılmıyor** (`rg -c` = 0).
**Dosyalar:**
- `ui/chat/ChatViewModel.kt` — 5 nokta, hepsi bugün ölçülmüş satırlarda:
  | Ne | Satır (bugün) | Çağrı |
  |---|---|---|
  | istek başladı | ~12224 (`streamStartMs`) | `tracker.requestStarted()` |
  | ilk parça | ~12355 (`firstChunkSeen.compareAndSet`) | `tracker.firstChunk()` |
  | akış bitti | ~12794 (`finally`, `turnStreamMs +=`) | `tracker.finished(outputTokens)` |
  | hata | ~12800+ (`catch (e: Exception)`) | `tracker.failed(http, kindOf(actual))` |
  | geri çekilme | ~2563–2571 (`_autoRetryAttempt/_autoRetryCountdown`) | `tracker.retryScheduled()` |
- yeni: `ozel/health/HealthBridge.kt` — `LLMError` → `ErrorKind` çevirisi (VM'i şişirmemek için)
- yeni: `ozel/health/HealthBridgeTest.kt` — 6 vaka (429/503/401/NET/TLS/unknown)
**Kabul:** `compileGithubDebugKotlin` yeşil; testler yeşil; davranış değişmiyor
(yalnız ölçüm — istek gövdesi, model seçimi, akış hiç ellenmiyor).
**Boyut:** 1,5 saat · **Risk:** orta (20k satırlık dosya; **tek `file_edit`/nokta**)

### G6 · (dilim 37097) Chip + ayrıntı kartı
**Dosyalar:**
- yeni `ui/chat/ChatHealthChip.kt` — 96 dp tavan, 10 sp, 10 dp ikon, 6 durum
- yeni `ui/chat/ChatHealthSheet.kt` — p50/p90, son süre, jeton/sn, "şimdi ölç"
- `ui/chat/ChatScreen.kt:3396` (`actions = { ... }` içine, `MoreVert` ikonunun **soluna**)
- `res/values/strings.xml` — 18 `mx_health_*` **zaten eklendi** (commit 3bbcb18)
**Çakışma uyarısı:** `RetryBadge.kt` **zaten var** ve mesajın yanında `2/3` gösteriyor
(`ChatIndicators.kt:218`). Chip aynı bilgiyi tekrarlamamalı:
**karar** — chip yalnız *üst çubuk* durumunu gösterir; tekrar sayısı chip'te
**görünmez**, ayrıntı kartında görünür. `RetryBadge` olduğu yerde kalır.
**Kabul:** debug APK'da chip görünür, başlığı sıkıştırmaz, döndürmede bozulmaz.
**Boyut:** 2 saat · **Risk:** orta (Compose ölçü/insets)

### G7 · (dilim 37097) Ayar satırı
`OzelFeaturesScreen.kt:312` civarına `SettingsSwitchRow` (REPLAY satırının üstüne).
**Neden zorunlu:** council dersinde anahtar vardı, **satırı yoktu** → özellik
kullanıcıdan saklanmıştı. Aynı hatayı tekrarlamıyoruz.
**Dosyalar:** `ui/settings/OzelFeaturesScreen.kt` (+2 string zaten var)
**Boyut:** 15 dk · **Risk:** düşük

### G8 · (dilim 37098) Kalıcılık
`filesDir/ozel-health.json`, halka tampon **50**, tur başına en fazla bir yazma,
bozuk dosya `.bad.json` (mevcut `ozel-approvals.json` deseni).
**Dosyalar:** yeni `ozel/health/HealthStore.kt` + `HealthStoreTest.kt` (5 vaka)
**Kabul:** uygulama yeniden başlayınca p50 korunuyor; bozuk JSON çökertmiyor.
**Boyut:** 1,5 saat · **Risk:** düşük

### G9 · Yayın 37092 / 1.15-ozel.15
**Dosyalar:** `src/android/app/build.gradle.kts` (**versionCode 37091→37092**,
versionName `1.15-ozel.14`→`1.15-ozel.15`), release notu, `docs/ozel/SIRADAKI-SURUM.md` güncelle.
**Ön koşul:** G1, G2, G5–G8 yeşil + bir **debug** koşu (chip gözle görülmüş).
**Kabul:** APK sertifika `3e62d262…`, `package=com.minis.x`, versionCode 37092,
`Run summary` dolu, release Latest.
**Boyut:** 40 dk + ~12 dk CI · **Risk:** orta (geri dönüşü yok — onay gerekir)

---

## DALGA 2 — "ilk mesaj sözleşmesi" (kaybolmayı bitiren iş)

Belge hazır: `ILK-MESAJ-SOZLESMESI.md`. Kod tarafı yok.

### G10 · Durum defteri (`durum_defteri.py`)
**Dosyalar:**
- yeni `scripts/durum_defteri.py` — `ekle` / `rapor` / `denetle`
- yeni `docs/ozel/durum/<konu>.md` (üretilen, elle yazılmaz)
- yeni `.ozel/durum.jsonl` (append-only; satır eklemek çakışmaz)
- yeni `src/android/app/src/test/.../durum` **değil** — Python testi: `scripts/tests/test_durum_defteri.py` (6 vaka)
**Kabul:** `ekle` sonrası `rapor` Z1/Z3/Z5/Z7 tablolarını üretir; iki eşzamanlı
yazma farklı satırlara düşer (git birleşmesi temiz).
**Boyut:** 2,5 saat · **Risk:** düşük

### G11 · `build_digest.py` sahipliği
Şu an **ölü dosya** (hiçbir akış çağırmıyor). İki seçenek:
**(a)** akışa bağla → son 15 koşunun hataları `docs/ozel/BUILD-GECMISI.md`'ye yazılır;
**(b)** sil. **Öneri (a)**, ama sahibi belli olsun: bu dosyayı yazan oturum
commit etmedi.
**Boyut:** 30 dk · **Risk:** düşük

---

## DALGA 3 — sağlayıcı sağlığı (bayat plandaki C1–C3, hâlâ açık)

Chat sağlığı bunun **görünen yarısı**; eksik olan kalıcı sayaç.

### G12 · Sağlayıcı başına sayaç + rozet
`QuotaRules.consecutiveFailures` var ama **yalnız kota yenileme** için; sohbet
hatasıyla ilgisi yok. İş: son 50 isteğin başarı oranı + ortalama gecikme (küçük
tablo), Ayarlar › Sağlayıcılar satırında rozet.
**Dosyalar:** `provider/ProviderHealth.kt` (yeni), `data/db/…` (yeni tablo + göç 16),
`ui/settings/ProvidersScreen.kt`
**Boyut:** 6–8 saat · **Risk:** düşük · **Not:** DB göçü → G2'den sonra

### G13 · Koşullu ping
Her ping ücretsiz değil, 429 üretebilir. Yalnız "son 10 dakikada hata var" ise at.
**Dosyalar:** `provider/ProviderConnectionTest.kt` (mevcut), `ozel/health/…`
**Boyut:** 2 saat · **Risk:** orta (kota)

### G14 · Dolar tavanı
`BudgetLimits` dolar alanı var, `BudgetStore` yalnız token okuyor; katalog fiyat taşımıyor.
**Karar:** fiyatı olan modellerde dolar, olmayanlarda "fiyat bilinmiyor" + token.
**Dosyalar:** `budget/BudgetStore.kt`, `provider/ModelsDevApi.kt`
**Boyut:** 3 saat · **Risk:** düşük

---

## DALGA 4 — senin kararın (kod değil)

| # | Karar | Bugünkü durum | Benim önerim |
|---|---|---|---|
| K1 | **37090 uzaktan konsol** | `remote/RemotePolicy.kt` (97 satır) + test, **bağlı değil**, anahtar kapalı | **Sil** — cep telefonunda değilken yazma yetkisi, kazandırdığından çok risk |
| K2 | **Otomatik yedek şifresiz** | `Request(passphrase = null)`: sohbetler + **sağlayıcı anahtarları** düz ZIP | `secrets` kategorisini yedekten çıkar; şifre isteğe bağlı |
| K3 | **STATION** | Anahtar `false`, `ui/station/` **hiç yazılmadı** | Ya 2 panelli A dilimi ya anahtarı kaldır |
| K4 | **21 dil** | `mx_*` metinleri yalnız TR+EN; diğerleri İngilizce | Önce TR+EN sabitlensin, sonra toplu çeviri |

---

## Dosya manifestosu — ne, nereye

| Dosya | Tür | Görev |
|---|---|---|
| `scripts/slice_gate.py` | düzenle (yol) | G1 |
| `.github/workflows/slice-gate.yml` | düzenle (yol) | G1 |
| `schemas/…/AppDatabase/15.json` | **yeni** | G2 |
| `ozel/health/ChatHealth.kt` | ✅ var | — |
| `ozel/health/HealthBridge.kt` | **yeni** | G5 |
| `ozel/health/HealthStore.kt` | **yeni** | G8 |
| `ui/chat/ChatHealthChip.kt` | **yeni** | G6 |
| `ui/chat/ChatHealthSheet.kt` | **yeni** | G6 |
| `ui/chat/ChatViewModel.kt` | 5 nokta | G5 |
| `ui/chat/ChatScreen.kt` | 1 nokta (3396) | G6 |
| `ui/settings/OzelFeaturesScreen.kt` | 1 satır | G7 |
| `res/values/strings.xml` | ✅ var (18 metin) | — |
| `scripts/durum_defteri.py` | **yeni** | G10 |
| `docs/ozel/durum/*.md` | **yeni** | G10 |
| `provider/ProviderHealth.kt` | **yeni** | G12 |
| `src/test/.../ozel/health/*.kt` | 9 ✅ + 11 yeni | G5/G8 |

## Aynı dosyayı 14 kez açmayalım — sabitlenmiş çapalar

| Dosya | Satır | Ne var |
|---|---|---|
| `ChatViewModel.kt` | 12224 | `streamStartMs` (istek başlıyor) |
| | 12355 | `firstChunkSeen.compareAndSet` |
| | 12794 | `finally` / `turnStreamMs +=` |
| | 12800+ | `catch (e: Exception)` |
| | 2563–2571 | `_autoRetryAttempt`, `_autoRetryCountdown`, `autoRetryMax` |
| `ChatScreen.kt` | 2990 | `TopAppBar(` başlıyor |
| | 3318 / 3396 | `navigationIcon` / `actions` |
| | 5252 | `RetryBadge` kullanımı (mesaj yanında) |
| `ChatIndicators.kt` | 218 | `retryAttempt > 0` rozeti |
| `OzelFeaturesScreen.kt` | 312 | REPLAY satırı (yeni satır bunun üstüne) |
| `OzelFeatures.kt` | 253 | `REMOTE_CONSOLE` (son giriş) |

Bu satırlar **bugün ölçüldü**. Bir düzenleme öncesi tekrar `rg -n` ile doğrula;
dosya 20k satır ve başka oturum da yazıyor.

## Önerilen sıra (tek cümle)

**G1 → G2 → G5 → G6 → G7 → G8 → debug koşu → G9 (yayın)** = 1,5 gün;
sonra **G10** (kaybolmayı bitiren defter), ondan sonra **G12–G14** (sağlayıcı sağlığı).
