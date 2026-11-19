<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# V · Veri sözleşmesi

> **Şema, göç, geri uyum**

Kademe: **K2** · Öncelik: **1**

## Amaç

Veritabanı ve dosya biçimi değişimlerini geri alınabilir biçimde kaydeder. Göç test edilmezse kullanıcının verisi sessizce bozulur.

## Ne zaman açılır

Room sürümü, tablo/kolon, JSON biçimi değiştiğinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `surum` | eski → yeni şema sürümü | ✅ |
| `goc` | göçün ne yaptığı (tek cümle) | ✅ |
| `sema_dosyasi` | schemas/<db>/<n>.json commit edildi mi | ✅ |
| `test` | MigrationTestHelper testi | ✅ |
| `geri` | geri alma: yeni göç mü, veri dönüşümü mü | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| V1 | Şema dosyası | her sürüm için JSON repoda — karşılaştırma tabanı |
| V2 | Göç testi | 14→15 gerçek veriyle koşar |
| V3 | İleri uyum | eski uygulama yeni veriyi açınca ne olur |
| V4 | Yıkıcı göç | kolon düşürme ayrı karar (K) gerektirir |

## Kapı — makine ne denetler

Şema sürümü değişmiş ama `schemas/<db>/<n>.json` yoksa hata

## Bu depodan gerçek örnek

Room 14→15 göçü var, `15.json` üç sürüm boyunca yoktu

## Yanlış kullanım

Göçü yazıp şema dosyasını commit etmemek

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
