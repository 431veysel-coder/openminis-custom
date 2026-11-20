# SIRADAKİ SÜRÜM — 37091 / 1.15-ozel.14 · iş listesi

`37090` yakıldı (v1.15-ozel.13 yayında). Sıradaki numara **37091**. Yeni kimlik:
`com.minis.x` / **Minis X**. `1.15-ozel.14` bu paket adıyla ilk sürüm olacak.

Bu liste uydurulmadı: her madde ya `integration_audit.py`, ya `DENETIM-BASLIKLARI.md`
ölçümünden, ya da bugün yaptığım aramadan çıktı. "Olmalı" değil, "şu an şu".

---

## Önce: bugün bulduğum iki yeni sessiz yalan

Ayar ekranındaki 17 anahtarın **hangisi gerçekten bir davranışa bağlı** diye
tek tek saydım (`rg -c "Feature.X"` üretim kodunda, kendi ekranı hariç):

| Anahtar | Kullanım | Durum |
|---|---|---|
| NOTES, VAULT, TRANSLATION, QS_TILE, LIVE_TASKS, TOOLCHAINS | 3–8 | ✅ bağlı |
| BACKGROUND_GATES, PROVIDER_TEST, JOURNAL, ACCEPTANCE_CRITERIA, COUNCIL | 2–5 | ✅ bağlı |
| APPROVAL_GATE, BUDGET_CAP, PLAN_PREVIEW, LESSON_BOOK | 3–5 | ✅ bağlı (yeni) |
| **STATION** | **0** | 🔴 **modül yok** — `ui/station/` hiç yazılmadı |
| **EDIT_MODE** | **0** | 🔴 **uygulama yok** — kaplama katmanı hiç yazılmadı |
| REMOTE_CONSOLE | 0 | ⛔ belgelenmiş, kasıtlı |

**STATION** anahtarı `true` varsayılanla AÇIK ve alt yazısı aynen şu:

> *"Two to four agents on one task, isolated from each other"*

Böyle bir şey yok. Kullanıcı açıyor, hiçbir şey olmuyor. `ui/station/` dizini
hiç oluşturulmadı.

**EDIT_MODE** aynı:

> *"A pen in the middle of every screen: tap it, mark what should change, and
> the note lands in Notes for the assistant"*

Kalem yok, not iliştirme yok, ekran yakalama yok.

Bu ikisi **FANOUT ile aynı sınıf**: kullanıcıya olmayan bir şeyi vaat eden,
çalışıyormuş gibi görünen arayüz. Özelliklerin en pahalı hatası.

---

## A. Yayın bloklayıcı (37091'de bitmeli)

### A1 · `schemas/…/15.json` yok
Room `CODE_DB_VERSION = 15`, göç var, ama şema dosyası repoda değil. İlk
derlemede KSP üretir, commit edilmemiş. Sonuç: göçün doğruluğu CI'da
doğrulanamıyor ve bir sonraki DB değişikliğinde karşılaştırma tabanı yok.

**İş:** derleme çıktısından şemayı al, `src/android/app/schemas/…/15.json` olarak
commit et, `Room` şema doğrulamasını testlere ekle.
**Boyut:** 1 saat. **Risk:** düşük.

### A2 · FANOUT sessiz yalan
`TeamEditorScreen` "Compare" modunu sunuyor: *"her üye aynı görevi bağımsız
yanıtlar, yanıtlar yan yana gösterilir, tartışma yok"*. `AndroidTeamRuntime:290`
yalnız `COUNCIL`'e bakıyor; FANOUT `TeamLoop`'tan (handoff) geçiyor. Kullanıcı
sıralı sonuç alıyor.

**İş:** `TeamMode.FANOUT` için gerçek dal — üyeler paralel (dalga sınırıyla),
her yanıt ayrı raporlanır, sentez yok. Veya modu kaldır. **Yarım bırakmak yok.**
**Boyut:** 4–6 saat. **Risk:** orta (eşzamanlılık).

### A3 · STATION anahtarı
İki seçenek:
- **(a)** İstasyon A dilimini yaz: 2 panel, araçsız, tek görev iki modele
- **(b)** Anahtarı `false`'a çek + alt yazıya "henüz yok" yaz

**Öneri (b)** — 37091 için. İstasyon büyük bir iş (paneller, terminal, tarayıcı
havuzu) ve temiz sürümün içine sığmaz. Yanlış vaadi kapat, işi 37093'e al.

### A4 · EDIT_MODE anahtarı
Aynı: **(a)** yüzen kalem + not iliştirme, **(b)** anahtarı kaldır.
**Öneri (b)** — anahtar zaten `false` varsayılanlı, ama satır duruyor. Kaldır.

---

## B. Güven ve doğruluk

### B1 · Otomatik yedek şifresiz
`BackupRunController.Request(passphrase = null)` — düz ZIP, depolama alanında.
Kullanıcı yedeği açtığında sohbetleri, **sağlayıcı anahtarları** ve MCP sırları
şifresiz duruyor.

**İş:** otomatik yedekte şifre zorunlu olsun (ayarlarda bir kez girilir) veya
`secrets` kategorisi otomatik yedekten çıkarılsın. **Öneri:** anahtarları çıkar,
şifre isteğe bağlı.
**Boyut:** 2 saat. **Risk:** orta (geri yükleme akışı).

### B2 · REMOTE_CONSOLE kararı
97 satırlık jeton modeli var, uygulama yok, anahtar kapalı. İki seçenek:
**(a)** sil (dosya + test), **(b)** uygula (jeton + QR + salt-okuma konsol).
**Karar senin.** Ben (a) derdim: telefon cebimizde değilken yazma yetkisi
vermek, kazandırdığından çok risk getiriyor.

### B3 · Tur özetinde gerçek satır sayısı
Şu an "3 dosya değişti" diyor, `+120/−8` demiyor — `ActionJournal.Entry` bayt
tutuyor, satır yok. Uydurmak yerine sustum. Gerçek sayı için `Entry`'ye
`addedLines`/`removedLines` eklenip yazma yolunda hesaplanmalı.
**Boyut:** 2 saat. **Risk:** düşük.

---

## C. Senin 13 ekseninden açık kalanlar

### C1 · Sağlayıcı sağlığı (ping / hata / takip / tamir)
Bugün: `PROVIDER_TEST` tek GET atıyor, **yalnız kullanıcı basınca**. Sohbet
isteklerinin sağlığı hiç sayılmıyor. `QuotaRules.consecutiveFailures` var ama
**yalnız kota yenileme** için geri çekilme; sohbet hatasıyla ilgisi yok.

**İş — üç parça:**
1. **Sayaç:** sağlayıcı örneği başına son 50 isteğin başarı/hata oranı,
   ortalama gecikme. `usage` tablosunun yanına küçük bir tablo.
2. **Takip:** Ayarlar › Sağlayıcılar satırında rozet — "%12 hata · 1.4 sn · son
   hata 3 dk önce". Kullanıcı mesaj göndermeden görür.
3. **Tamir:** 3 ardışık aynı tip hata → tek düğme: "modelleri yenile" /
   "base URL'i sıfırla" / "anahtarı yeniden gir". Otomatik değil, **öneri**.

**Boyut:** 6–8 saat. **Risk:** düşük. → **37092'nin ana maddesi olmalı.**

### C2 · Sağlayıcı periyodik ping
Bağlantı testini 5 dakikada bir kendiliğinden koşturmak. Dikkat: her ping
ücretsiz değil ve 429 üretebilir. **Öneri:** yalnız "son 10 dakikada hata var"
ise ping at.

### C3 · Dolar tavanı
`BudgetLimits` alanları var ama `BudgetStore` yalnız token okuyor; katalog her
model için fiyat taşımıyor. **Ya** fiyat kataloğu tamamlanır (models.dev
verisinde var, `ModelsDevApi.kt` zaten okuyor) **ya** alan kaldırılır.
**Öneri:** katalogdan fiyatı olan modeller için dolar tavanı, olmayanlar için
"fiyat bilinmiyor" ve yalnız token sayılır.

---

## D. Kalite altyapısı (sürüm değil, güven)

| # | İş | Neden | Boyut |
|---|---|---|---|
| D1 | `ktlint` veya `detekt` | `preflight` derleyici değil; stil/koku/tehlikeli kalıp denetimi yok | 3 saat |
| D2 | `jacoco` kapsam | Hangi satır test edilmiyor bilinmiyor; 6.481 test var ama kapsamı ölçülmedi | 3 saat |
| D3 | Rota testi | Yeni 2 rota (`ozel_budget_limits`, `ozel_replay`) elle denendi | 2 saat |
| D4 | Dilim kapısını genişlet | Şu an 11 dilim; yeni dilimler manifest'e girmezse kapıdan geçmiyor | 1 saat |

**D4 önemli:** `slices.json` elle güncelleniyor. Yeni bir dilim eklenip
manifest'e yazılmazsa **sessizce denetimsiz** kalıyor.

---

## E. Arayüz cilası

| # | İş | Neden |
|---|---|---|
| E1 | Onay kartında **titreşim** | Cep telefonunda, ekrana bakmıyorsa kart fark edilmiyor |
| E2 | Kuyruk görünürlüğü | 90 sn gözcüsü var, "sıra bekliyorum" ekranda yok |
| E3 | Ders geçmişi | Öneri kartı var, "onayladığım dersler" listesi yok |
| E4 | 21 dil | `mx_*` metinleri yalnız TR + EN; diğer 21 dil İngilizce görüyor |
| E5 | İstasyon A | (A3b seçilirse 37093'e) |

---

## Önerilen sürüm bölünmesi

### 37091 / 1.15-ozel.14 — "temiz sürüm"
**Tema:** yalanları kapat, güveni sağlamlaştır. Yeni büyük özellik yok.

- A1 `schemas/15.json`
- A2 FANOUT düzelt
- A3b STATION anahtarını kapat
- A4b EDIT_MODE anahtarını kaldır
- B1 yedek şifreleme (veya secrets hariç)
- B3 tur özeti gerçek satır sayısı
- D4 dilim manifestosu kapısı

**Tahmini:** 1 gün. **Risk:** düşük. **Kullanıcıya görünen:** az, ama
"ayarlarda gördüğüm her şey çalışıyor" garantisi.

### 37092 / 1.15-ozel.15 — "sağlayıcı sağlığı"
- C1 sayaç + rozet + öneri
- C2 koşullu ping
- C3 dolar tavanı (katalog fiyatıyla)

**Tahmini:** 1 gün. **Kullanıcıya görünen:** yüksek — bozuk sağlayıcıyı
mesaj atmadan görür.

### 37093 / 1.15-ozel.16 — "istasyon ve kalite"
- İstasyon A (2 panel, araçsız)
- D1/D2/D3 statik analiz + kapsam + rota testi
- E1/E2/E3 cila
- E4 21 dil

---

## Şu an hemen yapılabilecek 3 küçük iş

Bunlar sürüm beklemez, bugün 30 dakikada biter:

1. **REMOTE_CONSOLE dosyasını sil** (B2a) — karar verirsen.
2. **STATION + EDIT_MODE anahtarlarını kapat/kaldır** (A3b + A4b) — yarım saat.
3. **`slices.json`'a otomatik kapı** (D4) — yeni dilim manifest'e yazılmazsa
   kapı uyarır.
