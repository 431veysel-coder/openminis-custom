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

## 6 · Açık kaynak taraması (tamamlandı)

> **Yöntem:** paralel bir araştırma turu 5 alanı taradı; **sürüm/lisans iddialarını
> GitHub API'sinden kendim doğruladım** (aşağıdaki tablo doğrulanmış değerler).
> Doğrulayamadığım her şey "doğrulanmadı" diye işaretli.

> ## ⛔ KULLANICI KARARI (2026-11-26): yerel model YOK
>
> **"Yerel model çalıştırma olmayacak, tamamen anahtarlar üzerinden yapay
> zekaları kullanıyoruz. Uygulamanın amacı bu zaten."**
>
> Bu kararla **`.54` ve `.55` iptal edildi.** Aşağıdaki §6.1 tablosu ve sandbox-CLI
> gerekçesi **tarihsel kayıt olarak** duruyor — bir gün yerel çıkarım gerekirse
> hangi yolun seçilmiş olduğunu ve nedenini hatırlatıyor. Bugün için bağlayıcı
> değil.
>
> **Konseyin tek üye kaynağı: sağlayıcı anahtarları.** Bu bir kısıt değil,
> ürünün kendi tanımı: kullanıcı kendi anahtarını getirir, hangi modeli
> kullanacağını seçer.

### 6.0 Bizim iki kısıtımız — öneriyi bunlar belirliyor

| Kısıt | Ölçüm |
|---|---|
| **APK bütçesi** | `APK_BUDGET_KB` = **120 KB**, `MAX_EMBEDDED_ASSET_KB` = 4096 KB — yani APK'ya yük gömülmüyor, indiriliyor |
| **Yerel çıkarım** | **Sıfır.** Kodda `gguf/llama/onnx/tflite` geçmiyor; tüm modeller bulut sağlayıcısı |
| **Elimizde olan** | PRoot sandbox + `NativeOffloadHandler` altyapısı (tarayıcı CLI, konuşma CLI, cihaz CLI bunu kullanıyor) |

**Bu üçü birlikte tek bir mimari karar zorluyor:**

> Yerel çıkarım için **JNI/native kütüphane almayacağız**; **sandbox içinde CLI**
> olarak çalıştıracağız.

Gerekçe, sayıyla: llama.cpp JNI `.so` dosyaları ABI başına ~20-40 MB,
sherpa-onnx ~20 MB+. Bu, 43.5 MB'lık APK'yı **ikiye katlar** ve 120 KB'lık yük
bütçesiyle çelişir. Oysa sandbox zaten var, CLI çalıştırma yolu zaten çalışıyor,
model ağırlıkları (1-4 GB gguf) **cihaza indirilebilir** — APK'ya gömülmez.

Bedeli: süreç başlatma gecikmesi (~100-300 ms) ve akış yerine blok çıktı. Bir
konsey üyesi için bu **önemsiz** (tur saniyeler-dakikalar sürüyor). Sohbette
anlık yanıt için ise bulut sağlayıcı zaten var.

### 6.1 Doğrulanmış tablo (GitHub API, 2026-11-26)

| Proje | Ne yapar | Android | Lisans | Son sürüm | Bizde nasıl |
|---|---|---|---|---|---|
| [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) | GGUF çıkarım motoru, CPU+GPU | (a) + (c) | MIT | v1.1.0 · 2026-11-26 | **Sandbox CLI** — `llama-cli` derlenir, model indirilir |
| [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | ASR + TTS + VAD + diarization | (a) Kotlin API | Apache-2.0 | v2.0.3 · 2026-11-22 | **Sandbox CLI** (Python/CLI) — JNI almadan |
| [google/perfetto](https://github.com/google/perfetto) | İzleme + `heapprofd` + termal/CPU sayaçları | (a) SDK · (c) CLI | Apache-2.0 | v59.1 · 2026-10-27 | **Sandbox CLI** — §4 tanılamasını besler |
| [square/leakcanary](https://github.com/square/leakcanary) | Sızıntı tespiti | (b) Kotlin | Apache-2.0 | v3.0-alpha-9 · 2026-06-25 | `debugImplementation` — **APK'ya girmez** |
| [modelcontextprotocol/kotlin-sdk](https://github.com/modelcontextprotocol/kotlin-sdk) | MCP istemci/sunucu | (b) KMP | **belirsiz (NOASSERTION)** | 0.16.0 · 2026-10-27 | **Bekle** — pre-1.0, lisans net değil |
| [google-ai-edge/LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) | Google'ın kenar LLM çalışma zamanı | (a) Kotlin | Apache-2.0 | v0.20.0 · 2026-11-23 | **İzle** — NPU hikâyesi için tek taşınabilir yol |

**Doğrulanamayan:** `microsoft/onnxruntime` — API bu ağdan 404 döndü; mevcut
ama sürüm/lisans teyit edilemedi.

### 6.2 Ne alacağız, ne almayacağız

**Alacağız (sırayla):**

1. **llama.cpp — sandbox CLI.** Konseyin **yerel üyesi**: internet yokken de
   çalışan, ücretsiz, gizliliği tam bir heyet üyesi. Maliyet: 0 KB APK.
   İlk somut adım: `.54`'te **yalnız kurulum + sağlık kontrolü** (derleme
   çalışıyor mu, model indi mi) — entegrasyon sonraki tur.
2. **perfetto CLI — tanılama.** §4'teki kaynak ekranının **derin** verisi
   (CPU kümesi kullanımı, termal eğri). Uygulama içi SDK değil, sandbox CLI:
   `heapprofd` zaten debuggable/profileable build ister, üretimde koşmaz.
3. **LeakCanary — yalnız debug.** APK'ya girmez, CI'da çalışır. Sızıntı
   avında en yüksek getirisi olan tek satır.

**Almayacağız:**

| Proje | Neden |
|---|---|
| **MLC-LLM** | Model başına **telefon GPU sınıfına özel** derleme; 1-2 GB blob; TVM araç zinciri. Sahada yönetilemez |
| **ExecuTorch** | Android LLM yolu üç motorun en kırılganı; export zinciri + özel kernel boşlukları |
| **MediaPipe LLM** | LiteRT-LM'e devredilmiş, kullanımdan kalkma yolunda |
| **ONNX Runtime** | Doğrulanamadı + GenAI/LLM yolu Android'de en az olgun |
| **ncnn** | Otoregresif çözümlemede KV-cache/sampler yığını olgun değil |
| **Piper TTS** | **GPL-3.0** — JNI ile bağlamak uygulamayı GPL yapar. Sandbox'ta ayrı süreç sınırda |
| **Coqui TTS** | Ölü (2024), 1-2 GB Torch modeli |
| **Profilo / GAPID** | Arşivlenmiş / masaüstü |
| **Ethos-U Vela** | MCU'da, telefonda yok |
| **NNAPI** | Resmen kullanımdan kaldırıldı (belge alıntısı §4.1'de) |
| **MCP kotlin-sdk** | Şimdilik değil: pre-1.0, lisans NOASSERTION, spec hâlâ oynuyor. Bir yıl sonra tekrar bakılır |
| **langchain4j / adk-java** | Masaüstü-JVM varsayımları, R8/reflection acısı; bizim ajan döngümüz zaten var |

### 6.3 Donanım sorgulama: alacak kütüphane **yok**

Araştırmanın en net bulgusu ve benim §4 ölçümümle örtüşüyor: **Android'de
GPU/NPU/termal sorgulayan olgun açık kaynak kütüphane yok.** Bulunanlar ya
oyuncak depo (2018'de durmuş) ya masaüstü aracı ya da MCU için.

Yani §4'teki `DeviceProfile` **kendimiz yazacağız** — tahmini 200-400 satır ince
sarmalayıcı. Kaynaklar: `ActivityManager.MemoryInfo`, `PowerManager`
termal durumu, `/sys/devices/system/cpu/*/cpufreq/*`, `GLES20.glGetString`.
Bu bir **eksik değil, gerçek**: bu katman hiçbir yerde hazır satılmıyor.

### 6.4 Top 3'ün gerçekçi hâli

| Alt ajanın önerisi | Benim kararım |
|---|---|
| 1. llama.cpp | ✅ **ama JNI değil, sandbox CLI** — APK bütçesi zorluyor |
| 2. sherpa-onnx | ✅ aynı gerekçe; ayrıca cihazda zaten sistem STT/TTS var (§A4 cihaz sınaması) — **önce o ölçülsün**, sonra gerekirse sherpa |
| 3. MCP kotlin-sdk | ❌ **ertelendi** — pre-1.0 + lisans belirsiz |



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
| ~~.54~~ | ~~llama.cpp sandbox CLI~~ | — | **İPTAL** — aşağıdaki karar |
| ~~.55~~ | ~~llama.cpp'yi üye olarak bağla~~ | — | **İPTAL** |

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
