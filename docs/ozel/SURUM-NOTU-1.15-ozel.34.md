# SÜRÜM NOTU — 1.15-ozel.34 / 37115

> İlk APK yayını: **1.15-ozel.33 / 37114** (2026-11-24). Bu sürüm cihazda
> yapılan **canlı sınama** sonrası çıkan iki düzeltmeyi taşıyor.

## .33'te ne oldu (yayınlandı)

`.22`–`.33` arası **on iki sürümlük birikim** tek derlemede doğrulandı:
compile + 262 birim testi geçti, APK 43.4 MB olarak yayınlandı.

İlk denemede (`.32`) birim testleri düştü; sebep kendi hatamdı:
`absentWhy` alanına sınıf etiketi yazmıştım ("kota: rate limited"), mevcut test
ham gerekçeyi bekliyordu. **Test haklıydı** — kayıt sağlayıcının söylediğini
taşır, yorum sunumda eklenir. Düzeltildi.

## .34 — cihazda canlı sınama sonrası

### Kapanan gerçek hata: `apt install` (özellikle önemli)

Cihazda ölçüldü:
- `id -u` → **10000** (kök olmayan kimlik düzeltmesi çalışıyor)
- `apt-get update` → **çalıştı**, 608 kB indi
- `apt-get install jq` → **düştü**:
  `E: Archives directory /data/data/com.termux/cache/apt/archives/partial is missing.`

Yani `.20`'de çözdüğüm kök reddi gerçekten çözülmüştü, ama arkasında ikinci
bir engel vardı: bootstrap arşivinde apt'in `cache/` dizini yok (gerçek
Termux onu ilk açılışta kendisi kurar, bizim katman kurulumumuzda o adım
yoktu). Dizinleri elle açınca `jq` kuruldu.

Düzeltme:
- `TermuxLayer.requiredAptDirs(rootfs)` / `ensureAptDirs(rootfs)` — yol listesi
  **tek yerde** (kardeş hata sınıfı: dört PRoot kurucusuna bağ ekleyip birini
  unutmak).
- Kurulum adımına `7b` eklendi.
- **Var olan kurulumlar için** komut yolunda da onarım (`ExecutionCoordinator`):
  katman .34'ten önce kurulduysa dizinler yoktur; `mkdirs` zaten varsa hiçbir
  şey yapmaz.

### Cihazda doğrulananlar (kurulu .33)

| Alan | Sonuç |
|---|---|
| `device`, `location`, `contacts`, `photos`, `notification` | geçti |
| `speech`, `speak`, `weather`, `calendar` | geçti |
| `a11y-cli` (gerçek ekran okuma) | geçti |
| Konsey koşusu (`team_run`) | geçti — 1 tur, 3084 token, `converged` |
| Konsey dalga satırı | göründü: "Ekip 5 üye istiyor; dalga 4 ile sırayla çalışacak" |
| Termux kök olmayan kimlik | geçti (`id -u` = 10000) |
| Termux `apt install` | **düştü** → bu sürümde düzeltildi |
| `android-shizuku-cli` | kapalı (Ayarlar › İzinler › Entegrasyonlar) |

### Konsey hakkında bir not

Koşuda üyeler "dizinde 0 dosya var" dedi. Bu bir hata değil: üyeler **kendi
izole çalışma alanlarında** çalışıyor (`sp_<run>_<üye>`), paylaşılan
`/var/minis/workspace` onların sandbox'ı değil. Denetçi de sonucu "unsourced"
diye işaretledi — yani dürüstlük katmanı çalışıyor.

## Hâlâ açık

- `§5.1/5.2` sanal ekran yüzeyi (yakalama hattı yok, `needsCapture` latent)
- `§7.1` DNS kalıcılığı (kalıcı çözüm kullanıcı vekilini bozabilir)
- `§8.4` erişilebilirlik (TalkBack ile cihaz testi gerek)
