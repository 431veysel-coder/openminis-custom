# SÜRÜM NOTU — 1.15-ozel.41 / 37122

> **Yayındaki sürüm:** 1.15-ozel.34 / 37115. **Kapılar:** 11/11 · 58.2 s.

## §1.5 Karşılaştırma odası: gerçek matris

Önce: satır başına bir metin bloğu (`Ayşe ✓  Mehmet ✗`). 5 üye × 8 iddia'da
hangi sütunun kime ait olduğu kayboluyordu.

Sonra: satır = iddia, sütun = **oy vermiş** üye; iddia sütunu sabit, üye
sütunları yatay kayıyor.

### Neden sütun daraltmadım

5 üye × 96 dp = 480 dp; 320 dp'lik ekrana sığmaz. Sütunu 40 dp'ye indirmek
hücreyi boş gösterir ve **oy veren üyeyi yok sayar** — bu, "3'e 2 oy çıktı ama
kim verdi belli değil" hatasının görsel hâli. Tek doğru cevap kaydırmadır.

Yerleşim `ui/team/ClaimMatrixLayout.kt`'ta, saf ve testli (**8** test):
- `MIN_COLUMN_DP = 88` altına inilmiyor,
- iddia sütunu `168 dp` sabit,
- sıfır genişlikte "sığdı" demiyor, kaydırmayı seçiyor (ölçüm gelmeden çizim
  olabilir; "sığdı" demek tabloyu görünmez yapardı),
- hücre sol kenarı üye sırasıyla artıyor (`leftOf`, kaydırma konumu hesabı).

## §1.6 Cevap eşlemesi artık yaklaşık olduğunu söylüyor

Protokolde "şu soruya cevap" alanı yok; cevap, hedefin **sonraki turdaki**
pozisyonundan eşleniyor. Yani bir çıkarım, kayıt değil.

- `ClaimMatrixLayout.answerMark(answeredRound, questionRound)` → `≈ tur 3 (soru tur 2)`
- Cevapsız soruda işaret **üretilmiyor** (uydurma yok).
- İletişim odası bu satırı kullanıyor.

Gerekçe: yanlış üyeye atfedilmiş bir cevap, cevapsızlıktan daha zararlı.

## §3.3 Ret listesi: kapalıydı, doğrulandı

Plan "kod var, yüzey eksik" diyordu. Ölçüm: `PermissionGrantsScreen` içinde
`GrantCatalog.denialRows` **bağlı** — kural, kaç kez, kapsam (oturum/proje/cihaz)
ve son gerekçe görünüyor. Yeni iş yok; yanlış açık kaydı düzeltildi.

## Kapılar

```
11/11 geçti · 0 uyarı · 58.2s
```

## Kapanan

| Madde | Durum |
|---|---|
| §1.5 matris görünümü | ✅ kapandı (8 test, 320 dp kuralı) |
| §1.6 ≈ eşleme işareti | ✅ kapandı |
| §3.3 ret listesi | ✅ **zaten kapalıydı** — kayıt düzeltildi |

## Sırada

`.42` alt ajan/ekip yüzeyi (araç yüzeyi, ekip sonucu satırı, kuyruk görünürlüğü)
+ Terminal 2.2–2.4 · `.43` çeviri + taban eritme · `.44` ürün kapanışı ·
`.45` tam denetim + APK.
