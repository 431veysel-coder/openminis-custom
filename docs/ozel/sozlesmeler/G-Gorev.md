<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# G · Görev sözleşmesi

> **Bir işin sınırı ve durma koşulu**

Kademe: **K0** · Öncelik: **1**

## Amaç

Bir turun neyi kapsadığını, neye dokunmayacağını ve hangi kanıtla duracağını sabitler. Kapsam dışı alanı olmayan iş her dosyaya bulaşır.

## Ne zaman açılır

Her turun başında.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `kapsam` | en çok 3 madde, her biri kanıtlanabilir | ✅ |
| `disi` | dokunulmayacak dosya/paket | ✅ |
| `durma` | hangi komut yeşil olunca durulur | ✅ |
| `butce` | süre/deneme bütçesi | — |
| `kademe` | K0 / K1 / K2 | ✅ |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| G1 | Sınır | 3 madde tavanı — dördüncü madde yeni turdur |
| G2 | Ölçülebilirlik | 'iyileştir/düzenle' yasak; fiil + dosya + kanıt |
| G3 | Bırakma | bütçe aşılırsa 'bırakıldı' yazılır, yarım iş bırakılmaz |
| G4 | Sahiplik | aynı anda iki oturum aynı görevi alamaz |

## Kapı — makine ne denetler

`durma` alanı bir komut içermiyorsa hata; `disi` boşsa uyarı

## Bu depodan gerçek örnek

37095: kapsam 'HealthState+Tracker+9 test', dışı 'chip, ChatViewModel'

## Yanlış kullanım

'Sağlayıcı kodunu iyileştir' — ölçüsüz fiil

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
