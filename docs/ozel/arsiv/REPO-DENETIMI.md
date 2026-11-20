# REPO DENETİMİ — Konsey kodlamasından önce

> Taban: `ozel-11.4` @ `db2566e` · 810 Kotlin dosyası · 304.004 satır (app modülü)
> Yöntem: kaynak okuma + `rg`, tahmin yok. Her iddia `dosya:satır` ile.

Bu belge **planı değiştiren** bulguları içerir. Okumadan kod yazılmaz.

---

## 0. Ortam gerçekleri

| Şey | Değer | Kaynak |
|---|---|---|
| minSdk / targetSdk / compileSdk | 26 / 35 / 36 | `app/build.gradle.kts:56,57,52` |
| `jvmTarget` | 17 | `:236` |
| Test altyapısı | junit4, kotlinx-coroutines-test 1.9.0, **org.json**, robolectric | `:557-564` |
| En büyük dosya | `ChatViewModel.kt` **20.561 satır** | — |
| Dil dosyası | 23 `values-*` | `res/` |

`org.json` testte var → `PositionParser` / `ClaimMatrixParser` gerçekten test edilebilir (yazdım, çalışır).

---

## 1. 🔴 EN ÖNEMLİ BULGU — küresel eşzamanlılık tavanı 6

```kotlin
// service/SessionConcurrencyManager.kt:22
const val MAX_CONCURRENT = 6
```

**Uygulamada aynı anda akan ajan döngüsü sayısı 6.** Fazlası FIFO kuyruğa girer ve
**zaman aşımı yok** (`acquireSlot`, `:67`).

Kritik ayrım:

```kotlin
// ui/chat/ChatViewModel.kt:8714
val holdsSlot = helperConfig == null
```

- **Alt ajan** (`helperConfig != null`) slot **almaz** → ayrı kota: ebeveyn başına 3.
- **Konsey üyesi bugün `helperConfig` taşımıyor** → slot **alır**.

Sonuç, düz matematik:

| Senaryo | Slot kullanımı |
|---|---|
| 5 üye + ebeveyn sohbeti | **6 / 6** — tavan tamamen dolu |
| 5 üye + ebeveyn + kullanıcının açtığı başka sohbet | 6/6 + 1 **kuyrukta** |
| 3 üye + ebeveyn + 2 başka sohbet | 5/6 |

Yani **tek bir konsey koşusu uygulamanın tamamını kilitler**. Kullanıcı başka sohbet
açarsa "düşünüyor…" görür, sebep görünmez: `suspendedSessions` bir `StateFlow`
(`:62`) ama **hiçbir ekran onu okumuyor** (grep: yalnız kendi dosyasında geçiyor).

Bu, §9 ve §24'teki dalga matematiğimi geçersiz kılıyor. Düzeltmeler §24.5'te.

---

## 2. 🔴 Bloklayan `team_run` ebeveynin slotunu rehin alıyor

Slot, akış işinin `finally` bloğunda bırakılıyor (`ChatViewModel.kt:8772`) — yani
**tur bittiğinde**, araç bitince değil. `team_run` ise turun içinde bloklayan bir
çağrı (`:14298`).

Sonuç: 5 üyeli konseyde ebeveyn 1 slotu **20 dakika boyunca hiçbir şey yapmadan**
tutar, ve üyelere yalnız 5 slot kalır. 6 üye istenirse **son üye asla başlamaz**
(kuyrukta, süresiz).

Düzeltme: konsey koşusu açılırken ebeveyn slotu **bırakılmalı**, koşu bitince geri
alınmalı. Bugün böyle bir mekanizma yok, eklenecek (37062).

---

## 3. 🔴 Üye alt ajan kotası gerçekten 5×3'e açık

```kotlin
// agent/jobs/AgentJobRegistry.kt:183
const val MAX_CONCURRENT_CHILD_JOBS_PER_PARENT = 3
fun runningChildJobCount(parentSessionId: String) = ... // parent'a göre sayar
```

Kota **ebeveyn oturum başına**. Üye kendi child oturumunda bir ebeveyn olduğu için
5 üye × 3 = **15 eşzamanlı alt ajan**. Benim öngördüğüm tehlike doğrulandı; kodda
bunu engelleyen hiçbir şey yok.

Ayrıca `MAX_QUEUED_DELEGATIONS_PER_PARENT = 10` da parent başına → 50 kuyruk.

Düzeltme: koşu düzeyi semafor (§24.2) şart, isteğe bağlı değil.

---

## 4. 🟡 Tarayıcı: izolasyon bedava, **kota** değil

```kotlin
// ui/chat/ChatViewModel.kt:3170 — her VM kendi havuzunu kurar
_browserTabPoolRef ?: BrowserTabPool(context)
// browser/BrowserTabPool.kt:357 — kurucuda kayıt, registry zayıf tutar
BrowserTabPoolRegistry.register(this)
```

İyi haber: üye başına ayrı havuz **kendiliğinden** oluyor; VM bırakılınca havuz
çöp toplayıcıyla sayımdan düşüyor (unregister gerekmiyor).

Kötü haber üç tavan üst üste:

| Tavan | Değer | Yer |
|---|---|---|
| Havuz başına sekme | 3 | `BrowserTabPool.kt:54` |
| Ajan varken havuz tavanı | 6 | `:70` |
| **Küresel toplam** | **8** | `BrowserTabPoolRegistry.kt:103` |

5 üye × 3 sekme = 15 talep, küresel 8. Tavan dolunca registry **başka havuzun boşta
sekmesini tahliye ediyor** (`:205`) → bir üyenin araştırdığı sayfa, başka üye sekme
istediği için kayboluyor. Üye "sayfa gitti" der, kimse nedenini görmez.

İyi haber 2: alt ajanlar ebeveynin havuzunu **paylaşıyor**
(`ChatViewModel.kt:3186 adoptBrowserTabPool`) → derinlik 2 tavanı çoğaltmıyor.

Düzeltme: üye başına **1** sekme kotası, koşu toplamı 6, ve tahliye sırası
"başka havuzun AKTİF sekmesi" olmayacak.

---

## 5. 🟡 VM önbelleği 6 — sessiz yeniden kurma riski

```kotlin
// ui/chat/ChatViewModelStore.kt:138
internal const val MAX_CACHED_CHILD = 6
```

Soft cap: meşgul VM'ler tahliye edilmiyor (`busyKeys`, `:251`, `EvictionGuard`) —
yani çalışan üye güvende. Ama 5 üye + 3 alt ajan = 8 > 6 → **boştaki** VM düşer.

`HeadlessChatRunner` önbelleği `isLiveStore` ile doğruluyor (`:66`) → düşen oturum
yeniden kurulur ve **geçmişi kaybeder**. Üye 3. turda 1. turu hatırlamaz, kimse fark etmez.

Düzeltme: 10'a çıkar + koşu sonunda **açıkça** `release` (`:501`).

---

## 6. 🟡 Token sayısı üyelerde yok

```kotlin
// debug/HeadlessChatRunner.kt:676
data class PromptResult(status, responseText, timedOut, deletedMessageCount, retriedMessageId)
```

Kullanım alanı yok. Buna karşılık tek seferlik sağlayıcı çağrısı kullanım veriyor:

```kotlin
// data/model/LLMMessage.kt:74
data class LLMResponse(text, stopReason, usage: LLMUsage?, ...)
```

Yani: **başkan ve sentez gerçek token**, **üyeler tahmini**. `CouncilLoop`'taki
`estimateTokens` yolu tam da bu yüzden var; rapora "tahmini" notu düşülmeli.
`ContextSizeMeter.estimateTokens` (`data/ContextPolicy.kt:200`) hazır ve
karakter sınıfına göre kalibre — kendi kaba tahminim yerine **onu** kullanmalıyım.

---

## 7. 🟡 Hafıza gerçekten tek dizin

```kotlin
// MinisApp.kt:596
memoryRepository = MemoryRepository(java.io.File(filesDir, "minis-global/memory"))
// data/repository/MemoryRepository.kt:68
fun writeMemory(content: String): String  // kapsam parametresi YOK
```

Doğru desen başka yerde zaten var:

```kotlin
// data/repository/NoteStore.kt:177
fun visibleTo(scope: String) = _notes.value.filter { it.scope == scope || it.scope == GLOBAL }
```

Kural: hafıza notların desenine geçer, notlar hafızaya değil.

---

## 8. 🟢 İyi haberler (işi küçülten gerçekler)

| Gerçek | Kanıt | Kazanç |
|---|---|---|
| `ExecutionCoordinator.execute(sessionId=, fsSessionId=)` zaten oturum anahtarlı | `sandbox/ExecutionCoordinator.kt:238` | üye başına shell **bedava** |
| `TerminalSession.start(sessionId)` | `sandbox/TerminalSession.kt:133` | üye başına terminal **bedava** |
| `adoptBrowserTabPool` | `ChatViewModel.kt:3186` | alt ajan sekmeleri çoğaltmıyor |
| `agentTools` her okumada yeniden kuruluyor | `:2531` | kemer değişikliği anında etkili, önbellek yok |
| `teamNames = if (isHelper) emptyList()` | `:2589` | `AgentKind` buraya tek satır gibi oturuyor |
| `LiveTasksScreen` + `AgentJobRegistry.jobs` | `ui/settings/LiveTasksScreen.kt:74` | koşular için **hazır canlı liste** deseni |
| `ToolOverlayController` (1873 satır) | `service/ToolOverlayController.kt` | canlı araç rozeti için hazır desen |
| `suspendedSessions` / `runningSessions` StateFlow var | `SessionConcurrencyManager.kt:59,62` | **hiçbir ekran okumuyor** → bedava görünürlük |

---

## 9. 🟢 Editörün kendi itirafı — 37060'ın gerekçesi kodda yazılı

```kotlin
// ui/team/TeamEditorScreen.kt:43-47
 * The editor is deliberately the whole story of a member — a name, a role
 * sentence, and a model. Nothing about tools, because a member's tool belt is
 * inherited from the app ... a per-member checkbox
 * would be a promise the runner does not yet honour. The `TeamMember.tools`
```

Yani `TeamMember.tools` alanının uygulanmadığı **kod yorumunda kabul edilmiş**.
37060 bunu kapatıyor: alan gerçekten yürürlüğe girer, editör de onu gösterir.

---

## 10. 🟢 i18n kuralı — yeni metinler için isim seçimi

```python
# scripts/audit/compliance_audit.py:163
own_missing = {k for k in missing if k.startswith(("ozel_", "minis_ozel"))}
```

- **CMP003 = HATA**: kodda kullanılan `R.string` anahtarı tanımlı değilse.
- **CMP004 = UYARI**: yalnız `ozel_` / `minis_ozel` ön ekli anahtarlar sayılıyor.

Karar: yeni metinler **`teams_*`** ön ekiyle (mevcut 32 anahtarla aynı gelenek).
`ozel_council_*` deseydim 23 dilde eksik → 23 yeni uyarı. Varsayılan `values/strings.xml`
Türkçe metin taşıyor (`teams_title` = "Ekip"), diğer diller düşer.

---

## 11. Kod yazarken uyulacak biçim kuralları (repodan çıkarıldı)

1. **Kotlin dosya adı = içindeki ana tip.** `CouncilModels.kt` gibi.
2. **Yorum "ne" değil "neden" yazıyor.** Ölçülmüş bir hatayı anlatıyor (`EvictionGuard` buna örnek).
3. **Değişmezlik tercih ediliyor**: `data class`, `val`, saf fonksiyon. Ekip kodu böyle.
4. **Hata yutma açıkça gerekçelenir**; `runCatching` + `getOrNull` kabul, sessiz `catch` değil
   (CODE002 bunu arıyor).
5. **Test adları cümle**: `` `a silent member is counted absent and the others still decide` ``.
6. **Yeni özellik = `OzelFeatures` anahtarı** (`data/OzelFeatures.kt`), veri silmeden.
7. **Yeni ekran = `Routes` sabiti + `composable(...)`** (`ui/navigation/AppNavigation.kt:686`).

---

## 12. Planı düzelten maddeler (özet)

| Bulgu | Etki | Nereye yazıldı |
|---|---|---|
| Küresel tavan 6 | dalga 5 varsayılanı **yanlış**; ebeveyn slotu bırakılmalı | §24.5 |
| Bloklayan `team_run` slot rehini | koşu boyunca ebeveyn slotu iade | §24.5, 37062 |
| Üye alt ajan kotası 5×3 | koşu semaforu zorunlu | §24.2 (doğrulandı) |
| Tarayıcı küresel 8 | üye başına 1 sekme, aktif sekme tahliye edilmez | §24.6 |
| VM önbelleği 6 | 10 + açık `release` | §24.6 |
| Üye token'ı yok | `ContextSizeMeter` ile tahmin, rapora not | §10 |
| `suspendedSessions` okunmuyor | Kürsü'de "slot bekliyor" rozeti | §25.1 |
