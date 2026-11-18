# ÖZELLİK PAKETİ — 37080–37090 (11 dilim)

Bu dosya, planlanan işlerin (konsey 37037–37069, proje hafızası 37070–37079,
istasyon, notlar/kasa, çeviri paketleri, sağlayıcı testi, düzenleme modu)
**dışında** kalan dilimlerin tek haritası. Hiçbiri o çalışmaların dosyalarına
dokunmuyor; hepsi ayrı paketlerde ve ayrı commit'lerde.

Durum kodları: **kod hazır** · **test hazır** · **derlendi** · **entegre** ·
**yayında**. Bu tablodaki her dilim şu an yalnız ilk ikisinde.

---

## Tablo

| # | Dilim | Paket | Kaynak | Test | Durum |
|---|---|---|---|---|---|
| 37080 | Onay kapısı | `policy/` | 7 dosya | 37 | kod+test |
| 37081 | Bütçe tavanı | `budget/` | 1 dosya | 7 | kod+test |
| 37082 | Otomatik yedek | `backup/AutoBackupPolicy.kt` | 1 dosya | 10 | kod+test |
| 37083 | Plan önizleme | `plan/` | 1 dosya | 7 | kod+test |
| 37084 | Tur özeti diff'i | `ui/chat/diff/` | 1 dosya | 7 | kod+test |
| 37085 | Araç kapısı | `agent/router/` | 1 dosya | 9 | kod+test |
| 37086 | Ders defteri | `agent/lesson/` | 1 dosya | 8 | kod+test |
| 37087 | Kanıtlı "bitti" | `agent/evidence/` | 1 dosya | 7 | kod+test |
| 37088 | Yazma niyeti | `data/journal/WriteIntent.kt` | 1 dosya | 7 | kod+test |
| 37089 | Yeniden oynatma | `replay/` | 1 dosya | 6 | kod+test |
| 37090 | Uzaktan konsol | `remote/` | 1 dosya | — | **yalnız model** |

**Toplam: 2.416 satır kaynak · 105 test metodu · 11 commit.**

---

## Neden ayrı paketler

1. **Çakışma yok.** `ChatViewModel.kt`, `strings.xml`, `AppDatabase.kt`,
   `AgentTools.kt`, `MinisApp.kt`, `FolderEntity.kt` — bu paketin hiçbir dosyası
   bunlara yazmıyor. Entegrasyon sırasında dokunulacak, ama o zaman ve sırayla.
2. **Tek tek geri alınabilir.** Bir dilim beğenilmezse kendi commit'i geri
   alınır; diğer on dilim etkilenmez.
3. **Android'siz.** Ölçüldü: 30 kaynak dosyanın tamamında **iki** `import`
   satırı var (`java.time.Instant`, `java.time.ZoneId`). `android.*` yok.
   Sonuç: her dilim tek başına `kotlinc` ile derlenebilir, testleri JVM'de koşar.

---

## Dilimler

### 37080 · Onay kapısı — `policy/`

Ajan tehlikeli komut çalıştıracağında kullanıcıya sorar, ama **her komutta
değil**. Eşleşme → sınıflandırma → karar → kart. Karta beş satır:
**NE · NEDEN RİSKLİ · GERİ DÖNÜŞ · AMAÇ · YETKİ**.

- `RiskModel.kt` — `RiskLevel` (4), `Reach` (5: sandbox→session→device→network→account), `Recovery` (3: undoable/recoverable/permanent), `RiskVerdict`, `Purpose`
- `RiskRules.kt` — 21 kural + 4 mutlak ret kalıbı. Tablo, mantık değil.
- `RiskClassifier.kt` — komut → verdict; yol çözümleme (`..` sadeleştirme), parça bölme (`&&`, `;`, `|`), önek üretimi
- `ApprovalScope.kt` — `Session` / `Project` / `Global` kapsamları, `Grant`, `GrantBook`
- `ApprovalPolicy.kt` — ne zaman sorulur, hangi kapsamlar sunulur, ömür + kullanım tavanı
- `ApprovalRequest.kt` — kartın verisi ve metni
- `ApprovalQueue.kt` — bekleyen onaylar + toplu onay sınırı
- `GateDecision.kt` — `Silent` / `Ask` / `Deny` ve ajana dönen metin

**Çivilenen kararlar:** kalıcı iş oturumdan uzun yaşayan onay alamaz · kapsam
yukarı terfi etmez · ret izni yener · hedefi çözülemeyen silme en geniş kapsamda ·
aynı soru birleşir, farklı sorular birleşmez · amaç uydurulmaz.

### 37081 · Bütçe tavanı — `budget/`

`quota/` satıcının kotasını izler; bu paket kullanıcının **kendi** tavanını.
Sohbet/gün/ay üç kademe. Aşınca **yeni tur başlamaz** (ortada kesmek tutarsız
dosya bırakır). Tahmini token ayrı alanda ve tavana sayılır; fiyatı bilinmeyen
çağrı "bilinmiyor" sayılır, 0 sayılmaz.

### 37082 · Otomatik yedek — `backup/AutoBackupPolicy.kt`

Yedek altyapısı zaten var (`BackupRunController`, `BackupHistory`,
`BackupForegroundService`); eksik olan **yalnızca zamanlama**. Sabit saat değil:
aralık + pencere → kaçırılan gece ertesi sabah telafi edilir. Değişiklik yoksa
çalışmaz (pil). Varsayılan kapalı. Budama tarihe göre, ada göre değil.

### 37083 · Plan önizleme — `plan/`

10 dakikalık işe girmeden tek kart. Eşik ölçülebilir: dakika, token, geri
dönüşsüz işlem sayısı. Altındaki iş doğrudan başlar — "5 saniyelik iş için de
kart mı" dedirtmek özelliği ilk günde kapattırır. "Onay" değil "önizleme":
kullanıcının itirazı genelde "yapma" değil, "yanlış şeyi yapıyorsun".

### 37084 · Tur özeti — `ui/chat/diff/`

`ActionJournal` verisi ajana açık, kullanıcıya değil. Tur sonunda
"3 dosya, +120/−8 satır" + tek tuşla geri alma. Geri alma **en yeniden eskiye**;
ters sırada aradaki değişiklik nedensizce kaybolur. Ters kaydı olmayan adım
sessizce atlanmaz.

### 37085 · Araç kapısı — `agent/router/`

40+ araç şeması her turda gidiyor: token maliyeti + seçim gürültüsü. Niyet
sözlüğü (LLM'siz) → 6–10 aday → yalnız onların şeması; gerisi `tool_search` ile.
`user_ask`/`verify`/`journal`/`file_read` her zaman açık. **Ölçüm kazanç
göstermezse geri alınacak.**

### 37086 · Ders defteri — `agent/lesson/`

Araç hatası / kullanıcı düzeltmesi / döngü → **öneri**. Kendiliğinden hafızaya
yazmaz; onaylanırsa normal `memory_write` gibi girer. Tek hata ders üretmez
(3 tur içinde tekrar şart). Genel tavsiye yasak: her öneri somut bir sonraki
adım söyler. Kişisel hafızaya yazar — proje hafızası çalışmasına bağlanmaz.

### 37087 · Kanıtlı "bitti" — `agent/evidence/`

"Bitti" beyanı son 3 turda üretilmiş **kanıt** ister: test çıktısı, derleme,
var olan dosya, ekran görüntüsü, `verify` PASS. Kanıt aracın çıktısı, modelin
cümlesi değil. Ret cezalandırmaz, sonraki adımı tarif eder.

### 37088 · Yazma niyeti — `data/journal/WriteIntent.kt`

`FileLock` **zaten var** (yol başına kilit + parmak izi + çakışma mesajı).
Eksik olan zaman ölçeği: çok adımlı iş boyunca (oku → düzenle → derle → yaz)
araya giren ikinci ajan. `claim()` kilidi tutmaz, **beyan** eder ve ikinci ajanı
uyarır — kilidi dakikalarca tutmak zaman aşımı üretir. Bellekte yaşar; diske
yazılsa çökme sonrası kalıcı kilit olurdu.

### 37089 · Yeniden oynatma — `replay/`

Geçmiş koşuyu araç çıktıları dondurulmuş hâlde yeniden koşturur; değişen tek
şey model. "Model değişti, iyileşti mi" sorusunun tek dürüst cevabı. Kayıtta
olmayan çağrı **çalıştırılmaz** — sessizce canlı çağrıya düşmek ölçümü kirletir.
Iskalama bir sonuç, hata değil (`missRate` metrik).

### 37090 · Uzaktan konsol — `remote/` (UYGULAMA YOK)

Yalnız jeton/yetki modeli. Altyapı hazır (`NetworkHub`, Tailscale,
`DebugServer`) ama özellik uygulamanın tek güvenlik varsayımını kırıyor:
**kullanıcı cihazın başında.** Model üç şeyi imkânsız kılıyor: 30 dakikadan
uzun jeton, varsayılan yazma, uzaktan cihaz geneline dokunma.

**Ürün kararı bekliyor.** Karar "hayır" ise bu dosya silinmeli — yarım kalmış
güvenlik kodu, olmayan özellikten kötü.

---

## Entegrasyon noktaları (henüz yapılmadı)

| Dilim | Dokunacağı yer |
|---|---|
| 37080 | `ChatViewModel.executeTool` başı; `shell_execute` / `file_write` / `file_edit` |
| 37081 | tur başlangıcı + sağlayıcı yanıtı; `usage` tablosuna `estimated` kolonu |
| 37082 | `MinisApp` açılış + `ScheduledTaskManager` kontrolü |
| 37083 | tur başlangıcı + `AgentTools`'a `estimate` alanı |
| 37084 | `ActionJournal.entriesSince()`, sohbet sonu özet satırı |
| 37085 | `AgentTools.makeAgentTools` (kemer filtresinden **önce**), yeni `tool_search` aracı |
| 37086 | araç hata yolu, kullanıcı mesajı analizi, tur sonu tek kart |
| 37087 | tur sonu beyan kontrolü, araç çıktısı → `Proof` eşlemesi |
| 37088 | `file_read`/`file_edit` arası, tur sonu `releaseAll` |
| 37089 | Ayarlar › Geliştirici, replay modunda araçlar kayıttan döner |

---

## Bilinen borç

1. **Derlenmedi.** Bu cihazda JVM çalışmıyor (`Failed to mark memory page as
   executable` — PRoot sınırı), `kotlinc`/`gradle` yok. İlk gerçek derleme CI'da
   olacak; hata çıkması normaldir (özellikle `enum` karşılaştırmaları, `data
   class` kopyalama satırları).
2. **Ekran yok.** Hiçbir dilimde Compose dosyası yok. Kartlar/listeler/ayar
   satırları entegrasyon adımının işi.
3. **Kalıcılık yok.** `GrantBook`, `WriteIntent`, `ApprovalQueue` bellek içi.
   JSON yazma IO bağdaştırıcısının işi; biçim paket belgelerinde yazılı.
4. **37080'in proje kapsamı çalışmaz.** `ChatViewModel.folderId` bugün yok —
   proje hafızası çalışmasının işi. O gelene kadar `Session` ve `Global` çalışır,
   `Project` çalışmaz.

---

## Yeni dilim eklemek

1. `[OZEL 370xx]` etiketli yeni paket aç; mevcut altı ortak dosyaya dokunma.
2. Kaynak dosyanın başında "neden böyle" paragrafı olsun — karar yazmayan kod
   altı ay sonra silinir.
3. Test adı **kararı** söylesin (`kalici_islem_oturumdan_uzun_yasayan_izin_alamaz`),
   kod parçasını değil.
4. Tek commit. Tek dilim. Geri alma tek komut olsun.
5. Bu dosyaya satır ekle.
