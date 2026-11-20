# KONSEY — çoklu model tartışma motoru

> Tasarım belgesi. Kod yazılmadan önce kararlaştırılacak iskelet.
> Hedef: bir ekip (3–5 model) **aynı soruya** ayrı ayrı cevap versin, birbirinin
> cevabını görsün, itiraz etsin, soru sorsun, araştırsın, tur tur yakınlaşsın ve
> sonunda **kanıtlı + azınlık görüşü korunmuş** tek bir sonuç çıkarsın.

Bu belge **11.6** sürüm hattının tamamını tanımlar. Yürürlükteki `11.5` haritası
(37034–37046) bozulmaz; Konsey 37047'den başlar.

---

## 0. Kapsam ve kapsam dışı

**Kapsam içi**
- Ekip için üç çalışma modu: `handoff` (bugünkü), `fanout` (paralel bağımsız), `council` (tartışma).
- Tur tur tartışma: pozisyon → itiraz → soru → araştırma → yakınlaşma → sentez.
- Üyeler arası gerçek soru aracı (`ask_teammate`) ve paylaşılan kanıt tahtası.
- Deterministik durma kuralları, deterministik atıf doğrulaması.
- Canlı izlenebilir tartışma ekranı + müdahale.
- Üye başına araç/kapsam **zorması** (bugün saklanıyor, uygulanmıyor).

**Kapsam dışı**
- Üyelerin birbirinin sohbet geçmişini okuması (sadece yapılandırılmış özet paylaşılır).
- 8'den fazla üye, ekip içinde ekip (iç içe `team_run`), üye başına ayrı rootfs.
- Sunucu tarafı; her şey cihazda, mevcut sağlayıcı katmanı üzerinden.

---

## 1. Bugün ne var — kanıt

Bunlar tahmin değil, `ozel-11.4` (`db2566ee`) ağacından okundu:

| # | Gerçek | Kanıt |
|---|---|---|
| 1 | Döngü **sıralı**: her turda tek konuşmacı, tek `lastResult` | `agent/team/TeamLoop.kt` — `runner.run(member, …)` tek çağrı |
| 2 | Üyeler birbirini **görmüyor**; yalnız `lastResult` metni orkestratöre gidiyor | `TeamLoop` / `TeamPrompts.progressLedger(lastResult=…)` |
| 3 | Prompt üyeye `ask_teammate(name, question)` diyor, **araç yok** | `TeamPrompts.member()` vs `tools/AgentTools.kt` + `ChatViewModel` `when(name)` |
| 4 | `TeamMember.tools` ve `.scope` saklanıyor, **hiçbir yerde uygulanmıyor** | grep: yalnız `TeamStore.kt` + `TeamEditorScreen.kt` |
| 5 | Üye oturumları **helper sayılmıyor** (`helperConfig == null`) | `ChatViewModel.kt:1564`, üye `HeadlessChatRunner` ile açılıyor (2716 bloğu çalışmıyor) |
| 5a | → Üyenin **tam araç kemeri** var: `subagent_task`, `team_run` (iç içe!), `user_ask` | `AgentTools.build(isHelper=false)` |
| 5b | → Üye **gönderme kuyruğu slotunu tutuyor** | `holdsSlot = helperConfig == null` |
| 6 | `team_run` **bloklayan** çağrı, 20 dk, UI'ya olay akmıyor | `ChatViewModel.kt:14298` + `TeamRunner.run` |
| 7 | Doğrulama orkestratörün **kendi görüşü** | `TeamLoop.verify()` (TaskChecks ile bağlantı yok) |
| 8 | Orkestratör modeli yoksa **sessizce ilk görünür modele** düşüyor | `AndroidTeamRuntime.resolveOrchestratorEntry()` |

Yani bugünkü ekip: **sıralı iş dağıtımı**. Kullanıcının istediği şey — tartışma,
karşılıklı soru, fikir alışverişi — bugünkü kodda *yok*; `ask_teammate` cümlesi
promptta durduğu için üye onu çağırıyor ve `Unknown tool` alıyor.

---

## 2. Sözlük

| Ad | Anlam |
|---|---|
| **Ekip** | Kalıcı tanım: isim + orkestratör modeli + üyeler (model, rol, araç, kapsam) |
| **Koşu (run)** | Bir ekibin bir görev için tek yürütmesi. Kalıcı kimlik: `run_<8hex>` |
| **Tur (round)** | Bütün üyelerin bir kez konuştuğu dilim |
| **Pozisyon** | Üyenin o turdaki yapılandırılmış cevabı (iddia + gerekçe + itiraz + soru) |
| **İddia (claim)** | Üyenin savunduğu tek cümlelik önerme. Kimliklendirilir: `C1`, `C2` |
| **Tutum (stance)** | Bir üyenin bir iddiaya karşı duruşu: `support` / `oppose` / `uncertain` |
| **Kanıt (E#)** | Tahtaya yazılmış kaynak: URL + alıntı + kim + ne zaman |
| **Başkan (chair)** | Pozisyonları kanonik iddia listesine indirgeyen, tartışmayan model rolü |
| **Sentezci** | Sonucu yazan üye. Başkan **olamaz** |
| **Mutabakat** | Hiç itirazı olmayan ve hiç `uncertain` içermeyen iddia kümesi |
| **Azınlık raporu** | Yakınlaşmayan itirazların, sahibinin ağzından korunması |

---

## 3. Üç mod

`Team.mode` alanı (varsayılan `handoff`, mevcut davranış bozulmaz).

| Mod | Ne yapar | Ne zaman | Yaklaşık çağrı |
|---|---|---|---|
| `handoff` | Bugünkü: orkestratör planlar, tek tek devreder | yapılacak iş (dosya, derleme, düzeltme) | 6–12 |
| `fanout` | Aynı görev N üyeye **bağımsız**, sonuçlar yan yana; konuşma yok | "üç model ne diyor" | N |
| `council` | Aşağıdaki tartışma protokolü | karar, karşılaştırma, araştırma, tasarım | 3·tur + 2 |

`team_run` aracına `mode` parametresi eklenir; verilmezse ekibin kayıtlı modu.
Araç açıklaması mode seçimini bir **karar kuralı** olarak yazar (mevcut
`team_run` vs `subagent_task` ayrımıyla aynı üslup):
"nesne üret → handoff · kıyasla → fanout · karar ver/tartış → council".

---

## 4. Konsey protokolü — adım adım

```
run(task, team)
 ├─ 0. BRİF                     (model çağrısı YOK, deterministik)
 ├─ 1. BAĞIMSIZ TUR  (r=1)      N üye paralel, araç serbest
 ├─ 2. KANONİKLEŞTİR            başkan: pozisyonlar → iddia matrisi
 ├─ 3. YAKINLAŞMA KONTROLÜ      deterministik aritmetik → dur? devam?
 ├─ 4. TARTIŞMA TURU (r≥2)      N üye paralel, özet + itiraz + soru
 ├─ 5. SORU KUYRUĞU             sorular adreslerine dağıtılır
 ├─ 6. SENTEZ                   sentezci yazar
 ├─ 7. DOĞRULAMA                deterministik (atıf/dosya) → gerekirse 1 onarım turu
 └─ 8. RAPOR                    sonuç + azınlık + kanıt tablosu + sayaçlar
```

### 0. Brif — deterministik
Her üyeye **tıpatıp aynı** metin verilir. Farklı brif = farklı soru = tartışma değil.

```
TASK: <kullanıcı görevi>
TESLİM: <ne üretilecek — metin/karar/plan>
KISITLAR: <bütçe, dil, kapsam, yasaklar>
BAŞARI ÖLÇÜTÜ: <bu cevap neyi sağlarsa bitti sayılır>
KURALLAR:
 - Kanıtlanabilir her olguyu kaynağıyla ver. Kaynak yoksa "kanıtsız" yaz.
 - Rakip görüşü tahmin etme; kendi gerekçene odaklan.
 - Cevabını yapılandırılmış JSON olarak ver.
```

### 1. Bağımsız tur
N üye **paralel** çalışır (dalga kuralı §9). Kimse kimsenin cevabını görmez —
bu, sürü etkisini (herkesin ilk konuşana uyması) engelleyen tek kuraldır ve
Delphi yönteminin ilk adımıdır. Araçlar serbest: tarayıcı, shell, dosya, MCP.

### 2. Kanonikleştirme
Başkan (düşük sıcaklık, araçsız, JSON-only) N pozisyonu **aynı iddia kümesine**
indirger. Burada yaptığı iş yargı değil, **eşleme**: "A'nın 2. maddesi ile C'nin
1. maddesi aynı iddia → C1". Gerekçe: serbest metin üzerinden yakınlaşma
ölçülemez; ölçüm için ortak eksen şart.

### 3. Yakınlaşma kontrolü — model değil, aritmetik
§6'daki kurallar. Model "yeterince anlaştık" diyemez; sadece matris hesaplanır.

### 4. Tartışma turu
Her üye şunları alır:
- brif (aynı)
- **kendi** önceki pozisyonu (tam)
- **diğerlerinin** pozisyonu — *kısaltılmış*: iddia cümlesi + madde başına
  `{id, stance, ≤120 karakter}`. Tam metin paylaşılmaz: (a) token, (b) düz yazıya
  takılma (anchoring).
- kendisine sorulmuş sorular
- kanıt tahtasından **bu turda eklenenler + en çok atıf alan 20 kayıt**

Üyenin yükümlülüğü, üç seçenekten birini **açıkça** yazmak:
`koruyorum` / `bırakıyorum` / `tersine çeviriyorum` + gerekçe. Sessiz fikir
değişikliği yok; tersine çevirme gerekçesizse matriste `unstable` işaretlenir.

### 5. Soru kuyruğu
İki yol, ikisi de gerçek:
- **Kuyruk (varsayılan):** pozisyon JSON'undaki `questions[{to,q}]` bir sonraki
  turda adresin önüne konur. Paralel, ucuz, yeniden giriş yok.
- **Canlı araç (`ask_teammate`):** üye gerçekten bloke olduğunda (bir olgu olmadan
  ilerleyemiyor). Senkron, tek soru–tek cevap, derinlik 1, turlu sınırlı (§8).

### 6. Sentez
Sentezci **başkan değil**; genellikle en güçlü model veya en az yük alan üye.
Girdi: brif + son iddia matrisi + kanıt tablosu + **karşı çıkanların tam metni**.
Çıktı bölümleri zorunlu:

1. **Cevap/Karar** — tek paragraf, doğrudan.
2. **Gerekçeler** — her madde `[E3]` atıflı ya da açıkça `kanıtsız`.
3. **Karşı görüş** — itiraz edenin cümlesi, kırpılmadan.
4. **Açık kalan** — çözülmemiş iddialar, "bilinmiyor" demek serbest.
5. **Yapılacaklar** — görev üretim istediyse.

### 7. Doğrulama
Üç katman (§11). Sentezci kendi cevabını onaylayamaz.

### 8. Rapor
`TeamRun` kaydı: tur sayısı, durma nedeni, iddia matrisi, kanıt tablosu,
üye başına token/araç/süre, azınlık raporu. Ekranda ve `team_run` çıktısında
aynı veriden iki farklı yoğunlukta gösterilir.

---

## 5. Yapılar

### 5.1 Üye pozisyonu (üyenin döndürdüğü tek JSON)

```json
{
  "claim": "tek cümlelik ana cevap",
  "points": [
    {"ref":"p1","text":"savunulan madde","stance":"support|oppose|uncertain",
     "basis":"evidence|reasoning|guess","evidence":["E3","https://..."]}
  ],
  "changes": [{"ref":"C2","from":"oppose","to":"support","why":"..."}],
  "questions": [{"to":"Üye adı","q":"tek soru"}],
  "falsifier": "beni ne yanıltır — tek cümle"
}
```

- `ref` üyenin kendi etiketi; kanonik `C#` başkandan gelir.
- `basis:"evidence"` + boş `evidence` → iddia otomatik `kanıtsız` sayılır.
- `falsifier` zorunlu: yanlışlanamayan iddia tartışmaya katkı sağlamaz, matriste
  `non_falsifiable` işaretlenir ve sentezde gerekçe olarak kullanılamaz.

**Parser toleransı** (mevcut `ProgressLedgerParser` üslubu): kod bloğu, JSON
etrafında düz yazı, tek tırnak, eksik alan → kurtarılabilen kurtarılır; ikinci
denemede de olmazsa o üye **o tur için kayıp** sayılır ve matriste `absent`
görünür (koşu durmaz, sessizce doldurulmaz).

### 5.2 İddia matrisi (başkan çıktısı)

```json
{"claims":[
  {"id":"C1","text":"...","stances":{"Ada":"support","Bor":"oppose","Cem":"uncertain"},
   "evidence":["E3","E7"],"disputed":true}
]}
```

### 5.3 Kanıt kaydı

```json
{"id":"E3","url":"https://...","title":"...","quote":"≤400 karakter",
 "member":"Ada","round":2,"fetched_at":1732000000000,"hash":"sha256(url+quote)"}
```

Tekilleştirme: normalize URL (şema+küçük harf+`?`/`#` at, sondaki `/` at) +
`quote` ilk 80 karakteri. Aynı kaynak ikinci kez yazılmaz, mevcut `E#` döner.

### 5.4 Dosya düzeni

```
filesDir/teams/<teamId>.json                  # tanım (mevcut, genişler)
filesDir/team-runs/<runId>/run.json           # koşu başlığı + bütçe + durum
filesDir/team-runs/<runId>/round-<n>.jsonl    # pozisyonlar (satır = üye)
filesDir/team-runs/<runId>/claims.json        # tur başına matris
filesDir/team-runs/<runId>/evidence.jsonl     # kanıt tahtası (append-only)
filesDir/team-runs/<runId>/report.md          # son rapor
```

Room **kullanılmaz**: şema geçişi yok, bozuk dosya atlanır, koşu silmek = klasör
silmek. `TeamStore`'un bugünkü disiplini aynen sürer (atomik yazma: `.tmp` → rename).

---

## 6. Durma kuralları (deterministik)

N = o turda pozisyon veren üye sayısı, `unan` = `uncertain` sayısı.

| Ölçüt | Tanım | Eşik |
|---|---|---|
| **contested** | aynı iddiada hem `support` hem `oppose` var | çözülmemiş sayılır |
| **majority** | bir tarafta ≥ ⌈0.6·N⌉ | sağlanırsa iddia "karara bağlı" |
| **unresolved** | `unan` ≥ ⌈0.5·N⌉ ya da majority yok | çözülmemiş |
| **stable** | matris bir önceki tura göre ≤1 hücre değişti | yakınlaşma |

Kararlar, sırayla:

1. `contested == 0 && unresolved == 0` → **MUTABAKAT**, senteze geç.
2. `stable` **iki tur üst üste** → **KISMİ MUTABAKAT** (itirazlar azınlık raporuna), senteze geç.
3. `round >= maxRounds` → **BÜTÇE/TUR** durdu, `kısmi` işaretle, sentezle.
4. `elapsed > maxMinutes` → **SÜRE** durdu.
5. `spentTokens > maxTokens` → **MALİYET** durdu.
6. Kullanıcı iptal → **İPTAL**, son yazılan pozisyonlarla istenirse sentez.

Yeniden planlama (mevcut `stall>=3`) konseyde **yok**: tartışma kendi kendini
besler, plan yoktur. `handoff` modunda bugünkü davranış aynen kalır.

---

## 7. Araştırma disiplini

Konseyin araştırma değeri, beş modelin aynı sayfayı beş kez indirmesinden değil,
**ortak kanıt havuzundan** gelir:

1. Üye kaynağı kendi bulur (tarayıcı/MCP/shell — kemer değişmez).
2. Bulduğunu pozisyon JSON'unda `{url, quote}` olarak bildirir.
3. Tahtaya yazılır, `E#` atanır. Aynı URL ikinci üyeden gelirse **yeni kayıt
   açılmaz**; üyeye "bu kaynak zaten E3 olarak tahtada" denir.
4. Sonraki turlarda herkes `E#` ile atıf yapar; tam metni tekrar okumaz.
5. Sentez yalnız tahtadaki `E#`lere atıf yapabilir. Tahtada olmayan atıf →
   doğrulama hatası (§11).

Sınırlar: tur başına üye başına **en çok 6 yeni kaynak**, koşu başına tahta
**120 kayıt**. Aşılırsa "kanıt tavanı doldu, mevcut kaynaklarla sentezle" denir —
sessizce kırpılmaz.

Ağ gerçeği: `raw.githubusercontent.com` bazı ağlarda engelli; bir kaynağa
ulaşılamazsa üye **"kaynağa ulaşılamadı"** yazmak zorunda. Sessiz tahmin, kanıt
gibi görünen uydurma demektir ve doğrulamada yakalanamaz — bu yüzden brifte açık.

---

## 8. `ask_teammate` — gerçek araç

Bugün promptta var, kodda yok (kanıt §1/3). Eklenecek sözleşme:

```json
{"tool_title":"...","name":"Ada","question":"tek, kapalı uçlu soru"}
```

| Kural | Değer | Neden |
|---|---|---|
| Yalnız ekip üyesi oturumunda | `teamPeerNames` doluysa tanımlanır | görünmeyen araç çağrılamaz |
| Derinlik | **1** — cevaplayan soru soramaz | sonsuz soru sarmalı |
| Cevap modu | araçsız, geçmişsiz, 60 sn, ≤400 token | maliyet ve gecikme |
| Kota | üye/tur: 3 · koşu: 12 | bütçe |
| Aynı soru | (soran, hedef, normalize soru) tekilleştirilir | döngü engeli |
| Zaman aşımı | cevap yok → `"cevap gelmedi"`, üye kendi karar verir | koşu durmaz |
| Kayıt | `TeamEvent.PeerAsked/Answered` → ekranda görünür | denetlenebilirlik |

Kuyruk yolu (pozisyon JSON'u) varsayılan; araç **istisna**. İkisi de aynı
`TeamBoardStore` üzerinden yazılır ki tek yerden sayılabilsin.

---

## 9. Eşzamanlılık, bellek, sekme, kota — gerçek tavanlar

Bugünkü sabitler (kodda):

| Kaynak | Tavan | Konsey için anlamı |
|---|---|---|
| `ChatViewModelStore.MAX_CACHED_CHILD` | 6 | 5 üye + ebeveynin kendi 3 alt ajanı = 8 canlı VM → bellek |
| Meşgul oturum koruması | var (`busyKeys`) | çalışan üye VM'i **tahliye edilmez** — güvenli |
| `BrowserTabPoolRegistry.GLOBAL_TAB_CAP` | 8 | 5 üye × 2 sekme = 10 → **tavan aşılır** |
| `AgentJobRegistry` alt iş | 3 eşzamanlı | üyeler bu yoldan geçmiyor (HeadlessChatRunner) → bağlayıcı değil |
| Aynı sağlayıcı örneği | 429 riski | 5 üye tek örnekte = kota 5'e bölünür |

Kararlar:

- **Dalga (wave) yürütme.** Bir turda eşzamanlı üye = `waveSize`.
  Varsayılan **3**; `isLowRamDevice` → **2**; RAM ≥ 8 GB ve kullanıcı açıkça
  izin verdiyse **4–5** (§24.5: küresel tavan 6, biri kullanıcıya ayrılır).
  5 üye × dalga 3 = 2 dalga → tur gecikmesi ~2×.
- **Sekme bütçesi.** Konsey koşusu açıkken üye başına **1** sekme, koşu toplamı
  **6**; tur bitince üyenin sekmeleri `evictIdle`. Ekip modu açıkken normal
  sohbet havuzu 2'ye düşer (İstasyon mimarisindeki aynı kural).
- **Sağlayıcı dağılımı.** Editörde uyarı: aynı örnekten >2 üye → "kota ikiye
  bölünür" (engel değil, bilgi). 429 gelirse o üye dalgadan düşer, sonraki
  dalgada yeniden denenir; koşu durmaz.
- **Bellek bütçesi.** 3 eşzamanlı üye + 3 sekme ≈ 350–450 MB. 5 eşzamanlı ≈ 600 MB+.
  Bu yüzden 5 varsayılan değil, **izinle**.

---

## 10. Maliyet

Kaba hesap (etiket: tahmin, tek tur ≈ 2–4 sağlayıcı isteği: araç çağrıları dahil):

| Preset | Üye | Tur | Model çağrısı (yaklaşık) |
|---|---|---|---|
| **Hızlı** (varsayılan) | 3 | 2 | 3·2 + 2 başkan + 1 sentez ≈ **9** |
| **Derin** | 5 | 4 | 5·4 + 4 + 1 ≈ **25** |
| **Gözden geçirme** | 3 (1 yazar + 2 eleştirmen) | 2 | ≈ 8 |

Bütçe alanları `TeamBudget`'a eklenir: `maxTokens` (koşu toplamı),
`maxMemberTokens`, `maxQuestions`. Sayaç `ProviderResponse.usage`'tan okunur,
üye başına toplanır, **aşıldığında tur bitmeden durdurulur** ve rapora yazılır.
Sağlayıcı kullanım bilgisi vermiyorsa tahmini sayı (karakter/4) kullanılır ve
rapora "tahmini" notu düşülür — uydurma kesinlik yok.

---

## 11. Doğrulama — üç katman

Bugün tek katman var ve o da modelin kendi görüşü (`TeamLoop.verify`). Yerine:

**K1 — Deterministik (model yok).** Her sentezde:
- `[E#]` biçimindeki her atıf `evidence.jsonl` içinde var mı?
- `basis:"evidence"` diyen her maddenin en az bir `E#`i var mı?
- Metinde geçen her dosya yolu (`/var/minis/...`, `minis://...`) var mı?
- `claim` boş mu, `falsifier` yazılmış mı?
Hata listesi deterministik; onarım turuna **madde madde** gider. Bir tur sonra
hâlâ hata varsa rapor `DOĞRULANMADI` damgasıyla çıkar — "başarılı" denmez.

**K2 — Kabul ölçütleri (`TaskChecks`).** Uygulamada zaten var
(`data/verify/TaskChecks.kt`, `VerifyTool`), ekipte kullanılmıyor. Konseyde:
yazar model koşu başında ölçütleri yazar, **bakan üye** (yazardan farklı) çalıştırır.
Ölçüt yoksa K2 atlanır ve bu raporda görünür (sessizce "geçti" sayılmaz).

**K3 — Çapraz hakem.** İtirazı olan bir üye (varsa), sentez metnini görür ve tek
JSON verir: `{"holds":true,"broken":["C2"],"why":""}`. `holds:false` → tek onarım
turu; yine `false` → karşı görüş raporda baş köşeye taşınır.

Katmanlar **birbirinin yerine geçmez**; üçü ayrı satır olarak raporda görünür:
`atıf: ✓ · ölçüt: 3/3 · hakem: itiraz var`.

---

## 12. Arayüz — Tartışma ekranı

Yeni rota: `TEAM_RUN` (`ui/team/CouncilScreen.kt`). `SettingsScaffold` **kullanılmaz**
(çift `verticalScroll` tuzağı); düz `LazyColumn` + sabit alt eylem çubuğu.

Yerleşim:
- **Üst:** görev başlığı, mod rozeti, sayaç (`tur 2/4 · 1.8k token · 04:12`),
  durum noktası (çalışıyor / duraklatıldı / bitti).
- **Şerit:** üye kartları yatay; her kartta model adı, o anki araç, akan metin
  (ilk 3 satır), tutum rozeti. Karta dokun → o üyenin canlı oturumu.
- **Gövde:** tur tur zaman çizelgesi: `İddia` · `Destek` · `İtiraz` · `Soru` ·
  `Kanıt` olayları, `TeamEvent` sırasıyla.
- **Sekmeler:** `Matris` (iddia × üye tablosu, tutum renkleri) · `Kanıt` (E# listesi,
  link + alıntı + kim buldu) · `Rapor`.
- **Alt çubuk:** `Duraklat`, `Bir tur daha`, `Yönlendir` (steer), `Sentezle`,
  `İptal`.

Canlılık kaynağı: üyeler child `ChatViewModel` olduğu için `messages` ve
`streamingById` akışları **zaten var**; ekran `ChatViewModelStore.ownerFor(sessionId,
CHILD)` ile salt-okunur bağlanır. Ek paralel altyapı yok — mevcut akışın ikinci kullanımı.

`team_run` iki biçim kazanır:
- `wait=true` (varsayılan, bugünkü) — sohbetten çağrıldığında bloklar, tek rapor döner.
- `wait=false` — `runId` döner, iş arka planda sürer, ekran açılabilir, bitince
  ebeveyn oturuma yeni mesaj düşer (mevcut alt ajan bitiş bildirimi deseni).

Bildirim: koşu arka plandayken biterse `android-notification` üzerinden tek bildirim.

---

## 13. Dayanıklılık

| Durum | Davranış |
|---|---|
| Süreç öldü / uygulama kapatıldı | `run.json` + `round-*.jsonl` diskte; açılışta "yarım koşu" bulunur → `Devam et` / `Sentezle` / `Sil` |
| Tur ortasında iptal | o turun yarım pozisyonları `partial:true` ile yazılır, matrise girmez |
| Üye modeli silinmiş | üye `absent`, koşu devam (bugünkü davranış korunur) |
| Sağlayıcı 429/403 | üye dalgadan düşer, sonraki dalgada 1 kez yeniden dener |
| Başkan JSON üretemedi | 2 deneme → o tur matrise girmez, `stable` sayılmaz, tur tekrarı |
| Zombi üye oturumu | koşu bitince `parentSessionId` ile child oturumlar temizlenir (bugün temizlenmiyor) |
| Tarayıcı tavanı | tur sonunda üye sekmeleri boşaltılır, arka plan sekmeleri korunmaz |

---

## 14. Dilimler — 11.5 hattı (Konsey katıldı)

Kullanıcı kararı: **Konsey 11.5'in içine katıldı.** Dört madde ertelendi, numaraları
Konsey'e devredildi:

- ertelenenler → `11.7`: **İstasyon panelleri**, **eklenti DEX hattı**, **eklenti APK**,
  **kapılı kendi-kurulum**. (Silinmiyor, yalnız sıra değişti.)
- korunanlar: görünür kuyruk, projeler + CapabilitySet, Java 17 sabitleme,
  ücretsiz/limitli gruplar, düzenleme kalemi, sayaç temizliği.

Kural aynı: her dilim ayrı commit, **CI yeşil değilse sonrakisi yok**. Derlemeler
sonda toplu/paralel yapılabilir.

| # | versionCode | Sürüm | İçerik | Kabul kriteri |
|---|---|---|---|---|
| 1 | 37034 | 11.5 | **Fanout modu** (planlı S3): aynı görev N üyeye bağımsız, sonuçlar yan yana, konuşma yok | 3 üye paralel, tek dalga; kıyas ekranı |
| 2 | 37035 | 11.5.1 | **Görünür kuyruk**: kullanıcı görevleri ayrı tur, kalıcı, sırala/iptal/şimdi çalıştır | Uygulama kapanıp açılınca kuyruk duruyor |
| 3 | 37036 | 11.5.2 | **Projeler + CapabilitySet**: klasör → yetenek seti her oturuma uygulanır | Proje açılınca araç kemeri değişir |
| 4 | 37037 | 11.5.3 | **Konsey çekirdeği** ✅ AŞAMA 1: `CouncilModels`, `StanceMath`, `PositionParser`, `ClaimMatrixParser`, `CitationAudit`, `CouncilPrompts`, `CouncilPolicy`, `CouncilLoop` + 35 test | Sahte üyelerle mutabakat / kısmi / bütçe / absent / atıf |
| 5 | 37038 | 11.5.4 | **`ask_teammate` + üye kuyruk slotu**: araç (`teamPeerNames`), `TeamPeerRegistry`, derinlik/kota; üye artık gönderme kuyruğu slotunu **tutmaz**. **DÜZELTME (bkz. §23):** bu dilimde üyeye `subagent_task` **kapatılmaz** — üye üçüncü bir türdür, kendi alt ajanını açabilir. Kemer ayrımı 37060'a taşındı | Kota/derinlik testleri; kuyruk slotu tutmayan üye |
| 6 | 37039 | 11.5.5 | **Koşu deposu + tartışma ekranı (salt okunur)**: `TeamRunStore` (StateFlow + disk), `CouncilScreen` (üye şeridi, matris, kanıt, rapor) | Ekran donmadan N üyeyi gösterir; döndürmede durum korunur |
| 7 | 37040 | 11.5.6 | **Java 17 sabitleme** (planlı): cihaz/CI JDK 21 kırılganlığına karşı araç zinciri 17'ye çakılır | CI'da tek JDK; `preflight` yeşil |
| 8 | 37041 | 11.5.7 | **Kanıt tahtası + digest**: `TeamBoardStore` (atomik), URL tekilleştirme, tur özeti üretici, `E#` atama, `unverified` işareti | Tekilleştirme + bozuk dosya + digest token tavanı |
| 9 | 37042 | 11.5.8 | **Dalga yürütücü + cihaza göre derinlik**: eşzamanlılık, 429 geri çekilme, üye token sayacı, iptal yayılımı, §9 politikası | 5 üye / dalga 3 → 2 dalga; iptal hepsini durdurur; lowRam'de dalga 2 |
| 10 | 37043 | 11.5.9 | **Doğrulama K1+K2+K3**: atıf/dosya denetimi, `TaskChecks` bağlama, hakem turu, onarım turu | Eksik `E9` → DOĞRULANMADI; ölçüt 2/3 → onarım turu |
| 11 | 37044 | 11.5.10 | **Ücretsiz/limitli gruplar + maliyet paneli** (planlı madde birleşti): model seçicide ücretsiz/limitli/ücretli, üye ve örnek bazında token, tahmini ücret, bütçe aşımında durdurma | Bütçe aşılınca koşu durur, rapor nedeni yazar |
| 12 | 37045 | 11.5.11 | **Düzenleme kalemi** (planlı): her ekranda işaret, not olarak kaydet, ajan görür | Kapalıyken hiçbir giriş noktası kalmaz |
| 13 | 37046 | 11.5.12 | **Sayaç temizliği** (planlı): örnek silinince sayaç/kota kaydı silinir | Silinen örnek hiçbir listede görünmez |
| 14 | 37047 | 11.5.13 | **Müdahale**: `team_run(wait=false)`, duraklat/devam, bir tur daha, steer, erken sentez, iptal | Her komut `TeamEvent` üretir ve diske yazılır |
| 15 | 37048 | 11.5.14 | **Kesintiye dayanıklılık**: yarım koşu devam/sentez/sil, zombi üye oturumu temizliği, bitiş bildirimi | Süreç öldürülüp açılınca koşu kaldığı yerden sürer |
| 16 | 37049 | 11.5.15 | **Şablonlar + hub**: hazır ekipler (Araştırma masası, Kod inceleme, Ürün kararı), hub satırı, özellik anahtarı | Anahtar kapalıyken giriş gizlenir, veri silinmez |

**Neden bu sıra:** 1–3 kullanıcının zaten onayladığı işler, öne alınmış kalemler.
4–5 motor; 5 olmadan motor *güvensiz* (üye iç içe ekip açabilir), o yüzden izolasyon
erken ve `ask_teammate` ile aynı dilimde. 10 doğrulama olmadan 6. dilimdeki ekranı
tamamlamak yanlış sonucu güzel göstermek olur; bu yüzden ekran **salt okunur** çıkar,
müdahale (14) doğrulamadan sonra gelir.

**Toplam:** 16 dilim, 37034–37049. Tükenen: 37050 sıradaki boş numara.

> **DÜZELTME (2026-11-17, ikinci tur):** Bu tablodaki 5. dilim ilk yazıldığında
> üyeyi "helper eşdeğeri" yapıp `subagent_task`'ı kapatıyordu. Kullanıcı her üyenin
> **kendi alt ajanlarını** açmasını istedi; §23 bunu üçüncü bir ajan türü olarak
> tanımlıyor ve kemer ayrımı **37060**'a taşındı. 37038 yalnız `ask_teammate` +
> kuyruk slotu düzeltmesi olarak kalıyor. Yanlış gerekçe şuydu: "üye helper gibi
> olmalı" — doğrusu "üye ne üst düzey ne helper". 

### Durum (canlı)

Güncel kod durumu **§26'nın başındaki tabloda** — tek yerde tutuluyor, burada
ikinci bir kopya tutmak iki tablonun ayrışması demekti.

`TeamBudget` yerine ayrı **`CouncilBudget`** kullanıldı: birimleri farklı — handoff
turla sınırlı, konsey tur **ve** token'la. Tek tipe iki farklı kısıt koymak,
handoff'un token alanlarını sessizce yok sayması demekti.

---

## 15. Riskler ve tuzaklar

1. **İç içe ekip.** Üye `helperConfig` taşımadığı için bugün `team_run`
   çağırabiliyor → derinlik 2, bütçe kimsede değil, maliyet patlar. S5 bunu kapatır.
   S5 öncesi konsey **üretimde açılmaz**.
2. **Sekme tavanı.** 5 üye araştırırsa `GLOBAL_TAB_CAP=8` aşılır → sekmeler
   birbirini tahliye eder, üye "sayfa kayboldu" der. §9'daki bütçe şart.
3. **Aynı sağlayıcı.** 5 üye tek örnekte = 429. Editör uyarısı + dalga düşürme şart.
4. **Bellek.** 5 eşzamanlı üye orta seviye telefonda sınırda; `isLowRamDevice` →
   dalga 2, üye tavanı 3.
5. **Sürü etkisi.** İlk turda özet paylaşılırsa herkes birinciye uyar → 1. tur
   **her zaman** bağımsız, özet yok. Bu kural pazarlıksız.
6. **Uydurma kanıt.** Model URL uydurur. K1 yalnız "tahtada var mı" der; tahtaya
   yazılan URL'in gerçekten açıldığını **araç katmanı** garanti eder: `E#` yalnız
   `browser_use`/MCP sonucundan gelen URL'ler için otomatik atanır; üyenin elle
   yazdığı URL `unverified:true` işaretlenir ve sentezde "doğrulanmamış kaynak"
   olarak görünür.
7. **Türkçe uzunluk.** 5 modelin 4000 karakterlik raporu = tek ekranda okunmaz.
   Rapor katmanlı: 6 satır özet → katlanır detay.
8. **Test edilebilirlik.** Gerçek model testte yok; `TeamOrchestrator`/`TeamWorkerRunner`
   arayüzleri (bugün var) sahte üyelerle beslenir; saat enjekte edilir.
9. **`preflight.py` / `audit_all.py`** her dilimde koşar; yeni çeviri anahtarları
   EN+TR zorunlu (CMP004 uyarısı hataya dönüşmesin).

---

## 16. Kararlar (verildi) ve çalışma rejimi

**Kullanıcı kararları (2026-11-17):**

1. **Sıra:** Konsey **11.5'in içine katıldı** → 37034–37049, 16 dilim (§14).
   İstasyon panelleri, eklenti DEX hattı, eklenti APK, kapılı kendi-kurulum 11.7'ye.
2. **Derinlik:** **cihaza göre otomatik.** `isLowRamDevice` → 3 üye / 2 tur ·
   orta cihaz → 3 üye / 3 tur · RAM ≥ 8 GB → 5 üye / 4 tur. Kullanıcı ekip
   editöründen sabitleyebilir; otomatik karar her zaman gerekçesiyle gösterilir
   ("cihaz 4 GB → 3 üye").
3. **Soru yolu:** **ikisi birlikte.** Yapılandırılmış soru kuyruğu (çekirdekte,
   bedava) + gerçek `ask_teammate` aracı aynı dilimde: 37038.

**Çalışma rejimi — bu bir kısıt, tercih değil:**

- **Derleme YOK.** Derleme yalnız GitHub Actions'ta ve yalnız kullanıcı "derle"
  dediğinde. Özellikler hazırlanana kadar tek bir push/release/CI tetiklemesi
  yapılmaz. Plan hazırlığı, kod yazımı ve yerel statik doğrulama
  (`preflight.py`, `audit_all.py` — bunlar derleme değil, dosya denetimi) serbest.
- Yani 16 dilim **önce kodlanır**, numaraları ayrılır, hiçbiri yayınlanmaz;
  sonda tek seferde toplu derleme + yayın. Bu, sürüm numarasını harcamaz:
  yayınlanmayan kod numara yakmaz, yalnız APK çıkmış numara yanar.
- Cihazda JDK 17 ile Kotlin derlemesi **zaten imkânsız** (kotlinc yok), yani
  "yerelde derleyip öyle gönderelim" diye bir yol da yok. Tek derleyici CI.

---

## 17. Toplantı Salonu ve Görev Salonu

İki ayrı yer, karıştırılmaması şart:

| Yer | Nedir | Ömür |
|---|---|---|
| **Toplantı salonu** (meeting hall) | Bir koşunun canlı odası. Gündem, konuşma, kararlar | Koşu ile başlar, koşuyla kapanır (ama kaydı kalır) |
| **Görev salonu** (task hall) | Bütün koşuların görev/karar listesi | Kalıcı. Uygulama kapansa da durur |

Bugünkü planda `37039` "koşu deposu + tartışma ekranı (salt okunur)" diyordu.
Bu, salonun **iskeleti**ydi. Yeni tanım daha büyük: salon bir *sohbet ekranı*
değil, bir **toplantı odası** — üç yüzeyi var.

### 17.1 Toplantı salonunun üç yüzeyi

**Gündem (agenda)** — tartışılan maddeler ve onlara bağlı görevler.
Her satır: `C1 · "şifreleme olmadan olmaz" · 3 destek / 1 itiraz / 1 kararsız ·
G3 (Ada, çalışıyor)`. Yani kullanıcı "kim neyi savunuyor, kim neyi yapıyor"
sorusunu tek listede görür. Bu, "görevleri görebileceğimiz" isteğinin karşılığı.

**Kürsü (floor)** — canlı konuşma. Üye kartları yatay şeritte; her kartta o anki
araç, akan metin (ilk 3 satır), tutum rozeti. Karta dokun → o üyenin tam oturumu
(mevcut child sohbet ekranı, salt-okunur bağlanır).

**Karar (decisions)** — bittiğinde: cevap, gerekçeler, **karşı görüş (kırpılmamış)**,
çözülmemişler, yapılacaklar, kanıt tablosu. Kullanıcı buradan tek tuşla
"yapılacakları görev salonuna aktar" der.

### 17.2 Görev salonu

Koşu bitince yapılacaklar buraya düşer:

```
Görev salonu
├─ Açık (5)          ← yapılacak, sahibi belli
├─ Yapılıyor (1)     ← bir üye/koşu üstünde
├─ Bitti (12)        ← kanıtlı
└─ Düştü (2)         ← yapılamadı, nedeni yazılı
```

Her görev: `id`, başlık, sahip (üye veya "kullanıcı"), durum, kaynak koşu,
kaynak iddia (`C3`), kanıt (dosya/URL/test çıktısı), oluşturan (kim verdi).

"Bitti" işareti **kanıt ister**: dosya yolu, URL, ya da test çıktısı. Kanıtsız
"bitti" işaretlenemez — aksi hâlde görev salonu bir istek listesine dönüşür,
ki bugün birçok ajan arayüzünün ölümcül hatası tam budur.

---

## 18. Görev verme — üyeler birbirine iş verebilecek

Kullanıcının cümlesi: *"birbirlerine görevler verebilecek"*. Bunu iki yolla
karşılıyoruz ve ikisi **farklı** şeyler:

### 18.1 Kuyruk yolu (varsayılan, bedava)

Pozisyon JSON'una `tasks` alanı eklenir:

```json
{"tasks":[{"to":"Ada","title":"fiyat sayfasını doğrula","why":"C3 buna bağlı",
           "expect":"URL + tarih"}]}
```

Bu, sonraki turda hedefin **görev listesine** düşer ve gündemde görünür. Kimse
bloklanmaz, kimse beklemez, ek maliyet yok. Üye "ben bunu yapmam" derse görev
`düştü` olur ve nedeni yazılır.

### 18.2 Canlı araç yolu — `assign_task`

Üye gerçekten bir işin yapılmasını istiyor ama kendisi yapamıyorsa (başka
modelin uzmanlığı, başka aracın yetkisi) araçla verir:

```json
{"tool_title":"...","to":"Bor","title":"bu SQL'i çalıştır","expect":"satır sayısı"}
```

| Kural | Değer | Neden |
|---|---|---|
| Yön | üye → üye, **derinlik 1** | görev veren görev alamaz zincirlemesi yok |
| Kendine görev | yasak | kendi kendine iş üretip "yaptım" demenin önü |
| Kota | üye/tur 2 · koşu 15 açık görev | sınırsız görev listesi = yapılmayan iş |
| Yürütme | **araç yürütmez**, tahtaya yazar | anında çalıştırmak eşzamanlılığı ve bütçeyi bozar |
| Kapanış | ancak hedef `done` + kanıt ile | "verdim" ≠ "yapıldı" |
| Görünürlük | gündemde + `CouncilEvent.TaskAssigned` | denetlenebilirlik |

`ask_teammate` ile ayrımı net: **soru** cevap ister (senkron, tek tur),
**görev** iş ister (asenkron, tahtaya düşer). İkisi aynı araca sığmaz; sığsaydı
model hangisini istediğini ayırt edemezdi.

### 18.3 Başkanın görev üretmesi

Başkan iddia matrisini üretirken `C#` maddelerinin yanına `needs_task: true` işaretleyebilir
(kanıt eksik, ölçüm yok). Bu iddialar için koşu **otomatik görev açar** ve en uygun
üyeye atar (rol eşleşmesi: `researcher` → araştırma, `engineer` → çalıştırma).
Atama yapılamazsa görev "sahipsiz" olarak gündemde durur — sessizce düşmez.

---

## 19. Hafıza — üç katman, üç farklı ömür

Kullanıcının istediği: **grup hafızası** + **her modelin ayrı hafızası**.
Bugünkü kodun gerçeği bunu desteklemiyor, o yüzden önce sorunu yazıyorum:

> `MinisApp.kt:596` → `MemoryRepository(File(filesDir, "minis-global/memory"))`.
> **Tek dizin.** `memory_write` bugünkü hâliyle beş üyenin de aynı günlüğe
> yazması demek. Yani "beş modelin ayrı hatırası" değil, "beş modelin tek
> hatırası, birbirine karışmış" olur.

Buna karşılık **notlar zaten kapsamlı**: `NoteStore.visibleTo(scope)` — her not
bir `scope` taşıyor, `GLOBAL` herkese görünüyor. Doğru desen bu; hafıza da
notların desenine geçmeli, tersi değil.

### 19.1 Üç katman

| Katman | Kapsam anahtarı | İçinde ne var | Ömür |
|---|---|---|---|
| **Grup hafızası** | `team:<teamId>` | Verilen kararlar, çürütülen yollar, ekip kuralları, kullanıcı tercihleri | Ekip silinene kadar |
| **Üye hafızası** | `member:<teamId>:<ad>` | Kendi notları: "X konusunda yanıldım", "şu kaynağı güvenilmez buldum", kendi uzmanlık notları | Üye ekipten çıkana kadar |
| **Koşu bağlamı** | `run:<runId>` | Transkript, tahta, matris | Koşuyla ölür (kayıt kalır) |

Üç kural:

1. Üye **kendi** hafızasını okur/yazar. Grup hafızasını **okur**, yazmak için
   öneri sunar (`propose`), grup hafızasına yazma yetkisi **sentezcinin** ya da
   deterministik kuralın. Sebep: beş modelin serbestçe yazdığı ortak not, üçüncü
   turda çöp olur.
2. Üye başka üyenin hafızasını **göremez** (kapsam duvarı). İstasyon mimarisindeki
   "alanlar birbirine değmiyor" kuralının hafıza karşılığı.
3. Grup hafızasına yazılan her satır **kaynaklı**: hangi koşu, hangi iddia.

### 19.2 Neyin gruba, neyin üyeye yazılacağı

Bu ayrım keyfî değil; yanlış tarafa yazmak zarar veriyor:

| Bilgi | Nereye | Neden |
|---|---|---|
| "Kullanıcı Türkçe istiyor" | **Grup** | ekibin tamamını bağlar |
| "Bu repoda testler `./gradlew test`" | **Grup** | her üye aynı şeyi keşfetmesin |
| "Şu API 2024'te kaldırıldı" | **Grup** | olgu, kişisel değil |
| "Ben fiyat konusunda zayıfım, Cem'e sorayım" | **Üye** | kişisel, diğerlerini bağlamaz |
| "Bu kaynağı iki kez yanlış buldum" | **Üye** | kendi kalibrasyonu |
| Ham tartışma metni | **Koşu** | ölünce gitsin, hafızayı şişirmesin |

### 19.3 Saklama ve unutma

- Grup hafızası: `64 KB` / ekip. Aşılırsa en eski **kaynaklı** satır düşer
  (kaynaksız satırlar önce düşer — düşük değerli olan onlar).
- Üye hafızası: `16 KB` / üye.
- Tekilleştirme: normalize metin + kaynak anahtarı.
- `forget` komutu: ekip bazında, üye bazında, tek satır bazında.
- Sırlar **yazılmaz**: hafıza yazımı `SecretGuard` tarifesinden geçer
  (mevcut `check_token_scope.py` kuralıyla aynı aile). API anahtarı, jeton,
  parola içeren satır reddedilir ve nedeni rapora yazılır.

### 19.4 Prompt'a giren kısım

Hafıza "hepsi birden" konmaz — bağlam şişer:

- Grup hafızasından **en çok atıf alan + en yeni** 12 satır.
- Üye hafızasından **tamamı, en çok 16 satır** (zaten küçük).
- Her satır tek cümle; başlık + tarih.
- Toplam hafıza bütçesi: **1500 token**. Aşılırsa grup satırları kırpılır,
  üye satırları korunur (kişisel kalibrasyon daha değerli).

---

## 20. Üye bağlamı — her modelin kendi dünyası

Hafıza "geçmiş", bağlam "şu an". Ayrı tutuluyor çünkü ikisinin bozulma biçimi farklı.

Bugün: `AndroidTeamRuntime` her üye için **koşu başına** bir child oturum açıyor
ve turlar arasında yaşatıyor. Yani üye 2. turda 1. turu hatırlıyor — doğru.
Eksik olan üç şey:

**20.1 Bağlam bütçesi.** Üyenin geçmişi sınırsız büyür. Bugün `ContextSizeMeter`
var ama ekip üyesine bağlı değil. Kural: üye bağlamı `modelin bağlamının %60`ını
geçerse **özetle**: eski turlar tek paragrafa iner, son iki tur tam kalır.
Özetlemeyi üyenin kendisi yapar (kendi işini en iyi bilen), ama özet **tahtaya
yazılır** — yani kullanıcı neyin unutulduğunu görebilir.

**20.2 İzolasyon.** Üye oturumu bugün `helperConfig` taşımıyor
(`ChatViewModel.kt:1564`) — 37038'de kapanacak. Bağlam açısından anlamı:
üye `subagent_task` çağırabildiği sürece **başka bir bağlam** açıp kendi
penceresini dolaylı büyütebiliyor. İzolasyon bunu da kapatıyor.

**20.3 Bağlam paketi, tek yerden.** Üyenin gördüğü her şey tek bir `MemberContext`
nesnesinden üretilir:

```
MemberContext {
  brief          (tüm üyeler için aynı)
  ownHistory     (kendi son 2 turu, tam)
  ownSummary     (önceki turların kendi özeti)
  boardDigest    (iddialar + tutumlar, kısaltılmış)
  questions      (ona sorulanlar)
  tasks          (ona verilen görevler)
  ownMemory      (kendi hafızası)
  groupMemory    (ekip hafızasından seçilmiş 12 satır)
  scopeNote      (araç/kapsam sınırı)
}
```

Testte bu nesnenin **başka üyenin `ownHistory`'sini içeremediği** doğrulanır —
izolasyon iddiası bir yorum değil, `assert` olur.

---

## 21. Ekip arayüzleri ve geçişler

Bugün var: `TeamsScreen` (kadro listesi) → `TeamEditorScreen` (üye düzenleme).
Eksik: koşuya giriş, salona geçiş, üyeye geçiş, geri dönüş.

### 21.1 Rota haritası

```
Ekip (teams)
 └─ Ekip detayı (team_detail/{teamId})
     ├─ Üyeler        → düzenleme (mevcut ekran)
     ├─ Koşular       → geçmiş koşu listesi
     └─ Salon (team_hall/{runId})
         ├─ Gündem | Kürsü | Karar   (sekmeler)
         └─ Üye (team_member/{runId}/{memberName})
             └─ Tam sohbet (mevcut chat ekranı, salt-okunur)

Görev salonu (task_hall)          ← ana menüden ve salondan
```

### 21.2 Geçiş kuralları (bunlar test edilir)

1. **Salondan çıkış koşuyu öldürmez.** Arka tuşu salonu kapatır, koşu sürer;
   dönünce `TeamRunStore`'dan aynı durum gelir. (Bugünkü `team_run` bloklayan
   olduğu için böyle bir durum yok — 37047'de `wait=false` ile geliyor.)
2. **Üyeye giriş canlı oturumu açar**, kopyasını değil. `ChatViewModelStore.ownerFor`
   aynı VM'i vermeli; ikinci VM açmak üyenin akışını ikiye böler.
3. **Aynı koşu iki kez açılmaz**: `runId` anahtarlı tek ekran örneği.
4. **Derin bağlantı**: `minis://views/team_hall?run=run_ab12cd34` → koşu bitmişse
   karar sekmesi, sürüyorsa kürsü açılır.
5. **Bildirim → salon**: koşu arka planda bitince bildirime dokunmak salona,
   karar sekmesine götürür.
6. **Geri yığını**: Salon → Üye → geri = Salon. Salon → geri = Ekip detayı.
   Karar sekmesinden geri = salon (sekmeyi korur).
7. **Döndürme**: `rememberSaveable` + `TeamRunStore`; sekme ve kaydırma korunur.
8. **Çift `verticalScroll` yasağı** (bu kod tabanında bir kez patladı): salon
   `SettingsScaffold` **kullanmaz**; `LazyColumn` + sabit alt çubuk.

### 21.3 Geçiş maliyeti

Ekranlar arası geçişte üye VM'leri **tahliye edilmez** (`ChatViewModelStore`
CHILD havuzu, `MAX_CACHED_CHILD = 6`). 5 üyeli koşuda 5 slot dolu → ebeveynin
kendi alt ajanları için 1 slot kalır. Bu yüzden konsey koşusu açıkken
`subagent_task` çağrısı **uyarır**: "ekip koşusu sürüyor, alt ajan açmak üye
oturumunu tahliye edebilir". Engellenmez, bilgilendirilir.

---

## 22. Ek dilimler (37050 ve sonrası)

`37034–37049` (§14) aynen duruyor; bunlar arkasına ekleniyor. Mevcut iki dilim
kapsamı **büyüdü**, numarası değişmedi:

- `37039` → salonun **iskeleti** (salt okunur, tek sekme: kürsü).
- `37047` → müdahale (`wait=false`, duraklat/devam/steer/iptal).

| # | versionCode | Sürüm | İçerik | Kabul kriteri |
|---|---|---|---|---|
| 17 | 37050 | 11.5.16 | **Görev tahtası çekirdeği**: `TaskBoard`/`TeamTask` modeli, `TaskStore` (atomik, koşu bazlı), deterministik `done` kanıt kuralı | Kanıtsız `done` reddedilir; bozuk dosya atlanır; 15 test |
| 18 | 37051 | 11.5.17 | **Görev verme**: pozisyonda `tasks` alanı (kuyruk) + `assign_task` aracı (canlı, derinlik 1, kota) | Kendine görev reddi; kota; bilinmeyen hedef; 12 test |
| 19 | 37052 | 11.5.18 | **Başkanın görev açması**: `needs_task` işaretli iddialar için otomatik görev + rol eşleşmeli atama | Sahipsiz görev gündemde görünür; atama yanlış role gitmez |
| 20 | 37053 | 11.5.19 | **Hafıza kapsamı** (altyapı): `MemoryRepository`'ye `scope` parametresi, `filesDir/team-memory/<scope>/` düzeni, sır tarifesi | Üye A'nın yazdığı üye B'ye görünmez; API anahtarı içeren satır reddedilir; 14 test |
| 21 | 37054 | 11.5.20 | **Grup + üye hafızası**: `TeamMemory` (grup), `MemberMemory` (üye), öneri/yazma yetkisi ayrımı, `forget` | Sentezci grup hafızasına yazar, üye yalnız önerir; boyut tavanı düşürme sırası |
| 22 | 37055 | 11.5.21 | **Bağlam paketi**: `MemberContext` üretici, bütçe (%60 kuralı), kendi kendini özetleme, izolasyon testi | B üyesinin geçmişi A'nın bağlamında **hiç** yok; özet tahtaya yazılır |
| 23 | 37056 | 11.5.22 | **Toplantı salonu tam**: gündem + kürsü + karar, görev satırları, ilerleme rozetleri | 5 üye + 15 görev donmadan çizilir; sekme durumu döndürmede korunur |
| 24 | 37057 | 11.5.23 | **Görev salonu**: koşular arası kalıcı liste, durum akışı, kanıt önizleme, "yapılacakları aktar" | Yeni koşu eski görevleri görür; kanıtsız bitti yok |
| 25 | 37058 | 11.5.24 | **Arayüz geçişleri**: rota haritası, derin bağlantı, görünüm koruma, bildirim→salon, VM paylaşımı | §21.2'deki 8 kural ayrı ayrı test edilir |
| 26 | 37059 | 11.5.25 | **Ölçek**: üye sayısını çoğaltma (cihaz izin veriyorsa 5→7), dalga yeniden hesaplama, sekme/VM bütçesi | 7 üye lowRam'de reddedilir + nedeni gösterilir; RAM≥12 GB'de 2 dalga |

**Toplam:** 26 dilim, `37034–37059`. Sıradaki boş numara: **37060**.

### Neden bu sıra

- Görev tahtası (17–19) **motordan önce** geliyor çünkü `assign_task` motorun
  içine sonradan sokulursa tur döngüsü iki kez yazılır.
- Hafıza (20–22) görevden **sonra**: görev kaydı olmadan "neyi hatırlayalım"
  sorusunun cevabı yok. Hafızanın içeriği görevlerin ve kararların özetidir.
- Salon (23) en sona yakın: gündem sekmesi görev tahtası olmadan boş görünür,
  karar sekmesi hafıza olmadan her koşuda sıfırdan başlar.
- Geçişler (25) en sonda çünkü hedef ekranların var olması gerekir.

### Kapasite gerçeği (ölçek dilimi için)

| Üye | Dalga | Eşzamanlı VM | Sekme | Tahmini tepe bellek |
|---|---|---|---|---|
| 3 | 2 | 2 | 3 | ~250 MB |
| 5 | 3 | 3 | 5 | ~400 MB |
| 5 | 5 | 5 | 6 | ~600 MB |
| 7 | 5 | 5 | 6 | ~700 MB (VM 7, dalga 5) |

7 üye **desteklenir** ama yalnız RAM ≥ 8 GB'de ve dalga 5 ile; `MAX_CACHED_CHILD = 6`
tavanı 7. üye VM'ini tahliye edebilir → o üye turda `absent` olur. Bu yüzden 7
üye için `MAX_CACHED_CHILD` **8'e** çıkarılmalı (ve `GLOBAL_TAB_CAP` 8'de kalmalı,
sekmeler üye başına 1'e iner).

---

## 23. Üye yetenek yığını — her üye tam bir ajan

Kullanıcının isteği net: **her konsey üyesi** terminal, tarayıcı, görev takibi,
arka plan görevi ve **kendi alt ajanları** olsun; her alt ajanın da tarayıcı,
terminal, internet araştırması olsun.

Bu, §1'de tespit edilen tasarımı **değiştiriyor**: orada üyeyi helper'a benzetip
`subagent_task`'ı kapatmayı önermiştim. Doğrusu bu değil. Üye ne üst düzey
sohbet ne yardımcı — **üçüncü bir tür**.

### 23.1 Üç tür, üç kemer

Bugün `AgentTools.build(isHelper: Boolean)` iki değerli: üst düzey ya da yardımcı.
Üye bu ikisinin arasında bir yerde ve iki değerli bir bayrakla ifade edilemiyor.
Yeni: `AgentKind { TOP, MEMBER, HELPER }`.

| Yetenek | TOP (sohbet) | **MEMBER (konsey üyesi)** | HELPER (alt ajan) |
|---|---|---|---|
| `shell_execute` | ✓ | ✓ | ✓ |
| `browser_use` | ✓ | ✓ | ✓ |
| `file_read/write/edit` | ✓ | ✓ (kapsamına göre) | ✓ (kapsamına göre) |
| `memory_write` | ✓ | ✓ **kendi kapsamına** (§19) | ✗ |
| `memory_get` | ✓ | ✓ **kendi + grup** | ✓ |
| `note` / `journal` / `verify` | ✓ | ✓ | ✓ |
| `subagent_task` | ✓ derinlik 1→2 | ✓ **ama kota konseyin** (§24) | ✗ |
| `ask_teammate` | ✗ | ✓ derinlik 1 | ✗ |
| `assign_task` | ✗ | ✓ derinlik 1 | ✗ |
| `task_list` / `task_update` | ✓ (görev salonu) | ✓ **yalnız kendi görevleri** | ✗ |
| `team_run` | ✓ | **✗** | ✗ |
| `user_ask` | ✓ bloklar | ✓ **bloklamaz** → salona kart | ✗ |
| Terminal (interaktif) | ✓ | ✓ kendi `sessionId` | ✓ paylaşılan üye oturumu |
| Arka plan işi (`wait=false`) | ✓ | ✓ ama konsey kotasından | ✗ |

`team_run` üyeye kapalı olmasının sebebi derinlik değil **anlam**: üye zaten bir
ekibin içinde. İç içe ekip, bütçeyi ikiye böler ve raporun sahibi belirsizleşir.

`user_ask`'ın üye sürümü **bloklamaz**. Bloklarsa bir tur, kullanıcı telefonuna
bakmıyor diye 120 saniye durur ve beş üye boş bekler. Bunun yerine soru salona
kart olarak düşer, üye "cevap gelmedi" varsayımıyla devam eder, kullanıcı cevabı
yazarsa **sonraki turda** üyeye ulaşır. Yarım saat sonra gelen cevap da işe yarar.

### 23.2 Üye başına izolasyon — ne ayrı, ne ortak

| Varlık | Üye başına | Nasıl |
|---|---|---|
| Çalışma dizini | `spaces/sp_<member>/work` | `ExecutionCoordinator` zaten `sessionId` anahtarlı |
| Shell (`shell_execute`) | ayrı `sessionId` | aynı; **tembel** açılır, komut çalışmazsa hiç açılmaz |
| İnteraktif terminal | `TerminalSession.start(sessionId = sp_<member>)` | aynı |
| Tarayıcı sekmeleri | kendi `BrowserTabPool` | `BrowserTabPoolRegistry.register/unregister` |
| Alt ajanlar | kendi `parentSessionId`'si | ama **ortak** eşzamanlılık kotası |
| Görevler | yalnız kendine atananlar | tahtada filtre, kopya değil |
| Hafıza | `member:<teamId>:<ad>` | §19 |
| Notlar | `scope = sp_<member>` | `NoteStore.visibleTo` zaten bunu yapıyor |
| Araç nesneleri | ayrı | yarım kalan tarayıcı işlemi diğerini kilitlemesin |

Tüm bu ayrım **bedava**: bugünkü alt sistemlerin hepsi `sessionId` anahtarlı.
Eksik olan tek şey üyeye bu `sessionId`'yi vermek — yani `ExecutionCoordinator`,
`TerminalSession` ve `BrowserTabPool`'a üye kimliğini geçirmek. İstasyon
mimarisi (§ ISTASYON-MIMARISI) bunu panel için zaten tanımlamıştı; aynı mekanizma.

### 23.3 Rol → kemer eşlemesi

Her üyenin `role` alanı artık **yetenek setine** de karşılık gelir
(`TeamEditorScreen`'de hazır izin listesi):

| Rol | Varsayılan kemer | Neden |
|---|---|---|
| `researcher` | tarayıcı + arama + not + hafıza; **shell yok** | araştırma yapar, makineyi kurcalamaz |
| `engineer` | shell + dosya + tarayıcı + alt ajan | çalıştırır, test eder |
| `reviewer` | dosya okuma + tarayıcı + `assign_task`; **yazma yok** | inceler, düzeltmez |
| `critic` | dosya okuma + dosya okuma; araç minimal | görüş bildirir, iş yapmaz |

Kullanıcı bunu üye bazında değiştirebilir; değiştirmezse rolün varsayılanı.
Bugün `TeamMember.tools` alanı **saklanıyor ama uygulanmıyor** — bu dilimde
fiilen yürürlüğe giriyor.

---

## 24. Derinlik ve bütçe — telefonu öldürmeyen matematik

Kullanıcının istediği ağaç:

```
Konsey koşusu
└─ 5 üye (her biri tam ajan)
    ├─ terminal + shell
    ├─ tarayıcı (1 sekme)
    ├─ görev listesi
    └─ 0–2 alt ajan
        ├─ tarayıcı
        ├─ shell
        └─ (alt ajanın alt ajanı YOK)
```

Bu ağacı **sınırsız** kurmak 8 GB'lık telefonu 2 dakikada öldürür. Sınırlar
pazarlıksız ve gerekçeli:

### 24.1 Derinlik

| Seviye | Kim | Alt ajan açabilir | Neden |
|---|---|---|---|
| 0 | Kullanıcı sohbeti | ✓ | — |
| 1 | Konsey üyesi | ✓ | kullanıcının istediği |
| 2 | Üyenin alt ajanı | ✗ | 3. kat 4× bellek, 0 ek fayda: alt ajanın alt ajanı zaten aynı aracı kullanabilir |
| — | Konsey | ✗ (`team_run` yok) | iç içe ekip bütçeyi böler |

Derinlik 2'de doğal kilit var: alt ajan `helperConfig` taşır, `isHelper=true`,
`subagent_task` kemerde yok. Yani 3. kat **çağrılamaz**, "derinlik kontrolü"
diye ayrı bir mekanizma gerekmez — mevcut davranış zaten doğru.

### 24.2 Eşzamanlılık — kota üyenin değil, koşunun

Bu en önemli karar. `MAX_CONCURRENT_CHILD_JOBS_PER_PARENT = 3` üye başına
uygulanırsa 5 üye × 3 = **15 eşzamanlı alt ajan** olur. Telefon çöker.

Yerine: **konsey koşusu tek bir bütçe sahibidir.**

```
subAgentSlots(konsey) = 2  (lowRam) · 3 (orta) · 4 (RAM≥8GB)
```

Üye `subagent_task` çağırdığında slot yoksa **"bütçe dolu, sıraya alındı"**
cevabı alır ve görevi kendi görev listesine yazar (§18). Yani alt ajan isteği
kaybolmaz, **görev** olur. Bu, "her üye 3 alt ajan" ile "telefon yaşasın"
arasındaki tek gerçek uzlaşma.

Toplam alt ajan sayısı da sınırlı: **koşu başına 12 başlatma**. 13.'sü reddedilir.

### 24.3 Bellek ve kaynak tavanları

| Kaynak | Mevcut sabit | Konsey tüketimi | Karar |
|---|---|---|---|
| `ChatViewModelStore.MAX_CACHED_CHILD` | 6 | 5 üye + N alt ajan | **10'a çıkar**, ama koşu bitince hepsi `release` |
| `GLOBAL_TAB_CAP` | 8 | 5 üye × 1 + 2 alt ajan × 1 = 7 | **8'de kalır**, üye başına 1 sekme |
| Persistent shell | `ExecutionCoordinator` | üye başına 1, tembel | 60 sn boşta → kapat |
| İnteraktif terminal | `TerminalSession` | yalnız açan üye | 120 sn boşta → kapat |
| VM tahliyesi | meşgul korunur | çalışan alt ajan VM'i korunur | doğru, dokunulmaz |

Ölçülmüş tahminler (üye 30–50 MB, sekme 40–90 MB, shell 20–40 MB):

| Senaryo | VM | Sekme | Shell | Tepe bellek |
|---|---|---|---|---|
| 3 üye, alt ajan yok | 3 | 3 | 1 | ~250 MB |
| 5 üye, 2 alt ajan | 7 | 7 | 2 | ~520 MB |
| 5 üye, 4 alt ajan | 9 | 7 | 4 | ~650 MB |
| 7 üye, 4 alt ajan | 11 | 7 | 4 | ~780 MB |

6 GB altındaki cihazda son iki satır **desteklenmez**; `CouncilPolicy` bunu
baştan reddeder ve nedeni ekranda yazar.

### 24.4 Temizlik — bugün eksik olan

Bugün üye oturumları ve alt ajan oturumları koşu bitince **temizlenmiyor**;
`ChatViewModelStore` LRU ile tahliye edene kadar zombi VM kalıyor. Konsey için
bu kabul edilemez (bir koşu 9 VM bırakır). Kural:

1. Koşu bitince: üye VM'leri `ChatViewModelStore.forget`, tarayıcı havuzları
   `unregister + evictAll`, shell'ler `sessionDidTerminate`, terminaller `stop`.
2. Alt ajan bitince: kendi VM'i hemen bırakılır (koşu sonunu beklemez).
3. İptal: aynı temizlik + `job.cancel()` zinciri (alt ajanlar dahil).
4. Uygulama ölürse: `TeamRunStore`'da `cleanupPending` işareti, açılışta süpürme.

---

### 24.5 🔴 Uygulama gerçeği: küresel tavan 6 — dalga matematiği düzeltildi

Repo denetimi (`REPO-DENETIMI.md` §1–§3) bu bölümün ilk hâlini geçersiz kıldı.
Doğru tablo:

```
SessionConcurrencyManager.MAX_CONCURRENT = 6      // aynı anda akan ajan döngüsü
├─ alt ajan (helperConfig != null) → slot ALMAZ, ayrı kota (parent başına 3)
└─ diğer her şey (sohbet, üye)     → slot ALIR, kuyruk süresiz
```

Üç sonuç:

1. **5 üye + ebeveyn = 6/6.** Tek konsey uygulamanın tamamını kilitler; kullanıcı
   başka sohbet açarsa süresiz kuyrukta bekler ve **hiçbir yerde göremez**
   (`suspendedSessions` bir `StateFlow` ama onu okuyan ekran yok).
2. **Bloklayan `team_run` ebeveynin slotunu rehin alıyor.** Slot, akış işinin
   `finally`'sinde bırakılıyor — yani tur bitince. Konsey 20 dakika sürerse
   ebeveyn 20 dakika boş slot tutar.
3. **Üye alt ajan kotası gerçekten 5×3'e açık.** `MAX_CONCURRENT_CHILD_JOBS_PER_PARENT`
   ebeveyn oturum başına sayıyor; üye kendi oturumunda ebeveyn → 5 × 3 = 15.

**Düzeltilmiş kural:**

| Kural | Değer | Gerekçe |
|---|---|---|
| Konsey açılırken ebeveyn slotu | **bırakılır**, bitince geri alınır | 20 dk boş slot rehini biter; üyelere yer açılır |
| Kullanıcı için ayrılmış slot | **1** | konsey sürerken sohbet kullanılamaz olmasın |
| Dalga üst sınırı | **min(cihaz, MAX_CONCURRENT − 1)** = 5 | tavan 6, 1'i kullanıcıya |
| Dalga varsayılanı | lowRam 2 · orta **3** · büyük **4** (5 değil) | 4 üye + 1 kullanıcı + geçiş payı |
| Slot bekleyen üye | Kürsü'de **"slot bekliyor (2/6)"** rozeti | sessiz bekleme yasak |
| Slot bekleme süresi | 90 sn sonra `absent` + neden | süresiz kuyruk turu kilitlemesin |

**Mekanizma:** üye çalıştırılırken `SessionConcurrencyManager.suspendedSessions`
yoklanır; üyenin oturum kimliği 90 sn boyunca orada duruyorsa üye `absent`
işaretlenir ve tur devam eder. Bu şart, çünkü `HeadlessChatRunner.prompt` zaman
aşımı **kuyrukta geçen süreyi de** sayıyor: üye 8 dakikalık bütçesini hiç
başlamadan harcayıp "zaman aşımı" diyebiliyor, ve o mesaj gerçek nedeni saklıyor.

Büyük cihazda 5 üye yine mümkün, ama **açık kullanıcı tercihiyle** ve şu uyarıyla:
"bu koşu sürerken diğer sohbetler sıraya girer."

**Kod bağımlılığı:** dalga 5 ancak slot iadesi (37062) indikten sonra açılabilir.
Sıra bozulursa 5 üyeli koşu kullanıcıyı kilitler. Bu yüzden 37062, 37060'tan
sonra ama **dalga 5 açılmadan önce** gelmek zorunda.

### 24.6 Kaynak kotası düzeltmeleri (ölçülmüş)

| Kaynak | Bulunan | Düzeltme |
|---|---|---|
| Küresel akış | 6 | koşu ebeveyn slotunu bırakır; dalga ≤ 5 |
| Tarayıcı küresel | 8 (`GLOBAL_TAB_CAP`) | üye başına **1** sekme, koşu toplamı 6 |
| Havuz başına sekme | 3 (ajanla 6) | üye havuzu **1** ile sınırlı: `AGENT_TAB_QUOTA` benzeri üye kotası |
| Sekme tahliyesi | başka havuzun boşta sekmesi düşer | **aktif** sekme asla; önce kendi boşta sekmesi |
| `MAX_CACHED_CHILD` | 6 | **10** + koşu sonunda açık `release` |
| Üye alt ajanı | parent başına 3 → 5×3 | koşu semaforu 2/3/4 |
| Üye token sayacı | `PromptResult`'ta yok | `ContextSizeMeter.estimateTokens` + "tahmini" notu |
| Zombi VM | koşu sonunda temizlik yok | 37068 |

### 24.7 Görünürlük borcu — bedava kazanç

`SessionConcurrencyManager` iki `StateFlow` yayınlıyor ve **hiçbir ekran okumuyor**:

```kotlin
val runningSessions: StateFlow<Set<String>>   // :59
val suspendedSessions: StateFlow<List<String>> // :62
```

Bunları Kürsü'ye bağlamak yeni altyapı gerektirmiyor: "hangi üye çalışıyor,
hangisi slot bekliyor, kaç slot dolu" üçü de tek kaynaktan gelir. Kullanıcının
"neden sessiz" sorusunun en sık cevabı burada.

---

## 25. Arayüz — kullanıcı bunu nasıl görecek

Cevap: **üç katman, her katmanda farklı yoğunluk.** Telefonda 9 VM'in bütün
detayını aynı anda göstermek ekranı çöplüğe çevirir; ama "ne oluyor" sorusunun
cevabı hiçbir zaman bir tıkla uzakta olmalı.

### 25.1 Katman 1 — Toplantı salonu (kuş bakışı)

`team_hall/{runId}` · sekmeler: **Gündem · Kürsü · Karar**

**Kürsü** — üye şeridi, her kart:

```
┌─────────────────────────────┐
│ Ada · araştırma        ●    │   ← durum noktası: konuşuyor
│ "şifreleme şart çünkü…"     │   ← akan metin, 3 satır
│ 🔍 browser_use · 12s        │   ← canlı araç rozeti
│ 👥 1 alt ajan · 2 görev     │   ← sayaçlar
└─────────────────────────────┘
```

Kartta **rozet** olması şart: kullanıcı "üye neden 40 saniyedir sessiz"
sorusunun cevabını görmeli — tarayıcıda mı, terminalde mi, alt ajan bekliyor mu.

Üst çubuk: `tur 2/4 · 3/5 üye konuştu · 8.4k token · 04:12 · dalga 1/2`.

**Gündem** — iddialar + görevleri:
```
C1  "şifreleme olmadan olmaz"        3✓ 1✗ 1?   → G1 (Ada, çalışıyor)
C2  "çevrimdışı öncelik SQLite"      2✓ 3✗      → tartışmalı
C3  "iOS'ta Keystore yok"           1✓ 0✗ 4?    → G2 (Bor, açık)
```

**Karar** — ortak cevap + karşı görüş + yapılacaklar + kanıt tablosu.

### 25.2 Katman 2 — Üye ekranı (mikroskop)

Kürsüde karta dokun → `team_member/{runId}/{name}` · sekmeler:

| Sekme | Ne gösterir | Etkileşim |
|---|---|---|
| **Konuşma** | Üyenin tam oturumu (mevcut sohbet ekranı, salt-okunur bağlı) | Kaydır, kopyala — **yazamaz** |
| **Araçlar** | Bu turdaki her araç çağrısı: ad, süre, girdi özeti, çıktı ilk 5 satır | Dokun → tam çıktı |
| **Terminal** | Üyenin canlı shell'i + interaktif terminali | **"Ele geçir"** düğmesi |
| **Tarayıcı** | Açık sekmeler + son ekran görüntüsü | Dokun → tam WebView |
| **Alt ajanlar** | Ağaç: ad, durum, süre, hangi araçta | Dokun → alt ajanın kendi ekranı |
| **Görevler** | Yalnız bu üyenin görevleri, kanıt durumu | Kullanıcı işaretleyebilir |
| **Hafıza** | Kendi hafızası (düzenlenebilir) + gruptan gördüğü 12 satır | Satır sil |

**"Ele geçir" (takeover)** — kritik güvenlik kararı: terminal varsayılan
**salt-okunur**. Sebep: çalışan bir ajanın shell'ine yazmak, onun komut
akışının ortasına girmek demektir; iki yazar tek kabuk = bozuk çıktı. Takeover
açıkça istenir, ajanın o anki komutu bitene kadar **sıraya girer**, ve takeover
sırasında üye "kullanıcı terminalde" rozetini görür.

Tarayıcıda takeover yok: üyenin sekmesini kullanıcı sürükleyemez, ama
**kendi yeni sekmesini** açıp aynı sayfaya gidebilir. Ajanın gezinmesini
bozmamak için bu ayrım bilinçli.

### 25.3 Katman 3 — Aktivite haritası (hepsini birden gör)

Katman 1 ve 2 "ne oluyor" sorusuna cevap verir; "kim ne yapıyor, nerede
takıldı" sorusuna cevap vermez. Onun için `team_map/{runId}`:

```
tur 1  ├─ Ada    🔍 3 sayfa · 👥 2 alt ajan ──┬─ alt-1 ✅ 12s
       │                                     └─ alt-2 🔍 şu an
       ├─ Bor    🖥 4 komut · ⏱ 38s
       ├─ Cem    ✍ yazıyor
       └─ Deniz  ⏳ sırada (dalga 2/2)
tur 2  ├─ …
```

Zaman çizelgesi (yatay), üye başına şerit, alt ajanlar girintili. Her kutuya
dokun → o aracın detayı. Bu ekran **tek bakışta** "5 üye + 4 alt ajan + 7 sekme
nerede harcandı" sorusunu cevaplar; kullanıcının istediği "görebileceğim"
tam olarak burası.

### 25.4 Salonun dışı — kalıcı görünürlük

1. **Görev salonu** (`task_hall`): koşular arası kalıcı liste. Üstte sayaç:
   `Açık 5 · Yapılıyor 1 · Bitti 12 · Düştü 2`.
2. **Bildirim**: koşu arka planda biterken tek bildirim — "Konsey bitti: 4 tur,
   2 itiraz". Dokun → Karar sekmesi.
3. **Üye sorusu**: bir üye kullanıcıya soru sorduysa Kürsü'de kart + sekme
   rozeti. Cevap yazılınca üyeye sonraki turda gider.
4. **Ana menü rozeti**: süren koşu sayısı + açık görev sayısı, tek rakam.

### 25.5 Neden bu yoğunluk sırası

Kullanıcı bir konseyi iki farklı ruh hâlinde açar: *"ne çıktı"* (katman 1, 5
saniye) ve *"neden böyle oldu"* (katman 3, 30 saniye). Katman 2 arada: belirli
bir üyeyi merak ettiğinde. Üçünü tek ekrana sıkıştırmak, ikisini de bozar —
bu yüzden ayrı rotalar ve her rota **kendi başına** anlamlı.

---

## 26. Yeni dilimler (37060–37069)

### Durum (canlı)

| Dilim | Durum | Not |
|---|---|---|
| 37037 Konsey çekirdeği | **kod yazıldı** | 8 dosya + 35 test · `preflight`/`audit` temiz |
| 37060 `AgentKind` + üye kemeri | **kod yazıldı** | 6 yeni dosya, 7 dosya değişti, 28 test |
| 37061 Üye izolasyonu | **kod yazıldı** | dizin izolasyonu bedava çıktı; sekme kotası 1, tur sonu tahliye |
| 37062 Semafor + slot iadesi | **kod yazıldı** | `RunSubAgentBudget` + run kapısı + 90 sn kuyruk gözcüsü |
| 37063 Terminal devri | **kod yazıldı** | `TerminalTakeover` + `TerminalScreen(readOnly)` |
| 37064 Görünürlük | **kod yazıldı** | `CouncilLiveRegistry` + Konsey listesi/koşu ekranı + 3 rota |
| 37065 `ask_teammate` | **kod yazıldı** | gerçek araç + kota + tek seferlik akran çağrısı, 15 test |
| 37068 Koşu temizliği | **kod yazıldı (37062 içinde)** | `unregisterRun` kuyruğu da düşürüyor, `closeRunGate`/`releaseHolds` `finally`'de |
| 37066–37067, 37069 | sırada | |

**Derleme yok.** Tümü yerel ağaçta; `preflight.py` ve `audit_all.py` her turda koştu, 0 yeni hata.


`37034–37059` (§14, §22) duruyor. Bunlar üye yetenek yığını ve görünürlük.

| # | versionCode | Sürüm | İçerik | Kabul kriteri |
|---|---|---|---|---|
| 27 | 37060 | 11.5.26 | **`AgentKind` üçüncü tür**: `AgentTools.build` iki değerli bayraktan üç türe; üye kemeri (subagent ✓, team_run ✗, user_ask bloklamaz), rol→kemer varsayılanı, `TeamMember.tools` **fiilen uygulanır** | Üye `team_run` göremez; `reviewer` yazamaz; kapsam dışı yazma reddedilir; 18 test |
| 28 | 37061 | 11.5.27 | **Üye izolasyonu**: `sessionId = sp_<member>`, ayrı work dizini, ayrı shell, ayrı tarayıcı havuzu, ayrı not kapsamı; tembel açılış + boşta kapatma | İki üyenin shell'i karışmaz; üye sekmeleri karışmaz; 60 sn boşta shell kapanır |
| 29 | 37062 | 11.5.28 | **Üye alt ajanları**: koşu düzeyi semafor (2/3/4 slot), "bütçe dolu → görev olur", koşu başına 12 başlatma, alt ajan VM'i hemen bırakılır | 6. istek reddedilir ve göreve dönüşür; 3. kat açılamaz; koşu sonunda 0 zombi VM |
| 30 | 37063 | 11.5.29 | **Üye terminali + interaktif terminal**: `TerminalSession` üye başına, salt-okunur varsayılan, **takeover** kuyruklu, "kullanıcı terminalde" rozeti | Takeover olmadan yazma reddedilir; takeover bitmeden ajan yazamaz; 120 sn boşta kapanır |
| 31 | 37064 | 11.5.30 | **Üye tarayıcısı**: üye başına havuz, 1 sekme kotası, tur sonunda tahliye, canlı ekran görüntüsü akışı | 5 üye + 2 alt ajan = 7 sekme, cap aşılmaz; tur sonunda sekmeler kapanır |
| 32 | 37065 | 11.5.31 | **`ask_teammate`**: gerçek araç + kota + tek seferlik akran çağrısı. Planlanan "aktivite haritası" 37066'ya kaydı: ayrı bir harita yerine koşu ekranındaki üye kartı + canlı araç rozeti yeterli görüldü; ölçüm gerektirirse harita 37067'de geri gelir | Bilinmeyen ad reddi; tur 3 / koşu 8 kota; aynı soru tekilleşir; 15 test |
| 33 | 37066 | 11.5.32 | **Üye ekranı tam**: 7 sekme (konuşma/araçlar/terminal/tarayıcı/alt ajanlar/görevler/hafıza) + **aktivite haritası** (kaydı 37065'ten) | Her sekme canlı akar; döndürmede sekme korunur; VM kopyası açılmaz |
| 34 | 37067 | 11.5.33 | **Kürsü rozetleri + üye sorusu**: canlı araç rozeti, alt ajan/görev sayaçları, bloklamayan kullanıcı sorusu kartı | Rozet 1 sn içinde güncellenir; soru cevabı sonraki turda üyeye ulaşır |
| 35 | 37068 | 11.5.34 | **Koşu temizliği**: forget/evict/terminate zinciri, iptalde alt ajan kaskadı, açılışta zombi süpürme | 5 üye + 4 alt ajan sonrası canlı VM = 0; iptal 2 sn içinde her şeyi durdurur |
| 36 | 37069 | 11.5.35 | **Bütçe paneli + cihaz reddi**: kaynak tablosu ekranda, desteklenmeyen senaryo baştan reddedilir ve nedeni yazılır | 6 GB cihazda 5 üye + 4 alt ajan reddedilir; 8 GB'de kabul |

**Toplam:** 36 dilim, `37034–37069`. Sıradaki boş: **37070**.

### Neden bu sıra

1. **27 (kemer)** önce: üye bugün yanlış kemerle çalışıyor, izolasyon yapmak
   yanlış kemeri izole etmek olur.
2. **28 (izolasyon)** sonra: alt ajan (29) üyenin kendi `sessionId`'sini miras
   alacak; o kimlik önce var olmalı.
3. **29 (alt ajan)** üçüncü: bütçe semaforu olmadan 28'in izolasyonu telefonu
   yine öldürür.
4. **30–31 (terminal, tarayıcı)** izolasyonun üstüne oturur; ikisi de aynı
   `sessionId` mekanizmasını kullanır.
5. **32–34 (görünürlük)** en sonda: gösterecek bir şey olmadan ekran yazmak,
   boş ekranı güzel yapmak olur.
6. **35 (temizlik)** ve **36 (bütçe)** kapanış: bunlar olmadan özellik çalışır
   ama ikinci koşuda telefon yavaşlar.
