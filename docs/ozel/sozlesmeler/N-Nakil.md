<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# N · Nakil sözleşmesi

> **Devir: alt ajana ne verilir, ne döner**

Kademe: **K1** · Öncelik: **2**

## Amaç

Bir işin başka bir ajana/oturuma devredilmesini kaydeder: brief, bütçe, beklenen çıktı ve dönen sonucun nasıl doğrulandığı.

## Ne zaman açılır

Alt ajan, başka oturum ya da insan devreye girdiğinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `kime` | alt ajan / oturum / kullanıcı | ✅ |
| `brief` | tam görev metni (kısayol değil) | ✅ |
| `butce` | süre + tur sınırı | ✅ |
| `beklenen` | dönecek çıktının biçimi | ✅ |
| `sonuc` | dönen şey + doğrulandı mı | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| N1 | Bağlam | devralan konuşmayı görmez; brief eksiksiz olmalı |
| N2 | Bütçe | geniş tarama için 10 dk az (16 dk'da timeout yaşandı) |
| N3 | Kanıt | dönen iddia doğrulanmadan kullanılmaz |
| N4 | Sahiplik | devir sırasında dosya kilidi kimde |

## Kapı — makine ne denetler

`brief` alanı boşsa hata; timeout sonrası `sonuc` zorunlu

## Bu depodan gerçek örnek

16 dk'lık denetim ajanı timeout → çıktı yok, iş yeniden yapıldı

## Yanlış kullanım

'Şu dosyaya bak' — kapsamı, çıktısı, bütçesi belirsiz devir

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
