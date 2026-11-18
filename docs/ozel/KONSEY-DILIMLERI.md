# Konsey hattı — dilim haritası (37037–37069)

> Bu dosya **hangi konunun hangi dilimde** olduğunu tek yerde tutar.
> Sebep: 37037–37068 arası tek commit'te birleşti (`1a56959`) ve commit mesajı
> toplu; buradan bakmadan "bu iş nerede?" sorusu cevaplanamıyordu.

Dal: `toplama-11.4` · taban `db2566e` · **derleme yok** (bu dal `apk.yml`
desenine (`ozel-*`) uymuyor, o yüzden push derleme tetiklemez)

---

## 1. Dilim → içerik

| Dilim | Konu | Ana dosyalar | Test |
|---|---|---|---|
| **37037** | Konsey çekirdeği: döngü, durum matematiği, ayrıştırıcılar, atıf denetimi, cihaz politikası | `CouncilLoop`, `CouncilModels`, `StanceMath`, `PositionParser`, `ClaimMatrixParser`, `CitationAudit`, `CouncilPrompts`, `CouncilPolicy` | `CouncilLoopTest` (35) |
| **37050** | Görev tahtası: model, depo, `task_list`, `task_update` | `TaskBoard`, `TaskStore`, `TaskTools` | `TaskBoardTest` (19) |
| **37051** | Canlı görev verme (`assign_task`) + pozisyonda `tasks[]` | `AssignTaskTool`, `PositionParser`, `CouncilModels` | `TaskBoardTest` |
| **37052** | Başkanın `needs_task` işareti → otomatik görev + rol eşleşmeli atama | `CouncilPrompts`, `ClaimMatrixParser`, `AndroidCouncilRuntime` | — |
| **37055** | Görev salonu ekranı + koşu ekranında "Görevler" sekmesi | `TaskHallScreen`, `TeamsScreen`, `AppNavigation` | — |
| **37060** | `AgentKind` üçüncü tür + üye kemeri + editörde mod/rol seçimi | `AgentKind`, `MemberToolBelt`, `MemberConfig`, `TeamModeSection`, `TeamStore` (`TeamMode`) | `MemberToolBeltTest` (28) |
| **37061** | Üye izolasyonu: ayrı shell/terminal/tarayıcı/çalışma dizini, sekme kotası 1, tur sonu tahliye, yetim dizin süpürme | `AndroidCouncilRuntime`, `TeamRunStore`, `BrowserTabPool` (`setAgentTabQuota`), `ChatViewModel` (`spaceId`) | `TeamRunStoreTest` (5) |
| **37062** | Koşu düzeyi alt ajan semaforu, ebeveyn akış yuvası iadesi, 90 sn kuyruk gözcüsü | `RunSubAgentBudget`, `AgentJobRegistry` (run kapısı), `SessionConcurrencyManager` (`loanOutSlot`/`reclaimSlot`) | `RunSubAgentBudgetTest` (7) |
| **37063** | Terminal devri: salt-okunur → "ele geçir" (kuyruklu) | `TerminalTakeover`, `TerminalScreen` (`readOnly`), `MemberConfig.terminal` | `TerminalTakeoverTest` (9) |
| **37064** | Canlı koşu defteri + Konsey listesi/koşu ekranı (Kürsü·Gündem·Görevler·Karar) | `CouncilLiveRegistry`, `CouncilScreens`, `AppNavigation` (3 rota) | — |
| **37065** | `ask_teammate`: gerçek araç + kota + tek seferlik akran çağrısı | `AskTeammateTool`, `AskTeammateQuota`, `ChatViewModel` (`executeAskTeammate`) | `AskTeammateTest` (15) |

**Toplam 118 birim testi.** Hepsi saf JVM — ağ, cihaz, saat gerekmiyor.

## 2. Bu commit'te OLMAYAN (bilinçli)

| Dilim | Neden yok |
|---|---|
| **37034** fanout | Konsey hattı fanout'a bağımlı değil; fanout `TeamMode.FANOUT` olarak ayrı duruyor, koşu yolu henüz bağlanmadı |
| **37066** üye ekranı (7 sekme) | Sırada. Bugün üye ekranı = mevcut salt-okunur transkript + canlı araç rozeti |
| **37067** kürsü rozetleri (genişletme) | Sırada. Bugün rozet = çalışan araç adı |
| **37068** koşu temizliği | **37062 içinde çözüldü**: `closeRunGate` + `releaseHolds` `finally`'de, `unregisterRun` kuyruğu düşürüyor. `MAX_CACHED_CHILD` **6'da kaldı** — üyeler `holdWhileViewed` ile sabitlendiği için cap yükseltmek gereksizdi (yanlış bir yorum bu satırı "6→10" diye anıyordu, düzeltildi) |
| **37069** bütçe paneli | Sırada. Bütçe bugün `CouncilPolicy` + `RunSubAgentBudget` içinde, ekranda gösterilmiyor |
| **Station panelleri** | **11.4 kapsamında DEĞİL.** İstasyon mimarisi (§7) 11.7'ye ertelendi. `1a56959` commit mesajı "Station panelleri" diyor — o ifade yanlış, bu satır onu düzeltir |

## 3. Yerleşim kuralları (yanlış dilime düşmesin diye)

1. **Bir dilim = bir konu.** `AndroidCouncilRuntime` birden çok dilimin kodunu taşır;
   her blok kendi `[3706x]` etiketiyle işaretli. Etiket yoksa blok hangi dilime ait
   olduğu bilinmiyordur — öyle bırakılmaz.
2. **Araç tanımı ile uygulaması aynı dilimde.** `task_list` tanımı `TaskTools`'ta,
   uygulaması `ChatViewModel.executeTaskList`'te; ikisi de 37050 etiketli.
3. **Kemer (belt) ayrı dilim.** Bir aracın kime açık olduğu `MemberToolBelt`'te (37060),
   aracın kendisi kendi diliminde. Yeni araç eklerken **iki yer** güncellenir:
   `AgentTools.makeAgentTools` (tanım) ve `MemberToolBelt.MEMBER_ALLOWED` (izin).
   Biri unutulursa araç üyeye hiç görünmez ya da daima reddedilir.
4. **Test, kodun yanında ama ayrı dosyada.** `src/test/java/com/openminis/app/ozel/`.
5. **Metinler `teams_*` / `council_*` / `task_*` ön ekli.** `ozel_*` kullanılmaz:
   `compliance_audit.py` yalnız `ozel_*` anahtarlarını çeviri eksiği olarak sayıyor,
   bu ön ekler 23 dilde uyarı üretmez.

## 4. Bilinen açıklar (derleme sonrası ilk turda bakılacak)

1. `schemas/…/15.json` — proje hafızası göçünden (Room 14→15) sonra ilk CI
   derlemesi üretmeli ve commit edilmeli.
2. `TeamMember.scope` alanı hâlâ **okunmuyor**; kemer uygulanıyor (`tools`),
   kapsam (`scope`) uygulanmıyor. `AgentScope` küresel mod üzerinden çalışıyor.
3. `TeamMode.FANOUT` kaydedilebiliyor ama `TeamRunner` onu handoff gibi koşuyor —
   ayrı bir koşu yolu yazılmadı (37034).
4. `ask_teammate` akran cevabı **tahmini** token sayıyor; üye `PromptResult`'unda
   kullanım alanı yok (`HeadlessChatRunner.PromptResult`).
