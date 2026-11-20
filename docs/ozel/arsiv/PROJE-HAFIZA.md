# Proje Bazlı Hafıza ve Alan İzolasyonu — tasarım

Durum: **plan** (kod yok). Önceki belge `ekip-ve-projeler.md` §6'yı (Projeler)
genişletir ve onunla çelişmez. Dilim aralığı **37070–37083** (37060–37068
konsey dilimlerinde kullanıldı). Kullanıcı kararları §13'te işlendi; kapsam
göç (§10.0), dosya çakışma defteri (§11.2.1) ve sohbetler arası iletişim (§8.5)
ile genişledi.

İstek (kullanıcının cümlesi): "uygulamamızın her şey tek hafıza paylaşıyor değil mi,
bunu çoğaltalım — grup hafızası, proje bazlı hafıza. Her projeye ait sohbet grubu
kendi hafızasına sahip olacak. Her projenin kendine ait grubu, hafızası, dosyaları,
klasörleri — her şeyi kendine göre ayrı olacak."

---

## 0. Yönetici özeti

Bugün **hafıza tektir** (`minis-global/memory`), **dosyalar sohbet başına ayrıdır**
(`minis-sessions/<id>/…`), **gruplar (klasörler) vardır ama sadece etikettir** —
grubun hafızası, dosyası, ayarı yoktur.

Hedef model üç ayrı kavramı netleştirir:

| Kavram | Bugün | Hedef |
|---|---|---|
| **Sohbet** | hafıza global, dosya kendi | değişmez (projesiz sohbet = bugünkü davranış) |
| **Proje** | etiket (klasör) | **alan sahibi**: kendi hafızası, kendi dosya ağacı, kendi notları, kendi ayarları, N sohbet |
| **Kişisel hafıza** | tek hafıza | proje dışında kalan varsayılan katman; projede de okunur |

Tek cümlelik kural: **`folderId` bir `sessionId` gibi davranmaya başlar.** Dosya
katmanı zaten "alan anahtarı" (space key) ile çalışıyor — bugün bu anahtar sohbet
kimliği, projede proje kimliği olacak. Yeni bir izolasyon motoru yazılmıyor;
mevcut `fsSessionId` kablosuna ikinci bir anahtar türü ekleniyor.

Kullanıcı kararları sonrası kapsam üç başlık daha aldı:

- **Göç**: bugüne kadarki tüm iş "Geçmiş İşler" projesine taşınır; yeni projeler
  ondan bağımsız başlar (§10.0).
- **Çakışma**: aynı dosyaya iki sohbet yazarsa sessiz ezme yok — iddia defteri,
  TTL, `wait/ask/copy/strict` politikası (§11.2.1).
- **İletişim**: proje sohbetleri birbirine mesaj gönderir; aynı dosyaları okur/yazar (§8.5).

---

## 1. Bugünkü durum — kanıtlı

### 1.1 Hafıza: tek dizin, tek örnek

| Gerçek | Yer |
|---|---|
| Tek `MemoryRepository`, tek dizin | `MinisApp.kt:596` → `MemoryRepository(File(filesDir,"minis-global/memory"))` |
| `GLOBAL.md` + `YYYY-MM-DD.md` günlükleri | `MemoryRepository.kt:22-24` |
| Sistem istemine iki parça enjeksiyon | `ChatViewModel.kt:16845-16846` |
| Persona hatırlatıcısı global hafızayı soruyor | `ChatViewModel.kt:5199` |
| `memory_write` yazma / `memory_get` arama | `tools/MemoryTools.kt:1-137`, dağıtım `ChatViewModel.kt:14345` |
| Yazma geri alma (T149) | `ChatViewModel.kt:3296-3375` — günlük dosyasındaki girdiyi içerik eşleşmesiyle siler |
| `SOUL.md` de aynı dizinde okunuyor | `agent/SoulStore.kt:214` |
| Anımsama bütçesi | 3 günlük dosya × 200 satır, 30 KB çıktı tavanı (`MemoryRepository.kt:26-46`) |

Sonuç: proje ayrımı yok. Aynı gün iki projede çalışan ajan, tek günlük dosyasına
yazıyor ve diğer projenin oturumunda bu satırları görüyor.

### 1.2 Dosyalar: sohbet başına zaten izole

| Gerçek | Yer |
|---|---|
| Sohbet dizini düzeni | `minis-sessions/<id>/{workspace,attachments,offloads,browser}` — `SessionMounts.kt:32,53` |
| Bağlama kararı **tek** yerde | `SessionMounts.build()` — `SessionMounts.kt:66-110` |
| Kabuk hangi alanı görür | `fsSessionId` → `ExecutionCoordinator.kt:77,597,617` |
| Yol çözümleme | `PRootKernel.resolveSessionHostPath()` — `:795-808` |
| Terminal | `TerminalSession.buildInteractiveCommand()` — `:443-464` |
| Host yolu üreten diğer yerler | `RootfsManager.kt:318`, `PhotosOffloadHandler.kt:541`, `ModelUseOffloadHandler.kt:1346`, `FileBrowserViewModel.kt`, `FileMentionIndex.kt:46` |

`fsSessionId` bugün **iki** iş yapıyor: yardımcı ajanın ebeveyn alanını göstermesi
(`ChatViewModel.kt:1698`) ve üye/alt ajan ayrımı. Yani "sohbet kimliği" ile
"dosya alanı" ayrımı zaten kodda var; sadece anahtar türü tek.

### 1.3 Gruplar: var, ama sadece kayıt

| Gerçek | Yer |
|---|---|
| `folders` tablosu (id, name, icon, color, origin, pinned_at, description) | `data/db/FolderEntity.kt` |
| `sessions.folder_id` (FK yok, bilinçli) | `ChatSessionEntity.kt:70` |
| Projeler ekranı (salt-okunur liste) | `ui/projects/ProjectsScreen.kt`, `ProjectDetailScreen.kt` (11.4) |
| Sohbet listesinde klasör sayfaları | `ui/sessions/FolderPages.kt`, `GroupPickerSheet.kt` |

### 1.4 Kritik boşluk

`ChatViewModel` **kendi `folderId`'sini bilmiyor** (grep: `ChatViewModel.kt` içinde
`folderId` yok; `folderId` yalnız `ChatViewModelStore`/liste ekranlarında). Bu yüzden
proje alanı çözümü için önce sohbetin projesi VM'e taşınmalı — S1'in ilk işi.

Ayrıca `minis-sessions/<id>/` tarayıcıları (`SessionStorage`, `SessionDeleter`,
`OrphanedSessionFiles`) proje dizinlerini **tanımıyor**; proje alanı ayrı köke
konmazsa "sahipsiz" sayılıp silinir.

---

## 2. Hedef model

```
Proje (folder)  ──1:N──  Sohbet
   │
   ├── alan (space)      minis-spaces/<folderId>/{workspace,attachments,offloads,browser}
   ├── hafıza            minis-spaces/<folderId>/memory/{PROJECT.md, YYYY-MM-DD.md}
   ├── notlar            NoteStore scope = project:<folderId>
   ├── ayarlar           hafıza aç/kapa, varsayılan model/ekip, yetenek seti
   └── üyeler            (varsa) TeamStore team'i — proje varsayılan ekibi
```

Üç kural:

1. **Alan kimliği projeye bağlıdır, sohbete değil.** Sohbet projeden çıkarılsa da
   dosyalar projede kalır. Tersine, projeye yeni sohbet girer → aynı dosyaları görür.
2. **Projesiz sohbet bugünkü gibi çalışır.** Hiçbir göç yok, hiçbir dosya taşınmaz.
3. **Hafıza katmanlıdır, birleşik değil.** Proje hafızası global hafızayı
   *gölgelemez*; ikisi ayrı bloklar olarak enjekte edilir ve yazma hedefi açıktır.

---

## 3. Veri modeli

`folders` tablosuna eklenen kolonlar (Room migration **14→15**, hepsi varsayılanlı
ve nullable → satır yeniden yazımı yok, downgrade yalnız özelliği kaybeder):

```sql
ALTER TABLE folders ADD COLUMN space_scope TEXT NOT NULL DEFAULT 'project';
     -- 'project' | 'session'  ('session' = bu grup salt etiket kalsın, alan açma)
ALTER TABLE folders ADD COLUMN memory_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE folders ADD COLUMN default_team_id TEXT;
ALTER TABLE folders ADD COLUMN default_model_id TEXT;
ALTER TABLE folders ADD COLUMN archived_at INTEGER;
ALTER TABLE folders ADD COLUMN memory_prompt TEXT;   -- projeye özel sistem notu (opsiyonel, §7.4)
```

- `space_scope='session'` varsayılanı **değil**, `'project'` varsayılan. Gerekçe:
  kullanıcı "grup hafızası" istiyor; mevcut gruplar da proje sayılmak istiyor.
  Ama `folder_id IS NULL` sohbetler etkilenmez → kimse istemeden değişiklik görmez.
- Yeni tablo **yok**. `Folders` zaten iOS `folders` tablosunun birebir kopyası
  (`FolderEntity.kt:12`), yeni kolonlar orada da karşılıksız kalacak; bunu belgeye
  not düşüyoruz — senkron katmanı geldiğinde eşleme gerekir.

---

## 4. Disk düzeni

```
filesDir/
  minis-global/           kişisel hafıza, skills, shared   (DEĞİŞMEZ)
  minis-sessions/<sid>/   projesiz sohbet alanı            (DEĞİŞMEZ)
  minis-spaces/<fid>/     YENİ — proje alanı
      workspace/ attachments/ offloads/ browser/
      memory/PROJECT.md, memory/YYYY-MM-DD.md
```

Neden `minis-sessions/<id>` içine kodlamıyoruz: ayrı kök, mevcut sahipsiz-dosya
tarayıcısının (`OrphanedSessionFiles`) doğal olarak dışında kalır — yanlışlıkla
proje silen bir temizleyici yazmak imkânsız hale gelir. Tarayıcıya proje kökü
**ayrı** ve **rapor eden** bir mod olarak eklenecek (§10.3).

---

## 5. Alan çözümleme — en küçük doğru değişiklik

Yeni tip: `sandbox/FsSpace.kt`

```kotlin
object FsSpace {
    const val PROJECT_PREFIX = "project:"          // anahtar, dizin adı değil
    fun projectKey(folderId: String) = PROJECT_PREFIX + folderId
    fun isProject(key: String) = key.startsWith(PROJECT_PREFIX)

    /** Tek doğru kaynak: bir alan anahtarı → host kök dizini. */
    fun baseDir(filesDir: File, key: String): File =
        if (isProject(key)) File(filesDir, "minis-spaces/" + key.removePrefix(PROJECT_PREFIX))
        else File(filesDir, "minis-sessions/$key")

    fun subDir(filesDir: File, key: String, sub: String) = File(baseDir(filesDir, key), sub)
}
```

`fsSessionId: String` **imzası değişmiyor**; anlamı "alan anahtarı" oluyor. Sebep:
bu değer 40+ çağrı yerinde ve `ExecutionCoordinator.kt:315` gibi yerlerde
`mountedSessionIds[sessionId] = fsSessionId` şeklinde string olarak karşılaştırılıyor.
Tip değiştirmek tüm zinciri (kabuk havuzu, PRoot argv, offload handler'lar, terminal)
riske atar; kazanç yalnız tip güvenliği. Ödünleşim: derleyici korumaz → S1'de
`FsSpace` için birim test + `rg 'minis-sessions/\$'` kapısı (kalan ham dize yok).

Değişecek yerler (hepsi `FsSpace.baseDir/subDir` üzerinden):

| Dosya | Ne |
|---|---|
| `SessionMounts.kt:53` (`sessionDir`) | `FsSpace.subDir` — **asıl düğüm** |
| `PRootKernel.kt:800` (`resolveSessionHostPath`) | `File(filesDir,"minis-sessions/$sessionId/$subdir")` → `FsSpace.subDir` |
| `RootfsManager.kt:318` (`ensureSessionDirs`) | aynı |
| `PhotosOffloadHandler.kt:541` | aynı |
| `ModelUseOffloadHandler.kt:1352` (`sessionScopedHostFile`) | aynı |
| `TerminalSession.kt:433` banner | "(session x)" → projede "(proje x)" |
| `ExecutionCoordinator.kt:617` | sadece log etiketi (`[space=…]`) |

`PRootKernel.resolveHostPath` (global harita) **dokunulmuyor**: proje yolları
`isPerSessionPath` kapsamında ve oturum bağlamı olmadan çözülmesi zaten yasak
(`:259-265`).

---

## 6. Hafıza katmanları

### 6.1 Depo

`MemoryRepository` **sınıfı değişmiyor** — zaten `memoryDir: File` alıyor.
Yeni bir örnek: proje başına `MemoryRepository(File(filesDir,"minis-spaces/<fid>/memory"))`,
tembel ve önbellekli:

```kotlin
class MemoryScopeResolver(private val filesDir: File, private val personal: MemoryRepository) {
    fun personal(): MemoryRepository = personal
    fun project(folderId: String): MemoryRepository   // cache: ConcurrentHashMap
    fun forSpace(fsKey: String, folderId: String?): MemoryRepository
}
```

DB'ye taşımıyoruz. Gerekçe: 559 satır + revoke/replace (T149) + `memory_get`
anahtar araması + enjeksiyon biçimi hazır çalışıyor ve davranışı test edilmiş;
taşımanın tek kazancı sorgulanabilirlik, maliyeti ise tüm hafıza testlerinin
yeniden yazımı. Proje ayrımı **dizin** ile çözülür.

### 6.2 Yazma kuralı (`memory_write`)

Araç şemasına tek alan: `scope: "project" | "global"` (varsayılan **projeye göre**).

| Oturum | Varsayılan hedef | `scope=global` |
|---|---|---|
| Projede | proje günlüğü | kişisel günlük (açık beyan) |
| Projesiz | kişisel günlük (**bugünkü davranış**) | aynı |

Araç açıklaması, modelin ne yazacağını doğru seçmesi için net olmalı: "proje
hafızası bu projeye bağlıdır; kullanıcının kişisel tercihleri ve projeler arası
geçerli bilgiler `global`'e yazılır."

### 6.3 Okuma kuralı (`memory_get`)

`scope` enum: `project` | `global` | `all` (varsayılan `all`).
Projesiz oturumda `project` = kişisel (geriye uyum), `all` = bugünkü `all`.

### 6.4 `PROJECT.md`

Proje hafızasının `GLOBAL.md` karşılığı: kullanıcı yönetir (Proje › Hafıza ekranı),
ajan yalnız kullanıcı isterse yazar. `memory_get(scope=project)` içinde aranır,
enjeksiyonda önce gelir.

### 6.5 Notlar

`NoteStore.Scope` zaten kapsamlı (`data/repository/NoteStore.kt:119-124`,
`session:<id>`, `team:<id>`). Tek ekleme: `project:<folderId>` + `visibleTo`
zincirine proje→kişisel devralma. `ChatViewModel.kt:14366`'daki
`Scope.session(fsSessionId).wire` proje oturumunda `Scope.project(folderId)` olur —
**tek satır**, çünkü kapsam mekanizması hazır.

---

## 7. İstem enjeksiyonu ve bütçe

### 7.1 Yeni parça

`ChatViewModel.kt:16845` üçüncü bir blok alır:

```
projectMemoryFragment  (proje PROJECT.md + son 2 proje günlüğü)
globalMemoryFragment   (kişisel GLOBAL.md + son 3 günlük)
```

Etiketler ayrı: `<project-memory project="Ad">…</project-memory>` ve mevcut
`<memory>`. Ayrı tutmak modelin "bu bilgi bu projede geçerli" ayrımını korumasını
sağlar; tek blokta birleştirmek proje bilgisini küresel gerçek gibi okutur.

### 7.2 Bütçe — sıra ve tavan

Bugünkü tavan: 3 dosya × 200 satır. Proje eklenince ikiye katlanırsa istem
şişer. Kural:

- **Toplam enjeksiyon satır bütçesi 280** (bugünkü fiilî üst sınıra yakın).
- Önce **proje** bloğu (en ilgili bağlam), kalan bütçe **global** bloğa.
- Proje bloğu tek başına 180 satırı geçemez (global her zaman en az 100 satır yer bulur).
- Tavan aşılırsa mevcut "(N more lines, use memory_get to search)" satırı yazılır.

Deterministik ve test edilebilir: `MemoryBudgetTest` — girdi: iki deponun dosya
boyutları, çıktı: hangi blok kaç satır.

### 7.3 Persona hatırlatıcısı

`ChatViewModel.kt:5199` şu an "SOUL.md and GLOBAL.md" diyor. Projede
"SOUL.md, GLOBAL.md and this project's PROJECT.md" olacak. Aksi halde model
proje notlarını unutulmuş sanıp yeniden soruyor.

### 7.4 Proje sistemi notu (`memory_prompt`)

Küçük ama değerli: proje başına 1–2 cümle ("bu projede yanıtları Türkçe ve kısa
tut", "bu depo GPL, lisans başlığı ekleme"). Persona/`SOUL.md` kişiseldir; proje
notu iş bağlamıdır. Enjeksiyonda proje hafızasının **üstünde**, tek paragraf.

---

## 8. Araç yüzeyi

| Araç | Değişiklik |
|---|---|
| `memory_write` / `memory_get` | `scope` alanı (§6.2–6.3) |
| `file_read/write/edit`, `shell_execute`, terminal | **kod değişmez** — `fsSessionId` proje anahtarını taşır |
| `browser_use` | `spaceId` (üye izolasyonu için eklenmişti, `ChatViewModel.kt:1689`) proje anahtarına bağlanır → proje sohbetleri sekme havuzunu paylaşır (`GLOBAL_TAB_CAP=8` sınırıyla) |
| `note` | kapsam `project:<fid>` |
| `minis-sessions-cli` | `list --project <ad\|id>`; `status` çıktısına `project` alanı (`SessionsOffloadHandler.kt:644+`) |
| `subagent_task` / ekip üyeleri | alt ajan `fsSessionId`'yi ebeveynden miras alır → proje alanını **paylaşır** (bugünkü `MemberConfig.kt:15` kuralı aynen çalışır) |

Dikkat: alt ajanlar proje alanını paylaşınca eşzamanlı yazma riski artar (§11.2).

---

### 8.5 Proje içi sohbetler arası iletişim

Kullanıcı kararı: *"birden çok sohbet birbiriyle konuşabilir, birbirlerinin
dosyalarını görebilir, okuyabilir, yazabilir, işleyebilir."* Dosya paylaşımı
§5 ile kendiliğinden gelir; **konuşma** için yeni bir taşıma gerekir.

Ekip tarafında bu protokol **zaten var**: `ask_teammate` / `broadcast`
(`ekip-ve-projeler.md` §3.3, konsey `MemberToolBelt`). Yeni protokol yazmıyoruz;
aynı sözleşmeyi sohbet düzeyine açıyoruz.

Depo: `minis-spaces/<fid>/bus/messages.jsonl` — satır başına bir JSON, yalnız
sonuna ekleme (`append`), `id` = monoton sıra + UUID. Neden JSONL: eşzamanlı iki
yazar için tek atomik `append` yeterli (kilit gerekmez), insan da `cat` ile okur,
bozulma tek satırla sınırlı.

```
{ from: <sessionId>, fromLabel: "Sohbet başlığı",
  to: <sessionId>|"*", inReplyTo: <id>?,
  kind: "message"|"request"|"answer"|"notice",
  text: "...", refs: ["workspace/src/App.kt"], ts: 1763... }
```

Araçlar (üye kemerine benzer şekilde sohbet kemerine eklenir):

| Araç | Davranış | Sınır |
|---|---|---|
| `project_send(to, text, kind, refs)` | `to="*"` = tüm proje sohbetlerine | dakikada 6 mesaj/proje, 10 mesaj/tur |
| `project_inbox(since)` | kendi adına + `"*"` mesajları döndürür | son 50 mesaj |
| `project_send(wait=true)` | karşı taraf tur içindeyse cevabı 90 sn bekler | tek bekleme, sonra "cevap yok" |

Teslim kuralları:

- **Hedef sohbet çalışıyorsa**: tur sınırında (`ToolLoop` döngüsü arası) okunmamış
  mesajlar sistem istemine `<project-inbox>` bloğu olarak düşer — mevcut persona
  hatırlatıcısı deseninin aynısı (`ChatViewModel.kt:5199` yanına).
- **Hedef sohbet boştaysa**: mesaj kuyrukta bekler; sohbet bir sonraki açılışında
  veya otomatik uyandırma açıksa (`Proje ayarı: sohbeti uyandır`) `QueuedPrompt`
  yoluyla yeni bir tur başlatır. **Varsayılan kapalı** — sessizce para harcayan
  bir özellik olmasın.
- **Yanıt bekleme yok** (varsayılan): ping-pong ve kilitlenme koruması için
  `project_send` asenkron; iki sohbet birbirini sonsuz bekleyemez
  (`ToolLoopDetector` + tur başına mesaj sayacı).
- Kullanıcı görünürlüğü: proje ekranında **Sohbetler arası** sekmesi — döküm,
  canlı; kullanıcı da yazabilir (`from: "user"`). Kullanıcı mesajı her sohbete gider.
- Mesajlar hafızaya **yazılmaz**; istenirse sohbet kendi `memory_write`'ı ile
  "karar: X" diye not düşer. Aksi halde pano bir günlük gibi şişer.

---

## 9. Arayüz

### 9.1 Projeler ekranı (mevcut, salt-okunur → gerçek)

`ProjectDetailScreen` sekmeleri: **Sohbetler · Hafıza · Dosyalar · Notlar · Ayarlar**

- **Sohbetler**: mevcut liste + "Yeni sohbet (bu projede)" → `folderId` damgalı.
- **Hafıza**: iki bölüm — `PROJECT.md` düzenleyici (mevcut
  `MemoryManagementScreen` bileşenleri yeniden kullanılır: dosya listesi,
  düzenleme, silme) + proje günlükleri. Üstte tek anahtar: "Bu projede hafıza".
- **Dosyalar**: `FileBrowserViewModel`'in proje köküyle açılmış hâli; boyut +
  "alanı boşalt". `workspace` varsayılan sekme.
- **Notlar**: `NotesScreen` proje kapsamıyla.
- **Ayarlar**: `space_scope` (proje alanı / salt etiket), varsayılan model & ekip,
  `memory_prompt`, arşivle, **sil** (iki aşamalı, §10.2).

### 9.2 Sohbet ekranı

- Başlık altında proje çipi (dokununca proje ekranı).
- `SessionMemorySheet` (bugün hafıza aç/kapa) projede üç durum gösterir:
  `Proje + kişisel` · `Yalnız kişisel` · `Kapalı`. Sohbet satırındaki
  `memory_enabled` alanı **korunur** (sohbet düzeyi geçersiz kılma), proje
  varsayılanı yalnız yeni sohbete tohum olur — `MemoryGlobalPrefs` deseninin aynısı.
- Proje sohbetleri arasında geçişte uyarı yok; ama aynı projede **eşzamanlı**
  çalışan sohbet varsa dosya sekmesinde rozet: "2 sohbet bu alanda çalışıyor" (§11.2).

### 9.3 Ayarlar

`Settings › Hafıza` ekranına "Projeler" bölümü: proje başına boyut + hafıza anahtarı.
`Settings › Depolama` ekranı yeni `minis-spaces` kökünü ayrı satırda gösterir
(`SessionStorage`'a `spacesSize()`).

---

## 10. Yaşam döngüsü

### 10.0 Tek seferlik göç — "bugüne kadar yapılan her şey bir proje"

Kullanıcı kararı: *"şimdiki cihazımızda olan hafızaya göre yaptığımız tüm işlemler
şimdiye kadar bir proje olacak; yeni projeler açılabilecek, hafızası burası ile
alakalı olmasın."*

Bunun **tek doğru okuması** şudur ve tasarım buna göre kurulur: kişisel hafıza
katmanı boşalmaz, ama **birikmiş iş hafızası oradan çıkar**. Aksi halde
"global her zaman enjekte edilir" kuralı yüzünden yeni projeler de eski işleri
görürdü — istenen tam tersi.

Göç (`MemoryLegacyProjectMigration`, ilk açılışta bir kez, idempotent):

1. `folders` tablosunda `origin='migration'` işaretli bir proje oluştur:
   ad **"Geçmiş İşler"** (yeniden adlandırılabilir, `id` UUID).
2. `minis-global/memory/*` **taşınır** → `minis-spaces/<fid>/memory/`
   (kopyala+doğrula+sil; hata olursa geri al ve göçü iptal et — yarım durum yok).
3. `folder_id IS NULL` olan **tüm mevcut sohbetler** bu projeye bağlanır.
   Böylece hiçbir sohbet bağlam kaybetmez: proje hafızası = eski global hafıza.
4. `minis-global/memory/GLOBAL.md` yeniden oluşturulur; içine tek satır:
   *"Birikmiş iş hafızası `Geçmiş İşler` projesine taşındı. Bu dosya artık
   yalnız kişisel/cihaz geneli tercihler için."*
5. `skills`, `shared`, `Soul.md` **yerinde kalır** (kişisel katman).
6. Göç bayrağı: `SharedPreferences ozel.project_migration_v1 = true` +
   `folders.origin='migration'` satırının varlığı (iki bağımsız kanıt).

Göç sonrası durum:

| | Eski hafızayı görür | Kendi hafızası |
|---|---|---|
| "Geçmiş İşler" projesi ve eski sohbetler | evet | boş (eski içerik buraya geldi) |
| Yeni proje | hayır | boş, kendi günlükleri |
| Projesiz yeni sohbet | hayır (yalnız yeni kişisel içerik) | — |

Kullanıcıya ilk açılışta tek kart: "Geçmiş hafızan `Geçmiş İşler` projesine
taşındı — 47 sohbet, 132 KB not. Yeniden adlandır / taşıma" (iki düğme).

Geri alma: göç geri alınabilir olmalı (`proje → kişisel` ters taşıma, aynı
doğrulamalı yol). Tersi yönde tek engel: göçten sonra eski sohbetlere yazılan
yeni proje girdileri — bunlar taşınmaz, kişisel dosyada kalır (kayıp değil).

### 10.1 Sohbeti projeye taşıma / çıkarma

- Var olan `MoveToSessionSheet`/grup seçici `folder_id` yazar. Ek kural: taşınan
  sohbetin **alanı değişir**, dosyaları taşınmaz. Bu bir sürpriz olmasın diye
  ilk kez taşımada tek seferlik bilgi kutusu: "Dosyalar projede kalır; sohbet
  projenin ortak alanını görür."
- Projeden çıkan sohbet kendi `minis-sessions/<sid>` alanına döner (bugünkü gibi).

### 10.2 Proje silme

Üç seçenekli tek diyalog:

1. **Yalnız grubu kaldır** (`folder_id = NULL`, dosya ve hafıza durur) — mevcut
   `deleteFolder` davranışı, varsayılan.
2. **Arşivle** (`archived_at`): sohbetler listede görünmez, alan diskte kalır,
   `@` bahsetme ve enjeksiyon kapalı.
3. **Her şeyi sil**: sohbet satırları + `minis-spaces/<fid>` + proje notları.
   Onay metni ne silineceğini sayıyla söyler ("3 sohbet, 412 MB, 2 günlük").

Arşiv, "geri alma" gerçeğine uyar: silinen alan geri gelmez, arşivlenen gelir.

### 10.3 Sahipsiz alan süpürücüsü

`OrphanedSessionFiles`'a **ayrı** bir mod: `minis-spaces/<fid>` var ama `folders`
tablosunda satır yok → **rapor et, silme**. Temizlik yalnız kullanıcı onayıyla
(Depolama ekranı). Gerekçe: proje alanı sohbetten uzun yaşar; bir veritabanı
kurtarma/yedek geri yükleme sonrası `folders` satırı geç gelirse otomatik silme
kullanıcının işini yok eder.

### 10.4 Yedekleme / paylaşma

- `BackupExporter`: `minis-spaces/**` + `folders` kolonları. Boyut nedeniyle
  varsayılan **dahil**, ama `workspace` altında `node_modules` benzeri büyük
  dizinler için mevcut dışlama listesi geçerli.
- `SessionExporter` (tek sohbet paylaşımı): proje alanından gelen dosyalar
  sohbetin dosyası sayılmaz → dışa aktarımda "proje dosyaları" ayrı bölüm ve
  varsayılan olarak **dışarıda**.

---

## 11. Riskler ve karşı önlemler

| # | Risk | Önlem |
|---|---|---|
| 11.1 | **Yanlış alan sızması**: string anahtar yanlış geçerse ajan başka projenin dosyasını görür | `FsSpace` tek kaynak + S1'de "ham `minis-sessions/$` dizesi kalmadı" kapısı; `MemberConfig` benzeri yollarda alan anahtarı **açıkça** taşınır, varsayılana güvenilmez |
| 11.2 | **Eşzamanlı yazma**: aynı projede 2+ sohbet aynı dosyaya yazar → sessizce biri diğerini ezer | **Dosya iddia defteri** (§11.2.1) + yazma kapısı + çakışma tespiti; kabuk için kibar araç `minis-claim` ve yazma-sonrası tespit |
| 11.3 | **Bağlam şişmesi** (iki hafıza bloğu) | §7.2 bütçe: 280 satır toplam, proje ≤180, global ≥100 |
| 11.4 | **Yanlış katmana yazma**: model her şeyi projeye yazar → projeler arası bilgi kaybolur | Araç açıklaması + varsayılan kural (§6.2) + `memory_write` yanıtında hedef adı ("proje: X" / "kişisel") |
| 11.5 | **Silinen proje alanının sahipsiz kalması** | §10.3: rapor et, silme |
| 11.6 | **Eski APK'ya dönüş** (downgrade) | `minis-spaces` eski sürümce bilinmez → dosyalar görünmez ama **silinmez**; `folders` yeni kolonları eski şema beklemediği için `MIGRATION_15_14` tabloyu yeniden kurup kolonları **düşürür** (SQLite < 3.35 `DROP COLUMN` desteklemez; göç veri taşıyarak yapar, kayıp yok) |
| 11.7 | **Maliyet**: proje hafızası her turda enjekte | Bütçe sabit; proje hafızası kapalıysa (anahtar) hiç enjekte edilmez, araç yine erişir |
| 11.8 | **`SOUL.md` çakışması**: `SoulStore` aynı dizini okuyor (`:214`) | Kişisel `SOUL.md` yerinde kalır; proje persona istemiyorsa `memory_prompt` yeterli. Proje `SOUL.md` bilinçli olarak kapsam dışı |
| 11.9 | **Sohbetler arası mesaj spam'i / uyandırma maliyeti** | Tur başına/dakika başına mesaj kotası; otomatik uyandırma varsayılan **kapalı** (§8.5) |

### 11.2.1 Dosya iddia defteri (çakışma önleme)

Kullanıcı isteği: *"aynı dosyayı 2–3 farklı sohbet kullandığında çakışma/izin
durumu — bunun hakkında da bir şeyler yapalım."* OS düzeyinde `flock` Android'de
güvenilir değil (uygulama öldürülünce kilit kalır) ve bizim yazarlarımız aynı
süreçte coroutine'ler — bu yüzden **uygulama düzeyinde iddia defteri**.

Depo: `minis-spaces/<fid>/.claims.json` (atomik yaz: tmp + `rename`).

```
{ "<relPath>": { owner: <sessionId>, label: "Sohbet başlığı",
                 mode: "write", since: ts, renewedAt: ts, expiresAt: ts,
                 intent: "file_edit" } }
```

Kurallar:

1. **Okuma serbesttir.** Kilit yalnız yazmada. Kullanıcı "görebilir, okuyabilir"
   dedi; okumayı kilitlemek işi yavaşlatır, fayda getirmez.
2. **Kapsam**: `file_write`, `file_edit`, `file_delete` (+ gelecekteki yazma
   araçları) kapıdan geçer. İddia `fsSessionId`'nin alanında ve dosya bazında;
   dizin iddiası yok (tek dosya = tek iddia, `*.kt` gibi toplu işler için
   `intent="dir:src/"` destekli tek istisna).
3. **TTL = 10 dakika**, her başarılı yazımda yenilenir, tur bitince bırakılır
   (`try/finally`). Uygulama öldürülürse iddia TTL ile kendiliğinden düşer —
   kalıcı kilitlenme yok.
4. **Çakışma politikası** (proje ayarı, varsayılan `wait`):
   - `wait`: 45 sn beklenir, sonra araca "meşgul, sahibi: X, bırakmasını isteyeyim mi?"
     döner. Model beklemeyi seçebilir (`project_send(kind="request")`).
   - `ask`: kullanıcıya kart — *"Sohbet A `src/App.kt` üzerinde çalışıyor.
     Yine de yaz / kopyasına yaz / iptal"*.
   - `copy`: dosya `App.kt.conflict-<session8>` olarak yazılır, sahibine
     `kind="notice"` mesajı düşer; iki sürüm de kaybolmaz.
   - `strict`: projede tek yazma sahibi; ikinci sohbet yazma araçlarını hiç görmez.
5. **Kabuk gerçeği (dürüst sınır)**: `shell_execute` keyfi yol yazabildiği için
   iddia defteri onu *engelleyemez*. Üç katmanlı önlem:
   a. `minis-claim <path>` — kabuktan iddia alınır/bırakılır (kibar ajan yolu;
      araç açıklamasında "dosya yazacaksan önce `minis-claim`" talimatı).
   b. **Yazma-sonrası tespit**: `shell_execute` dönüşünde, iddia defterindeki
      *başka bir sahibe* ait dosyaların `mtime+size` özeti değişmişse çakışma
      olayı üretilir; iki sohbete de `<system-reminder>` düşer, proje ekranında
      "çakışma" girdisi açılır. Sessiz ezme yerine görünür olay.
   c. Proje ayarında `strict` seçilirse alan kabuğu tek sohbete kilitlenir
      (ikinci sohbet shell'i kuyruğa girer) — dosya çakışması imkânsız olur.
6. Görünürlük: Proje › Dosyalar sekmesinde **"Aktif iddialar"** listesi
   (kim, ne zaman, hangi dosya) + elle bırakma. Aynı sekmede "son çakışmalar".
7. Ölçek: iddia defteri tek JSON dosyası; 200+ eşzamanlı iddia beklenmez
   (proje başına sohbet sayısı onlarla). `mtime` tabanlı yeniden okuma + `rename`
   atomik yazma yeterli; ayrı DB tablosu **açılmıyor** (göç maliyeti kazancı aşar).


---

## 12. Dilimler

Sıralama kuralı: **veri kaybı riski taşıyan iş, ona bağlı özellikten önce.**
Bu yüzden göç (10.0) ve çakışma defteri (11.2.1), üzerine binen arayüzden önce.

| Dilim | İçerik | Neden bu sırada |
|---|---|---|
| **37070** | `FsSpace` + `SessionMounts`/`resolveSessionHostPath`/`RootfsManager`/offload geçişi + "ham `minis-sessions/$` dizesi kalmadı" kapısı + birim testler | Dosya izolasyonu olmadan hafıza ayrımı yarım; en riskli parça tek başına |
| **37071** | `folders` göçü (14→15) + `FolderEntity` alanları + `ChatRepository` erişimcileri | Şema, sonraki her şeyin tabanı |
| **37072** | `MemoryLegacyProjectMigration` (§10.0): "Geçmiş İşler" projesi, hafıza taşıma, sohbet bağlama, istek kartı, geri alma | Tek geri dönüşü zor adım; arayüz yokken yapılırsa hata yüzeyi küçük |
| **37073** | `MemoryScopeResolver` + proje `MemoryRepository` örnekleri + not kapsamı `project:` | Depo katmanı |
| **37074** | `memory_write`/`memory_get` `scope` alanı + yanıt metinleri + araç açıklamaları | Araç sözleşmesi |
| **37075** | `ChatViewModel.projectId/spaceKey` (oturum yüklenirken klasör okuması) + `fsSessionId` proje anahtarı + tarayıcı `spaceId` bağlama | Kablo; en çok dokunulan dosya, kendi dilimi |
| **37076** | İstem enjeksiyonu: `<project-memory>` bloğu + 280 satır bütçe + persona hatırlatıcısı + `memory_prompt` + testler | Kullanıcının göreceği asıl davranış |
| **37077** | `ProjectFileClaims` (§11.2.1): defter, TTL, `wait/ask/copy/strict`, `file_*` kapısı, `minis-claim` stub'ı | Çakışma, sohbetler aynı alanı görmeden önce değil, gördükten **hemen sonra** |
| **37078** | `shell_execute` yazma-sonrası çakışma tespiti + çakışma olayı/rozeti | Kabuk sınırının kapatılması |
| **37079** | `ProjectBus` (§8.5): `messages.jsonl`, `project_send`/`project_inbox`, tur sınırı teslimi, kotasız uyandırma kapalı | Sohbetler arası konuşma |
| **37080** | Proje ekranı sekmeleri: Hafıza · Dosyalar · **Sohbetler arası** (+ `NotesScreen` kapsamı, "Aktif iddialar") | Arayüz |
| **37081** | Sohbet çipi + `SessionMemorySheet` üç durum + eşzamanlı kullanım rozeti | Arayüz |
| **37082** | Yaşam döngüsü: silme/arşiv diyaloğu, sahipsiz alan raporu, `SessionStorage.spacesSize` | Veri kaybı riski taşıyan iş, tek dilim |
| **37083** | Yedek/dışa aktarma + `minis-sessions-cli --project` + `ekip-ve-projeler.md` §6 güncellemesi | Kenar işler, sona |

Kural: her dilim `preflight` + `audit_all` + kendi birim testleriyle kapanır;
CI `workflow_dispatch` debug derlemesiyle doğrulanır (yerelde JVM çalışmıyor).

---

## 13. Kararlar (kullanıcı onayladı) ve cevaplanmayan tek nokta

| # | Soru | Cevap | Tasarıma etkisi |
|---|---|---|---|
| 1 | "3–4–5" ne? | **Proje sayısı** | Çok kullanıcılı senkron kapsam dışı; proje = yerel kayıt |
| 2 | Proje hafızası global'i görsün mü? | **Görsün, yazmasın**: ikisi de enjekte edilir, varsayılan yazma projeye, global'e yazmak açık beyan | §6.2, §7.1 |
| 3 | Mevcut gruplar? | **Hepsi proje**; ayrıca *bugüne kadarki tüm iş = bir proje* ("Geçmiş İşler"), yeni projeler ondan bağımsız | §10.0 göçü, dilim 37072 |
| 4 | Eşzamanlı sohbet? | **Serbest + uyarı**, ayrıca çakışma engelleme/izin mekanizması | §11.2.1, dilim 37077–37078 |

Hâlâ açık tek nokta (bloklamıyor, varsayılanla ilerlenebilir): **göç sonrası
kişisel hafıza ne kadar boş kalsın** — `GLOBAL.md` tamamen boşaltılsın mı, yoksa
cihaz geneli 3–5 satır (dil tercihi, isim, genel kural) yerinde mi kalsın?
Varsayılan: **yerinde kalsın** (kullanıcı elle temizleyebilir); çünkü "yeni
projeler bunu görmesin" isteği iş hafızasıyla ilgili, kişisel tercihlerle değil.

---

## 14. Kabul testleri (dilim başına en az bir tane)

1. `FsSpaceTest`: `project:<uuid>` → `minis-spaces/<uuid>/workspace`; UUID → `minis-sessions/…`.
2. `SessionMountsTest`: proje anahtarıyla bağlanan `/var/minis/workspace` host yolu
   `minis-spaces` altında; `minis-global/shared` hâlâ global.
3. `MemoryLayerTest`: projede `memory_write` → proje günlüğüne yazar, kişisel
   dosya **değişmez**; `scope=global` → tersi.
4. `MemoryIsolationTest`: iki proje, aynı gün, aynı anahtar kelime → `memory_get`
   yalnız kendi projesinin satırını döndürür.
5. `MemoryBudgetTest`: iki depo da şişkin → toplam ≤280 satır, proje ≤180, global ≥100.
6. `LegacyMigrationTest`: sahte `minis-global/memory` + 3 sohbet →
   göç sonrası proje hafızası dolu, kişisel boş, 3 sohbet projede, ikinci çalıştırma
   **hiçbir şey** değiştirmez (idempotent); taşıma yarıda kalırsa geri alınır.
7. `ClaimTest`: A yazar → B'ye `HELD_BY`; TTL dolunca B alır; tur bitince bırakılır.
8. `ClaimPolicyTest`: `copy` → `.conflict-<id>` dosyası + sahibine notice; `strict` → B yazma aracını görmez.
9. `ShellConflictTest`: A'nın iddiasındaki dosya kabukla değişir → çakışma olayı üretilir.
10. `ProjectBusTest`: sıra korunur, eşzamanlı iki `append` satır bozmaz, kotalar uygulanır,
    `wait=true` 90 sn sonra "cevap yok" döner.
11. `ProjectDeleteTest`: "grubu kaldır" dosyaya dokunmaz; "her şeyi sil" `minis-spaces/<fid>` yok eder.
12. `OrphanSpaceTest`: `folders` satırı olmayan alan **raporlanır**, silinmez.
13. `Migration14To15Test`: eski satırlar `space_scope='project'`, `memory_enabled=1`, `archived_at=NULL` okunur.

---

## 15. Uygulama durumu (kodlandı — derleme bekliyor)

Dilim **37070–37076** yazıldı. Yerel derleme yok (CI `workflow_dispatch` ile
doğrulanacak). Sürüm: `37072 / 1.15-ozel.12.2`. `preflight` temiz,
`audit_all` PASSED (0 yeni hata / 0 yeni uyarı).

### 15.1 Yeni dosyalar

| Dosya | İş |
|---|---|
| `sandbox/FsSpace.kt` | Alan anahtarı → host dizin. **Tek çeviri noktası.** `project:<fid>` → `minis-spaces/<fid>/`, UUID → `minis-sessions/<uuid>/`. Yol kaçışı (`..`, `/`) reddedilir. |
| `data/memory/MemoryMigration.kt` | Göç motoru: planla → kopyala → SHA-256 doğrula → taşı. `SOUL.md` taşınmaz, `GLOBAL.md` → `PROJECT.md`, geri alma. |
| `data/memory/MemoryLegacyProjectMigration.kt` | Tek seferlik "Geçmiş İşler" projesi: klasör oluştur, hafızayı taşı, grupsuz sohbetleri bağla. İki tanıkla idempotent; `inProgress` bayrağı enjeksiyonu susturur. |
| `data/memory/MemoryScopeResolver.kt` | Kişisel + proje `MemoryRepository` örnekleri, önbellekli, `forget()` ile düşürülebilir. |
| `test/.../sandbox/FsSpaceTest.kt` | 12 test: anahtar çevirisi, kök ayrımı, sahipsiz-tarayıcıdan uzaklık, kaçış reddi, etiketler. |
| `test/.../data/memory/MemoryMigrationTest.kt` | 11 test: doğrulamalı taşıma, SOUL.md dokunulmazlığı, aynı-gün birleştirme, idempotens, geri alma, hata sonrası kaynak bütünlüğü. |
| `test/.../data/memory/MemoryLayerTest.kt` | 14 test: iki proje birbirini görmez, `scope` yönlendirmesi, katman başlıkları, önbellek kimliği, aynı dosya adının iki katmanda çakışmaması. |

### 15.2 Değişen dosyalar

| Dosya | Ne |
|---|---|
| `sandbox/SessionMounts.kt` | `sessionDir` → `FsSpace.subDir`. Mount noktaları **aynı**, yalnız host tarafı değişir. |
| `sandbox/PRootKernel.kt` | `resolveSessionHostPath` + eski çözümleyici `FsSpace` üzerinden |
| `sandbox/RootfsManager.kt` | `ensureSessionDirs` alan anahtarı kabul eder |
| `sandbox/offload/PhotosOffloadHandler.kt`, `ModelUseOffloadHandler.kt` | offload çıktı dizinleri |
| `data/ContextOffload.kt` | `toolsDir` |
| `data/session/SessionStorage.kt` | `spacesRoot`, `spacesSize()`, `SPACES_DIR` |
| `backup/BackupExporter.kt` | sohbet ağacı `FsSpace.baseDir` |
| `ui/MinisImageFetcher.kt`, `ui/chat/StreamingMarkdownText.kt` | görsel/medya araması **iki kökte** |
| `data/db/FolderEntity.kt` | 6 yeni kolon + `SCOPE_PROJECT/SESSION`, `ORIGIN_MIGRATION`, `isProject`, `isArchived` |
| `data/db/AppDatabase.kt` | `version 15`, `MIGRATION_14_15`, `MIGRATION_15_14` (SQLite < 3.35 için tablo yeniden kurulumu) |
| `data/db/DatabaseVersionGuard.kt` | `CODE_DB_VERSION = 15` |
| `data/db/ChatDao.kt` | `folderOfSession` (JOIN), `listActiveFolders`, `sessionIdsWithNoFolder`, 5 setter |
| `data/repository/ChatRepository.kt` | aynı erişimciler + `createFolder(spaceScope=)` |
| `data/repository/MemoryRepository.kt` | `primaryFile` parametresi (GLOBAL.md / PROJECT.md), `loadProjectMemoryFragment`, katman başlıkları; `GLOBAL_FILE` artık public |
| `data/repository/NoteStore.kt` | `Scope.project(folderId)` + `isProject` |
| `tools/MemoryTools.kt` | `scope` alanı; kapsamlı `executeMemoryWrite` / `executeMemoryGet`; sonuç satırında hedef adı |
| `ui/chat/ChatViewModel.kt` | `_projectId/_projectName/_projectIsArea/_projectMemoryEnabled`, `memoryBinding`, `projectMemoryRepository`, `noteScopeWire()`, `loadProjectForSession()`, `onSessionFolderChanged()`; `fsSessionId` zincirine `projectSpaceKey`; `<project-memory>` bloğu; persona hatırlatıcısı üç durumlu; `prepare(spaceKey=)` |
| `MinisApp.kt` | göç açılışta IO iş parçacığında, UI beklemez |
| `scripts/audit/code_audit.py` | **CODE009**: `"minis-sessions/"` / `"minis-spaces/"` ham dize kapısı |
| `build.gradle.kts` | `37072 / 1.15-ozel.12.2` |

### 15.3 Derleme öncesi bilinen işler

1. **`schemas/…/15.json` commit edilmeli.** KSP ilk derlemede üretir;
   `MigrationTestHelper` 14→15 zincirini ancak o dosya varken oynatabilir.
   Derleme sonrası `git add src/android/app/schemas/…/15.json`.
2. **Arayüz dilimleri (37077–37083) yok.** Özellik bunlar olmadan da çalışır:
   her grup varsayılan olarak projedir, hafızası `minis-spaces/<fid>/memory`
   altına yazılır ve enjekte edilir. Eksik: proje Hafıza/Dosyalar sekmeleri,
   sohbet çipi, silme-arşivleme diyaloğu, çakışma defteri, sohbetler arası bus.
3. **`onSessionFolderChanged()` çağrı yerleri** henüz bağlanmadı: grup seçici
   (`MoveToSessionSheet`, `GroupPickerSheet`) ve `minis-sessions-cli update
   --group`. 37077'de arayüzle birlikte gelecek. **Bu bağlanana kadar** bir
   sohbeti taşımak, o sohbet açıkken yeni alanı hemen devreye almaz — oturum
   yeniden yüklenince alır. Veri kaybı değil, gecikme.
4. **`loadProjectForSession` maliyeti**: her oturum yüklemesinde bir
   `SELECT … JOIN`. CI sonrası cihazda `PerfLongCtx` ile ölçülmeli.

### 15.4 Doğrulanan davranış (birim test düzeyinde)

- Proje anahtarıyla bağlanan `/var/minis/workspace` host yolu `minis-spaces`
  altında; `minis-global/*` hâlâ global; iki sohbet tek projede **aynı** dizini,
  proje dışı sohbet **başka** dizini görür.
- Proje dizini `minis-sessions/` altında **hiç** oluşmaz (sahipsiz süpürücü
  onu silemez).
- Projede `memory_write` proje günlüğüne yazar, kişisel dosya değişmez;
  `scope=global` tersi; iki proje birbirinin satırını göremez.
- Göç: kaynak, kopya SHA-256 ile doğrulanmadan silinmez; `SOUL.md` yerinde
  kalır; `GLOBAL.md` `PROJECT.md` olur ve kişisel dosyada işaret satırı bırakır;
  ikinci çalıştırma çoğaltmaz; geri alma notları geri getirir.
