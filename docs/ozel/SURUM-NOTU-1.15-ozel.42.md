# SÜRÜM NOTU — 1.15-ozel.42 / 37123

> **Yayındaki sürüm:** 1.15-ozel.34 / 37115. **Kapılar:** 11/11 · 49.6 s.

## §4.3 Konsey: "kaç üye cevap verdi, kim düştü"

`CouncilLoop` tur içinde `answered` sayısını **biliyordu** (`StanceMath.evaluate`
için) ama rapora hiç yazmıyordu. 5 üyeli ekipte 2 cevap alındığında kullanıcı
"konsey çalıştı" görüyor, 3 üyenin düştüğünü göremiyordu — `.22`'deki
`no_positions` sessizliğinin küçük kardeşi: koşu **boş dönmüyorsa** sessiz.

- Yeni `AttendanceLine.line(total, answered, absent)` — rapor **başına** yazılır.
  Sonuna eklenen bir not, uzun raporu okumaya başlamış kullanıcıya ulaşmaz.
- `synthesize` imzasına `plannedMembers` eklendi: `lastPositions.size` ile
  planlanan sayı farklı olabilir (preflight/dalga elemesi). "5/5 katıldı" ile
  "5/2 katıldı" arasındaki farkı yazabilmek için ayrı parametre.
- **Herkes katıldıysa satır yazılmaz.** Her koşuda "5/5 katıldı" görmek, gerçek
  eksilme olduğunda satırın fark edilmemesine yol açar.

## §4.4 Kuyruk görünürlüğü

`AgentJobRegistry.queueSummaryOf` vardı ama yalnız **ajana** söyleniyordu
(İngilizce araç sonucu); kullanıcı "2 çalışıyor, 3 sırada" satırını hiçbir yerde
göremiyordu. Canlı Görevler ekranının başına eklendi.

**Bulunan gerçek sorun:** `AgentJobState`'te `QUEUED` **yok** — sınırı aşan işler
`PENDING` kalıyor. Yani "sırada" sayısı bir durum değil, **türetilmiş** bir sayı:
`pending - boş yer`. Bunu her çağrı yerinde yazmak iki farklı sonuç üretir ve
birinde `coerceAtLeast` unutulur → negatif sayı. Bu yüzden ayrım saf çekirdeğe
alındı: `AttendanceLine.split(running, pending, limit) -> (running, starting, waiting)`.

## §4.2 Araç yüzeyi: kapalı, gerekçesiyle

Plan "çocuğun gördüğü araç listesi istemde yazılmalı" diyordu. Ölçüm: `AgentTools`
zaten tek yerden üretiyor ve `MemberToolBelt` filtresi **son** uygulanıyor
(`afterBelt`), yani şema ile kemer aynı listeden çıkıyor — ayrışma imkânsız.
İstemde ikinci bir liste yazmak, iki kaynak üretmek olurdu. Madde **kapandı
(mevcut tasarım yeterli)**; bir kaynak-olgu testi (`her_arac_adi_belt_ile_ayni
_kaynak`) zaten `MemberToolBeltTest` içinde var.

## Kapılar

```
11/11 geçti · 0 uyarı · 49.6s
```

## Kapanan

| Madde | Durum |
|---|---|
| §4.3 ekip sonucu satırı | ✅ kapandı (9 test) |
| §4.4 kuyruk görünürlüğü | ✅ kapandı (`split` ile saf hesap) |
| §4.2 araç yüzeyi | ✅ **zaten tek kaynak** — yanlış açık kaydı düzeltildi |
| §2.2–2.4 (Terminal) | açık → `.43` |

## Sırada

`.43` çeviri + taban eritme (+ Terminal 2.2–2.4) · `.44` ürün kapanışı
(TalkBack, rapor render, dil) · `.45` tam denetim + APK (izinle).
