<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# B · Bağımlılık sözleşmesi

> **Sürüm, pin, lisans, yükseltme**

Kademe: **K1** · Öncelik: **2**

## Amaç

Dışarıdan gelen her şeyin sürümünü ve yükseltme yöntemini sabitler. Hareketli etiket, kontrolü başkasına vermektir.

## Ne zaman açılır

Yeni kütüphane/action eklendiğinde ya da sürüm yükseltildiğinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `ad` | paket/action | ✅ |
| `surum` | tam sürüm ya da SHA | ✅ |
| `pin` | etiket mi SHA mı + neden | ✅ |
| `yukseltme` | nasıl yükseltilir (komut/PR) | ✅ |
| `lisans` | lisans + uyum notu | — |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| B1 | Pin | üçüncü taraf + yazma yetkisi olan action SHA'ya |
| B2 | Araç zinciri | JDK/NDK/CMake/Go/Gradle pinli |
| B3 | Sürüm aralığı | `+` ve `latest` yasak |
| B4 | Lisans | GPL uyumu: yeni bağımlılık lisansı kontrol edilir |

## Kapı — makine ne denetler

Üçüncü taraf action etikette kalmışsa uyarı

## Bu depodan gerçek örnek

`softprops/action-gh-release@3bb1273…` (v2 SHA)

## Yanlış kullanım

`@master` / `@latest` ile yayın hattı

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
