<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# IZN · Izin sözleşmesi

> **Token, sır, yetki kapsamı**

Kademe: **K2** · Öncelik: **1**

## Amaç

Kimin neye yazabildiğini ve sırrın nerede durduğunu sabitler. Log/artifact sır sızdırmanın en kolay yoludur.

## Ne zaman açılır

CI izni, API anahtarı, dosya izni ya da sır akışı değiştiğinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `kaynak` | hangi izin/sır | ✅ |
| `kapsam` | en dar kapsam (read/write, hangi iş) | ✅ |
| `saklama` | nerede duruyor (secret/env/dosya) | ✅ |
| `sizinti` | log/artifact'a düşme yolu + önleyici | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| İ1 | En az yetki | `contents: write` yalnız yazan işte |
| İ2 | Maskeleme | canlı log maskelenir, artifact maskelenmez |
| İ3 | Sabitleme | üçüncü taraf action SHA'ya sabitlenir |
| İ4 | Sır yazımı | deftere değer değil AD yazılır |

## Kapı — makine ne denetler

Artifact'a log yükleyen işte scrub adımı yoksa uyarı

## Bu depodan gerçek örnek

`contents: write` akış genelindeydi → yalnız build işine indirildi

## Yanlış kullanım

Sırrı `echo` ile loga basmak (maskeleme yalnız canlı akışta)

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
