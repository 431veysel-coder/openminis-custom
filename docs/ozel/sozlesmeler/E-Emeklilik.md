<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# E · Emeklilik sözleşmesi

> **Saklama, süre, budama, silme**

Kademe: **K1** · Öncelik: **2**

## Amaç

Hiçbir şeyin sonsuza kadar büyümemesini sağlar: artifact ömrü, cache budama, eski dosya, ölü kod.

## Ne zaman açılır

Kalıcı bir çıktı üretildiğinde ya da biriktiren bir yapı eklendiğinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `nesne` | ne birikiyor | ✅ |
| `omur` | ne kadar saklanır | ✅ |
| `budama` | nasıl temizlenir (komut/zamanlanmış) | ✅ |
| `maliyet` | yer/kota etkisi | — |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| E1 | Artifact | log 90 gün, ara ürün 7, APK release'te kalıcı |
| E2 | Cache | anahtar + eviction; süresiz cache yok |
| E3 | Ölü kod | okuyanı olmayan dosya/anahtar silinir |
| E4 | Yedek | budama tarihe göre; son N saklanır |

## Kapı — makine ne denetler

Yeni artifact'ta `retention-days` yoksa uyarı

## Bu depodan gerçek örnek

154 artifact silindi (3,8 GB → 206 MB); loglar o temizliğin içindeydi

## Yanlış kullanım

Sınırsız büyüyen rapor dizini

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
