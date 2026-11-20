# Kimlik geçişi — `com.minis.x` tek kimlik

**Karar (20 Kasım 2026):** Bu çatal bundan sonra **tek** kimlikle devam eder:
`com.minis.x` / **Minis X**. Eski paket adı emekli. Geri dönüş yok.

## Ne değişti

| | önce | sonra |
|---|---|---|
| applicationId | `com.veysel.minis` | **`com.minis.x`** |
| ad | Minis Özel | **Minis X** |
| namespace | `com.openminis.app` | `com.openminis.app` — **bilerek aynı** |

`namespace` bir sınıf adı önekidir. `AppIconRepository` activity-alias CLASS
adlarını bu önekten kurar; değişirse ikon alias'ları çalışmaz ve bu **derlemede
görünmez, çalışma anında görünür**. Bu yüzden yalnız PackageManager'ın gördüğü
kimlik (applicationId) değişir.

## Veri taşınmaz — bilerek

`applicationId` değişince Android yeni bir uygulama sayar: yeni `filesDir`, yeni
Room veritabanı, yeni izinler. Eski kurulum güncellenmez, yanına kurulur.

Karar: **otomatik geçiş yok**. Sohbetler/ayarlar/anahtarlar taşınmaz;
kullanıcı isterse yedek paketiyle kendi taşır. Bunun nedeni, iki paketin aynı
sürüm kodlarını farklı APK'lar olarak gösterme riskidir — yarım bir taşıma
"sessizce eski veri" bırakır ve bu depoda o hata sınıfı dört kez görüldü.

## Cihaz tarafında yeniden verilmesi gerekenler

- **Erişilebilirlik servisi** — component kimliği paket adını içerir:
  `com.minis.x/com.openminis.app.accessibility.MinisAccessibilityService`
  (eski kayıt `com.veysel.minis/…` idi; ikisi de kayıtlı olabilir, eskisi
  uygulama kaldırılınca ölü satır olarak kalır).
- **Shizuku yetkisi** — uid başına verilir; yeni uygulama kendisi ister.
- **Sağlayıcılar** — yeni `filesDir` boş başlar. API anahtarları yeniden girilir.

## Sürüm numarası

`versionCode = upstream*1000 + özel` şeması korunur. **37092 yanmıştır**;
sıradaki **37093**. Bir numara iki farklı APK'yı gösteremez.

## Kapı

`scripts/check_identity.sh` bu kararı zorlar: applicationId, namespace, `app_name`,
tüm locale başlıkları, self-update deposu ve versionName/versionCode birlikte
denetlenir. Bir literal yanlış yere yazılırsa derleme değil **kapı** kırmızı olur.

## Tarihsel kayıtlar

`docs/ozel/arsiv/` ve yayınlanmış sürüm notları **değiştirilmez**. Eski paket
adının geçtiği belgeler o günün gerçeğidir; düzeltmek kaydı bozar. Yeni belge
yazarken `com.minis.x` kullanılır.
