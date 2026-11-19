<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# S · Sürüm sözleşmesi

> **versionCode, yayın, imza, geri dönüş yok**

Kademe: **K2** · Öncelik: **1**

## Amaç

Geri dönüşü olmayan tek işi korur: yayın. Yakılan numara geri gelmez, yanlış imza kimseyi güncelleyemez bırakır.

## Ne zaman açılır

`versionCode` değiştiğinde ya da release oluşturulduğunda.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `surum` | versionCode + versionName | ✅ |
| `yayin` | release etiketi + Latest mi | ✅ |
| `imza` | sertifika SHA-256 | ✅ |
| `onceki` | bir önceki yayın (karşılaştırma) | ✅ |
| `geri_alma` | eski kod + YENİ numara (downgrade yok) | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| S1 | Numara | yakılan numara listeye girer, asla tekrar kullanılmaz |
| S2 | Kapı | yayın öncesi versionCode > yayındaki numara |
| S3 | İmza | release anahtarı yoksa yayın KIRILIR, debug'a düşmez |
| S4 | Geri dönüş | eski kod daha YÜKSEK numarayla yeniden yayınlanır |
| S5 | Güncelleyici | APK adı `MinisApp-<name>-build<code>-<abi>.apk` |

## Kapı — makine ne denetler

Yayın işinde S kaydı yoksa hata; yayındaki numara ≤ yeni numara değilse hata

## Bu depodan gerçek örnek

37009–37013 yakıldı → bir numara iki APK'yı gösteremez

## Yanlış kullanım

Aynı versionCode ile yeniden yayın: kimse güncelleme görmez

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
