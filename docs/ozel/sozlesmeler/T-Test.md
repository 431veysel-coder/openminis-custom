<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# T · Test sözleşmesi

> **Hangi davranış, hangi sınır, hangi kanıt**

Kademe: **K1** · Öncelik: **1**

## Amaç

Bir testin neyi garanti ettiğini ve etmediğini sabitler. Test sayısı kanıt değildir; testin adı ve ölçtüğü sınır kanıttır.

## Ne zaman açılır

Davranış değiştiğinde ya da yeni dilim eklendiğinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `test` | Sınıf#metot | ✅ |
| `olcen` | hangi davranışı sabitliyor | ✅ |
| `sinir` | hangi kenar durumu kapsıyor | ✅ |
| `calistiran` | komut ya da CI işi | ✅ |
| `kapsam_disi` | bilerek test edilmeyen şey | — |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| T1 | Ad = iddia | test adı bir cümle: 'p50 medyandır, ortalama değil' |
| T2 | Zaman | gerçek saat/uyku yok; saat dışarıdan verilir |
| T3 | Sınır | boş, tek eleman, pencere taşması, negatif süre |
| T4 | Kanıt bağı | test adı `rg` ile bulunabiliyor olmalı |

## Kapı — makine ne denetler

`test` alanındaki ad repoda bulunamazsa hata (uydurma test)

## Bu depodan gerçek örnek

`ChatHealthTrackerTest#p50 medyandır ortalama değil`

## Yanlış kullanım

'9 test yazdım' — hangi davranışı kilitlediği söylenmiyor

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
