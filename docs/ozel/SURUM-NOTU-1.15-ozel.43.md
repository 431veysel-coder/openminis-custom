# SÜRÜM NOTU — 1.15-ozel.43 / 37124

> **Yayındaki sürüm:** 1.15-ozel.34 / 37115. **Kapılar:** 11/11 · 51.6 s.

## §7.3 Çeviri tabanı: 521 → **481**

Yeni araç `scripts/ozel/ceviri-erit.py` — batch başına 40 anahtar.

**Neden ayrı araç, neden `--fix-baseline` değil:** `ceviri-denetim.py
--fix-baseline` tabanı bugünkü gerçeğe eşitler, yani eksiği **görünmez** yapar.
Bu araç tersini yapıyor: anahtarı gerçekten çeviriyor **ve** tabanı o kadar
daraltıyor. İkisi aynı dosyaya yazar; biri borcu saklar, diğeri öder.

Bu turda ilk 40 anahtar çevrildi (çoğu tek kelimelik etiket: "Sırada",
"Token", "Araçlar", "Yeniden dene", "Günlük", "Kota"…). Kalan 481 bir sonraki
batch'e. Taban liste daralması `.ozel/ceviri/taban.json` içinde görülüyor.

**Neden hepsini bir turda yapmadım:** 521 anahtarı tek turda çevirmek (a)
turu bitirmez, (b) yanlış bir tercihi 500 anahtara yayar. Batch = geri
alınabilir adım.

## §6.3 Sözlük dürüstlüğü: ölçüldü, testle çivilendi

Plan "her çağrı yerinde turuncu etiket var mı" diye soruyordu. Cevap: **evet**,
ve nedeni kodda:

- `OfflineGlossary.Result.degraded` **her zaman** `true` (gerçek bir sözlük
  sonucu için), `complete` **her zaman** `false` — cümle yapısı hâlâ kaynak
  dilde olduğu için "tam" demek yalan olurdu.
- `ChatAssistantMessageUI` `tr.degraded` gördüğünde turuncu "sözlük tabanlı"
  etiketini çiziyor.

Yani mekanizma doğruydu ama **test yoktu**. `.31`'de tam bu sınıf hata
bulunmuştu (`ThinkingTranslator` sözlük sonucunda `degraded` dalını
düşürüyordu) ve 5 sürüm görünmeden yaşadı. Yeni `OfflineGlossaryHonestyTest`
(**7** test) bayrağı çiviliyor: sonuç asla `complete`, hiç terim değişmezse
boş döner (uydurma yok), uzun terim önce değişir, kelime sınırında değişir,
değişim sayısı gerçek.

## §6.1 / §6.2: zaten bağlı — ölçüldü

- `TranslationPacks.recommend(context)` → `TranslationSettingsScreen:163`,
  yalnız aktif paket yokken gösteriliyor (`.28` işi).
- "Kalanı çevir": `ThinkingTranslator` önbellekten `done` sayacıyla devam
  ediyor (`.28`'de doğrulandı, o günden beri değişmedi).

Yeni iş yok; iki madde yanlış "açık" kaydından düşüldü.

## Kapılar

```
11/11 geçti · 0 uyarı · 51.6s
ceviri: values 3016 · values-tr 2535 · taban 481 · eksik 481 (0 YENİ) → YEŞİL
```

## Kapanan

| Madde | Durum |
|---|---|
| §7.3 taban eritme | ◐ ilk batch (521 → 481), araç hazır |
| §6.3 sözlük dürüstlüğü | ✅ kapandı (7 test) |
| §6.1 / §6.2 | ✅ **zaten bağlı** — ölçüldü |
| §2.2–2.4 (Terminal) | açık → `.44` |

## Sırada

`.44` ürün kapanışı (TalkBack etiketleri, rapor render bağlama, dil tutarlılığı,
boş durum eylemleri + Terminal 2.2–2.4) · `.45` tam denetim + APK (izinle).
