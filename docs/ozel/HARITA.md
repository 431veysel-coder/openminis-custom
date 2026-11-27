# HARİTA — Minis X

Bu dosya "ne, kim için, nasıl" sorusunun tek cevabı. Sayılar **ölçülmüş**;
ölçülmeyen her yer açıkça "ölçülmedi" diye işaretli.

Sürüm `1.15-ozel.17` / 37098 · commit ve sayılar aşağıdaki ölçüm bloğunda.

<!-- OTO:BASLA -->
| Ölçüm | Değer |
|---|---|
| Kotlin dosyası (main) | 910 · 324 044 satır |
| Ekran | 76 · UI paketi 19 |
| Dilim | 13 (bağımlılığı olan: 1) |
| Risk kuralı | 43 — DENY 5 · karta düşen 38 |
| Özellik anahtarı | 25 — açık 21 · kapalı 4 |
| İzin | 27 Android + 2 özel |
| Test | 6 782 birim (790 dosya) + 124 cihaz |
| Çeviri | en 3112 · tr 2671 · eksik 441 (yeni 0) |
| Sözleşme | 13 · kayıt 72 |

Ayrıntı ve yönlendirme: [`docs/harita/00-GIRIS.md`](../harita/00-GIRIS.md)
<!-- OTO:SON -->

---

## 1. Amaç — neden var

OpenMinis, telefonun içinde gerçek bir Linux kabuğu çalıştıran, açık kaynaklı
bir yapay zekâ ajanı. Minis X onun çatalı (GPL-3.0). Dört sebep:

| # | Sebep | Somut karşılığı |
|---|---|---|
| 1 | **Özellik ekleme özgürlüğü** | Issue oylanmasını beklemeden yazmak. 13 dilim, 37080–37098 |
| 2 | **Ajanın gerçekten yetenekli olması** | Kutudaki kök 8 MB (busybox+apk+curl); §6'daki 12 grup + Termux katmanı bunu kapatıyor |
| 3 | **Kendi dağıtım hattı** | Kendi imza, kendi CI, kendi güncelleme kanalı |
| 4 | **Deney alanı** | Okumak ile değiştirip sonucu görmek arasındaki fark |

**Kim için:** tek kişi — cihazın sahibi. Uygulama `com.minis.x` ile resmî
Minis'in **yanına** kurulur, onu ezmez. İkinci sırada: çatalı alan başka
biri (GPL-3.0, kaynak açık, `FORK.md` §1.1 altı değişmez ilke).

**Neden tek kişi önemli:** her karar "ben kullanacak mıyım" sorusuyla
veriliyor. `37090` (uzaktan konsol) bu yüzden yarım: cep telefonunda
olmadığımda yazma yetkisi, kazandırdığından çok risk.

---

## 2. Ne yapar — üç katman

```
┌──────────────────────────────────────────────────────────────┐
│ 1. AJAN       sohbet · araçlar · ekip/konsey · alt ajan       │
├──────────────────────────────────────────────────────────────┤
│ 2. KABUK      PRoot konuğu (Alpine 3.21.3)                    │
│               ├─ Alpine (musl, apk)      ← varsayılan         │
│               └─ Termux katmanı (bionic, apt) ← isteğe bağlı  │
├──────────────────────────────────────────────────────────────┤
│ 3. CİHAZ      Android API'leri: bildirim, konum, kamera,      │
│               takvim, kişiler, alarm, TTS, STT, pano          │
└──────────────────────────────────────────────────────────────┘
```

Üç katmanın sınırı **tek bir kuralla** korunuyor: 3. katmana giden her çağrı
ayrı bir araç (`android-*` CLI) ve kendi izin akışı var; 2. katman onay
kapısından geçiyor; 1. katman araç seçimini yapıyor.

---

## 3. Kod haritası

Sayılar yukarıdaki ölçüm bloğunda; bu bölüm **yapıyı** anlatıyor.

### 3.1 `ozel/` — çatala ait olan

| Dosya | Ne |
|---|---|
| `PolicyGate.kt` | Onay kapısının Android tarafı: kuyruk, kalıcı izin, kapsam |
| `ApprovalInbox.kt` | Android'siz onay kutusu: zaman aşımı, geç/çift cevap (7 test) |
| `GrantStorage.kt` | "Bu sohbette hep" defterinin diske yazımı |
| `BudgetStore.kt` | Sohbet/gün/ay tavanları |
| `AutoBackupTrigger.kt` | Yedek zamanlaması (aralık + pencere) |
| `TurnSummaryBridge.kt` | Tur sonu diff'i: "3 dosya +120/−8" |
| `LessonRecorder.kt` | Ders defteri: hata → öneri (kendiliğinden yazmaz) |
| `EvidenceRecorder.kt` | Kanıtlı "bitti": son 3 turda üretilmiş kanıt ister |
| `ReplayStore.kt` | Araç çıktıları dondurulmuş yeniden koşu |
| `ChatHealthWiring.kt` | Sağlık çekirdeğini sohbete bağlayan köprü (3 gerçek an) |
| `TermuxPrompt.kt` | Sistem istemine giren iki sabit metin (kurulu / değil) |
| `health/ChatHealth.kt` | Sağlık çekirdeği — Android'siz, saf hesap |

Kural: **çekirdek Android'siz, bağlantı dışarıda.** `ozel/health/` bir dilim
(37095) ve dilim kapısı orayı Android'siz derliyor; `ChatHealthWiring`
`Context` kullandığı için aynı dizinde duramaz.

### 3.2 `policy/` — risk tablosu (dilim 37080)

| Dosya | Ne |
|---|---|
| `RiskRules.kt` | **42 kural**, veri olarak. DENY 5, gerisi ALL |
| `RiskClassifier.kt` | Komutu parçalara ayırır, en ağır karara indirger |
| `RiskModel.kt` | Seviye (`LOW/MEDIUM/HIGH`), kapsam (`SANDBOX→ACCOUNT`), geri alınabilirlik |

### 3.3 `sandbox/` — kabuk (en büyük ve en riskli alan)

`PRootKernel` · `RootfsManager` · `ExecutionCoordinator` · `ShellExecutor` ·
`PersistentShell` · `FreshProcessShell` · `TerminalSession` · `ToolchainManager` ·
`ToolchainProbe` · `TermuxLayer` · `TermuxInstaller` · `NativeOffloadServer`

---

## 4. Sözleşmeler — "ne vaat ettim, kanıtı ne"

`.ozel/sozlesmeler/` altında **13 sözleşme, 72 kayıt**. Her kayıt bir satır
JSONL, üç işlem tipi:

| `op` | Ne demek | Sayı |
|---|---|---|
| `ac` | sözleşmeyi aç (kapsam + dışarıda kalan + kademe + taban commit) | 13 |
| `alan` | alan yaz (kapsam, kaynak, saklama, sızıntı…) | 46 |
| `kanit` | kanıt komutu/çıktısı | 13 |

Bir sözleşmenin açılış kaydı — gerçek örnek (`IZN-surum-37092`):

```json
{"op":"ac","tur":"IZN","kademe":"K2","taban":"c836bd8",
 "kapsam":["yayin izni"],"disi":["-"]}
{"op":"alan","alan":"kaynak","deger":"GITHUB_TOKEN contents"}
{"op":"alan","alan":"saklama","deger":"GitHub secrets (4 imza sirri)"}
{"op":"alan","alan":"sizinti","deger":"log artifact maskelenmez -> scrub_logs.py"}
{"op":"kanit","deger":"komut:rg -n 'contents:' .github/workflows/apk.yml"}
```

**Sözleşmenin değeri:** `disi` alanı. "Bunu yapacağım" kadar "bunu
yapmayacağım" da yazılı. Sessiz kapsam kayması — en sık hata — burada
yakalanıyor.

---

## 5. Dilimler — 13 birim, 13 versionCode

Bir dilim = bir `versionCode` = bir derleme birimi. Kural: dilim yalnız kendi
dosyaları + `deps` ile derlenebilmeli, ve **`android.*` import edemez**
(kapı hata sayar).

| # | Dilim | Ne | Durum |
|---|---|---|---|
| 37080 | Onay kapısı | risk + kapsam defteri + kuyruk | ✅ bağlı |
| 37081 | Bütçe tavanı | sohbet/gün/ay | ✅ |
| 37082 | Otomatik yedek | aralık + pencere | ✅ |
| 37083 | Plan önizleme | pahalı işten önce tek kart | ✅ |
| 37084 | Tur özeti | diff + geri alma sırası | ✅ |
| 37085 | Araç kapısı | niyet → 6-10 aday araç, LLM'siz | ✅ |
| 37086 | Ders defteri | hata → öneri | ✅ |
| 37087 | Kanıtlı "bitti" | son 3 turda kanıt | ✅ |
| 37088 | Yazma niyeti | bloklamayan beyan + çakışma sicili | ✅ |
| 37089 | Yeniden oynatma | dondurulmuş çıktı | ✅ |
| 37090 | Uzaktan konsol | **yalnız jeton modeli** | ⏳ bilerek yarım |
| 37095 | Sağlık çekirdeği | TTFB/p50, hata etiketi | ✅ |
| 37098 | Termux çekirdeği | karar mantığı (bağlantı katmanı dilim dışı) | ✅ |

Bağımlılık: yalnız `37088 → 1 dep`. Diğer 12 dilim bağımsız — bu tesadüf
değil, dilim kapısının şartı.

---

## 6. Kurulum yolları — dört ayrı şey

| # | Ne | Nereden | Boyut | Ne zaman |
|---|---|---|---|---|
| 1 | **APK** | kendi CI, kendi imza | ~43 MB | kurulumda |
| 2 | **Alpine kökü** | `assets/alpine-minirootfs.tar.gz` (derlemede iner, `.gitignore`'da) — Alpine **3.21.3** | ~8 MB → 1,4 GB dolu | ilk açılış |
| 3 | **Araç zinciri grubu** | `apk add`, 12 grup | 20–260 MB/grup | isteğe bağlı |
| 4 | **Termux katmanı** | resmî bootstrap, çalışma anında, sha256 sabit | 32 MB indirme → 110 MB disk | isteğe bağlı |

`proot` ikilisi repoda derlenmiş hâlde: `libproot-loader.so`, `libproot-loader32.so`.

**Bu dördü karıştırılmamalı.** APK'yı büyütmeyen tek şey 4. satır — ve bedeli
"indirme çalışma anında" olması.

---

## 7. "Farz / caiz" — üç karar sınıfı

Risk tablosunun tamamı bu üç kutuya düşer. Korpustan ölçüm: **74 satır →
22 SILENT · 45 ASK · 7 DENY**.

| Sınıf | Anlamı | Ne olur | Örnek |
|---|---|---|---|
| **DENY** | farz yasak — doğru cevap "evet" olamaz | kart bile açılmaz | `rm -rf /` · fork bomb · `pm uninstall com.minis.x` |
| **ASK** | caiz, izinle | onay kartı: seviye + gerekçe + geri alınabilirlik | `apt install` · `sshd` · `mv` · `curl \| bash` |
| **SILENT** | serbest | komut koşar | `ls` · `rg` · `git status` · kendi `/tmp`'si |

**Seviye ≠ sınıf.** MEDIUM ve üstü ASK'tır; LOW ve altı SILENT. Ama bir kural
hedefe göre yükselir: `rm -rf` ajanın kendi çöpünde **LOW** (sessiz),
`/var/minis/shared`'de **HIGH** (kart), kökte **DENY**.

**Bu ayrımın tek amacı:** gerçek uyarının görünür kalması. Her komuta kart
çıkarmak, hiçbir komuta kart çıkarmamakla aynı sonucu verir.

---

## 8. Tercihler — anahtarlar

`OzelFeatures.kt`, `SharedPreferences`. Varsayılan **açık** olan bir anahtarın
üretim kodunda okuyucusu olmak zorunda — `.14`'te `STATION` bu kuralı çiğnedi
ve kapatıldı.

Tam liste (adaydan üretilmiş): [`40-OZELLIKLER.md`](../harita/40-OZELLIKLER.md) —
orada her anahtarın adı ve varsayılanı var.

Kapalı olanların her biri **bilinçli**: `evidence_gate` ve `replay` gürültülü,
`remote_console` ürün kararı bekliyor, `station` hiç yazılmadı.

---

## 9. Tarayıcılar ve köprüler

| Yol | Ne yapar | Nerede |
|---|---|---|
| `ui/browser/` | uygulama içi tarayıcı (sekmeler, indirme) | 12 test |
| `ui/webview/` | WebView sarmalayıcı | — |
| `minis-open` | `/var/minis/**` dosyasını uygulama içi önizlemede açar (md, pdf, ofis, medya) | kök kabukta `/usr/local/bin/minis-open` |
| `$BROWSER` | kabuktan gelen "link aç" çağrıları → `minis-open` → host `OpenOffloadHandler` | `PRootKernel.boot` |
| `android-open` | sistem işleyicisi (`tel:`, `mailto:`, `geo:`, `market:`) | CLI |
| `termux RUN_COMMAND` | **yok** — P4'te eklenecek (bkz. `TERMUX-APK-KARARI.md`) | — |

---

## 10. Testler — 6 517 birim + 124 cihaz

| Paket | Dosya | Ne ölçüyor |
|---|---|---|
| `ui/chat` | 156 | sohbet akışı, araç çağrısı, yeniden deneme |
| `provider` | 69 | sağlayıcı adaptörleri, akış, hata eşleme |
| `backup` | 52 | yedek, geri yükleme, şifreleme |
| `data` | 38 | Room, depo, göç |
| `sandbox` | 25 | PRoot, kabuk, kök çıkarma |
| `service` · `speech` · `ozel` | 23 · 20 · 20 | arka plan, konuşma, çatal özellikleri |
| … | | 758 dosya toplam |

**Bu cihazda koşmuyor.** JVM PRoot altında başlamıyor
(`Failed to mark memory page as executable`). Yani 6 517 testin hiçbiri
burada doğrulanmadı; doğrulama CI'da. Buna karşılık `risk-corpus.py` gibi
JVM'siz aynalar var — **gölge, kaynak değil**: ayrışırsa doğru olan Kotlin'dir.

---

## 11. Kapılar — 6 kontrol, saniyeler

| Kapı | Ne yakalar | Süre |
|---|---|---|
| `risk-corpus.py` | kural tablosu ↔ 74 komutluk beklenti ayrışması | ~1 sn |
| `kotlin-denetim.py` | parantez, çift imza, kapanmamış `"""` (1 646 dosya) | ~3 sn |
| `tag-denetim.py` | yayın etiketi ↔ sürüm uyuşmazlığı | ~1 sn |
| `ceviri-denetim.py` | `values/` ↔ `values-tr/` farkı (ratchet) | ~1 sn |
| `apk-butce.py` | `assets/` altında gömülü işletim sistemi yükü | ~1 sn |
| `integration_audit.py` | dilim üretimden çağrılıyor mu (10/11) | ~2 sn |

Üçü **yapıya** bakıyor. `37099`'da 9 hata bulundu ve hiçbiri bu üçünden
geçmiyordu — ders: kapı eklemek bir sınıfı kapatmaz, yazılan testin kapsamı
kadar kapatır. Bu yüzden `ceviri-denetim.py` ve `apk-butce.py` eklendi.

---

## 12. İzinler — 27 Android + 2 özel

**Yüksek risk:** `MANAGE_EXTERNAL_STORAGE` · `SYSTEM_ALERT_WINDOW` ·
`REQUEST_INSTALL_PACKAGES` · `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` ·
`RECORD_AUDIO` · `CAMERA` · `ACCESS_FINE_LOCATION`

**Kişisel veri:** `READ/WRITE_CONTACTS` · `READ/WRITE_CALENDAR` ·
`READ_MEDIA_IMAGES` · `ACCESS_MEDIA_LOCATION`

**Sistem:** `SCHEDULE_EXACT_ALARM` · `RECEIVE_BOOT_COMPLETED` ·
`FOREGROUND_SERVICE*` · `WAKE_LOCK` · `POST_NOTIFICATIONS`

**Özel:** `com.android.alarm.permission.SET_ALARM` ·
`moe.shizuku.manager.permission.API_V23` (Shizuku istemcisi — normal korumalı,
yalnız "istedim" demek için)

Kural (fork §1.1 madde 5): *"Bir özellik ekstra izin gerektiriyorsa, o izni
isteme akışı da o özelliğin parçasıdır."* `diagnostics/BackgroundGates.kt`
bunun için var.

---

## 13. Bağımlılıklar

**Gradle:** 49 tanımlama. Ana hatlar: Compose (material3 + adaptive + icons),
Room 2.6.1, DataStore 1.1.1, security-crypto, OkHttp/Retrofit (sağlayıcılar),
jieba (Çince bölütleme), KaTeX (matematik), androidx.browser.

**Sistem:** PRoot (kendi derlememiz, `deps/build_proot.sh`) · Alpine 3.21.3
minirootfs · Termux bootstrap (sabit sha256) · rclone-mobile (bulut)

**Dış servis:** yalnız yapılandırdığın LLM sağlayıcıları. Uygulamanın kendi
sunucusu yok — `37090`'ın jeton modeli hariç, o da uygulanmadı.

---

## 14. Senaryolar — gerçek yollar

### S1 · "Şu JSON'u işle"
`jq` yok → araç zinciri ekranı → `devtools` (35 MB) → `apk add jq` → sessiz
(LOW) → ajan `jq` çalıştırır. **Bugün çalışır.**

### S2 · "Bu depoyu incele, testleri koş"
`python` grubu → `git` + `python3` (+ `c` grubu `gcc` için) → `git clone` →
`pytest`. Ağ erişimi PRoot içinden. **Çalışır.** Testler kırmızıysa ajan
kanıtı gösterir.

### S3 · "apt ile bir Termux paketi kur"
Termux katmanı kurulu değilse: istem `NOT_READY` metnini taşır, model `apt`
denemez, kullanıcıya doğru yolu söyler. Kuruluysa: `apt install` → MEDIUM →
kart → kurulur. `bash --version` çıktısı kanıt olarak gösterilir. **Çalışır
(P3'ten sonra; symlink hatası düzeltildi).**

### S4 · "Bu klasördeki her şeyi sil"
`rm -rf /var/minis/shared` → HIGH + PERMANENT → kart, "bu sohbette hatırla"
yok. `rm -rf /` → DENY, kart yok. **Çalışır.**

### S5 · "İnternetten bir betik kur"
`curl … | bash` → düne kadar SILENT'ti, artık **ASK**. 37099'un varlık sebebi
bu satır. **Çalışır.**

### S6 · "Haberim olmadan çalışsın"
`dev_server` kapalı → modelin kabuğa erişimi yok. `background_gates` açık →
arka plan kısıtları için izin akışı devrede. **Çalışır.**

### S7 · "APK derle"
❌ **Çalışmaz.** JVM yok. `java` grubu kurulsa bile `java -version`
"Failed to mark memory page" verir. Derleme CI'da, `apk.yml`'de `needs: tests`.

### S8 · "Kendi kendimi güncelle"
⚠️ **Bozuk.** 5 yayın etiketi yanlış commit'i gösteriyor (`ffeb948`/11.2).
Kullanıcı ya "güncelleme yok" görür ya Android'in reddettiği düşük
`versionCode` iner. Plan hazır, uygulanmadı (geri dönüşsüz).

---

## 15. Açık uçlar — tek bakışta

| Ne | Durum | Neden |
|---|---|---|
| Push | ⛔ 9 commit ileride, 0 push | `GITHUB_TOKEN` `enabled: false` |
| Etiketler | ⚠️ 5 yanlış commit | uzak etiket silme geri dönüşsüz |
| Derleme | ⛔ bu cihazda yok | JVM/PaX |
| Testler | ⛔ bu cihazda koşmuyor | aynı |
| `37090` | ⏳ yarım | ürün kararı bekliyor |
| Termux APK köprüsü | ⬜ P4 | `RUN_COMMAND` ölçülmedi |
| `sshd` | ⬜ P4 | en riskli yüzey en sonda |
| Gerçek sandbox | ⬜ ayrı sürüm | seccomp/landlock |

---

## 16. Tek cümlelik harita

**Telefonunda gerçek bir Linux kabuğu olan, o kabuğa ne yaptığını her adımda
söyleyen (kart), yaptığını kanıtlayan (test + kapı + kanıt dosyası), ve
kendini kandırmayan (ölçülmemiş iddia yok) bir ajan** — tek kişi için,
kendi imzasıyla, kendi hattıyla.

Ve dürüst hali: **çalışan bir APK henüz yok**; olan şey çalıştığı ölçülmüş
bir kaynak ağacı, altı kapısı ve dokuz commitlik yayınlanmamış bir dal.
