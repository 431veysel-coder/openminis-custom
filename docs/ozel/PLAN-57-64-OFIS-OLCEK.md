# PLAN — Konsey Ofisi · Adım Adım Koşu · Bellek Disiplini

> **Damga:** 2026-11-28 · sürüm `1.15-ozel.56` / 37136 sonrası
> **Kaynak kararlar:** bu belge konuşmada alınan kararların yazıya geçmiş hâli.
> Ölçülen her sayı `ÖLÇÜLDÜ`, hesaplanan `HESAP`, tahmin `TAHMİN` etiketli.
> **Bu belge plan; uygulama sırası bağlayıcı.**

---

## 0 · Problem, tek paragrafta

Konsey **çalışıyor** ama **izlenemiyor**: altı oda aynı anı (koşunun sonunu)
gösteriyor, koşu sürerken "takıldı mı" sorusunun cevabı yok, ve bir koşunun
kaydı uygulama kapanınca siliniyor. Ayrıca ölçek yok: ekip 8 üyeyle sınırlı,
dalga sabit, alt ajan derinliği 1. Yani bugünkü ürün **bir rapor üretiyor, bir
toplantı olmuyor**; **birkaç ajan çalıştırıyor, bir sürü yönetmiyor**.

Bu plan iki şeyi sırayla kurar: önce **ofisi** (zaman + yerleşim + katılım),
sonra **ölçeği** (sınırsız kadro + hiyerarşi). Sıra tesadüf değil: ölçek
görünürlük olmadan açılırsa ajanların ne yaptığını kimse bilemez ve ilk
arıza "her şey bozuk" olarak okunur.

---

## 1 · Değişmez ilkeler (pazarlık dışı)

| # | İlke | Neden |
|---|---|---|
| İ1 | **Karar tek yerde.** `StanceMath`/`CouncilLoop` karar verir; özet/arayüz yalnız **sayar**. | İki yerde karar = iki doğruluk = çelişen rapor. `.56`'da `CouncilDigest` bunu böyle kurdu. |
| İ2 | **Kullanıcı bir üye değil, ayrı bir eksendir.** Not/görüş gönderir; konsensüs hesabına **girmez**, matriste ayrı sütun olur, kayıtta "kullanıcı yönlendirdi" etiketi durur. | Aksi hâlde ertesi gün "konsey şunu dedi" diye kendi cümleni okursun. |
| İ3 | **Kırpma sessiz olmaz.** Sınırlı her kuyruk (Kayıt 500 satır, görev panosu, hata listesi) düşen satır sayısını **yazar**. | Sessiz kırpma = eksik kaydı tam göstermek. |
| İ4 | **Ölçülmeyen sayı ekrana yazılmaz.** GPU/NPU'da olduğu gibi: bilinmiyorsa "ölçülmedi" yazar. | Uydurma sayı, yanlış karar verdirir. |
| İ5 | **Dalga bir mimaridir, kısıt değil.** Kadro geniş olabilir ama **aynı anda** koşan sayı cihazın taşıdığı kadardır. | Telefonda paralellik 12'yi geçince kazanç düşer, hata artar, ısı çıkar. |
| İ6 | **Kabuk ile model ayrı havuz.** Model bekleyen işçi bedava; kabuk çalıştıran işçi pahalı. | Tek sayıyla yönetmek, ya modeli fazla ya kabuğu fazla açmak demek. |

---

## 2 · Bellek bütçesi — ölçülmüş zemin

| Bileşen | Değer | Etiket |
|---|---|---|
| Uygulama Java heap (1-2 oturum) | 28 MB / 512 MB tavan | ÖLÇÜLDÜ |
| `largeHeap` | açık (512 MB) | ÖLÇÜLDÜ |
| PRoot kabuk tabanı | 3.9 MB RSS | ÖLÇÜLDÜ |
| Kabuk + `rg`/`python3` | 8–40 MB | TAHMİN |
| Kabuk + `apt install` | 100–300 MB | TAHMİN |
| Boşta işçi (Java) | ~0.3 MB | TAHMİN |
| Aktif streaming işçi (Java) | ~1–2 MB tepe | TAHMİN |
| Cihaz toplam / boş RAM | 11.2 GB / 4.4 GB | ÖLÇÜLDÜ |

**Türetilen tavanlar:**

| Kaynak | Tavan | Gerekçe |
|---|---|---|
| Kadro (kayıtlı işçi) | **sınırsız** | Disk + UI meselesi, RAM değil |
| Eşzamanlı **model** çağrısı | **8–12** (cihaz/ağ) | Ağ + ısı; 4.4 GB'da 12 sorun değil |
| Eşzamanlı **kabuk** süreci | **3–4** | Yerel bellek; `apt` sınıfı 300 MB |
| Toplam yerel bellek | **1 GB sert tavan** | Aşılırsa yeni kabuk sıraya girer, sessizce çalışmaz |
| Boş RAM eşiği (dalga düşürme) | **600 MB** | Altındaysa dalga kendiliğinden küçülür + **sebebi yazılır** |

**Doğrulama borcu:** işçi başına 0.3 / 1–2 MB satırları TAHMİN. Shizuku açıkken
12 işçili bir denemede `dumpsys meminfo` ile `TotalPss` ölçülüp bu tablo
güncellenecek. Ölçülene kadar bu sayılar ekranda **tahmin** olarak gösterilmez;
yalnız bu belgede durur.

---

## 3 · Fazlar

Her faz: **amaç · kapsam · dokunulan yer · kabul ölçütü · kapı · risk**.
Bir faz bitmeden sonrakine geçilmez; her faz tek yayın sürümü.

---

### F1 · `1.15-ozel.57` — Bakış panosu ve tur gerekçesi

**Amaç:** Koşu sürerken 5 saniyede "takıldı mı, kim çalışıyor" cevabı; ve
"neden bir tur daha" sorusunun **gerekçesi**.

**Kapsam**
1. **Bakış şeridi**: `tur 2/3 · dalga 3 · 04:12 · 12.4k token · 2 açık iddia`.
   Hiç cümle yok, hiç kart yok.
2. **Üye satırı**: `● Ayşe — araçta (rg) — 2 iddia` (tek satır, kaydırma yok).
3. **Tur gerekçesi**: `tur 3'e geçiliyor: C2 hâlâ 2-2, eşik %60`.
   Kaynak: `CouncilLoop`'un durma kararı — bugün hesaplanıyor ama **hiçbir yere
   yazılmıyor**. Yeni `CouncilEvent.RoundReason(why, detail)`.

**Dokunulan yer**
`agent/team/CouncilModels.kt` (yeni olay) · `CouncilLoop.kt` (yayma) ·
`CouncilLiveRegistry.kt` (alan) · `ui/team/CouncilScreens.kt` (yeni `OverviewTab`) ·
`strings.xml` ×2 · `CouncilDigest.kt` (gerekçe satırı)

**Kabul ölçütü**
- Canlı koşuda şerit görünür ve tur/dalga/süre/jeton alanları **gerçek** veriden gelir.
- Gerekçe satırı yoksa **çizilmez** (uydurma cümle yok).
- `CouncilTimeline`'a `RoundReason` satırı düşer (Kayıt'te görünür).

**Kapı:** `kural_oda_sozlesmesi.py` — yeni olay tipi bir odaya bağlı olmak zorunda ·
`CouncilDigestTest` genişler

**Risk:** Düşük. Yeni veri yok, var olanı görünür kılıyor.

---

### F2 · `1.15-ozel.58` — Ofis: zaman kaydırıcı + Kat ızgarası

**Amaç:** Koşuyu **sarılabilir** hâle getirmek ve üyeleri liste değil **yerleşim**
olarak göstermek. Bu, "sanal ofis"in gerçek karşılığı.

**Kapsam**
1. **Zaman şeridi** (Kayıt odasının üstünde): çekince o anki durum görünür —
   kim konuşuyordu, hangi iddia yeni düştü, hangi araç çalışıyordu. Veri
   `CouncilTimeline` + `atMs`'ten türetilir; **yeni kayıt yok**.
2. **Kat ızgarası**: üye = kutu. Durum rengi: konuşuyor / düşünüyor / araçta /
   bekliyor / gelmedi. Son cümle tek satır kırpık, rol rozeti üstte.
3. **Soru okları**: `İlişkiler` odasındaki "kim kime sordu" metni ızgaraya ok
   olarak çizilir. **İki yerde iki cevap olmaz**: metin satırı kalır ama ızgara
   asıl yüzey olur.
4. **Gelmedi ≠ bekliyor**: `waveSize` yüzünden sırada olan üye "sırada" yazar,
   `absent` olan soluk/kesik çerçeveyle **"gelmedi"** ve tek kelime nedeni.

**Dokunulan yer**
`ui/team/CouncilScreens.kt` (`FloorTab` yeniden yazılır, `TimelineRoom` şerit alır) ·
yeni `ui/team/OfficeGrid.kt` (yerleşim matematiği **saf**, testli) ·
`strings.xml` ×2

**Kabul ölçütü**
- Şerit 500 satırlık kayıtta da akıcı (sanal liste).
- Izgara 8 üyede 2 sütun, 12+ üyede gruplu (F7'de tam ölçek).
- Erişilebilirlik: her kutu tek cümle okunur (`TeamA11y` kalıbı).
- Boş/yok durumunda kutu **çizilmez**, "koşu bulunamadı" yazar.

**Kapı:** yeni `OfficeGrid` birim testi (sütun/taşma/isabet) ·
`kural_ic_ice_kaydirma.py` (sanal liste + dış scroll)

**Risk:** Orta. Yerleşim matematiği test edilmezse taşma olur; bu yüzden saf
sınıf + test şart.

---

### F3 · `1.15-ozel.59` — Son koşunun kaydı diskte

**Amaç:** Ofis her açılışta yeniden doğmasın.

**Kapsam**
1. `CouncilTimeline.State` son koşu için diske yazılır (`run.json` yanında ayrı
   dosya, `timeline.json`), **yalnız son koşu**.
2. Ölçüm: 500 satır × ~120 bayt ≈ 60 KB sıkıştırılmamış; `kind+who+atMs` +
   kısaltılmış detay ≈ **20 KB**. Bugünkü gerekçe ("run.json'ı şişirir")
   **ölçülmemiş bir korkuymuş** — belgeye böyle yazılır.
3. Açılışta: canlı koşu yoksa kayıt **"son koşu (bitmiş)"** etiketiyle çizilir;
   canlıyla karıştırılmaz (`.58`'deki "kaynaktan" etiketinin kardeşi).

**Dokunulan yer**
`agent/team/CouncilTimeline.kt` (codec) · `TeamRunStore.kt` (yaz/oku) ·
`CouncilScreens.kt` (etiket) · yeni `CouncilTimelineCodecTest`

**Kabul ölçütü**
- Uygulama kapanıp açılınca son koşunun Kayıt'ı görünür ve **bitmiş** etiketli.
- Bozuk dosya: sessizce silinmez, `.bad` olarak yana alınır (GrantStorage kalıbı).
- Boyut 64 KB'ı aşarsa yazma reddedilir ve **neden** yazılır.

**Kapı:** `CouncilTimelineCodecTest` (round-trip + bozuk dosya + sınır)

**Risk:** Düşük.

---

### F4 · `1.15-ozel.60` — Üye karnesi

**Amaç:** "Hangi üye işe yaradı?" — bugüne kadar hiçbir yerde yok.

**Kapsam** (üç ölçülebilir alan, hepsi modelde **zaten** var)
1. Üyesi olduğu kaç iddia **kapandı / açık kaldı**.
2. Kaç kez `UNCERTAIN` dedi (12 kez "bilmiyorum" diyen üye ağırlık taşımıyor).
3. Kaç kez **kaynaksız kanıt** iddia etti (`Basis.EVIDENCE` + boş `evidence` —
   modellenmiş, hiç sayılmamış).

**Dokunulan yer**
yeni `agent/team/MemberScorecard.kt` (**saf**: `ClaimMatrix` → satır) ·
`ui/team/CouncilScreens.kt` (Sonuç odasının altı) · `strings.xml` ×2

**Kabul ölçütü**
- Karne **karar vermez**, sayar (İ1).
- Veri yoksa satır çizilmez; "ölçülemedi" yazar.
- `LessonRecorder` ile bağı **kurulmaz** (ayrı faz, ayrı karar).

**Kapı:** yeni `MemberScorecardTest` (6 vaka: tek üye, boş matris, hepsi belirsiz)

**Risk:** Düşük-orta. "İşe yaramaz üye" damgası incitici olabilir → metin
**nötr** yazılır ("12 kez belirsiz dedi"), suçlayıcı değil.

---

### F5 · `1.15-ozel.61` — Müdahale (kullanıcı ekseni)

**Amaç:** İzlemekten katılmaya geçmek. **En pahalı faz**, en sona yakın.

**Kapsam**
1. Üyeye **not** gönder → sonraki turda prompt'a girer; o turun kaydı
   "kullanıcı yönlendirdi" etiketi taşır.
2. İddiaya **görüş** yaz → matriste ayrı sütun, farklı renk, **konsensüse girmez** (İ2).
3. **"Yeter, sentezle"** → koşu sentezle biter; kayıtta `kullanıcı bitirdi`
   (konsey kararı **değil**).
4. **"Bir tur daha"** → tur planı +1.

**Dokunulan yer**
`CouncilLoop.kt` (geri kanal — bugün akış **tek yönlü**) ·
`CouncilLiveRegistry.kt` (istek kuyruğu) · `ui/team/CouncilScreens.kt` (alt panel) ·
`CouncilModels.kt` (`CouncilStop.USER_STOPPED`) · `CouncilDigest.kt` (sütun)

**Kabul ölçütü**
- Kullanıcı görüşü konsensüs sayısına **hiçbir koşulda** eklenmez — test bunu kilitler.
- Not gönderilmişse rapor "kullanıcı yönlendirdi" satırı taşır.
- Koşu bitmişse müdahale düğmeleri **çizilmez**.

**Kapı:** `CouncilInterventionTest` (dokunulmazlık: görüş → consensus değişmez)

**Risk:** **Yüksek.** Döngüye geri kanal girmek, tek yönlü akışın tüm
varsayımlarını bozar; iptal/yeniden başlatma semantiği dikkat ister. Bu yüzden
F5'ten önce F1–F4 cihazda doğrulanmış olmalı.

---

### F6 · `1.15-ozel.62` — **Makul ölçek** + adım adım ilerletme

> **Karar değişti (2026-11-28):** 100 ajan hedefi **iptal**. Sebep aşağıda.
> Yerine "tek tek attırılabilir" (adım adım) koşu ve makul bir kadro geldi.

**Neden 100 ajan değil**
1. **Maliyet doğrusal, fayda logaritmik.** 100 işçi × 3k token = 370k jeton; ama
   orkestratörün onlardan çıkaracağı bilgi 20 işçiden sonra artmıyor — aynı
   dosyaları okuyan 100 ajan 20 kez aynı şeyi söyler.
2. **Telefonda ısı ve radyo.** 20 paralel çağrıda bile gecikme artıyor; 100'de
   toplam süre **uzuyor**.
3. **Kullanıcı 100 satırı okuyamaz.** Okunmayan çıktı, üretilmemiş çıktıdır.

**Yeni hedef (makul):**

| Boyut | Değer | Neden bu |
|---|---|---|
| Kadro (kayıtlı) | **6–24** | Elle kurulabilir, gözden geçirilebilir |
| Eşzamanlı model | **4–8** | Cihaz/ağ/ısı (§2) |
| Eşzamanlı kabuk | **3–4** | Yerel bellek (asıl tavan) |
| Sürü (F7) | **≤ 12 işçi, 2 seviye** | 3 lider × 4 işçi; daha fazlası kazanç getirmiyor |

Kadro üst sınırı **sert** (24) ve aşılamaz: "makul miktar" dediğin şey bir
niyet değil, **koddaki tavan** olmalı. İleride gerekirse tek sabitle açılır.

**Kapsam**
1. Kadro tavanı 8 → **24**; 24'ü aşan ekip kaydı reddedilir, **neden** yazılır.
2. `CouncilPolicy.forDevice` ısı + boş RAM + **ağ tipi** okur (bugün yalnız RAM):
   - Wi-Fi: 6 · mobil: 4 · ısı `severe`+: 3 · boş RAM < 600 MB: 3.
   - Karar her zaman **gerekçesiyle** ekranda (bugünkü `Preset.reason` kalıbı).
3. `SessionConcurrencyManager` tavanı kullanıcı ayarı olur (bugün yalnız DEBUG).

**★ Adım adım ilerletme — yeni çekirdek özellik**

Koşu iki modda çalışır ve mod **koşu başında** seçilir:

| Mod | Davranış | Kim için |
|---|---|---|
| **Otomatik** | Bugünkü gibi: tur bitene kadar kendiliğinden | "Sonucu istiyorum" |
| **Adım** | Bir adım = bir üye cevabı ya da bir faz. **Kendiliğinden ilerlemez**, kullanıcı butona basar | "İzlemek ve yönetmek istiyorum" |

Adım modunda butonlar:

```
[ ▶ Sıradaki adım ]  [ ⏭ Tümünü bitir ]  [ ⏸ Duraklat ]  [ ⏹ Durdur ]
```

- **▶ Sıradaki adım**: sıradaki üye cevaplar (ya da sıradaki faz: kanonikleştirme,
  sentez, denetim). Her basışta **tam bir adım**, yarım kalmaz.
- **⏭ Tümünü bitir**: kalan adımları otomatik koşar (adım → otomatik geçiş,
  kayıtta "otomatikleştirildi" yazar).
- **⏸ Duraklat**: otomatik modda uçuştaki adım biter, sonra durur.
- **⏹ Durdur**: `CouncilStop.USER_STOPPED`, yarım matris **bitmiş sayılmaz** (İ3).

Bu butonlar **F5'in (müdahale) yerini almaz**; F5 ayrı kalır (not gönderme,
görüş yazma). Adım = **zaman kontrolü**, müdahale = **içerik kontrolü**.

**Dokunulan yer**
`data/team/TeamStore.kt` (tavan 24) · `agent/team/CouncilPolicy.kt` (ısı/ağ) ·
`agent/team/CouncilLoop.kt` (adım kapısı — `suspend` bekleme noktası) ·
`agent/team/CouncilModels.kt` (`CouncilMode`, `CouncilStop.USER_STOPPED`) ·
`service/SessionConcurrencyManager.kt` · `ui/team/*` (mod seçimi + butonlar)

**Kabul ölçütü**
- Adım modunda koşu **tek başına ilerlemez**; 10 dakika beklenirse aynı yerde durur.
- Her ▶ basışı tam bir adım ilerletir; kayıtta adım numarası görünür.
- 25. üye eklenemez; denemede neden reddedildiği yazar.
- Isı `critical` → adım modunda bile yeni adım **başlamaz**, nedeni yazar.

**Kapı:** `CouncilStepModeTest` (adım = tam adım, kendiliğinden ilerleme yok,
durdur → bitmiş sayılmaz) · `CouncilPolicyTest` genişler (ısı × RAM × ağ, 12 vaka)

**Risk:** Orta-yüksek. Adım kapısı döngünün **ortasına** `suspend` bekleyişi
koyuyor; iptal ve süreç ölümü bu bekleme noktasında doğru davranmalı. Bu yüzden
F6, F5'ten sonra ve cihazda doğrulanmış F1–F5 üzerine kurulur.

---

### F7 · `1.15-ozel.63` — Sürü: küçük hiyerarşi (≤12 işçi, 2 seviye)

> **Karar değişti:** "3 lider × 20-30 işçi" **iptal**. Küçük ve derin olmayan
> bir ağaç yeterli; asıl kazanç 4-12 işçide.

**Amaç:** "her şeyi 3 ajan yapıyor" halinden, işi **bölüp toplayan** küçük bir
yapıya geçmek — 100 kişilik orduya değil.

**Kapsam**
1. **`depth` alanı:** çocuk `depth = parent + 1`. `depth >= 2` ise
   `delegate_task` araç listesinden **düşer**. (Bugünkü "helper hiç görmez"
   kuralının yerini alır: koruma aynı, sınır 1 değil 2.)
2. **Yukarı özet, aşağı görev:** işçi ham transcript göndermez; **≤ 300 bayt**
   rapor gönderir.
3. **Sürü planlayıcı:** görev → ağaç. Tavan **12 işçi, 2 seviye** (3 lider × 4
   işçi). `SwarmPlanner` **saf**: verilen görev ve işçi sayısına göre ağaç üretir,
   test edilir.
4. **Konsey küçük kalır:** 24 kişilik "konsey" yok. Konsey tam ağ (5–8),
   sürü hiyerarşik (≤12). İkisi ayrı ekran, ayrı amaç.

**Kabul ölçütü**
- Depth 2'de `delegate_task` görünmez; depth 1'de görünür.
- 12 işçilik sürüde orkestratör bağlamı **≤ 8k token** (özet kuralı tutuyor).
- Bir işçi çökerse lider **raporlar**, sessizce düşürmez.
- 13. işçi reddedilir ve neden yazar.

**Kapı:** `SwarmPlannerTest` · `AgentDepthTest`

**Risk:** Orta. Küçük ağaçta bile "aynı işi iki işçiye vermek" mümkün →
planlayıcı görev ayrık­lığını **kontrol eder**, çakışma varsa uyarır.

---

### F8 · `1.15-ozel.64` — Muhasebe, bellek kapısı, **ofis ölçeği**

**Amaç:** Koşuyu **güvenli** ve **tek ekranda okunur** çalıştırmak.

**Kapsam**
1. **Koşu muhasebecisi:** global jeton/bütçe defteri; tavan aşılınca her şey
   durur, **neden** yazılır.
2. **Yerel kabuk havuzu** (İ6): model havuzundan ayrı, **3-4 slot**, toplam
   yerel bellek **1 GB sert tavan**. Aşılırsa kabuk **sıraya girer**.
3. **Bellek kapısı:** boş RAM < 600 MB → dalga kendiliğinden küçülür + sebep.
4. **Süreç ölümü:** her işin "nerede kaldım" bilgisi diske; açılışta
   **yeniden başlatılabilir**.
5. **Ofis ölçeği:** 24 üye bile tek ekranda 24 kutu demek — ızgara **2 sütun +
   gerektiğinde katlanır grup**. Sürüde (≤12) grup başlığı:
   `Lider A · 4 işçi · 3 bitti · 1 hata`.

**Kapı:** `RunLedgerTest` · `ShellPoolTest` · `OfficeGridTest` (24 kutu taşmaz)

**Risk:** Yüksek ama **en kritik**: bu faz olmadan adım modu bile cihazı boğabilir.

---
## 4 · Sıra ve bağımlılık

```
F1 Bakış ──> F2 Ofis ──> F3 Kayıt diski ──> F4 Karne
                                                │
                                      (cihazda doğrulama)
                                                │
                                                v
                                            F5 Müdahale
                                                │
                                                v
                              F6 Makul ölçek + adım ──> F7 Sürü ──> F8 Muhasebe
```

**Neden bu sıra:**
- F1+F2 kullanıcının **bugün** hissettiği eksiği kapatır (en ucuz, en çok şikâyet).
- F5 (müdahale) ölçekten **önce**: 12 ajana müdahale etmek, 5 ajana müdahale
  etmekten zor. Önce 5'te doğru kurulur.
- **Adım modu (F6) ofisten ayrı düşünülemez:** ofis "şu an ne oluyor"u gösterir,
  adım butonu "bir sonrakini göster" der. İkisi aynı ekranın iki yarısı.
- F6→F7→F8 **birlikte anlamlı**: kadro büyümeden sürü gereksiz, sürü olmadan
  muhasebe gereksiz.

---

## 5 · Yapmayacaklarımız (ve neden)

| Yapmıyoruz | Neden |
|---|---|
| Avatar / piksel masa / yazma animasyonu | Soru cevaplamıyor, pil yakıyor, test edilemiyor. Ofis = yerleşim + zaman, illüstrasyon değil. |
| 100 paralel LLM çağrısı | Daha yavaş (radyo), daha pahalı (429), daha hatalı (ısı). Dalga mimaridir (İ5). |
| 20+ üyeli konsey (tam ağ) | 20 kişilik tartışma tartışma değil; maliyet karesel, dikkat sıfır. Ayrı özellik: sürü. |
| **100 ajan hedefi** | İptal edildi: maliyet doğrusal, fayda 20'den sonra düz. Telefonda ısı/radyo toplam süreyi **uzatır**. Makul tavan **24 kadro / 8 eşzamanlı** (F6). |
| Ekranda 4. bölge | Her bölge tek soruya cevap verir; dördüncüsü yoğunluk hatası (§6.4). |
| Sınırsız özyineleme | `depth` olmadan 3 ajan birbirine iş verip hiçbir şey yapmaz. |
| Kullanıcı oyunun konsensüse girmesi | İ2. En pahalı hata sınıfı. |
| Yedinci sekme | Üç çözünürlük (Bakış/Kat/Kayıt) zaten var; yedinci sekme "hangi sekmedeyim" sorusunu doğurur. |
| Ölçülmemiş sayı göstermek | İ4. GPU/NPU dersinden. |

---

## 6 · Arayüz — **Sanal Ofis** (tam ekran) ve girişi

### 6.1 Giriş: sohbet ekranındaki ikinci simge

Bugün sohbet üst barında **tek** simge var: `Icons.Outlined.Groups` → konsey
paneli (sekmeli). Bu kalır. Yanına **ikinci** simge gelir:

| Simge | Nerede | Ne yapar |
|---|---|---|
| `Groups` (var) | sohbet üst barı | Konseyi **başlat / raporu oku** (sekmeli panel) |
| **`Villa` / `Apartment`** (yeni) | sohbet üst barı, `Groups`'un yanında | **Sanal Ofis'i tam ekran açar** |

Ayrım net olmalı, yoksa iki simge iki farklı cevap verir:
- `Groups` = **"ne yapacağım"** → sonuç, matris, görevler, rapor.
- **Ofis** = **"şu an ne oluyor"** → canlı, müdahaleli, tam ekran.

Simgelerden biri **yalnız o sohbete bağlı koşu varken** görünür (koşu yoksa
`Groups` başlatma diyaloğunu açar, ofis simgesi **çizilmez** — boş odaya
sokmak yanlış beklenti üretir). Koşu varsa ofis simgesinde **canlı nokta**
(titreşen küçük yeşil) olur: kullanıcı ekrana bakmadan koşunun sürdüğünü bilir.

**Erişim yolları (üçü de aynı ekranı açar)**
1. Sohbet üst barı → ofis simgesi (o sohbetin koşusu)
2. Ekip ekranı → bir ekip satırı → "Ofisi aç"
3. Bildirim / canlı görev kartı → "Ofise git" (koşu sürerken)

### 6.2 Tam ekran davranışı

- **Immersive**: sistem çubukları gizlenir, `ImageGalleryViewer`'daki kalıp
  yeniden kullanılır (`hide(systemBars)`, `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`).
  Üstten aşağı kaydırınca çubuklar geçici gelir — çıkış yolu her zaman var.
- **Geri tuşu**: ofisten çıkar, **koşuyu durdurmaz**. Koşu arka planda sürer
  (bildirimde kalır). Yanlışlıkla çıkmak iş kaybettirmez — bu şart.
- **Ekran açık kalsın**: koşu sürerken `FLAG_KEEP_SCREEN_ON`. Pil uyarısı
  gereksiz; kullanıcı zaten izliyor.
- **Yatay destek**: ofis yatayda daha iyi çalışır (daha çok kutu). Kilitlenmez;
  dikeyde 2 sütun, yatayda 3–4 sütun otomatik.
- **Düşük güç**: ısı `severe`+ ise üstte tek satır uyarı + "adım moduna geç"
  önerisi (otomatik moddayken).

### 6.3 Tek ekranın düzeni — sade, üç bölge

Amacı "düzenli, sade, gelişmiş". Kural: **her bölge tek soruya cevap verir**,
ve ekranda **en fazla 3 bölge** var. Dördüncü bölge eklemek = yoğunluk hatası.

```
┌───────────────────────────────────────────────────────────┐
│  Konsey · GECE İNCELEMESİ              ⏸  ▶  ⏭  ⏹  ✕      │ ← 1) ŞERİT
│  tur 2/3 · dalga 4 · 04:12 · 12.4k · 2 açık iddia          │
│  tur 3'e geçiliyor: C2 hâlâ 2-2, eşik %60                  │
├───────────────────────────────────────────────────────────┤
│  ┌──────────────┐  ┌──────────────┐                       │
│  │ ● Ayşe       │  │ ● Bora       │   2) KAT (ızgara)      │
│  │ analiz       │  │ güvenlik     │   üye = kutu           │
│  │ araçta: rg   │  │ düşünüyor    │    oklar = sorular     │
│  │ "derleme…"   │  │ "riskli…"    │                       │
│  └──────────────┘  └──────────────┘                       │
│  ┌──────────────┐  ┌──────────────┐                       │
│  │ ○ Cem sırada │  │ ✕ Deniz gelmedi                       │
│  └──────────────┘  └──────────────┘                       │
├───────────────────────────────────────────────────────────┤
│  C1 ✓ 4-0   C2 ⚠ 2-2   C3 ? 0-0        ▸ Kayıt  3) ALT     │
└───────────────────────────────────────────────────────────┘
```

**1) Şerit (üst, sabit)** — "takıldı mı?"
Ad · mod · tur/dalga · süre · jeton · açık iddia sayısı · **tur gerekçesi**.
Sağda butonlar. Butonsuz bir ofis, izlenecek ama yönetilemeyen bir ekran olur.

**2) Kat (orta, kayan)** — "kim ne yapıyor?"
Üye = kutu; durum noktası renkli; son cümle tek satır; sorular **ok** olarak
kutular arasında. Gelmedi ≠ sırada (İ3'ün görsel karşılığı).

**3) Alt şerit (sabit, tek satır)** — "karar nereye gidiyor?"
İddia çipleri: `C1 ✓ 4-0` `C2 ⚠ 2-2`. Dikeyde tek satır, yatayda kaydırılır.
Sağında `▸ Kayıt` → zaman kaydırıcı **alttan açılır** (ekran değiştirmez).

**Butonlar (şeridin sağı)**

| Buton | Ne yapar | Mod |
|---|---|---|
| `▶` Sıradaki adım | Bir tam adım ilerletir | Adım |
| `⏸` Duraklat | Uçuştaki adım biter, durur | Otomatik |
| `▶▶` Devam | Duraklatılanı sürdürür | Otomatik |
| `⏭` Tümünü bitir | Kalanı otomatik koşar | Adım |
| `⏹` Durdur | Bitirir, **bitmiş sayılmaz** | Her ikisi |
| `✕` Çık | Ofisten çıkar, **koşu sürer** | Her ikisi |

`⏹` iki adımlı: ilk dokunuş "durdurulacak", 2 sn içinde ikinci dokunuş onaylar.
Geri alınamaz bir iş (koşunun yarısını çöpe atar) tek dokunuşta olmamalı.

**Müdahale (F5) ayrı katmanda**
Bir kutuya dokun → alt panel: *"not gönder"* · *"bu turda sustur"* · *"yeniden
çağır"*. Bir iddia çipine dokun → *"görüşünü yaz"* (ayrı sütun, konsensüse
**girmez** — İ2). Panel açılırken ekran küçülmez, üstte yarı saydam tabaka olur.

### 6.4 Sadelik kuralları (yazılı, denetlenebilir)

1. **Ekranda 3 bölge.** Dördüncü bölge eklemek yasak; yeni içerik **var olan**
   bölgeye katlanır.
2. **Kutu başına en fazla 4 satır** (rol · ad · durum · son cümle).
3. **Renk anlam taşır, süs değil**: yeşil=konuşuyor, mavi=düşünüyor,
   turuncu=araçta, gri=sırada, kırmızı=hata, kesik çerçeve=gelmedi.
4. **Animasyon yok** (durum noktası nabzı dışında). Kutu kaymaz, zıplamaz.
5. **Sıralama sabit**: üyeler kadro sırasında. "En aktif üste" gibi oynayan
   sıralama, kullanıcının gözüyle kutu eşlemesini bozar.
6. **Her kutu tek cümle okunur** (TalkBack): "Ayşe, analiz, araç çalıştırıyor,
   son sözü: derleme geçiyor".
7. **Boş durum dürüst**: koşu yoksa "koşu yok" + tek buton (konseyi başlat).

### 6.5 Ofis ile sekmeli panel arasındaki iş bölümü

| Soru | Nerede |
|---|---|
| "Şu an ne oluyor?" | **Ofis** (tam ekran, canlı) |
| "Adım adım ilerlet" | **Ofis** |
| "Şuna müdahale et" | **Ofis** |
| "Sonuç ne oldu?" | Panel → Sonuç |
| "Kim neye karşı?" | Panel → Karşılaştırma |
| "Ne yapmam lazım?" | Panel → Görev |
| "Geçen koşuda ne oldu?" | Ofis → Kayıt (son koşu, F3) |

**İki yüzey aynı veriden okur** (`CouncilLiveRegistry`). Ofis **karar üretmez**,
panel **canlı durum göstermez**. Aynı soruya iki cevap verme riski böyle kapanır.

---

## 7 · Kabul — bu plan "bitti" ne demek

1. Koşu sürerken **5 saniyede** "takıldı mı" cevabı alınabiliyor (F1).
2. Bir koşu uygulama kapanıp açıldıktan sonra **sarılabilir** (F2+F3).
3. Hangi üyenin ne kadar işe yaradığı **sayıyla** görünüyor (F4).
4. Kullanıcı koşuya **katılabiliyor** ve katkısı konsensüse **karışmıyor** (F5).
5. 24 üyeli ekip kurulup koşu **başlayabiliyor**; adım modunda koşu **kendiliğinden ilerlemiyor** (F6).
6. 12 işçilik sürüde orkestratör bağlamı **8k token'ı aşmıyor** (F7).
7. Jeton/bellek tavanı aşılınca koşu **duruyor** ve **neden** durduğu yazıyor (F8).
8. Sohbet üst barındaki ofis simgesi **tek dokunuşta tam ekran canlı ofisi** açıyor; çıkmak koşuyu durdurmuyor (§6).
9. Hiçbir ekran ölçülmemiş sayı göstermiyor (İ4).

---

## 8 · Açık borçlar (bu plandan bağımsız, kapanmadı)

- **441 çeviri borcu** — kapı yeşil çünkü borç tavanı affediyor (H-011).
- **Termux P4** (`sshd`) / **P5** ölçüm tablosu.
- **Cihazda sınama**: ses, TalkBack, DNS.
- **Canlı içerik**: sanal ekranda yalnız durum karesi var.
- **`TotalPss` ölçümü** — Shizuku kapalı; §2'deki TAHMİN satırları ölçüme çevrilecek.
