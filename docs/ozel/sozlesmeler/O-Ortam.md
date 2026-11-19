<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# O · Ortam sözleşmesi

> **Nerede çalışıyoruz: araç, sürüm, sınır**

Kademe: **K1** · Öncelik: **2**

## Amaç

Çalışma ortamının yeteneklerini ve sınırlarını kaydeder: JVM yok, ICMP bloklu, aarch64, sürümler pinli. Olmayanı varsaymak tur kaybıdır.

## Ne zaman açılır

Yeni araç/varlık eklendiğinde ya da ortam sınırına çarpıldığında.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `ortam` | yerel sandbox / CI / cihaz | ✅ |
| `arac` | araç + sürüm | ✅ |
| `sinir` | çalışmayan şey + belirtisi | ✅ |
| `cozum` | sınırın etrafından nasıl dolaşılır | — |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| O1 | Ölçüm | sürüm `komut --version` ile doğrulanır, varsayılmaz |
| O2 | Sınır | 'çalışmıyor' + hata metni birlikte yazılır |
| O3 | Sır | ortam değişkeni ADI yazılır, değeri asla |
| O4 | Bölünme | yerel yoksa CI'da doğrula, 'koşmadı' diye yaz |

## Kapı — makine ne denetler

Sır değeri içeren kayıt reddedilir; `arac` sürümsüzse uyarı

## Bu depodan gerçek örnek

Bu cihazda JVM yok: 'Failed to mark memory page as executable'

## Yanlış kullanım

'gradle çalışıyor' varsayıp 3 tur boyunca hata aramak

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
