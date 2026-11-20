<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# L · Terminal sözleşmesi

> **Uzun/etkileşimli süreç: oturum, kesinti, çıktı**

Kademe: **K1** · Öncelik: **2**

## Amaç

Uzun süren ya da etkileşim isteyen süreçlerin nasıl başlatılacağını, nasıl izleneceğini ve kesilirse ne olacağını sabitler.

## Ne zaman açılır

Arka plan süreci, sunucu, etkileşimli komut, uzun derleme başlatılırken.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `komut` | tam komut | ✅ |
| `sure` | beklenen süre + üst sınır | ✅ |
| `cikti` | stdout/stderr nereye (dosya) | ✅ |
| `kesinti` | kesilirse ne olur, nasıl temizlenir | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| L1 | Arka plan | stdout/stderr yönlendirilmezse süreç ölür |
| L2 | Bekleme | `sleep` yerine gecikmeli kontrol |
| L3 | Etkileşim | şifre/stdin gerekiyorsa terminal açılır |
| L4 | Yetim | süreç biter, dosya kapanır, kilit bırakılır |

## Kapı — makine ne denetler

Arka plan süreci redirect'siz başlatılırsa uyarı

## Bu depodan gerçek örnek

`python3 -m http.server 8765 > /dev/null 2>&1 &`

## Yanlış kullanım

`&` ile başlatıp çıktıyı yönlendirmemek — süreç sessizce ölür

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
