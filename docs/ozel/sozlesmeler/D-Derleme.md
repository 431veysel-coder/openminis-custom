<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# D · Derleme sözleşmesi

> **Build: hangi komut, hangi çıktı, hangi log**

Kademe: **K0** · Öncelik: **1**

## Amaç

Bir derleme iddiasının hangi komutla, hangi çıktıyla ve nerede saklanan log'la doğrulandığını sabitler. Log kaybolursa hata yeniden keşfedilir.

## Ne zaman açılır

Kotlin/Go/C derlemesi içeren her turda.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `komut` | tam komut (task adı dahil) | ✅ |
| `cikti` | BUILD SUCCESSFUL / hata sınıfı | ✅ |
| `log` | log dosyası ya da artifact adı | ✅ |
| `sure` | duvar saati | — |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| D1 | Tek yol | yerel derleme yoksa bunu açıkça söyle |
| D2 | Log kalıcılığı | tee + `if: always()` + artifact |
| D3 | Kapı sırası | ucuz kapı önce: preflight → compile → tests → apk |
| D4 | Yeniden üretilebilirlik | araç zinciri pinli mi |

## Kapı — makine ne denetler

`log` alanı boşsa uyarı: log'suz derleme iddiası doğrulanamaz

## Bu depodan gerçek örnek

37094: `assemble.log` 94 satır, `BUILD SUCCESSFUL in 1m 43s`

## Yanlış kullanım

'Derleme geçti' — hangi iş, hangi artefakt

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
