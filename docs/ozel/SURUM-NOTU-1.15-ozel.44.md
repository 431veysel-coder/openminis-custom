# SÜRÜM NOTU — 1.15-ozel.44 / 37124

> **Yayındaki sürüm:** 1.15-ozel.34 / 37115. **Kapılar:** 11/11 · 50.9 s.

## 🔴 Bu turun asıl bulgusu: dokuz sürüm hiç derlenmedi

`.41`'de matris yamasını yazarken `DecisionTab` fonksiyonunu yanlışlıkla
sildim. Çağrısı kaldı (sekme 2), tanımı gitti.

Bunu **hiçbir kapı yakalamadı**. Sebep iki katmanlı:

1. `Slice Gate` yalnız `scripts/slices.json`'da listelenen dizinleri `kotlinc`
   ile derliyor; `ui/team/**` o listede **yoktu**.
2. `apk.yml` — gerçek Gradle derlemesi — yalnız `main` ve **`ozel-*`** (tire)
   deseninde koşuyor. Çalışma dalı `ozel/16-duzeltmeler` (**slash**).
   Glob eşleşmedi.

Yani `.35` → `.44` arasındaki **on** sürümün hiçbiri derlenmedi. CI'da gördüğüm
dört yeşil iş (denetim, dilim, secret scan, etiket) **derleme değildi**.

### Düzeltme

- `apk.yml` dal filtresine `'ozel/**'` eklendi. Bu dalda artık gerçek Gradle
  derlemesi koşuyor; yayın kararı değişmiyor (sürüm kapısı aynı `versionCode`'u
  iki kez yayınlatmıyor).
- `DecisionTab` geri kondu (KDoc'unda bu vaka yazılı).
- `ui/team`'i dilim olarak eklemeyi **denedim ve geri aldım**: o dosyalar
  Compose; `kotlinc` Compose compiler plugin olmadan derleyemez. Dilimi eklemek
  kapıyı sonsuza kadar kırmızı yapardı — bu depoda "susturulan kapı" sınıfı.

**Ders:** bir kapının yeşil olması, kapsadığı alanın yeşil olduğunu söyler;
kapsamadığı alan hakkında hiçbir şey söylemez. `.36`'da `gorunurluk.py`'yi bu
yüzden yazdım; aynı soru CI için de geçerliydi ve orada sormamıştım.

## §8.2 Rapor render — gerçekten bağlı

Plan "bağla" diyordu. Ölçüm: `DecisionTab` zaten `ReportBody(answer)` çağırıyor;
`ReportText.lines` başlık (`#`/`##`), madde (`-`/`*`/`•`), alıntı (`>`),
numaralı liste ve ayırıcıyı ayırıyor. Yani madde **zaten kapalıydı** — ve silinen
fonksiyon onu da götürmüştü.

## Kapılar

```
11/11 geçti · 0 uyarı · 50.9s
```

## Kapanan / düzelen

| Madde | Durum |
|---|---|
| §8.2 rapor render | ✅ **zaten bağlıydı** — `DecisionTab` içinde |
| `DecisionTab` kaybı | ✅ düzeltildi (derleme hatası) |
| CI dal filtresi | ✅ `ozel/**` eklendi — **kök neden** |
| §8.4 TalkBack / §8.1 boş durum / §8.3 dil | açık → `.45` |

## Sırada

`.45` ürün kapanışı (TalkBack etiketleri, boş durum eylemleri, dil tutarlılığı)
+ **ilk gerçek derleme** — artık CI bu dalda derliyor, ilk koşuda gerçek hatalar
çıkabilir ve çıkması iyi haber.
