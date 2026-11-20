<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# H · Hatırlama sözleşmesi

> **Ne hatırlanacak, ne hatırlanmayacak**

Kademe: **K1** · Öncelik: **2**

## Amaç

Kalıcı hafızaya neyin yazılacağını ve neyin yazılmayacağını sabitler. Hafıza günlüğe dönüşürse okunmaz; sır yazılırsa sızar.

## Ne zaman açılır

Kullanıcı tercihi, proje kuralı ya da tekrar kullanılacak bilgi çıktığında.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `ne` | hatırlanacak bilgi, tek cümle | ✅ |
| `kapsam` | günlük / GLOBAL | ✅ |
| `neden` | hangi gelecek kararı etkiler | ✅ |
| `sir` | sır içeriyor mu (evet ise yazılmaz) | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| H1 | GLOBAL eşiği | yalnız kullanıcı isterse; günlük varsayılan |
| H2 | Sır yasağı | değer değil AD yazılır ('anahtar adı: X') |
| H3 | Çürüme | yanlış hatıra silinmez, düzeltilir |
| H4 | Tekrar | aynı bilgi ikinci kez yazılmaz, birleştirilir |

## Kapı — makine ne denetler

Kayıtta sır deseni (token/PEM/uzun base64) varsa hata

## Bu depodan gerçek örnek

37094: sürüm şeması + yakılmış numaralar GLOBAL'e yazıldı

## Yanlış kullanım

Her turda 'bugün şunları yaptım' günlüğü — okunmaz

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
