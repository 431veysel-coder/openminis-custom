<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# F · Geri bildirim sözleşmesi

> **Hata → sınıf → önleyici kapı**

Kademe: **K1** · Öncelik: **1**

## Amaç

Bir hatanın tekrar etmesini engelleyen mekanizmayı zorunlu kılar. Hata düzeltmek onu kapatmaz; kapıya eklemek kapatır.

## Ne zaman açılır

Her hata sonrası, özellikle ikinci kez görülen hata.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `hata` | komut + can alıcı 2 satır | ✅ |
| `sinif` | DERLEME/TEST/CI/ORTAM/MANTIK/SESSIZ | ✅ |
| `deneme` | kaçıncı kez | ✅ |
| `onleyici` | hangi kapıya eklendi (dosya + kural adı) | ✅ |
| `kanit` | kapının hatayı yakaladığını gösteren komut | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| F1 | Sınıf | 'sessiz' ayrı sınıf: hata yok, yanlış sonuç var |
| F2 | Önleyici zorunlu | düzeltme + kapı; sadece düzeltme borç |
| F3 | 3 kuralı | aynı sınıf 3. kez → yaklaşım değişir |
| F4 | Negatif test | kapı, hatayı içeren örnekle sınanır |

## Kapı — makine ne denetler

`onleyici` boşsa kayıt kapanamaz (Ç7)

## Bu depodan gerçek örnek

`${{ }}` run yorumunda → preflight `L0 ci-expr` + 3 selftest vakası

## Yanlış kullanım

Hatayı düzeltip 'çözüldü' demek, kapı eklememek

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
