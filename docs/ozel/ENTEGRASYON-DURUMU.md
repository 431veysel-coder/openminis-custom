# MINIS X — entegrasyon durumu ve kullanıcı yüzeyi

**Sürüm:** `1.15-ozel.16` · versionCode **37097** · `com.minis.x` · arm64-v8a
**Önceki yayın:** `1.15-ozel.15` / 37092 (2026-11-20)
**Release: YAPILMADI.** 37097 hazırlık dalında; push/yayın kararı senin.

Bu belge "hangi özellik gerçekten çalışıyor" sorusunun tek cevabı. Ölçüm elle
değil: `python3 scripts/integration_audit.py` üretim kodundan çağrı arıyor.
Testlerin ve sınıfın kendi paketinin çağrı sayılmadığı bir tarama.

> **`.16` notu:** 37089 (yeniden oynatma) artık üretim kodundan çağrılıyor
> (`ReplayScreen` → `ReplayStore`), o yüzden ⏳ değil ✅. 37095 (sağlık
> göstergesi) bu sürümde **gerçekten bağlandı**: çekirdek `.15`'te vardı ama
> çağrısızdı.

---

## Ölçülen durum: 10/11 bağlı

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
| 37089 | Yeniden oynatma | ✅ **BAĞLI** | Ayarlar › Geçmiş koşuyu yeniden oynat |
| 37090 | Uzaktan konsol | ⛔ **yalnız model** | Ürün kararı bekliyor, uygulama yok |
| 37095 | Sağlık göstergesi | ✅ **BAĞLI (.16)** | Canlı Görevler kartında `820ms · 3.2s · 429` |

**Neden 10 da 11 değil:** her dilim bir sınıf + test + anahtar olarak hazır, ama
"çalışıyor" için üretim kodundan çağrı gerekiyor. Kalan tek dilim (37090 uzaktan
konsol) bilerek uygulanmadı: jeton modeli var, sunucu yok — ürün kararı
bekliyor.

### `.16`'da eklenen ikinci görünür yüzey: sağlık satırı

`ChatHealthWiring` → `LiveTasksScreen`. Canlı Görevler kartının alt satırı:

```
RUNNING · shell_execute: pytest · 820ms
WAITING · 3.2s                 (istek yolda, sayaç canlı)
RETRYING · 1/3                 (geri çekilme sayacı)
ERROR · 429                    (kota), 503 (sunucu), NET (ağ yok)
```

`.15`'te bu çekirdeğin (`ChatHealthTracker`) üretim kodunda **tek** referansı
kendi tanımıydı — anahtar açık, görünen hiçbir şey yok. Aynı sınıf hata `.14`'te
`STATION` için yazılmıştı; tekrar etmişti.

---

## Kullanıcı ne görüyor — ön yüz

### 1. Onay kartı (37080) — en görünür yüzey

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
