<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# Y · Yapılacaklar sözleşmesi

> **Kim, neyi, ne zaman kapatacak**

Kademe: **K0** · Öncelik: **1**

## Amaç

Eksiklerin sahibini zorunlu kılar. Sahibi olmayan eksik kapanmaz — bu depoda `schemas/15.json` üç sürüm boyunca bu yüzden açık kaldı.

## Ne zaman açılır

Bir eksik fark edildiğinde, o turda kapatılamıyorsa.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `ne` | eksik olan şey | ✅ |
| `tur` | ŞEMA / TEST / İZİN / KARAR / BELGE / SÜRÜM | ✅ |
| `sahip` | kim kapatacak (ajan / kullanıcı / CI) | ✅ |
| `kapanis` | hangi adımla kapanır | ✅ |
| `yas` | kaç gündür açık (denetle hesaplar) | — |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| Y1 | Sahiplik | sahip yoksa satır süs sayılır, listeye girmez |
| Y2 | Yaş | yaşsız eksik unutulmuş eksiktir; `denetle` yaşı basar |
| Y3 | Kapanış kanıtı | kapanış bir komut/dosya adı olmalı |
| Y4 | Taşıma | kapanmayan eksik sonraki sürüme taşınır, silinmez |

## Kapı — makine ne denetler

`sahip` boşsa hata; 7 günden eski açık kayıt uyarı olarak basılır

## Bu depodan gerçek örnek

`[ŞEMA] 15.json → sahip: ajan, kapanış: room-schemas artifact'ından indir`

## Yanlış kullanım

'TODO: şemayı ekle' — kimsede değil

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
