<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# P · Programlama sözleşmesi

> **Kod: sahiplik, biçim, tekrar, geri alma**

Kademe: **K0** · Öncelik: **1**

## Amaç

Bir dosyaya nasıl dokunulacağını sabitler: önce ölç, sonra tek geçiş; ortak dosyalarda kilit aralık düzeyinde; aynı hunk üçüncü kez düzeltiliyorsa dur.

## Ne zaman açılır

Her kod düzenlemesinde.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `dosya` | yol + satır aralığı | ✅ |
| `once_hash` | düzenleme öncesi sha256[:12] | ✅ |
| `sonra_hash` | düzenleme sonrası sha256[:12] | ✅ |
| `deneme` | bu hunk için kaçıncı düzeltme | ✅ |
| `kanit` | test adı ya da derleme işi | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| P1 | Tek okuma | hash aynıysa dosya yeniden okunmaz |
| P2 | Tek geçiş | tüm dokunma noktaları önce bulunur, sonra yazılır |
| P3 | Üç kuralı | aynı hunk 3. düzeltmede tasarım değiştirilir |
| P4 | Kilit | ortak dosyada aralık kilidi; kilitsiz yazma yok |
| P5 | Geri alma | her yazma journal'da; `geri_alma` boş olamaz |

## Kapı — makine ne denetler

`deneme` > 3 ise hata; `sonra_hash` == `once_hash` ise kayıt anlamsız (uyarı)

## Bu depodan gerçek örnek

FanoutRunner üç kez kırıldı (import, tip, imza) — 3 kuralı ihlali

## Yanlış kullanım

Dosya dosya dolaşıp aynı hatayı 5 tur düzeltmek

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
