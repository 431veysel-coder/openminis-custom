# Minis Özel — ANA BAĞLAM

Bu dosya cihazda yaşar. Her oturumun başında okunur. Yeni özellik eklerken buradaki
kurallara uyulur; her seferinde sıfırdan plan yapılmaz.

## Kimlik

- Depo: `431veysel-coder/openminis-custom` · upstream `OpenMinis/OpenMinis` · GPL-3.0
- Paket: `com.veysel.minis` · namespace **bilerek** `com.openminis.app`
- İmza SHA-256: `3e62d262631a8182167bfb22df64ae413286257fc6e6a77a08c6ced0bc0bb6c9`
- Uygulama adı: 22 dilde "Minis Özel" (`check_identity.sh` kapısı)

## Sürüm şeması

- `versionName = 1.15-ozel.<major>.<minor>` · `versionCode = 37000 + <sıra>`
- **Harcanmış (boşa giden):** 37009–37013 (eski denemeler), 37017–37027 (yayınlanmayan
  kırık seri). Tekrar kullanılmaz.
- **Kullanımda:** 37014 = ozel.9, 37015 = ozel.10, **37016 = ozel.10.1 (yayında)**
- **Sıradaki:** 37028 = `1.15-ozel.11.1`
- Numara asla geri gitmez. Android düşük versionCode'lu APK'yı kabul etmez; "geri alma" =
  eski kodu **daha yüksek** numarayla yeniden yayınlamak.

## Yayın akışı

1. Dal → push → GitHub Actions `apk.yml` → Release (etiket CI'ın **çıktısı**)
2. Elle tag push edilmez.
3. İmza anahtarı yoksa release derlemesi kırılır (debug anahtarına dönüş yok).
4. Yayın öncesi elle doğrula: imza sertifikası, versionCode, `app_name`, `libproot*`, rootfs.

## Taban

- **`v1.15-ozel.10.1` (versionCode 37016)** — son sağlıklı, yayınlanmış taban.
- `main`'deki ozel.10.2–10.21 serisi **kırık** (NoteRepository KSP/kaçış hatası, 9 kör
  düzeltme). O seriden kod kopyalanmaz, yalnızca fikir olarak okunur.

## Değişmez kurallar

1. Her özellik **ayrı commit**; CI yeşil değilse sonraki özelliğe geçilmez.
2. Özellik anahtarı **veri silmez** — yalnızca giriş noktasını gizler / arka plan
   davranışını durdurur.
3. Yeni özellik = hub'a satır + kayıt defterine anahtar. Yeni `composable()` rotası
   zorunlu değil.
4. Sırlar (API anahtarı, token) hafızaya ve günlüklere yazılmaz.
5. Kapsam genişletilmez; kullanıcı ne dediyse o.

## Bilinen tuzaklar

- `AppIconRepository.PACKAGE_NAME` — paket adı değişirse ikon yenileme kırılır.
- `raw.githubusercontent.com` Türkiye ağlarında sık engelli → ağdan gelen katalog/veri
  tek başına kaynak olamaz, gömülü yedek şart.
- `BrowserTabPoolRegistry` global — çoklu panelde tarayıcı sekmeleri karışır.
- `ChatViewModelStore` sınırlı önbellek; panel VM'leri tahliye edilmemeli.
