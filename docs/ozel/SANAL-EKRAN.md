<!-- ELLE YAZILDI: tasarım kararı. Ölçüm bloğu OTO ile tazelenir. -->

# SANAL EKRAN — ne mümkün, ne değil

Soru (kullanıcı, 2026-11-22): *"Uygulamamıza sanal ekran özelliği
kazandırabilir miyiz? Uygulamanın ikinci, arka planda ona özgü bir ekranı
olmalı; yapay zekâ bu ekranda gezip işlem yapabilmeli — tarayıcısından
terminaline, not defterlerine kadar her şeyi olan. Android cihazın yetkileri
izinleri hep aynı olacak."*

**Kısa cevap:** Evet, ama üç ayrı şeyi tek kelimeye sıkıştırdığımız için
"hangisi" sorusu cevaplanmadan yapılamaz. Üçü **çok farklı** maliyette:

| Seçenek | Ne demek | Bugün | Maliyet |
|---|---|---|---|
| **A. Uygulama içi sanal ekran** | Ajanın kendi çalışma alanı: tarayıcı + terminal + notlar, ekranda görünmeyen bir yüzeyde | ✅ **var** (`HeadlessRenderHost`) | düşük |
| **B. Gerçek cihazı sürme** | Ajan telefonun gerçek ekranında gezer (başka uygulamalar dahil) | ✅ **var** (erişilebilirlik + Shizuku) | mevcut |
| **C. İkinci Android ekranı** | Başka uygulamaların **içinde** çalıştığı ikinci bir ekran (çoklu ekran) | ❌ **imkânsız** (platform) | — |

---

## 1. A — uygulama içi sanal ekran: VAR

Zaten yazılmış. `browser/HeadlessRenderHost.kt` şunu yapıyor:

```
VirtualDisplay (PRIVATE)  →  Presentation penceresi  →  WebView
        ↑                                                   ↓
   ImageReader  ←──────── GPU compositor (gerçek donanım) ──┘
```

Yani ajanın tarayıcısı **pencere olmadan** çalışıyor, ama GPU ile çiziliyor —
`WebGL`, `<video>`, 2D canvas dahil. Ekran görüntüsü gerçekten "kullanıcı ne
görürdü"yü veriyor. Ölçülmüş sorun şuydu: yazılım çizimi (`WebView.draw`)
Chromium'da GPU yüzeylerine erişemiyor, WebGL kutusu **beyaz** çıkıyordu.

Tasarım kararları (hepsi maliyet kontrolü):
- Hiçbir şey **ilk yakalamaya kadar** oluşmuyor; son yakalamadan 15 sn sonra
  hepsi bırakılıyor (`IDLE_RELEASE_MS`). Boşta maliyet sıfır.
- Pencere `NOT_FOCUSABLE` + `NOT_TOUCHABLE`: sayfa içindeki bir input klavyeyi
  **gerçek ekranda** açmıyor.
- Yakalamalar sırayla; kenar başına 4096 px üstü yazılım yoluna düşüyor.
- 3 başarısız denemeden sonra yol süreç boyunca kapalı: "her ekran
  görüntüsünde aynı hatayı ödeme" yerine bir kez öde.

**Terminal ve notlar da bu yüzeye girer mi?** Girer, aynı yöntemle: PRoot
kabuğu arka planda zaten başsız çalışıyor (`PersistentShell`,
`FreshProcessShell`); "sanal ekranda terminal" = aynı kabuğun çıktısını ekran
görüntüsü gibi göstermek. Zaten `TerminalSession` bunu yapıyor.

**A'nın sınırı:** bu ekran **yalnız bizim uygulamamızın** pencerelerini taşır.
Başka bir uygulamayı bu sanal ekrana **koyamayız** — Android'de `VirtualDisplay`
bir `Display`tir, ama uygulamaları ona başlatmak `ActivityOptions.setLaunchDisplayId`
ve üçüncü taraf uygulamaların `resizeableActivity` desteğini gerektirir; sıradan
uygulamalar `displayId` verildiğinde varsayılan ekranda açılır.

---

## 2. B — gerçek cihazı sürme: VAR

`MinisAccessibilityService` + `android-a11y-cli` bugün yapabildikleri:

| Fiil | Metot |
|---|---|
| Oku | `rootNodes()` · `windowInfos()` · `snapshotEvents()` |
| Dokun / kaydır | `dispatchSimpleGesture(path, …)` |
| Sıkıştır | `dispatchPinch(cx, cy, scale)` |
| Ekran görüntüsü | `captureScreenshot(displayId)` |
| Metin yaz | `setNodeText(node, text)` |
| Ön plan | `foregroundPackage()` |

Yani "tarayıcıdan terminale, not defterine kadar her şey" cümlesi **bugün**
şu anlama geliyor: ajan senin gerçek ekranında geziyor — senin tarayıcında,
senin notlarında. Bu, A'dan daha güçlü ama **daha riskli**: kullanıcının
gördüğü ekranı değiştiriyor.

`PRootKernel` zaten `--fake-netlink` ile ağ arayüzlerini boş bildiriyor; yani
kabuk tarafındaki araçlar "gerçek cihaz" varsayımıyla çökmesin diye bir yalan
söyleniyor (yorumda "deliberate lie" diye işaretli).

---

## 3. C — ikinci Android ekranı: İMKÂNSIZ

"Başka uygulamaların içinde çalıştığı ikinci ekran" üç ayrı yerden duvara
çarpar:

1. **`createVirtualDisplay` yalnız kendi pencerelerinizi gösterir.** Üçüncü
   taraf bir uygulamayı o ekrana **taşıyamazsınız** (bkz. §1).
2. **Sistem ayrıcalığı gerekir.** `DisplayManager` ile ikinci ekranı
   *sistem* olarak sahiplenmek `INTERNAL_SYSTEM_WINDOW` / imza izni ister;
   normal uygulama alamaz. Shizuku ile bile "ekran sahibi olmak" yok —
   Shizuku `shell` uid'i verir, `system_server` değil.
3. **Tek ekranlı cihazda ikinci ekran fiziksel olarak yok.** HDMI/DeX gibi
   gerçek bir ikinci ekran varsa `DisplayManager.getDisplays()` onu gösterir
   ve oraya `Presentation` açılabilir — ama o ekran **kullanıcının** ekranıdır,
   uygulama gölgesi değil.

Yani "uygulamanın ikinci arka plan ekranı, içinde her uygulama" bir Android
uygulamasının yetkisi **dışında**. Root'lu cihazda bile "başka uygulamayı
ikinci ekranda çalıştırma" için framework yaması gerekir.

---

## 4. O hâlde doğru tasarım ne

**A + B'yi iki ayrı mod olarak sunmak**, tek bir "sanal ekran" vaadi altında
birleştirmemek:

```
Ajan
├── Çalışma alanı (A)          ← sanal, güvenli, varsayılan
│   ├── tarayıcı   (HeadlessRenderHost)
│   ├── terminal   (PRoot kabuğu)
│   └── notlar     (uygulama içi)
└── Cihaz ekranı (B)           ← gerçek, izin ister, her adımda görünür
    └── erişilebilirlik/Shizuku ile gezinme
```

Farkı **kullanıcıya tek cümleyle** söylemek zorunlu: A'da ajan kendi odasında
çalışıyor, B'de senin masanda. İkisini "sanal ekran" diye aynı düğmeye koymak,
`.14`'te `STATION` için kınanan hatanın aynısı olurdu: var görünen ama
tanımsız bir vaat.

### Eksik olan üç parça (A'yı "ekran" yapan)

| # | Eksik | Neden gerekli |
|---|---|---|
| 1 | A'nın **tek yüzeyde** birleşmesi | Bugün tarayıcı ve terminal ayrı ekranlarda; ajan ikisini yan yana göremiyor |
| 2 | A'nın **durumunu kaydetme** (sekme, geçmiş, kaydırma) | Kapanınca sıfırdan başlamak "ekran" değil, "tek kare" olur |
| 3 | A'da **eylem izni** ayrımı | Ajanın kendi tarayıcısında tıklaması kullanıcının tarayıcısında tıklamasından farklı risk taşır; kural tablosunda ikisi aynı görünüyor |

Bunlar `37099` kapsamında **başlatılmadı**. Sıraya konacaksa doğru sıra 1 → 2
→ 3: önce yüzey, sonra durum, sonra yetki.

---

## 5. Karar

| Soru | Cevap |
|---|---|
| Uygulama içi sanal ekran olur mu? | **Olur, kısmen var** (A). Eksik: tek yüzey + durum |
| Ajan orada gezebilir mi? | **Evet** — tarayıcıda tıklama/kaydırma, terminalde komut, notlarda yazma |
| Cihaz izinleri aynı mı olacak? | A'da **hayır** (sanal yüzey kendi izinleriyle), B'de **evet** (gerçek ekran) |
| Başka uygulamalar o ekranda çalışır mı? | **Hayır** — platform izin vermiyor (C) |
| Gerçek ekranda gezme? | **Zaten var** (B), erişilebilirlik açıksa |

Sıradaki iş: A'yı **tek yüzeye** çıkarmak (`SANAL-EKRAN-P1`), sonra durum
kaydı (`P2`), sonra A/B eylem ayrımı (`P3`). C hiç yapılmayacaklar listesinde.
