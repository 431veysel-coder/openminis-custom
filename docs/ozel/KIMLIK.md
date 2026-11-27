# YAYIN KİMLİĞİ — tek kaynak

> `scripts/check_identity.sh` ve `scripts/ozel/kimlik-url.py` bu dosyayı okur.
> Buradaki değerler değişirse, `app/build.gradle.kts` ile **aynı commit'te**
> değişmek ve bir sürüm notuyla açıklanmak zorundadır (bkz.
> `HATA-DEFTERI.md` → H-009).

| Alan | Değer | Not |
|---|---|---|
| applicationId | `com.minis.x` | `.44`–`.53` arasında `com.veysel.minis`'ten değişti |
| namespace | `com.openminis.app` | upstream ile aynı (R sınıfı, kaynak yolları) |
| app etiketi | `Minis Özel` | `strings.xml` → `app_name` |
| imza anahtarı | `3e62d262…76bc9` (SHA-256 parmak izi) | 4096-bit RSA, 2059'a kadar |
| sürüm şeması | `1.15-ozel.NN` / versionCode `37100 + NN` | |
| güncelleme deposu | `431veysel-coder/openminis-custom` | `BuildConfig.RELEASE_REPO` |
| güncelleme kanalı | stable = `v1.15-ozel.*` etiketleri | |

## Uyarı — paket adı değişikliği

`com.veysel.minis` kurulu bir cihaza `com.minis.x` **güncelleme olarak
kurulamaz**: Android bunu ayrı bir uygulama sayar. Sonuç:

- eski paketin sohbetleri yeni pakete **taşınmaz** (uygulama verisi
  `applicationId` altında yaşar),
- iki uygulama yan yana durur, ikisi de güncellenebilir kalır.

Bilinçli bir karar ise sorun yok; kazara olduysa geri alınması gerekir.
Geri alma da bir göç planı ister (dışa aktarma → yeni paket → içe aktarma).
