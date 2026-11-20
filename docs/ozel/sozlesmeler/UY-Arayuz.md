<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# UY · Arayuz sözleşmesi

> **Yerleşim, ölçü, erişilebilirlik**

Kademe: **K1** · Öncelik: **2**

## Amaç

Görsel değişimin ölçülebilir sınırlarını sabitler: genişlik tavanı, punto, dokunma hedefi, renk körlüğü, döndürme.

## Ne zaman açılır

Ekrana yeni bir öğe eklendiğinde ya da mevcut yerleşim değiştiğinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `yer` | ekran + konum | ✅ |
| `olcu` | genişlik/yükseklik/punto dp-sp | ✅ |
| `erisim` | contentDescription + renk dışı ayırt edici | ✅ |
| `durumlar` | boş / dolu / hata / karanlık tema | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| U1 | Tavan | başlığı sıkıştırmayan genişlik sınırı |
| U2 | Dokunma | en az 40dp hedef (ikon küçükse padding) |
| U3 | Renk körlüğü | renk tek başına anlam taşımaz; ikon da değişir |
| U4 | Uyandırma | boştayken animasyon/sayaç durur |

## Kapı — makine ne denetler

Yeni composable'da `contentDescription` yoksa uyarı

## Bu depodan gerçek örnek

Sağlık chip'i: 96dp tavan, 10sp, 10dp ikon, 6 ayrı ikon

## Yanlış kullanım

Aynı bilgiyi iki yerde göstermek (chip + RetryBadge çakışması)

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
