<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# A · Ayar sözleşmesi

> **Yapılandırma: varsayılan, anahtar, kapsam**

Kademe: **K1** · Öncelik: **1**

## Amaç

Bir ayarın adını, varsayılanını, kimin okuyacağını ve kapalıyken ne olacağını sabitler. Anahtarı olup satırı olmayan özellik kullanıcıdan saklanmış demektir.

## Ne zaman açılır

Yeni bir özellik anahtarı ya da ayar alanı eklendiğinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `anahtar` | kalıcı anahtar adı (snake_case) | ✅ |
| `varsayilan` | açık/kapalı + gerekçe | ✅ |
| `okuyan` | üretim kodunda okuyan dosya(lar) | ✅ |
| `kapali_davranis` | kapalıyken ne olmuyor | ✅ |
| `satir` | ayar ekranındaki satır (varsa dosya:satır) | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| A1 | Geri dönüş | kapalıyken davranış kalkar, veri SİLİNMEZ |
| A2 | Görünürlük | anahtar varsa ayar satırı da olmalı |
| A3 | Ölü anahtar yasağı | okuyan 0 ise anahtar kaldırılır |
| A4 | Varsayılan | güvenlik artıran açık, davranış değiştiren kapalı |

## Kapı — makine ne denetler

`okuyan` boşsa hata (ölü anahtar); anahtar var + satır yoksa uyarı

## Bu depodan gerçek örnek

COUNCIL: anahtar vardı, ayar satırı yoktu → kullanıcı kurtaramıyordu

## Yanlış kullanım

STATION: varsayılan açık, `ui/station/` hiç yazılmadı

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
