# İSTASYON MİMARİSİ — tek ortam, ayrı alanlar

> 11.1'in belkemiği. "Duvarlar" isteği burada tanımlı: ortam bir tane, alanlar birbirine değmiyor.

## 1. Sözlük (bundan sonra bu adlar kullanılacak)

| Ad | Nedir | Kaç tane |
|---|---|---|
| **Ortam** (environment) | PRoot sandbox, rootfs, `/var/minis` | 1 — tektir, çoğaltılmaz |
| **Alan** (space) | Bir ajanın bütün özel dünyası | panel başına 1 |
| **İstasyon** (station) | Alanları bir arada tutan görev kabuğu | sohbet başına 1 |
| **Panel** | İstasyon içindeki tek alanın ekranı | 2–4 |

Kullanıcının cümlesiyle: *"ortam her ne kadar bir olsa da alanları birbirinden alakasız olmalı"*.
Tek olan şey **makine**; her ajanın gördüğü **dünya** ayrı.

## 2. Alan kimliği

```
spaceId  = "sp_" + <8 hex>            // kalıcı, sohbetle yaşar
```

Bir alan açıldığında şunlar **o alana ait** olur, başka alanla paylaşılmaz:

| Varlık | Alan içindeki yeri | Neden ayrı |
|---|---|---|
| Çalışma dizini | `/var/minis/spaces/<spaceId>/work` | aynı dosyaya eşzamanlı yazma = bozuk çıktı |
| Terminal oturumu | `sessionId = spaceId` | tek kabuk iki ajan = komutlar birbirine karışır |
| Tarayıcı sekmeleri | kendi `BrowserTabPool` örneği | global havuzda sekmeler birbirinin üstüne biner |
| Sohbet geçmişi | kendi child `ChatViewModel` | bağlam sızması = ajan başkasının cevabını görür |
| Notlar | `scope = spaceId` | ajanın defteri kendi görevine ait |
| Kasa | `scope = user` (tek, ortak) | sır kullanıcınındır, alanın değil |
| Model/sağlayıcı | panelin kendi seçimi | ana özellik bu |
| Araç havuzları | `tools` nesnesi alan başına | yarım kalan tarayıcı işlemi diğerini kilitlemesin |
| Günlük dosyası | `/var/minis/spaces/<spaceId>/log.txt` | hata ayıklamada hangi ajan ne yaptı belli olsun |

**Duvarın kuralı:** bir alan diğerinin `spaceId`'sini bilmez. Paneller arası `delegate_task`
**kapalı** — ajanlar birbirine soru soramaz, birbirinin çıktısını okuyamaz. Ortak olan tek
şey görev metni (kullanıcı verir) ve — kullanıcı açarsa — kasanın okunur olması.

## 3. Neden child ChatViewModel

Bugün `ChatViewModel.createChildViewModel(..., PoolKind.CHILD)` alt ajan için tam olarak
bunu yapıyor: bağımsız oturum + bağımsız VM + `ChatViewModelStore` ile canlı tutulan kendi
ajan döngüsü. Yani panel başına bağımsız ajan **yeni mimari değil, var olanın ikinci
kullanımı**. `ChatViewModel`'ın 20 bin satırına dokunulmaz; istasyon yalnızca
*"kaç child, hangi model, hangi dizin"* sorusunu sorar.

Fark: `delegate_task` çocuğu **eşzamanlı ve gizli** çalışır (sonucu ebeveyne döner).
İstasyon çocuğu **görünür ve kalıcı**dır (kendi ekranı, kendi terminali, kendi geçmişi vardır,
tahliye edilmez).

## 4. Sağlayıcı duvarları (11.1'de zaten kurulu olanlar)

Kullanıcının "sağlayıcılar birbiriyle çakışıyor olabilir" endişesi için ölçüm:

| Katman | Ayrı mı | Kanıt |
|---|---|---|
| API anahtarı | ✅ | `EncryptedSharedPreferences`, `apikey_<instanceId>` |
| Model önbelleği | ✅ | anahtar = `baseURL + "\\|" + apiKey` (OpenAI), aynısı Anthropic'te |
| Ağ rotası | ✅ | `NetworkRuntime.providerTag(id)` = `prov:<id>` |
| Kota kaydı | ✅ | `QuotaStore`, örnek başına |
| Düşünme kuralı | ✅ | `ProviderThinkingRuleEntity`, örnek başına |
| User-Agent | ✅ | `customUserAgent`, örnek başına |
| Model listesi | ✅ | `ProviderModelEntryEntity`, örnek başına |
| **Bağlantı durumu** | ❌ **yoktu** | 11.1'de eklendi: `ProviderConnectionTest` |

Kalan tek gerçek çakışma riski: **aynı örnek iki panelde birden** kullanılırsa hız sınırı
ikiye katlanır. Buna izin verilir (kullanıcı isteyebilir) ama istasyon panelinde uyarı
gösterilir — engel değil, bilgi.

## 5. Tarayıcı: tek havuz değil, havuz başına alan

`BrowserTabPoolRegistry` zaten havuzları **kimlikle** (referans) tutuyor ve her sohbetin
kendi havuzu var. İstasyon için eksik olan tek şey **kota**:

- `GLOBAL_TAB_CAP = 8`. 4 panel × 2 sekme = 8 → tek başına cap'i doldurur.
- Karar: istasyon paneli başına sekme hakkı **2**, toplam istasyon hakkı **6**, ve
  istasyon açıkken tek sohbet havuzu 2'ye düşer. Cap 8'den 12'ye çıkarılmaz — WebView
  ağır, düşük RAM'li cihazda OOM.
- Panel kapanınca havuzu `unregister` + `evictAll` — yoksa zombi WebView kalır.

## 6. Terminal: çoğaltma, paylaşma değil

- Terminal zaten `sessionId` anahtarlı → panel başına ayrı oturum **bedava**.
- Panel terminali açıldığında `cwd = /var/minis/spaces/<spaceId>/work`, `HOME` aynı.
- **Panel kapanınca süreç öldürülür.** `TerminalSession` `WeakReference` ile tutuluyor;
  panel VM'i tahliye edilirse oturum da düşer. İstasyon VM'leri tahliye edilmediği için
  açıkça `dispose()` çağrılır.

## 7. Mobil uyum (tek elle, dikey ekran)

Panel düzeni sabit bölme değil, **kaydırmalı şerit**:

- Dikey telefonda: üstte panel sekmeleri (rozet: model adı + durum noktası), altta
  **tek** panel tam genişlik. İki paneli yan yana sığdırmak 360 dp'de okunamaz.
- Yatay/tablet: `>= 720 dp` → iki sütun.
- Panel başına eylem çubuğu: `terminal`, `tarayıcı`, `notlar`, `durdur`, `yeniden çalıştır`.
- Hiçbir panel diğerinin altında saklanmaz; şerit yatay kaydırılır.

## 8. Sonuç birleştirme

- Her panel bitince `PanelResult { spaceId, status, summary, artifacts[], terminalTail }`.
- Üstte **karşılaştırma** ekranı: yan yana özet + süre + token + araç sayısı.
- Kullanıcı bir paneli "ana yanıt" seçebilir; seçim sohbete tek mesaj olarak düşer.
- Otomatik birleştirme yok — iki modelin cevabını bir modelin özetlemesi, kullanıcının
  kaçındığı "ajanlar birbirine konuşsun" davranışıdır.

## 9. Bellek bütçesi (gerçek sayılar)

| Şey | Yaklaşık |
|---|---|
| 1 child ChatViewModel (boş) | 8–15 MB |
| 1 child + 20 mesaj geçmişi | 30–50 MB |
| 1 WebView sekmesi | 40–90 MB |
| 1 terminal oturumu | 20–40 MB (PRoot) |

4 panel + 4 terminal + 6 sekme ≈ **500 MB** — orta seviye telefonda sınırda.
Bu yüzden:
- Panel sayısı varsayılan **2**, üst sınır **4**, ayarlardan seçilir.
- `ActivityManager.isLowRamDevice` ise üst sınır **2**'ye düşer.
- `onTrimMemory(TRIM_MEMORY_RUNNING_LOW)` → panel başına tarayıcı sekmeleri boşaltılır,
  oturumlar kalır.

## 10. Aşamalar

| Aşama | Ne | Risk |
|---|---|---|
| **A** | 2 panel, araç yok, sadece yanıt karşılaştırma | düşük |
| **B** | Panel başına terminal + araçlar + izole dizin | orta |
| **C** | 3–4 panel, panel başına tarayıcı, kaydırmalı şerit | yüksek |

Her aşama ayrı commit ve ayrı CI yeşili ister.
