# MINIS X — entegrasyon durumu ve kullanıcı yüzeyi

**APK:** `MinisX-37072-apk.apk` · `com.veysel.minisx` · `Minis X` · versionCode 37072 · 73,3 MB · arm64-v8a
**Release: YAPILMADI.** 37072 hâlâ yanmadı, release sayfasında `1.15-ozel.11.4` duruyor.

Bu belge "hangi özellik gerçekten çalışıyor" sorusunun tek cevabı. Ölçüm elle
değil: `python3 scripts/integration_audit.py` üretim kodundan çağrı arıyor.
Testlerin ve sınıfın kendi paketinin çağrı sayılmadığı bir tarama.

---

## Ölçülen durum: 9/11 bağlı

| # | Dilim | Durum | Kullanıcı nasıl görür |
|---|---|---|---|
| 37080 | Onay kapısı | ✅ **BAĞLI** | Tehlikeli komutta **kart** (5 satır). Ayarlar › Güvenlik |
| 37081 | Bütçe tavanı | ✅ **BAĞLI** | Tavanda tur başlamaz + bildirim |
| 37082 | Otomatik yedek | ✅ **BAĞLI** | Ayarlar › Otomatik yedek (kapalı gelir) |
| 37083 | Plan önizleme | ✅ **BAĞLI** | Uzun işte kart (mevcut onay kartı) |
| 37084 | Tur özeti | ✅ **BAĞLI** | Tur sonunda snackbar: kaç dosya |
| 37085 | Araç kapısı | ✅ **BAĞLI** | Görünmez; şema daralır (kapalı gelir) |
| 37086 | Ders defteri | ✅ **BAĞLI** | Sinyal toplanıyor; kart ayrı dilim |
| 37087 | Kanıtlı bitti | ✅ **BAĞLI** | Kanıtsız "bitti"de sonraki tura hatırlatıcı |
| 37088 | Yazma niyeti | ✅ **BAĞLI** | Görünmez; çakışma günlüğe yazılır |
| 37089 | Yeniden oynatma | ⏳ sınıf+test | Geliştirici aleti, ekran yok |
| 37090 | Uzaktan konsol | ⛔ **yalnız model** | Ürün kararı bekliyor, uygulama yok |

**Neden 9 da 11 değil:** her dilim bir sınıf + test + anahtar olarak hazır, ama
"çalışıyor" için üretim kodundan çağrı gerekiyor. Sırayla yapıldı: en yüksek
riskli olan (onay) ve en bağımsız olan (yedek) önce.

---

## Kullanıcı ne görüyor — ön yüz

### 1. Onay kartı (37080) — tek gerçekten görünür yeni şey

Tehlikeli bir komut geldiğinde sohbetin üstünde kart:

```
Dizin ağacı silinecek
NE           rm -rf /var/minis/shared/proje-a
NEDEN RİSKLİ dizin ağacını geri dönüşü olmadan siler — /var/minis/shared/proje-a
GERİ DÖNÜŞ   GERİ GELMEZ
AMAÇ         amaç belirtilmedi
YETKİ        bu oturumun dosyaları
  rm -rf /var/minis/shared/proje-a
  Geri alınamadığı için yalnız bu sohbette hatırlanır.

        [Bir kez izin ver]   [Kalıcı…] [Hepsini reddet]
```

- **Birincil düğme en dar yetkiyi verir.** Kullanıcı refleksle en büyük düğmeye
  basar; o düğmenin "yalnız bu sefer" olması tasarımın savunma hattı.
- **Kalıcı…** → kapsam seçimi (bu sohbet / bu proje / her yerde). Kalıcı iş
  (silme) yalnız sohbette hatırlanır ve bunu kart **yazar**.
- **Hepsini reddet** → birikmiş tüm kartlar reddedilir. Toplu *onay* yok.
- Ajan cevabı **bekler** (5 dk). Süre dolarsa ret + nedeni ajana döner ve ajan
  aynı komutu tekrar denemez.

### 2. Ayarlar › Minis X › Özellikler — iki yeni bölüm

**Güvenlik ve maliyet:** Yıkıcı işlemlerden önce sor · Harcama tavanı · Otomatik yedek
**Ajan kalitesi:** Uzun işi önizle · Bu turda ne değişti · Hatalardan ders al ·
Paylaşılan dosyada uyar · Tur başına daha az araç · "Bitti" demeden önce kanıt ·
Geçmiş koşuyu yeniden oynat

Her satırın alt yazısı ne yaptığını **ve maliyetini** söylüyor. 11 anahtar,
5'i kapalı gelir; her kapalı olanın gerekçesi koda ve teste yazıldı.

---

## Arka yüz — ne nereye dokunuyor

| Bağlantı | Dosya | Satır |
|---|---|---|
| Onay kapısı | `ui/chat/ChatViewModel.kt` `executeTool` başı | 14471+ |
| Onay kartı | `ui/chat/ChatScreen.kt` Scaffold içeriği | `ApprovalSheet()` |
| Onay kalıcılığı | `ozel/GrantStorage.kt` → `filesDir/ozel-approvals.json` | — |
| Yedek tetiği | `MinisApp.kt` `onCreate`, IO kapsamı | bellek göçünden sonra |
| Araç filtresi | `tools/AgentTools.kt` kemerden sonra | 154+ |
| Niyet | `ChatViewModel.lastUserIntent` | `sendMessage` |
| Ders sinyali | `ChatViewModel` araç hatası | `!success` yolu |
| Yazma niyeti | `tools/FileWriteTool.kt` yazmadan önce | 146+ |

### Paket ayrımı (mimari karar, sonradan düzeltildi)

```
policy/       saf karar mantığı — Android importu YOK, dilim kapısında derlenir
ozel/         Android köprüsü — Context, dosya, StateFlow, zaman aşımı
agent/router  araç seçimi
data/journal  FileLock (mevcut) + WriteIntent (yeni)
```

`PolicyGate` ilk yazıldığında `policy/` içindeydi ve dilim kapısının "Android
sızıntısı" kuralını bozuyordu. Taşındı: karar saflığı test edilebilirliğin
şartı, köprü ise Android'in.

### Bağımlılıklar — yeni kütüphane YOK

11 dilimin tamamı mevcut bağımlılıklarla çalışıyor:

| Ne | Nereden | Yeni mi |
|---|---|---|
| JSON | `org.json` (Android SDK) | — |
| Eşzamanlılık | `kotlinx.coroutines` (mevcut) | — |
| Compose | mevcut sürüm | — |
| Test | JUnit4 (mevcut) | — |

`policy/`, `budget/`, `plan/`, `replay/`, `agent/{router,lesson,evidence}`,
`ui/chat/diff/` paketlerinde **iki** import var: `java.time.Instant`,
`java.time.ZoneId`. Bu yüzden 80 MB'lık `kotlinc` ile Gradle/NDK olmadan
derlenebiliyorlar — dilim kapısı bunu kullanıyor.

---

## Derleme hattı — ne değişti

| İş | Ne yapar | Süre |
|---|---|---|
| **Slice Gate** (yeni) | 11 dilimi AYRI derler, AYRI test eder, AYRI rapor | 2 dk |
| Preflight | XML kaçışı, `R.string`, parantez, import | 10 sn |
| Version gate | Yanmış versionCode'u reddeder | 5 sn |
| Unit tests | 221 test (bu fork'un kendi) | 4-5 dk |
| Native | proot + rootfs + rclone | 1 dk |
| Build APK | Debug/release | 4-5 dk |

**Debug derlemesi ilk kez çalışabiliyor:** `build` işi `needs: [..., version-gate, ...]`
idi ve version-gate debug'da atlanınca build de atlanıyordu. `if: !cancelled() && !failure()`
eklendi.

---

## Kalan 6 dilim — neden bağlı değil, ne gerekiyor

| Dilim | Eksik olan tek şey |
|---|---|
| 37089 replay | **kayıt/oynatma ekranı** — kayıt üretimi ve oynatma düğmesi ayrı dilim |
| 37090 uzaktan | **ürün kararı** — "hayır" ise `remote/RemotePolicy.kt` silinmeli |

Her biri kendi başına 1-2 saatlik iş; hiçbiri diğerine bağlı değil.

---

## Bilinen borç

1. **`schemas/…/15.json` yok.** Room 14→15 göçü release öncesi şema dosyası ister.
2. **Ekran eksikleri:** 37084 tur özeti satırı, 37086 ders kartı, 37081 tavan
   girişleri. Sınıflar hazır, arayüz yok.
3. **`Consensus.converged`** kararlı-seri beklemeli mi — ürün kararı (test
   beklentisi düzeltildi, davranış değiştirilmedi).
4. Ekran görüntüsü alınamadı: PRoot'ta JVM yok, emülatör yok. Doğrulama
   `aapt2` (paket/isim/sürüm), dex dize taraması ve `resources.arsc` ile yapıldı.
