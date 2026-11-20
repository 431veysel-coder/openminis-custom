<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# C · Claim sözleşmesi

> **Dosya/satır sahipliği ve çakışma**

Kademe: **K1** · Öncelik: **1**

## Amaç

İki yazarın aynı dosyaya aynı anda yazmasını engeller. Bu depoda iki oturum aynı ağaçta çalışıyor; çakışma şans eseri fark ediliyor.

## Ne zaman açılır

Ortak bir dosyaya (ChatViewModel, strings.xml, workflows) yazmadan önce.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `dosya` | yol | ✅ |
| `aralik` | satır aralığı (ortak dosyada zorunlu) | ✅ |
| `sahip` | oturum kimliği | ✅ |
| `bitis` | kilit ne zaman bırakılır | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| C1 | Kilit | yazmadan önce al, bitince bırak |
| C2 | Çakışma | hash beklenenden farklıysa DUR, üç karardan biri |
| C3 | Karar | onlarınki / benimki (gerekçe) / hunk hunk birleştir |
| C4 | Bayat kilit | süresi geçmiş kilit devralınabilir, kayıtla |

## Kapı — makine ne denetler

Kilitli dosyaya ikinci yazma → hata; çakışma kararsızsa uyarı

## Bu depodan gerçek örnek

`scripts/slice_gate.py` başka oturumundu → iş akışı ONA uyduruldu

## Yanlış kullanım

Çakışmada otomatik 'benimki doğru' demek

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
