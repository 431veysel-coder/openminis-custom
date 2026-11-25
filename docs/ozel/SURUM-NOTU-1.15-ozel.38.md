# SÜRÜM NOTU — 1.15-ozel.38 / 37119

> **Yayındaki sürüm:** 1.15-ozel.34 / 37115 (`.35`–`.38` derlenmedi).
> **Kapılar:** 11/11 · 0 uyarı · 51.5 s. **Derleme yapılmadı.**

Bu tur planın **en riskli** turuydu ve sınırını baştan söylüyorum: tek tuval
**var**, canlı içerik **yok**. Aşağıda hangisinin kapandığı, hangisinin açık
kaldığı ayrı ayrı yazılı.

## 1 · Tek tuval: yerleşim (saf, testli)

`screen/VirtualCanvas.kt` — Android importu yok, JVM'de koşuyor.

| Karar | Neden |
|---|---|
| Tüm hesap `Int` dp | `Float` birikimi "kapat-aç yapınca 1 px kaydı" hatası üretir |
| Tek mod düşürme noktası | 320 dp'de GRID anlamsız → SPLIT; 1 bölme → SINGLE. Her çağrı yerinde aynı `if`i yazmak iki farklı cevap üretir |
| `hitTest` sınırda dışarıda | Bitişik iki bölme arasındaki 1 px çizgi yanlış bölmeye düşmemeli |
| Son sütun/satır kalan pikseli alır | Yuvarlama boşluğu iki bölme **arasında** çizgi olarak görünmesin |

`VirtualCanvasTest` — **13** test. En değerlisi:
`hicbir_mod_320dp_de_tasmaz_ve_cakismaz` — 1..3 bölme × 3 mod × 2 yükseklik =
18 durum, hepsinde taşma ve çakışma yok. Bu testi yazmadan tuvali çizseydim,
ilk dar telefonda sağdaki panel 0 px genişlikte çizilir ve "açtım ama boş"
olurdu.

## 2 · Kare üreticisi: kanıt artık gerçek bir PNG

`screen/CanvasFrameRenderer.kt` + `MinisApp` kancası:
`ScreenTool.frameSource = { pane -> canvasFrameRenderer.frame(pane) }`.

`.37`'de `capture` eylemi "kare kaynağı bağlı değil" deyip `success=false`
dönüyordu. Artık üretiyor — ama **durum karesi**:

- her bölme kendi dikdörtgeninde (aynı `VirtualCanvas.layout` ile),
- içinde: bölme adı, odak işareti, adres/çalışma dizini, kaydırma, taslak
  uzunluğu, son yakalama zamanı,
- karenin **alt şeridinde** şu yazı: `state frame — live page/terminal content
  is not rendered yet`.

O son satır süs değil. Kare üretildiğinde model "ekranı gördüm" diye düşünür;
durum karesi bunun için yeterli değil. Karenin içine yazmak, aracın cevabından
bağımsız olarak da yanlış varsayımı engelliyor.

## 3 · Ekran: bölüm + kanıt düğmesi

- `ui/settings/VirtualCanvasSection.kt` — tuval Compose'da; `offset {
  IntOffset(px) }` ile çiziliyor. dp → px **tek** yerde (`LocalDensity`);
  `toPx(x, density).dp` yolu yoğunluğa göre yuvarlama hatası üretip iki
  bitişik bölme arasında çizgi bırakıyordu.
- Ayar ekranında iki yeni bölüm: **Tuval** (alt yazısı canlı içerik olmadığını
  söylüyor) ve **Şimdi yakala** düğmesi — ajanın `capture` eylemiyle **aynı**
  yolu kullanır (`frameSource`), yani ekranda gördüğün kare ile ajanın aldığı
  kare ayrışamaz.
- Düğme sonucu görünür: "N bölme yakalandı" / "Kare üretilemedi". Sessiz düğme
  bu depoda ölçülmüş bir hata sınıfı.

## 4 · Dürüst kapanış tablosu

| Madde | Durum |
|---|---|
| §5.1 **tuval yerleşimi** (bölmeler tek yüzeyde, taşma yok, isabet testi) | ✅ **kapandı** — 13 test, 18 durumda taşma yok |
| §5.1 **canlı içerik** (WebView karesi + PTY çıktısı bölme içinde) | ⛔ **AÇIK** — yapılmadı |
| `needsCapture` / `markCaptured` hattı | ✅ **kapandı** — kare üretilince damga düşüyor |
| `capture` kanıtı | ✅ üretiyor, ama **durum karesi** olarak etiketli |

**Canlı içerik neden yapılmadı (dürüst gerekçe):** bölme içine gerçek bir
`WebView` ve gerçek bir PTY emülatörü yerleştirmek, iki mevcut ekranı
(`browser` sayfası, `terminal`) Compose ağacına taşımak demek. Bu cihazda
derleme yok, emülatör yok: sonucu **görmeden** yapılacak bir refactor, `.38`'in
"ekran görüntüsü kanıtı şart" kuralını kendi eliyle çiğnemek olurdu. Yerleşim +
kare + kanıt üçlüsü, o iş için zemin; sırası `.41`'de (kapalı anahtar kararları
sonrası) yeniden değerlendirilecek.

## 5 · Yeni bir sessiz hata sınıfı yakalandı (kayıt)

Bu turda `denetim` kapısı **iki kez** aynı satırı kırmızı yaptı:
`virtual_screen_canvas_footer` içinde kaçırılmamış `'`. İlk düzeltmem yalnız
ikinci kesme işaretini kaçırdı (`agent's`) — birincisi (`pane's`) kaldı, kapı
yine kırmızı. Ders: kapı satırı gösteriyor ama **hangi** kesme işareti olduğunu
söylemiyor; düzeltmeyi elle hecelemek yerine satırın tamamını taramak gerek.
Betikle yaptım (`re.sub` + tüm `'` → `\'`).

## Sırada

`.39` Terminal/Termux apt sarmalayıcısı · `.40` DNS kalıcılığı · `.41` 8 kapalı
anahtarın kararı (önerilerim: `STATION` ve `REMOTE_CONSOLE` silinir,
`REPLAY`/`EVIDENCE_GATE`/`AUTO_BACKUP` açılır, `TOOL_ROUTER`/`DEV_SERVER`/
`VAULT_AGENT_READ` kapalı kalır) · `.42`–`.45`.
