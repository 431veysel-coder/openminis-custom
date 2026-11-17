# Ekip + Projeler — tasarım belgesi

Durum: tasarım (kod yok). Hedef sürüm hattı: 37031+.
Bağlam: kullanıcı ana ekrandan iki giriş istiyor — **Ekip** ve **Projeler** — ve
sohbette (a) tek sohbette çoklu model, (b) görünür çoklu görev sırası.
Ekip, panelli (İstasyon) **ve** orkestratör olarak birlikte olacak; üyeler
birbirleriyle konuşacak, soru soracak, tarayıcı/terminal/araçları kullanacak.

---

## 1. Bugün elimizde ne var (yeniden yazılmayacak)

| İhtiyaç | Mevcut kod |
|---|---|
| Klasör | `data/db/FolderEntity.kt`, `ChatSessionEntity.folderId` (`:70`), `ui/sessions/FolderPages.kt`, `GroupPickerSheet.kt` |
| Mesaj başına model | `MessageEntity.kt:58-63` — `model_id`, `model_display_name`, `provider_type`, `provider_instance_id` zaten yazılıyor |
| Oturum modeli | `ChatSessionEntity.modelId` + `modelBinding`, seçici `ui/chat/ChatModelPickerSheet.kt` |
| Kuyruk | `QueuedPrompt` (`ChatModels.kt`), `QueuedPromptBatching.kt` (araç sınırı ekleme / drain / scheduled tekilliği) |
| Çocuk ajan | `agent/jobs/AgentJobRegistry.kt` (ebeveyn başına 3 eşzamanlı, ayarlanabilir), `HelperRunner.kt`, `ModelTierResolver.kt`, `SubAgentTermination.kt` |
| Oturum eşzamanlılığı | `service/SessionConcurrencyManager.kt` (1..32, askıya alma) |
| Araç kemeri | `shell_execute`, `browser_use`, `file_*`, `note`, `memory_*`, `read_image`, `subagent_task` + MCP + `android-*` CLI'ları |
| Kalıcı yüzeyler | `LiveTasksScreen`, `SubAgentsScreen`, `OzelFeaturesScreen` (anahtar kayıt defteri) |

Sonuç: **şema geçişi gerekmeden** tek sohbette çoklu model mümkün; transkript
hangi mesajı hangi modelin yazdığını zaten taşıyor. Eksik olan yürütme + yüzey.

---

## 2. Referanslar ve onlardan aldığımız kararlar

1. **Magentic-One** (arXiv 2411.04468; AutoGen `_magentic_one_orchestrator.py`)
   İki döngü: dış döngü **görev defteri** (bilinen gerçekler / araştırılacaklar /
   türetilecekler / eğitimli tahminler + adım-adım plan), iç döngü **ilerleme
   defteri** — her turda JSON:
   `{is_request_satisfied, is_progress_being_made, is_in_loop, instruction_or_question, next_speaker}`.
   Anahtar alan yoksa/bozuksa en fazla `max_json_retries` kez yeniden istenir.
   `is_progress_being_made=false` veya `is_in_loop=true` → `stall++`, `max_stalls`
   aşılırsa **yeniden planla**. → Bunu birebir alıyoruz; ucuz model ledger'a yeter.
2. **AutoGen GroupChat** konuşmacı seçimi: auto / round_robin / random / manual +
   özel fonksiyon. Bağlam şişmesine karşı `MessageHistoryLimiter`,
   `MessageTokenLimiter`. → Bizde `auto` + `round_robin`; uzun oturumlarda
   oturumun mevcut bağlam kırpma politikası (`ContextPolicy`) üyeler için de geçerli.
3. **OpenAI Agents SDK**: *handoffs* (kontrol devri) vs *agents-as-tools*
   (yönetici kontrolde kalır). → Ekipte **agents-as-tools**: orkestratör son sözü
   söyler; üye yalnız sınırlı bir yetenek olarak çağrılır. "Uzman sadece sözü
   devralacaksa handoff" kuralı İstasyon panellerine bırakılır.
4. **Claude Code alt-ajanlar**: taze bağlam, sadece son mesaj döner, araç kısıtı
   üye başına; `SendMessage` aracı olan ajan ilk turda **akran listesini** alır.
   → Üye = mevcut `AgentJobRegistry` çocuğu; `ask_teammate` aracı ve ilk turda
   isim listesi enjeksiyonu aynı desen.
5. **CAMEL** (arXiv 2303.17760): inception prompting — rol promptu sabitlenir,
   AI-User yalnız sorar / AI-Assistant yalnız cevaplar, tur sayısı sınırlı.
   Rol kaymasını engelleyen asıl şey bu. → Üye promptlarında "sorma/cevaplama"
   ayrımı ve `max_turns`.
6. **MAST** (Berkeley, 14 başarısızlık modu): FC1 tasarım (görev/rol ihlali, adım
   tekrarı, **sonlanma koşulunu bilmeme**), FC2 uyumsuzluk (soru sormama, girdi
   yok sayma, bilgi saklama), FC3 doğrulama (**erken bitirme, doğrulamama, yanlış
   doğrulama** — en sık ikisi). → Her koşu sonunda zorunlu doğrulama adımı
   (`TaskChecks`), sonlanma koşulları promptta açıkça, adım tekrarına karşı
   "yapıldı" defteri. 11.3'te yazılan `data/verify/TaskChecks.kt` buraya bağlanır.
7. **Orkestrasyon topolojileri** (preprints.org derlemesi): merkezî / merkezsiz /
   hiyerarşik / dinamik. 3-7 ajan için merkezî en denetlenebilir; >10 için
   hiyerarşik. GPTSwarm/DyLAN: elle tasarlanmış topolojiler token açısından
   savurgan. → Varsayılan: merkezî orkestratör + 3-5 üye + akran mesajı; 8+ üyede
   alt-şef (hiyerarşi) otomatik.

---

## 3. Ekip (Team)

### 3.1 Veri modeli — `data/team/TeamStore.kt`
`filesDir/teams/<id>.json` (Room'a gerek yok, ekip tanımı seyrek değişir):

```
Team(id, name, orchestrator: MemberRef, members: [Member], policy)
Member(name, role, systemPrompt, providerInstanceId, modelId, thinking,
       tools: allowlist, scope: open|read_only|workspace, maxTurns)
MemberRef = "orchestrator" | Member.name
policy: maxRounds, maxStalls(=3), maxWallClockMin, tokenBudget, costBudget,
        speakerSelection: auto|round_robin, verification: required|off,
        peerMessaging: on|off (depth=2)
```
Üye modeli `ProviderInstanceEntity` + model kimliğiyle çözülür — mevcut
`ModelTierResolver` yeniden kullanılır, yeni sağlayıcı kavramı yok.

### 3.2 Koşu — `agent/team/TeamRun.kt`
```
outer: TaskLedger(facts, toLookUp, toDerive, guesses, plan[])   // dış döngü
loop (round < maxRounds):
  inner: ProgressLedger JSON ← orkestratör (ucuz model, json_output yoksa
         metin ayrıştırma + 3 deneme + deterministik yedek: sıradaki üye)
  satisfied  → doğrulama adımı → sentez → bitti
  stall>=3   → dış döngü: defteri güncelle, yeniden planla
  aksi       → instruction_or_question + next_speaker'a ilet
```
Üye çağrısı = `AgentJobRegistry.submit(parentSessionId, prompt, config)`.
Üyenin araç kemeri `tools` allowlist'iyle kırpılır; dosya erişimi `AgentScope`
ile sınırlanır (11.3).
Dönen şey: `{summary, artifacts[], verification: pass|fail|unknown, usage}`.
Orkestratöre **tam transkript gitmez** — sadece özet (bağlam patlamasını önler).

### 3.3 Üyeler arası iletişim — `ask_teammate` / `broadcast` araçları
- `ask_teammate(name, question)` → hedef üye tek soruya tek cevap verir
  (CAMEL kuralı), dönüş çağırana. Derinlik 2: üye, üyeye soru soramaz.
- Her üyenin ilk turunda **akran listesi** (isim + rol, tek satır) enjekte edilir
  (Claude Code `SendMessage` deseni).
- Tüm mesajlar `TeamRun` defterine yazılır → İstasyon panelinde ve transkriptte
  görünür; bilgi saklama (MAST FM-2.4) böylece denetlenebilir.

### 3.4 Araçlar
Üye varsayılan kemeri: `shell_execute`, `browser_use`, `file_read/write/edit`,
`read_image`, `note`, `memory_get/write`, MCP sunucuları, `android-*` CLI'ları.
Kısıtlar mevcut altyapıdan: `SessionConcurrencyManager` (oturum slotu),
`ExecutionCoordinator` (sandbox komut sırası), `OffloadPermissionManager`
(izin kapısı), `ToolLoopDetector` (döngü tespiti — MAST adım tekrarına karşı
zaten var, üyelerde de açık).

### 3.5 İstasyon (panelli mod)
Aynı `TeamRun` üzerinde yalnız **görünüm** farkı: 2-4 panel, her panel bir üyenin
canlı transkripti + terminali + izole çalışma dizini. Paneller arası konuşma
yok; koordinasyon orkestratörden. Sohbet↔panel geçişi aynı koşu kimliğiyle.
(Daha önce hiç yazılmadı — bu yüzden S5'te, ekip çekirdeği oturduktan sonra.)

---

## 4. Tek sohbette çoklu model

Üç mod, `ChatSessionEntity` üzerinde tek ek kolon (`chat_mode` + `team_id`,
additive `ALTER TABLE`, `AppDatabase.kt:196-204` deseniyle):

| Mod | Davranış |
|---|---|
| `single` | Bugünkü. |
| `fanout` | Bu tur seçili N modele paralel gider; her cevap **ayrı** asistan mesajı (model etiketi zaten var), istenirse sonunda sentez mesajı. |
| `team` | Oturum bir ekibe bağlı; `team_run` orkestratörü çalışır, üyeler arka planda. |

Besteci üstünde tek çip: `V4.1 Flash ▾ · Fanout 3 · Ekip` — model seçici mevcut
`ChatModelPickerSheet`'e "bu turda" ve "ekip üyeleri" bölümleri eklenir.
Tur grubu: `turn_group_id` (nullable, additive) — fanout kollarını aynı turda
tutmak ve sonradan tek blok olarak katlamak için.

---

## 5. Görünür görev sırası

Bugün: kuyruk var ama görünmez, üstelik kullanıcı görevleri **tek mesajda
birleştiriliyor** (`QueuedPromptBatching.nextDrainBatch`). Değişiklik:

- Kullanıcı görevleri **ayrı ayrı** çalışır (her biri kendi turu); birleştirme
  yalnız programatik/scheduled için kalır. Bu bilinçli bir davranış değişikliği.
- `QueuedTask { id, text, attachments, target: default|model:<id>|team:<id>,
  state: queued|running|done|failed|cancelled, createdAt, resultRef }`
- Kalıcılık: `filesDir/queue/<sessionId>.json` — süreç ölürse görev kaybolmaz.
- UI: besteci üstünde yatay çipler ("2. 3. sırada"), dokununca tam sayfa:
  sürükle-sırala, iptal, "şimdi çalıştır" (araç sınırında araya sokma — mevcut
  `nextInsertBatch`), bitenlerde sonuca git.
- Durum makinesi `ChatViewModel`'in mevcut `_isStreaming` + drain yoluna bağlanır;
  yeni bir yürütücü yazılmaz.

---

## 6. Projeler

Proje = yükseltilmiş klasör (mevcut `folder_id` üzerine, ayrı tablo yok):

```
Project(folderId, name, icon, color,
        workspaceDir: String?,          // sandbox içi bağlı dizin
        defaultTeamId / defaultModel,
        capabilities: CapabilitySet,    // proje politikası
        createdAt, archived)
CapabilitySet(tools: allowlist?, mcpServers: [id], skills: [name],
              scope: open|read_only|workspace, mounts: [name],
              budget: {tokens?, cost?, turns?}, confirmPolicy)
```

`CapabilitySet` projedeki **her** oturuma uygulanır — tek yerden yetenek/beceri
yönetimi kullanıcının istediği "projelere özellik/yetenek/beceri ekleme".
Proje ekranı sekmeleri: **Sohbetler · Görevler · Notlar · Dosyalar · Ayarlar**
(son sekme = CapabilitySet + varsayılan ekip/model).

Ana ekran girişleri:
- Üst çubuk: `Ekip` ikonu (gruplar simgesi) ve `Projeler` ikonu, mevcut
  `Schedule` + `Terminal` ikonlarının soluna (`SessionListScreen.kt:1030-1060`).
- Oturum listesi: proje çipleri başlık olarak; "Bugün" bölümü korunur, proje
  dışı oturumlar "Diğer" altında.

---

## 7. Dilimler ve sürüm planı

| Dilim | İçerik | Sürüm |
|---|---|---|
| **S1** ✅ | Girişler: Ekip + Projeler ikonları, rotalar, `TeamStore`, grup→proje okuma ekranları | 37031 |
| **S2** ✅ | `TeamLoop` iki döngü + `AndroidTeamRuntime` (orkestratör doğrudan sağlayıcıya, üye = gizli alt oturum) + `team_run` aracı + üye düzenleyici + 15 birim testi | 37032 |
| **S3** | Fanout modu + besteci çipi + model seçicide "bu turda / ekip üyeleri" | 37033 |
| **S4** | Görünür ve kalıcı görev sırası | 37034 |
| **S5** | Projeler + `CapabilitySet` + proje ekranı | 37035 |
| **S6** | İstasyon panelleri (aynı `TeamRun`, farklı görünüm) | 37036 |

Her dilim: `apk.yml` `workflow_dispatch` + `build_type=debug` ile doğrulanır
(yerel derleme yok — PRoot'ta `java` çalışmıyor). Sürüm numarası her dilimde
bir artar; 37030 `ozel-11.3` tarafından alınmış durumda.

### 7.1 Yayın otomasyonu (11.4'ten itibaren)

`ozel-*` dallarına yapılan **her push** release derlemesi üretir ve GitHub
Release'e yayınlar — APK hazır olduğu anda uygulamanın kendi güncelleyicisi
onu görür. İki kapı bunu güvenli tutar:

1. **`version-gate` işi** (build'den ÖNCE, ~15 s): `build.gradle.kts`'ten
   versionCode/versionName okur, aynı etiketle yayınlanmış build numarasını
   karşılaştırır. Yükseltilmemiş sürümle push = saniyeler içinde kırmızı,
   6 dakikalık derleme harcanmaz.
2. **Publish adımındaki kapı** (yayın anında): aynı soruyu bu kez gerçek APK
   için sorar. İki soru, iki cevap yeri.

Debug dispatch yayın **yapmaz** — o hâlâ atılabilir doğrulama derlemesi.

---

## 8. Riskler ve karşı önlemler

| Risk | Önlem |
|---|---|
| Maliyet patlaması (N model × tur) | `tokenBudget`/`costBudget` zorunlu; ledger ucuz modelde; üye başına `maxTurns`; bütçe aşılınca sentez turu |
| Yapısal JSON üretemeyen ucuz modeller | 3 deneme + metin ayrıştırma + deterministik yedek (sıradaki üye) |
| Bağlam şişmesi | Üye özeti döner; orkestratör transkripti `ContextPolicy` ile kırpılır |
| Erken bitirme / doğrulamama (MAST FC3) | Doğrulama adımı zorunlu, `TaskChecks` ile somut kabul kriteri |
| Sonsuz akran ping-pong | Derinlik 2, `ToolLoopDetector`, tek soru-tek cevap |
| Android arka plan | Mevcut `AgentForegroundService` + `SessionConcurrencyManager` slotları |
| Çeviri/AAPT | Metinler `values/strings.xml`'e; kaçış denetimi `scripts/preflight.py`'de zaten var |
