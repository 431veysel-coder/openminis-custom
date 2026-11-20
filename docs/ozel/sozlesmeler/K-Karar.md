<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# K · Karar sözleşmesi

> **Neyi neden seçtik, nasıl geri alırız**

Kademe: **K1** · Öncelik: **1**

## Amaç

Geri dönüşü olan ve olmayan seçimleri gerekçesiyle kaydeder. Bir hafta sonra 'bunu neden böyle yaptık' sorusunun cevabı kafada değil kayıtta durur.

## Ne zaman açılır

İki makul seçenek varsa ve seçim kodu/davranışı etkiliyorsa.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `karar` | seçilen şey, tek cümle | ✅ |
| `gerekce` | sayı varsa sayı; yoksa '(tahmin)' | ✅ |
| `alternatif` | elenen seçenek + neden elendi | ✅ |
| `geri_alma` | nasıl geri alınır (commit / ayar / göç) | ✅ |
| `etki` | hangi dosya/davranış etkilenir | — |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| K1 | Sınıf | geri dönüşü var / yok — K2'de 'yok' zorunlu |
| K2 | Ölçüm | gerekçe sayıyla ya da açık '(tahmin)' ile |
| K3 | Geçersiz kılma | karar silinmez, `gecersiz` + yeni karar yazılır |
| K4 | Sahiplik | kararın sahibi: ajan mı, kullanıcı mı |

## Kapı — makine ne denetler

K2 kademeli işte K kaydı yoksa iş kapanamaz; `geri_alma` boşsa hata

## Bu depodan gerçek örnek

37094: `bash --noprofile --norc -eo pipefail` — gerekçe: doküman satırı birebir

## Yanlış kullanım

'Daha temiz olur' — hangi ölçüm, hangi alternatif

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
