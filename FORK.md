# FORK.md — bu çatalın upstream'den farkı

Bu depo `OpenMinis/OpenMinis`'in bir fork'udur (GPLv3). **Uygulama davranışı
upstream ile aynıdır.** Tek fark, uygulamanın resmî sürümle yan yana
kurulabilmesi için gereken kimliktir.

Taban: `1a71df0` — upstream 1.15 stabil (2026-10-26).

## 1. Kasıtlı farklar (kimlik)

| Alan | Upstream | Bu çatal |
|---|---|---|
| `applicationId` | `com.openminis.app` | `com.veysel.minis` |
| `namespace` | `com.openminis.app` | **aynı bırakıldı** |
| Uygulama adı | Minis | Minis Özel |
| `versionCode` | 37 | 37001 |
| `versionName` | 1.15 | 1.15-ozel.1 |
| İmza | OpenMinis anahtarı | kendi 4096-bit RSA anahtarımız |
| `RELEASE_REPO` | `OpenMinis/OpenMinis` | bu çatal |
| `AUTO_UPDATE_CHECK` | true (github flavor) | false |

`versionCode = upstream * 1000 + 1` kuralı: upstream 38 yayınlandığında biz
`38001` yaparız (bkz. §4).

## 2. Kod değişiklikleri (toplam 5 satır)

Hepsi paket adı sabiti. Uygulama mantığına dokunulmadı.

| Dosya | Ne | Hangi değer |
|---|---|---|
| `accessibility/MinisAccessibilityService.kt` | `SERVICE_ID` | **applicationId** |
| `diagnostics/ResourceWatermark.kt` | `/data/user/0/<id>/` eşleşmesi | **applicationId** |
| `res/xml/shortcuts.xml` (×3) | `targetPackage` | **applicationId** |
| `res/values/strings.xml` | `app_name` | — |
| `data/repository/AppIconRepository.kt` | `PACKAGE_NAME` | **namespace** (sınıf adı ön eki!) |

> `AppIconRepository.PACKAGE_NAME` tuzağı: `ComponentName(ctx, "$PACKAGE_NAME.MainActivityIconAuto")`
> için kullanılıyor, yani **sınıf adı** üretiyor. Namespace değişmediği sürece
> `com.openminis.app` kalmalı. Bir kez yanlışlıkla applicationId yapıldı ve
> simge değiştirme `ClassNotFoundException` ile kırıldı — derleme hatası
> vermedi, sadece çalışma anında patladı.

Bunu bir daha yaşamamak için: `scripts/check_identity.sh`. CI her derlemede
çalıştırır ve hangi sabitin hangi değeri alması gerektiğini doğrular.

## 3. Altyapı farkları (uygulama kodu değil)

- `.github/workflows/apk.yml` — derleme hattı (upstream'de yok; onlar özel ağaçtan derliyor).
  - İki NDK kurar: **27.0.12077973** (AGP'nin CMake JNI işleri) + **r28** (proot çapraz derlemesi).
  - `gradle.properties`'e yalnız CI'da heap ekler (`-Xmx4g`, Kotlin daemon `-Xmx6g`);
    repodaki 2 GB varsayılanı OOM veriyor.
  - APK içeriğini doğrular: applicationId, imza parmak izi, `libproot-loader*.so`, rootfs.
- `src/android/app/build.gradle.kts` — `own` signingConfig (CI secret'ından veya
  `keystore.properties`'ten okur; yoksa debug anahtarına düşer).
- `scripts/check_identity.sh` — kimlik muhafızı.

## 4. Upstream güncellemesi alma

```sh
git remote add upstream https://github.com/OpenMinis/OpenMinis.git
git fetch upstream
git checkout -b sync-1.16 upstream/main

# kimlik yamasını yeniden uygula
python3 ../../../patch_identity.py      # ya da elle: bkz. §2 tablosu

bash scripts/check_identity.sh           # geçmezse derleme yapma
git diff upstream/main --stat            # 5 satır + build.gradle + workflow görmelisin
```

Sonra `versionCode`/`versionName` güncelle (§1).

## 5. Bilinen sınırlar

- **Kaynak 1.15'te duruyor.** 1.16 beta'lar upstream'in özel ağacından
  yayınlanıyor; genel aynada 1.16 kaynağı yok. Resmî uygulaman 1.16-beta.5
  ise bu APK **ondan eski** görünecek — fark bizden değil.
- 1.15'in bilinen hatası **#426**: uzun oturumlarda `MarkdownParser` her satır
  için 8 regex derliyor → VMA tükenmesi → `SIGABRT`. 1.16'da düzeltildi,
  bizim tabanda **açık**.
- iOS derlenemiyor (macOS/Xcode gerekir); bu çatal yalnız Android.
- `minis://` şema çakışması: iki uygulama da aynı özel şemayı kaydediyor.
  Resmî uygulama da kuruluysa harici bir `minis://` bağlantısı seçim
  penceresi gösterir.
- OAuth geri dönüş portları (`localhost:54545/1455/8085/3000-3002`) iki
  uygulamada aynı. Sağlayıcılar bu adresleri beyaz listede tuttuğu için
  keyfî değiştirilemez; oturum açarken diğer uygulamanın kurulu olması
  karışıklık yaratabilir.
