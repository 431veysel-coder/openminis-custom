# ÖNERİLER — planlananların DIŞINDA (2026-11-18)

Kapsam dışı bırakılanlar (başka sohbetlerde yürüyor, buraya karışılmadı):
konsey 37037–37069 · proje hafızası 37070–37079 · istasyon · notlar+kasa · çeviri
paketleri · sağlayıcı testi · düzenleme modu.

Bu belgedeki dilimler **37080 ve sonrası** için; hiçbiri yukarıdakilerin yerine geçmez.

> **Güncel durum:** bu listedeki 11 dilimin kodu ve testleri yazıldı →
> [`ozellik-paketi/`](ozellik-paketi/README.md). Bu dosya yalnız gerekçe
> defteri olarak kalıyor; hazır/değil bilgisi artık paketin tablosunda.

---

## A. KULLANICILAR İÇİN

### A1 — Komut risk kapısı (politika motoru) · 37080
Bugün ajan `shell_execute` ile istediğini çalıştırıyor; tek fren modelin sağduyusu.
Shizuku / erişilebilirlik açıksa etki cihazın tamamına iner.

- Kural dosyası (`policy.json`): `allow` / `ask` / `deny` üçlüsü, desen bazlı.
- Risk sınıfları: silme (`rm -rf`, `mv /`), yıkıcı git (`push --force`, `reset --hard`),
  yetki (`pm`, `settings put`, `su`), dışa sızma (`curl -F`, `nc`), kurulum (`apk add`, `pip`).
- Varsayılan: yüksek risk → `user_ask` tek kartı; düşük risk → sessiz geçiş.
- Her karar `PolicyLog`'a yazılır: hangi komut, neden geçti/engellendi.
- Kabul: `rm -rf /var/minis` varsayılanda onay ister; `ls` istemez; 20 test.

### A2 — Bütçe tavanı (para freni) · 37081
Token sayısı görünüyor, **tavan** yok. Sessiz harcama en sinir bozucu hata sınıfı.
- Üç kademe: sohbet / gün / ay. Aşıma yaklaşınca (%80) uyarı, tavanı geçince **koşu durur**.
- Team run ve alt ajanlar aynı havuza yazar (tahmin değil, gerçek `usage`).
- Model fiyatı bilinmiyorsa "sayı yok" olarak rapor edilir — uydurulmuş maliyet yazılmaz.
- Kabul: tavan aşılınca yeni tur başlamaz; yarım kalan iş "bütçe bitti" olarak raporlanır.

### A3 — Otomatik yedek + geri yükleme · 37082
Bugün yedek = elle `SessionExporter`. Room DB + `minis-sessions` + `memory` birlikte gider.
- Gece snapshot (varsayılan kapalı, açılınca 03:00), son 5 tutulur, boyut üst sınırı var.
- Geri yükleme **tek yönlü**: snapshot → boş kurulum; canlı veri üzerine yazmaz (kaza payı).
- Kabul: 200 MB veri setinde snapshot < 15 sn, geri yükleme sonrası sohbet sayısı eşit.

### A4 — Plan önizleme (pahalı işten önce tek kart) · 37083
Ajan 10 dakikalık koşuya girmeden önce "şunu yapacağım, şu kadar sürecek, şu kadar
harcayacağım" kartı. Onay yoksa koşu başlamaz. Sadece eşiği aşan işlerde (ör. > 3 dk
veya > N token tahmini) — küçük işlerde soru sormak işi öldürür.

### A5 — Tur özeti diff'i · 37084
Dosya düzenlemeleri tek tek görünüyor, "bu turda ne değişti" görünmüyor.
Tur sonunda değişen dosyalar + satır sayısı + tek dokunuşla geri alma (`JournalTool`
zaten ters kaydı tutuyor; eksik olan kullanıcı yüzeyi).

### A6 — Uzaktan konsol (düşük öncelik, yüksek risk) · 37090+
`NetworkHub` + Tailscale var, `DebugServer` var. Aynı ajana tarayıcıdan bağlanma.
Cazip ama saldırı yüzeyi: telefon cebimizde değilken her şey açık. Yapılırsa:
sadece okuma modu + kısa ömürlü jeton + tailnet dışına kapalı.

---

## B. YAPAY ZEKALAR İÇİN (ajan ergonomisi)

### B1 — Araç kapısı / kademeli şema (tool gating) · 37085
40+ araç tek seferde sunuluyor. Küçük modeller yanlış aracı seçiyor, büyükler
şema için bağlam harcıyor.
- 1. aşama: niyetten 6–10 aday araç (sözlük + anahtar kelime, LLM'siz).
- 2. aşama: yalnız adayların şeması gönderilir; `tool_search` ile geri kalanı açılır.
- Ölçüm: yanlış araç oranı ve ilk turda doğru araç seçimi. Ölçülmezse geri alınır.

### B2 — Ders defteri (post-mortem) · 37086
`self-improving-agent` becerisi var ama uygulamaya gömülü değil.
- Tetikleyiciler: araç hatası, kullanıcı düzeltmesi ("hayır öyle değil"), 3+ tur döngü.
- Yazılan yer: **kişisel** hafıza günlüğü (proje hafızasına dokunmaz — 37070–37079 ile çakışmasın).
- Otomatik yazmaz, **öneri** üretir; kullanıcı onaylarsa `memory_write` gibi girer.

### B3 — Kanıtlı "bitti" · 37087
`verify` aracı var ama opsiyonel. Zorunlu kılınırsa: biten iş için son 3 turda üretilmiş
bir kanıt (komut çıktısı / test sonucu / ekran görüntüsü) yoksa "bitti" reddedilir.
Küçük modellerin en pahalı hatası erken "tamam" demek.

### B4 — Yazma niyeti · 37088
Dosya kilidi **zaten var** (`data/journal/FileLock.kt`): yol başına kilit + parmak izi
kontrolü + çakışma mesajı. Eksik olan **zaman ölçeği**: çok adımlı iş boyunca (oku →
düzenle → derle → yaz) araya giren ikinci ajan. `WriteIntent` bloklamayan bir *beyan*
tutuyor ve ikinci ajanı uyarıyor; kilit yine `FileLock`'ta kalıyor.

### B5 — Kayıtlı yeniden oynatma (replay) · 37089
Bir sohbeti araç çıktıları dondurulmuş hâlde yeniden koşturma. Model değişikliğini
"iyileşti mi" diye ölçmenin tek dürüst yolu. Ağ erişimi kapalı, deterministik.

---

## Elenenler ve nedenleri

| Aday | Neden elendi |
|---|---|
| Sohbet arama | Var (`SessionListViewModel`, `SearchHighlight`) |
| Sesli okuma / dikte | Var (`TextToSpeechManager`, `SpeechSystem`) |
| Sohbet dışa aktarma | Var (`SessionExporter`) |
| Token/maliyet raporu | Var (`UsageReportLoader`) — eksik olan *tavan* (A2) |
| Kullanıcıya soru sorma | Var (`user_ask`, bildirim altyapısı) — eksik olan *otomatik* risk kapısı (A1) |
| Proje bazlı hafıza | 37070–37079'da yürüyor, buraya alınmadı |
| Konsey görünürlüğü / aktivite haritası | 37064 + 37066'da yürüyor |
| Yeni sağlayıcı ön ayarları | Metastır, ürün değil; sırası geldiğinde |
| Yerel model (GGUF) | APK +2 GB, termal; ayrı bir sürüm kararı |

## Sıralama mantığı

1. **Para ve veri önce** (A2, A3): kaybı geri gelmeyen iki şey.
2. **Sonra güvenlik** (A1): risk büyüdükçe (Shizuku, a11y, uzaktan konsol) değeri artar.
3. **Sonra ajan kalitesi** (B1, B3): ikisi de ölçülebilir, ikisi de geri alınabilir.
4. **En son ergonomi** (A4, A5, B2, B4, B5): rahatsızlık verir, veri kaybettirmez.
