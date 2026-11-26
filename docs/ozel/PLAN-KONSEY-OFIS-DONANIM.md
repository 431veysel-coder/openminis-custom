# PLAN — Konsey · Sanal Ofis · Odalar · Donanım Farkındalığı

> **Yazıldığı an:** 2026-11-26 · yayındaki sürüm `v1.15-ozel.47` / 37127
> **Durum:** ÖNERİ. Bu belge onaylanmadan kod yazılmaz.
> **Yöntem:** her iddia kodda ölçüldü; ölçemediğim yere "ölçülmedi" yazdım.

---

## 0 · Ölçüm: bugün elimizde ne var

| Konu | Bulgu | Kanıt |
|---|---|---|
| Sohbetten konsey | **`team_run` aracı var** — model çağırır, kullanıcı çağıramaz. Üst barda konsey simgesi **yok** | `ChatViewModel.kt:14819`, `ChatScreen.kt:3396` |
| Sohbete özel konsey | **Yok.** `default_team_id` yalnız `folders` tablosunda (proje düzeyi) ve **hiçbir yerde okunmuyor** (0 okuyucu) | `FolderEntity.kt:96`, `ChatDao.kt:287` |
| Sanal ofis | **Yok.** Üye canlı durumu var (`MemberLive`), olay akışı var (`CouncilEvent`) ama **zaman damgası yok** → "kim ne zaman konuştu" cevaplanamıyor | `CouncilModels.kt:298-326`, `LiveRun:48` |
| Odaların sözleşmesi | **Yok.** Altı oda yalnız **sekme etiketi**; amacı/yetkisi/tüketicisi yazılı değil | `CouncilScreens.kt:173-178` |
| Donanım ölçümü | **Kısmi.** RAM'e göre dalga boyu seçen saf fonksiyon var (`forDevice`); `ResourceWatermark` thread/map/VmSize/VmRSS ölçüyor ve **JankDiag'a bağlı** — ama yalnız **loga** yazıyor, UI'da görünmüyor | `CouncilPolicy.kt:59`, `ResourceWatermark.kt`, `JankDiag.kt:415` |
| GPU/NPU/termal | **Yok.** Hiçbir yerde okunmuyor | tarama: 0 sonuç |

**Ortak desen:** ölçüm var, gösterim yok · veri var, zaman yok · sekme var, anlam yok.
Bu depoda tekrar eden "yazılmış ama bağlanmamış" sınıfının üç yeni örneği.

---

## 1 · Sohbetten konseye erişim

### 1.1 Ne istiyoruz

Her sohbetin **kendi konseyi** olmalı; kullanıcı sohbetin içinden konseyi
kurabilmeli, çalıştırabilmeli, durdurabilmeli ve cevabı **o sohbete** almalı.

### 1.2 Karar: sohbet ↔ takım bağı nerede tutulur

Bugün `default_team_id` **projede** (klasörde) tanımlı ve okunmuyor. Üç seçenek:

| Seçenek | Artı | Eksi |
|---|---|---|
| A. Proje düzeyi (`folders.default_team_id`) | Zaten kolon var; bir projedeki tüm sohbetler aynı konseyi kullanır | Bir sohbette farklı konsey istersen projeyi bölmen gerekir |
| **B. Sohbet düzeyi (`sessions.team_id`) + proje varsayılanı** | En esnek; proje varsayılanı devralınır, sohbet ezebilir | Yeni kolon + migration |
| C. Yalnız sohbet düzeyi | En basit | Proje varsayılanı fikri kaybolur |

**Önerim: B.** Gerekçe: kullanıcı "bu projenin konseyi X" diyor, ama tek bir
sohbette farklı bir heyet denemek istediğinde projeyi bölmek zorunda kalmamalı.
Devralma tek satır kural: *sohbetin takımı → projenin varsayılanı → yok*.
Mevcut `defaultTeamId` böylece **ilk kez okunmuş** olur (ölü kolon dirilir).

### 1.3 Arayüz

**Üst barda simge** (`groups`/`diversity_3`), durum üç halde:

1. **Konsey yok** — soluk simge. Dokun → "Bu sohbet için konsey kur" sayfası.
2. **Konsey var, koşmuyor** — normal simge + küçük nokta. Dokun → alt sayfa:
   takım adı, üye+model listesi, **[Çalıştır]**, [Değiştir], [Kaldır].
3. **Koşuyor** — dolu/rozetli simge, **dokun → canlı koşu ekranı**, uzun bas →
   **Durdur**. (Durdur `.48`'de geliyor; bu plan ona yaslanıyor.)

**Neden alt sayfa, yeni ekran değil:** konsey sohbetin bağlamında bir eylem;
kullanıcıyı ekran değiştirmeye zorlamak "sohbetten erişim" fikrini boşa çıkarır.

### 1.4 Koşu sonucu nereye yazılır

Bugün `team_run` cevabı **araç sonucu** olarak sohbete düşüyor. Simgeden
başlatılan koşu için aynı kural: sonuç sohbete **normal asistan mesajı** olarak
yazılır, ayrı bir "konsey sonucu" tipi icat edilmez. Nedeni: kullanıcı sohbeti
kopyalayıp paylaştığında rapor da gitsin.

### 1.5 Maliyet / risk

| İş | Süre |
|---|---|
| `sessions.team_id` + migration + devralma kuralı (saf, testli) | yarım tur |
| `SessionCouncil` çözümleyici + `ChatViewModel` bağı | yarım tur |
| Simge + alt sayfa + üç durum | 1 tur |
| **Toplam** | **~2 tur** |

Risk: **düşük.** Yeni mimari yok; mevcut `TeamStore` + `FanoutRunner` +
`CouncilLiveRegistry` kullanılıyor.

---

## 2 · Sanal ofis — ve yoğunluk sorunu

### 2.1 Soruya dürüst cevap

> "Her şeyi tek sayfa yaparsak çok yoğun olur — ne düşünüyorsun?"

**Katılıyorum, ve sorun daha derin: yoğunluk sayfa sayısıyla değil, *aynı anda
kaç soruya cevap vermeye çalışmakla* ilgili.**

Bir konsey koşusunda kullanıcının sorduğu soru **zamana göre değişiyor**:

| Ne zaman | Gerçek soru | İstediği şey |
|---|---|---|
| Koşu **sürerken** | "Takıldı mı, kim çalışıyor?" | 5 saniyede okunan durum |
| Koşu **bittiğinde** | "Ne oldu, kim ne dedi?" | Zamanda gezilebilir kayıt |
| **Karar verirken** | "Neye katılıyorlar, neye karşı?" | Matris |
| **Sonra** | "Ne yapmam gerekiyor?" | Görev listesi |

Bunları tek sayfaya koymak, dördünü de okunamaz yapar. **Yedi sekme de aynı
hatayı yapar** — kullanıcı hangi sekmede olduğunu unutur.

### 2.2 Öneri: oda değil **yakınlaştırma**

Ofis yeni bir sekme **olmasın**; var olan "Kat" odası bir **zoom kontrolü**
kazansın. Aynı veri, üç çözünürlük:

```
        [ Bakış ]  [ Kat ]  [ Kayıt ]        ← tek segment kontrolü
```

**1. Bakış (özet)** — 5 saniyelik cevap
- Üye başına tek satır: ad · model · durum noktası (konuşuyor / düşünüyor /
  araçta / bekliyor / yok)
- Üstte: tur 2/3 · dalga 3 · geçen süre · token
- **Hiç cümle yok.** Cümle "Kat"ta.

**2. Kat (floor)** — bugünkü oda, zenginleşmiş
- Üye kartı: rol, araç rozeti, **son söylediği cümle** (kırpılmış), oy verdiği
  iddia sayısı
- Kim kime soru sordu → ok işareti

**3. Kayıt (transcript)** — yeni, ve ofisin gerçek kalbi
- **Zaman damgalı**, sanallaştırılmış liste (`LazyColumn`)
- Her satır: `12:04:31 · Ayşe · pozisyon · "Derleme geçiyor…"`
- Filtre: üye · olay tipi (pozisyon/araç/soru/görev) · yalnız hatalar
- Uzun koşularda **sanal liste** şart; 3 tur × 5 üye × 10 olay = 150 satır,
  hepsi bellekte tutulursa büyük koşuda şişer

### 2.3 Plandaki asıl iş: **zaman**

Bugün `CouncilEvent` **zaman damgası taşımıyor** (`CouncilModels.kt:298`).
`LiveRun` de olay geçmişi tutmuyor. Yani "kayıt" görünümü **veri yokluğundan**
yapılamıyor.

Gereken: `CouncilEvent`'e `atMs: Long` (tek alan, varsayılan `0` — mevcut
üreticiler derlenmeye devam eder) + `LiveRun.events: List<TimedEvent>` (sınırlı
kuyruk: son **500** olay; sınırsız kuyruk = bellek sızıntısı, bkz. §5).

**Halka:** olaylar zaten `CouncilLiveRegistry`'ye akıyor (`onEvent`); eksik olan
sadece damga ve saklama.

### 2.4 Odalar 6 → 5

İki oda **aynı sorunun iki yarısı**: "İletişim" (kim kime sordu) ve
"Bağımlılık" (kim neyi bekliyor, sahipsiz iş). İkisi de *ilişki* sorusu.
Birleştirilsin: **"İlişkiler"** (sorular + sahipsiz + kanıt bekleyen +
iddia→görev bağları `.47`'de eklendi).

Sonuç: **Sohbet · Görev · Sonuç · Karşılaştırma · İlişkiler** = 5 oda, her biri
tek soruya cevap.

### 2.5 Maliyet

| İş | Süre |
|---|---|
| `CouncilEvent.atMs` + `TimedEvent` + sınırlı kuyruk (saf, testli) | yarım tur |
| Zoom kontrolü + Bakış görünümü | yarım tur |
| Kayıt görünümü (filtreli, sanal liste) | 1 tur |
| Oda birleştirme (6→5) + sözleşme tablosu (§3) | yarım tur |
| **Toplam** | **~2,5 tur** |

Risk: **orta.** Yoğunluk tasarımı cihazda görülmeden doğrulanamaz — `.49`/`.50`
gibi bir cihaz turu ister.

---

## 3 · Odaların sözleşmesi

Bugün oda = sekme etiketi. Bir odanın **amacı, yetkisi, girdisi, çıktısı**
yazılı olmadığı için iki hata tekrar etti: boş oda (Comms `.22`'de boş açıldı) ve
aynı soruyu iki odada iki farklı cevapla gösterme.

### 3.1 Sözleşme biçimi

Her oda için **tek satır, makine okunur** tablo (`ui/team/RoomContract.kt`):

| Alan | Anlamı |
|---|---|
| `id` | `floor` / `tasks` / `result` / `compare` / `relations` |
| `question` | Odanın cevapladığı **tek** soru (dize anahtarı, dil bilmez) |
| `feeds` | Bu odayı besleyen `CouncilEvent` tipleri |
| `stores` | Diskte hangi kayıttan okunur (canlı / `run.json`) |
| `claimsNot` | Bu odanın **söylemediği** şey (yanlış beklentiyi önler) |

`claimsNot` neden var: "Karşılaştırma" odası **karar** göstermiyor, yalnız
duruş. Bunu yazmazsak kullanıcı tablodan karar bekler (`.46`'da tam bu oldu,
`verdict` boş kaldı).

### 3.2 Kapı

Yeni kural `kural_oda_sozlesmesi.py`:
1. Her oda tanımlı mı (`RoomContract` içinde)?
2. `feeds` içindeki her olay tipi **kodda var mı** ve **üretiliyor mu**?
3. Her `CouncilEvent` alt tipi **en az bir odaya** bağlı mı? (Bağlı değilse veri
   üretilip atılıyor demektir — `.22`'de tam bu oldu.)

Bu kapı, "boş oda" sınıfını **yapısal** olarak imkânsız kılar.

**Maliyet: yarım tur. Risk: düşük.**

---

## 4 · Donanım farkındalığı — ne ölçülebilir, ne ölçülemez

### 4.1 Dürüstlük sınırı (bunu başa yazıyorum)

> **Android'de NPU/TPU ölçen bir genel API yok.**

- `NNAPI` **resmen kullanımdan kaldırıldı.** Android geliştirici belgeleri
  (doğrulandı, 2026-11-26) aynen şöyle diyor: *"NNAPI is deprecated. While you
  can continue to use NNAPI, we expect the majority of devices in the future to
  use the CPU backend…"* — yani NPU'ya giden resmî yol **CPU'ya dönüyor**.
  Sorgulanabilir bir TOPS değeri hiç vermedi.
- Hiçbir uygulama "cihazımda kaç TOPS NPU var" sorusunu **ölçemez**. Yalnız
  SoC modelinden **çıkarım** yapabilir.
- GPU'da da benzer: gerçek FLOPS yok; ama **renderer adı** okunabiliyor.
- Termal: `PowerManager.getCurrentThermalStatus()` **var** ve güvenilir.
- CPU: çekirdek sayısı + frekans kümeleri `/sys` üzerinden okunabilir.
- RAM: `ActivityManager.MemoryInfo` + `ResourceWatermark` (VmRSS/VmSize).

**Kural:** ölçülen ile çıkarılan **ekranda ayrı işaretlenir**. "Qualcomm
Snapdragon 8 Gen 2 → tahmini NPU ~45 TOPS (üretici beyanı)" doğru;
"NPU: 45 TOPS" yanlış ve bu depoda kınanan türden bir yalan.

### 4.2 Yeni modül: `device/DeviceProfile.kt` (saf çekirdek + ölçen sarmalayıcı)

Ayrım `.46`'daki `ProjectRules` dersiyle aynı: **saf kısım karar verir, ölçen
kısım veri toplar.**

| Bileşen | Kaynak | Ölçülür mü |
|---|---|---|
| RAM toplam/boş/düşük | `ActivityManager.MemoryInfo` | ✅ ölçülür |
| Süreç RSS/VmSize/thread/map | `ResourceWatermark` (**var**) | ✅ ölçülür |
| CPU çekirdek + küme frekansları | `/sys/devices/system/cpu/*/cpufreq/` | ✅ okunur |
| ABI / SoC modeli / üretici | `Build.*` | ✅ okunur |
| GPU adı | `GLES20.glGetString(GL_RENDERER)` | ✅ okunur |
| Termal durum | `PowerManager.getCurrentThermalStatus` | ✅ ölçülür |
| Pil + şarj durumu | `BatteryManager` | ✅ ölçülür |
| **NPU/TPU varlığı + tahmini güç** | SoC modeli → **tablo** | ⚠️ **çıkarım** |
| **GPU gerçek FLOPS** | — | ❌ **ölçülemez** |

### 4.3 Ne yapar (adaptasyon, sadece rapor değil)

Mevcut örnek: `CouncilPolicy.forDevice` RAM'e göre dalga/ tur seçiyor.
Genişletme:

1. **Konsey boyutu** — RAM + termal + pil. Termal `SEVERE`+ ise dalga düşür
   (cihaz ısındığında 5 paralel model = 5× bellek + 5× ısı).
2. **Koşu öncesi bütçe uyarısı** — "Bu cihazda 5 üye ~250 MB; şu an 400 MB boş,
   dalga 3'e düşüreceğim" (sessiz düşürme yok, söylenir).
3. **Araç seçimi** — `read_image`/`browser_use` ağır; bellek `LOW` iken başsız
   tarayıcı yerine `fetch` tercih et.
4. **Açık kaynak model önerisi** (§6) — NPU/soc tablosu hangi yerel modelin
   makul olduğunu söyler.

### 4.4 Bellek sızıntısı görünürlüğü

`ResourceWatermark` bugün **yalnız loga** yazıyor. Öneri:

- **Ayarlar › Tanılama › Kaynaklar** ekranı: thread · map · VmRSS · VmSize ·
  boş RAM · termal · pil. 5 sn'de bir tazelenen basit tablo.
- **Eğilim (leak watch):** son 10 dakikada VmRSS **monoton artıyorsa** uyarı.
  Tek anlık değer sızıntı göstermez; **eğim** gösterir.
- Sınır: ölçüm 30 sn'de bir, tampon 120 örnek (~1 saat) — sabit bellek,
  sınırsız büyüme yok.

**Maliyet: 1 tur. Risk: düşük.**

---

## 5 · Bellek güvenliği: bu işin kendi riski

Bu özelliğin **kendisi** yeni bellek yükü getiriyor. Kurallar:

| Kural | Neden |
|---|---|
| Olay kuyruğu **500** ile sınırlı | Sınırsız transcript = koşu uzadıkça şişen bellek |
| Kaynak örnekleri **120** ile sınırlı | Halka tampon; büyüme yok |
| Yakalanan bitmap **tek ve yeniden kullanılan** (`Bitmap.recycle`) | `.50`'de canlı içerik gelince her kare yeni bitmap olmamalı |
| Office görünümü **`LazyColumn`** | 150+ satırı bir seferde çizmek = jank |
| Ölçüm okumaları `runCatching` | Bozuk bir `/proc` okuması tanılamayı **düşürmemeli** (ResourceWatermark'ın kendi kuralı) |

---

## 6 · Açık kaynak taraması

> Mobil uyumlu açık kaynaklar ayrı bir araştırma turunda toplanıyor
> (paralel alt ajan). Sonuç geldiğinde bu bölüme **ölçülmüş** hâliyle eklenecek:
> proje · Android uygunluğu · lisans · son sürüm · entegrasyon riski.
> Bu bölüm boşken plan **tamamlanmış sayılmaz** — kullanıcı bunu istedi.

---

## 7 · Sıra önerisi

`.48` (durdur + kapı hızı) zaten onaylandı; bu plan **`.49`'dan** itibaren geçerli.

| Tur | İş | Maliyet | Neden bu sırada |
|---|---|---|---|
| **.49** | Sohbetten konsey (bağ + simge + alt sayfa) | 2 tur → 1. tur | En çok istenen, en düşük risk; `durdur`a yaslanıyor |
| **.50** | Sohbetten konsey 2. tur (koşu sonucu + durum rozetleri) | 1 tur | .49'un yarısı |
| **.51** | Zaman hattı altyapısı (`atMs` + sınırlı kuyruk) + oda sözleşmesi + 6→5 | 1 tur | Ofisin **ön koşulu**; sözleşme kapısı boş oda riskini kapatır |
| **.52** | Sanal ofis: zoom + Bakış + Kayıt | 1 tur | Veri artık var |
| **.53** | Donanım profili + tanılama ekranı | 1 tur | Bağımsız, paralel yapılabilir |
| **.54** | Açık kaynak entegrasyonu (araştırma sonucuna göre 1-2 kalem) | 1-2 tur | En pahalı, en sona |

**Not:** canlı içerik (`.49`/`.50` eski plan) bu planda **geriye** düştü.
Gerekçe: kullanıcı üç kez konsey/ofis istedi, canlı içerik bir kez. Ayrıca
canlı içerik cihazda doğrulanmadan kapanamıyor; ofis ise mantık ağırlıklı ve
testle ilerleyebiliyor.

---

## 8 · Onaya ihtiyacı olan üç karar

1. **Konsey bağı:** sohbet düzeyi + proje varsayılanı (önerim) — yoksa yalnız
   proje düzeyi mi?
2. **Ofis biçimi:** yeni sekme değil **zoom** (önerim) — yoksa ayrı bir 7. oda mı?
3. **Oda sayısı:** Comms + Needs → **"İlişkiler"** birleşmesi (önerim) — yoksa
   altı oda olduğu gibi kalsın mı?
