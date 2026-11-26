# SÜRÜM NOTU — 1.15-ozel.44

> ## ✅ YAYINLANDI — `v1.15-ozel.43` / build **37124**
>
> .43 ve .44 **aynı sürüm numarasını** taşıyor: .44'ün düzeltmeleri, .43
> yayınlanmadan önce aynı `versionCode`'a katlandı. Yayın kapısı aynı kodu iki
> kez yayınlatmadığı için bu mümkündü ve doğruydu — ayrı bir kod harcamak,
> kullanıcıya hiçbir şey eklemeyen bir sürüm numarası üretirdi.
>
> **CI 6/6 yeşil:** Native deps · Preflight · Version gate · **Compile** ·
> **262 test** · **APK 43.5 MB**. `.35`'ten beri ilk kez gerçekten derlendi.
>
> **Kapılar:** 11/11 · 50.9 s.

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


## İlk derleme düştü — ve düşmesi iyiydi

`ozel/**` eklenince Gradle ilk kez koştu ve **derleme hatası verdi**:

```
e: CouncilScreens.kt:487:1 Unclosed comment
```

Sebep: `DecisionTab` geri koyarken KDoc'una glob yazdım — yorum içindeki
`/` + `*` + `*` dizisi **yeni bir yorum açar**, dosya derlenmez. Kendi
eklediğim dokümantasyon kendi kodumu kırdı. Bu, "test yoksa doğrulanmamıştır"
kuralının derleme sürümü.

### Yerel kapı da bu sınıfı görmüyordu — iki sebeple

1. `check_comments` fonksiyonu **hiç çağrılmıyordu**. Kapının kendi içinde
   "yazdım, bağlamadım" örneği. `olu-kod.py` bilgi olarak listeliyordu ama
   kapı `--sadece-yeni` ile koştuğu için görünmedi.
2. Naif sayım da yanlış olurdu: `text.count("/*") != text.count("*/")`
   yorumlarda apostrof yüzünden (Türkçe: "kullanıcı'nın") onlarca yanlış
   pozitif üretti. Ölçtüm: tam ağaçta **8+ dosya** sahte "dengesiz".

Düzeltme: gerçek durum makinesi (`code` / `line` / `block` / `"` / üçlü
tırnak / char literal). Üç vakayla sınandı:

| Vaka | Beklenen | Sonuç |
|---|---|---|
| KDoc içinde glob | kırmızı | ✅ yakalandı |
| Yorumda apostrof + string içinde `/*` | temiz | ✅ sessiz |
| Sıradan KDoc | temiz | ✅ sessiz |

Tam ağaç taraması: **0 yanlış pozitif**.

**Ders:** kapıyı yazmak yetmiyor; (a) çağrıldığını, (b) yanlış pozitif
üretmediğini, (c) bilerek sokulan hatayı yakaladığını ayrı ayrı sınamak
gerekiyor. Üçünü de bu turda yaptım — ve üçü de ilk denemede kırıktı.

## Kapılar (son)

```
11/11 geçti · 0 uyarı · 61.6s
```

## Sırada

`.45` ürün kapanışı (TalkBack etiketleri, boş durum eylemleri, dil tutarlılığı)
+ **ilk gerçek derleme** — artık CI bu dalda derliyor, ilk koşuda gerçek hatalar
çıkabilir ve çıkması iyi haber.
