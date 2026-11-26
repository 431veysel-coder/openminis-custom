# SÜRÜM NOTU — 1.15-ozel.47 / 37127

> **Önceki yayın:** `v1.15-ozel.46` / 37126

> ## 🔴 EN ÖNEMLİ: görevler bölümü çöküyordu — bulundu ve onarıldı
>
> `TaskHallScreen` **görev listesi doluyken** açılır açılmaz düşüyordu:
>
> ```
> IllegalStateException: Vertically scrollable component was measured
> with an infinity maximum height constraints, which is disallowed.
> ```
>
> Sebep: `SettingsScaffold` içeriği varsayılan olarak `verticalScroll` içine
> sarar; ekranın gövdesine konan `LazyColumn` yükseklik sınırı **almamıştı**.
> Compose iki dikey kaydırılabiliri ölçemez.
>
> **Neden kolay gizlendi:** boş liste dalı yalnız bir `SettingsRow` çiziyor,
> orada sınırsız yükseklik sorunu yok. Yani ekran "çalışıyor gibi" görünüyordu;
> hata **ilk görevle** birlikte ortaya çıkıyordu. Geliştirme sırasında boş bir
> cihazda hiç görünmez, kullanıcıda görev birikince patlar.
>
> Onarım: `scrollable = false` + `Column(fillMaxSize)` + `LazyColumn(weight(1f))`
> — kod tabanındaki mevcut kalıp (LogManagement/StorageManagement da böyle).
> `fillMaxSize` şart: `weight(1f)` yalnız ebeveynin yüksekliği sınırlıysa "kalan
> alan" demek; olmasa çökmeyi düzeltirken ekranı boşaltırdım.
>
> **Kapıya çevrildi** (`kural_ic_ice_kaydirma.py`): bir dosyada
> `SettingsScaffold` + `LazyColumn` varsa ya `scrollable = false` ya da
> `weight`/`heightIn` olmalı. Eski hâline döndürüp sınandı → KIRMIZI verdi;
> temizde susuyor.

---

## Eksen eksen: ne vardı, ne eksikti, ne değişti

### Görevler
| Durum | Ayrıntı |
|---|---|
| **Çöktü** | İç içe kaydırma (üstte) |
| **Eksikti** | Kanıt sayısı **sabit İngilizce** "evidence" → Türkçe arayüzde İngilizce kelime. Düzeltildi: `task_evidence_count` (`%1$d kanıt`) |
| **Eksikti** | Koşu içi görev odası durumu `state.wire` ile yazıyordu → `open`/`doing` İngilizce. Aynı ekrandaki sayaçlar `stateLabel()` kullanıyordu; iki oda aynı görevi iki dilde anlatamaz. Tek kaynağa bağlandı |
| Zaten iyiydi | Kanıt kuralı (`canBeDone`), atama/düşürme, ölçülen 4 durum |

### Bağlantılar / ilişkiler
| Durum | Ayrıntı |
|---|---|
| **Tek yönlüydü** | `TeamTask` üç bağ taşıyordu (`runId`, `fromClaim`, `createdBy`) ama **yalnız görev satırında** okunuyordu. Ters yön yoktu: karşılaştırma odasında "bu tartışma işe dönüştü mü" görünmüyordu |
| **Yeni** | `TaskLinks` (saf, testli): iddia → görevler, `taskCountFor`, `orphans` |
| **Yeni UI** | İhtiyaç odasında **"Göreve dönüşen iddialar"** bölümü: `C3 → 2 görev` + iddia metni. Göreve dönüşmeyen iddia listelenmez (üç önemli satırı dokuz önemsizle gömmemek için) |
| Not | `C3` ile ` c3 ` aynı iddia — normalizasyon testle çivilendi |

### Roller
| Durum | Ayrıntı |
|---|---|
| **Görünmezdi** | Dört ön ayar + Türkçe eş adlar zaten vardı (`researcher`, `araştırmacı`, …) ama **hangi rolün hangi araçları açtığı hiçbir yerde yazmıyordu** |
| **Tehlikeli** | Tanınmayan rol **hata vermiyor**, sessizce genel araç setine düşüyor (`MemberToolBelt`'in kendi KDoc'u: "silently get the generalist belt") |
| **Yeni** | `RoleHint` (saf, testli) + editörde rol alanının altında canlı satır: `Bu rolün verdiği araçlar: browser_use, file_read, memory_get, memory_write +8` |
| **Yeni** | Tanınmayan rol → **hata renginde** "Tanınmayan rol — bu üye genel araç setine düşer." Sessiz davranış artık görünür |
| **Yeni** | Elle araç seçimi rolu ezer ve bu söylenir: "Rol yok sayılıyor — araçları elle seçtin: …" |

### Kurallar
Kanıt kuralı tek yerde (`TeamTask.canBeDone`), `doneRefusal()` gerekçeyi cümleyle
veriyor; düşürme gerekçesi kayda yazılıyor. **Değişmedi** — ölçtüm, eksik yok.

### Canlı içerik
**Hâlâ açık.** `screen capture` gerçek PNG üretiyor ama içinde sayfa/terminal
değil **bölme durumu** var; karenin altında `state frame — live page/terminal
content is not rendered yet` yazıyor. Bunu kapatmak iki ekranı (WebView + PTY)
Compose içine taşımak demek. Bu cihazda görmeden yapılacak bir refactor, kendi
koyduğum "kanıt şart" kuralını çiğner. Sıradaki turun en büyük işi.

### Altyapı
`.46`'da zaman aşımı politikası bağlandı (derleme/kurulum/ağ/tarama sınıfları).
Bu turda **yeni ölçüm**: yerel kapıların **paralel** koşusu artık 600 s'yi
aşıyor (`gorunurluk` tek başına 303 s) → sahte KIRMIZI üretiyor. `--seri` ile
11/11. Not: kapı zaman aşımı ya artırılmalı ya `gorunurluk` hızlandırılmalı.

### Sohbet odaları (6 oda)
| Oda | Durum |
|---|---|
| Sohbet | Canlı pozisyon akışı, üye kartları, araç rozeti |
| Görev | Atama, kanıt ekleme, DONE/düşür (`.24`) |
| Sonuç | Rapor render + atıf denetimi + duruş gerekçesi |
| Karşılaştırma | Matris — `.46`'da **diskten** de çiziliyor (koşu bittikten sonra da) |
| İletişim | `ask_teammate` soru/cevap hattı, yaklaşıklık işaretli |
| Bağımlılık | **Yeni**: göreve dönüşen iddialar + sahipsiz + kanıt bekleyen |

### Chat
Değişmedi. `live!!` kaldırıldı (savunmasız `!!`, delege edilmiş özellikte
sessiz NPE'ye açık kapı).

### Koşucular
**Hâlâ açık:** tur sırasında **durdurma** yok. `CouncilLoop` iptali doğru şekilde
yukarı bırakıyor (kendi KDoc'u "the caller owns stop and synthesise what we
have" diyor) ama çağıran tarafta bir Job tutamacı yok. Yeni bir kayıt defteri
gerektiriyor — tek başına bir tur.

---

## Ölçüm

```
kapi (--seri)      : 11/11 · 0 uyarı · 479.9s
kotlin-hizli       : 7 dosya temiz · yeni kural probe ile sınandı
integration_audit  : 11/11 dilim bağlı
gorunurluk         : 25 anahtar · 21 açık+görünür · 0 açık+görünmez
ceviri             : taban 441 · 0 YENİ
yeni test          : 10 (4 TaskLinks + 6 RoleHint)
yeni dize          : 9 × 2 dil
```

## Bu turda eklenen kapı

`kural_ic_ice_kaydirma.py` — iç içe dikey kaydırma. Bu, bu depoda **12. kural
sınıfı** ve şu özelliği taşıyor: yakaladığı hata **boş veriyle görünmez**,
yani test cihazında değil kullanıcıda çıkar. Kapı tam olarak bu kör nokta için.
