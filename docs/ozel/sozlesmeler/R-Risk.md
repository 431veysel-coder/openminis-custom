<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# R · Risk sözleşmesi

> **Kırmızı çizgi: neyi yapmayacağız**

Kademe: **K2** · Öncelik: **1**

## Amaç

Yapılmaması gerekenleri açıkça yazar. Yasak listesi olmayan bir sözleşme ailesi, her şeyi izin sanır.

## Ne zaman açılır

Yeni bir iş türü, izin ya da otomasyon devreye girdiğinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `risk` | ne tehlikeli | ✅ |
| `tetik` | hangi durumda gerçekleşir | ✅ |
| `etki` | geri dönüşü var mı | ✅ |
| `fren` | hangi kapı/onay engelliyor | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| R1 | Kırmızı çizgi | onaysız yayın, onaysız silme, sır yazma |
| R2 | Şüphede dur | belirsizlikte yapmamak, yapmaktan iyidir |
| R3 | Geri dönüşsüz | downgrade yok, yakılan numara dönmez |
| R4 | Kapsam kayması | istenmeyen iş yapılmaz; önerilir, yapılmaz |

## Kapı — makine ne denetler

K2 işte `fren` alanı boşsa hata

## Bu depodan gerçek örnek

'Onay almadan yayın yapma' — GLOBAL'de yazılı

## Yanlış kullanım

Sessizce debug anahtarıyla yayınlamak (bir kez oldu)

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
