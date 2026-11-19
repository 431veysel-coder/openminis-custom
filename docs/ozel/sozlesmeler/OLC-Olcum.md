<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# OLC · Olcum sözleşmesi

> **Hangi metrik, nasıl, eşik ne**

Kademe: **K1** · Öncelik: **2**

## Amaç

Bir iyileştirmenin ölçülebilir olduğunu garanti eder. 'Daha hızlı' bir metrik değildir; p50 TTFB bir metriktir.

## Ne zaman açılır

Performans/sağlık iddiası varsa ya da yeni bir gösterge eklendiğinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `metrik` | ad + birim | ✅ |
| `kaynak` | nereden okunuyor (kod:satır / tablo) | ✅ |
| `esik` | hangi değer iyi/kötü | ✅ |
| `orneklem` | kaç ölçüm, hangi pencere | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| Ö1 | Titreşimsizlik | ham değer değil medyan/p90 gösterilir |
| Ö2 | Maliyet | ölçümün kendisi pil/CPU harcamamalı |
| Ö3 | Kalıcılık | halka tampon; her olayı diske yazmak israf |
| Ö4 | Yanlış pozitif | tek yavaş örnek metriği bozmamalı |

## Kapı — makine ne denetler

`kaynak` kod satırı içermiyorsa uyarı (ölçüm uydurma riski)

## Bu depodan gerçek örnek

p50 TTFB son 20 ölçümden; p90 ayrıntı kartında

## Yanlış kullanım

Ortalama gecikme — tek 30 sn'lik yanıt ortalamayı yalan yapar

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
