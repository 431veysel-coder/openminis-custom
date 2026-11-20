# DİLİM KAPISI — her dilim ayrı derlenir, ayrı rapor verir

**Durum: kuruldu ve çalışıyor.** İlk gerçek koşu 2026-11-18: **11/11 dilim geçti, 105 test yeşil.**

---

## Neden böyle bir şey

Tek büyük derlemede 11 dilimin hatası tek log'a düşer ve birbirini maskeler.
Hedef: *"bir hata yerine karşılaşacağımız tüm hataları hemen görebilmek"*.

Bu yüzden her dilim **kendi `kotlinc` çağrısıyla** derleniyor. 10 dilim yeşil,
1 dilim kırmızıysa 10'u birleştirilebilir, 1'i izole kalır. Rapor dilim başına
ayrı: hangi dosyada ne olduğu tek bakışta görünür.

---

## Nasıl çalışıyor

| Parça | Ne yapar |
|---|---|
| `scripts/slices.json` | manifest: 11 dilim → kaynak dosyalar, test dosyaları, `deps` |
| `scripts/slice_gate.py` | dilim başına derle + test et + rapor yaz |
| `.github/workflows/slice-gate.yml` | CI: kotlinc indir, kapıyı koş, raporu yükle |
| `build/slice-report/<dilim>.md` | dilim başına rapor |
| `build/slice-report/SUMMARY.md` | tablo: hangisi geçti, hangisi kaldı |
| `build/slice-report/results.json` | makine okunur hâli |

Komutlar:

```
python3 scripts/slice_gate.py              # hepsi
python3 scripts/slice_gate.py 37080 37088  # seçili dilimler
python3 scripts/slice_gate.py --list       # manifestoyu göster
```

Çıkış kodları: `0` hepsi geçti · `1` en az bir dilim kırmızı · `2` **atlandı**
(`kotlinc` yok — "temiz" değil, "bilinmiyor").

---

## İlk koşuda ne öğrendik

İlk koşu **10/11 FAIL** verdi, ama hepsinde derleme **geçmişti**. Hatanın kökü:

```
java.lang.NoClassDefFoundError: kotlin/jvm/internal/Intrinsics
```

`kotlinc` derlerken `kotlin-stdlib`'i kendi ekliyor; `java -cp` eklemiyor.
Testleri çalıştırırken stdlib yoktu. Düzeltme: runtime classpath'e
`kotlinc`'in yanındaki `lib/kotlin-stdlib.jar` eklendi.

**Bu, kapının var olma sebebinin kanıtı.** Statik denetim (`preflight`), kod
okuma, hiçbiri bunu göremezdi — derleme geçiyor çünkü kod doğru; test
*koşamıyor*. Bunu ancak gerçek bir koşu gösterdi.

İkinci bulgu: **37090 tek başına geçti**, çünkü testi yok. Yani "testi olmayan
dilim yeşil görünür" tuzağı var. Rapor bunu `test: —` diye açıkça yazıyor.

---

## Neden dilimler Android'siz

Her dilim yalnız `java.*` import ediyor. Ölçüldü: 30 kaynak dosyada toplam
**2 import** (`java.time.Instant`, `java.time.ZoneId`). Sonuç: Android SDK,
Gradle, NDK, `rclone.aar` gerekmiyor — 80 MB'lık `kotlinc` ve üç jar yeter,
iş ~2 dakikada bitiyor.

Kapı bunu bir kural olarak uygular: bir dilim `android.*` veya `androidx.*`
import ederse "**Android sızıntısı**" olarak ayrı raporlanır. Dilimin tek başına
derlenebilirliği bozulmuş demektir.

---

## Sınırı — neyi kanıtlamaz

Kapı "**dilim kendi içinde tutarlı mı**" der. "**Dilim uygulamayla uyumlu mu**"
demez. Dilim `ChatViewModel`'e yanlış imzayla bağlanırsa dilim yeşil kalır,
uygulama kırılır.

İki ayrı kapı:
1. **Dilim kapısı** (bu) — erken, ucuz, izolasyon söyler.
2. **APK derlemesi** — geç, pahalı, bütünleşmeyi söyler.

Birincisi olmadan ikincisi çorba olur; ikincisi olmadan birincisi yanlış güven
verir. Sıra: dilim → birleştir → APK.

---

## `deps` ne zaman kullanılır

37088, `data/journal/FileLock.kt`'ye bağlı (zaten repoda var, Android'siz).
Manifest'te `deps` alanına yazılı. Kural: `deps` **dilimin kendi dosyası
olmayan, ama derlemede gereken** repo dosyalarıdır ve onlar da Android'siz
olmak zorunda — değilse dilim tek başına derlenemez hâle gelir.

Yeni bir dilim eklerken `deps`'e bir şey yazmak zorunda kalıyorsan, bir kez
daha düşün: dilim gerçekten bağımsız mı, yoksa mevcut koda sıkı mı bağlı?
