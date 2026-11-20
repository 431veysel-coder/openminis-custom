# DENETİM HATTI — nasıl çalışır, neden böyle

> 11.2'de kuruldu. Dört CI turu aynı sınıf hatalarla kaybedildikten sonra.

## Sorun

Aynı hata ailesi dört kez CI'da patladı, her biri 4–5 dakikalık tur:

| Hata | CI mesajı |
|---|---|
| `TranslationPrefs.engine(...)` import edilmemiş | `Unresolved reference 'TranslationPrefs'` |
| `<string>Güncelle'ye</string>` | `Invalid unicode escape sequence` (aapt2 flatten) |
| `Feature.BROWSER_TAB_LIMIT` bu dalda yok | `Unresolved reference 'BROWSER_TAB_LIMIT'` |
| `R.string.notes_title` tanımsız | `resource string/notes_title not found` |

Dördü de derleyici gerektirmeden, kaynak metninden görülebilir.

## Üç deneme, iki ders

**Deneme 1** — O(dosya × tip) tarama. 25 saniyede bitmedi, takıldı.
**Ders:** algoritma doğrusal olmalı; tip dizini tek geçişte kurulmalı.

**Deneme 2** — düzeltilmiş ama **1926 sorun** üretti. Dağılım: enum 1237,
import 674, denge 66. Neredeyse hepsi yanlış pozitif.
**Ders:** yanlış pozitif, kaçırmaktan kötüdür. Gürültülü kapıyı insan kapatır.
Kök nedenler: yorumlar/string'ler taranıyordu; aynı basit ad iki farklı tipe
işaret edebiliyordu (`Channel.CONFLATED` kotlinx, bizim `Channel` değil);
iç içe enum'lar yanlış ayrıştırılıyordu; `--staged` modunda tip dizini yalnız
sahnelenen dosyalardan kuruluyordu (yani hiçbir şey yakalanmıyordu).

**Deneme 3 (mevcut)** — 0 sorun, 10 s.

## Tasarım

### 1. Kesinlik > kapsam
Her denetim yalnız **emin olduğunda** konuşur:

- Yorumlar ve string/karakter literal'leri taranmadan önce boşlukla değiştirilir
  (satır numaraları korunur).
- Import denetimi: yalnız **tek** tanımlı tipler; aynı paket import gerektirmez;
  wildcard import varsa susulur; JDK adları (`System`) ve generic parametreler
  (`S`, `T`) dışlanır.
- Enum denetimi: yalnız proje içinde tanımlı, **tek** ve **tam nitelikli import
  ile eşleşen** ya da aynı paketten gelen enum'lar. Companion `val`'leri de üye
  sayılır (`ShellExecutionStrategy.DEFAULT` gerçek kod, enum girdisi değil).
- Denge denetimi kapıyı **tutmuyor**: sözdizimi hatası derlemede anında patlar,
  burada yakalanacak sessiz hata yok; 66 yanlış pozitif üretiyordu.

### 2. Baseline
`scripts/preflight-baseline.json` kabul edilmiş sorunları tutar. Kapı yalnız
**yeni** sorunda düşer. Şu an **0 kayıt** — hedef bu. Bir kaydı silmek, o
sorunu düzeltmeyi taahhüt etmek demektir.

### 3. Watchdog — iki kademe
| Kademe | Nerede | Sınır | Aşılırsa |
|---|---|---|---|
| İç | `preflight.py` | 45 s | çıkış 2 |
| Dış | `preflight.sh` (`timeout`) | 90 s | çıkış 2 |

Busybox `timeout` SIGTERM (143), GNU 124, SIGKILL 137 döndürür — **üçü de 2'ye**
çevrilir. Takılan bir kapı **asla 0 döndürmez**.

### 4. Denetimler kendini sınar
`--selftest` her denetime bilinen-kötü bir örnek verir ve denetimin gerçekten
ateşlendiğini doğrular (9 vaka). Sessizce bozulan bir denetim, sonsuza kadar
"temiz" demeye devam ederdi. Bu, CI'da ayrı bir adım olarak koşar.

## Ölçüm

| Mod | Süre | Kapsam |
|---|---|---|
| Tam ağaç | 10.6 s | 1520 Kotlin + 477 strings dosyası |
| `--staged` (hook) | 7.3 s | rapor yalnız sahnelenenlerde, dizin tam ağaçtan |
| `--selftest` | <1 s | 9 vaka |
| CI `preflight` işi | ~15 s | selftest + tam hat |

Katman süreleri: L0 strings 1.2 s · L0 version 0.0 s · L1 R.string 1.4 s ·
L2 import 0.6 s · L2 enum 0.75 s.

## Entegrasyon

```
git commit
   └─ .githooks/pre-commit  (kurulum: sh scripts/install_hooks.sh)
        └─ scripts/preflight.sh --staged   [timeout 60]

git push
   └─ CI: job "Preflight (fast gate)"   (~15 s, build'den ÖNCE)
        ├─ python3 scripts/preflight.py --selftest
        └─ scripts/preflight.sh          [timeout 120]
   └─ job "build" needs: [preflight, native]
```

## Kanıt (gerçek test)

Hook, kasıtlı bir ihlalle sınandı: `NoteStore` import edilmeden kullanıldı.
Aynı koşuda **ikinci** bir gerçek hata da yakalandı — TR metnindeki
kaçırılmamış kesme işareti (`Güncelle'ye`), ki o sırada `git reset` yüzünden
geri gelmişti. İki hata, commit engellendi.

## Ne yakalamaz

Bu bir derleyici değil. Yakalamadıkları: tip uyuşmazlıkları, eksik `when`
dalları, arayüz değişiklikleri, Kotlin sözdizimi. Onlar için CI'ın
`Unit tests` + `Android APK` işleri var — bu hat yalnız onlara **gitmeden**
en sık ve en ucuz hataları keser.
