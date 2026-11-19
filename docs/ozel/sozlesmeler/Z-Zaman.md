<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# Z · Zaman sözleşmesi

> **Bütçe: süre, tur, jeton, para**

Kademe: **K1** · Öncelik: **2**

## Amaç

Bir işin ne kadar kaynak harcayabileceğini sabitler. Bütçesiz iş ya yarım kalır ya sonsuza kadar sürer.

## Ne zaman açılır

Uzun süren ya da pahalı (jeton/CI dakikası) işlerde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `butce` | süre / tur / jeton / dakika | ✅ |
| `harcanan` | gerçekleşen | ✅ |
| `esik` | ne zaman durulur | ✅ |
| `kalan` | devam için ne gerekiyor | — |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| Z1 | Duvar saati | derleme ~12 dk, compile kapısı ~2 dk |
| Z2 | Tur | ajan turu sınırı; 3. turda yaklaşım değişir |
| Z3 | Jeton | bağlam maliyeti; uzun dosyayı tekrar okumak pahalı |
| Z4 | Para | CI dakikası + API kotası |

## Kapı — makine ne denetler

`butce` aşılmış ve `kalan` boşsa uyarı (yarım iş)

## Bu depodan gerçek örnek

Debug koşu 11m28s; release ~12 dk

## Yanlış kullanım

Bütçesiz 'hepsini denetle' — 16 dk'da timeout

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
