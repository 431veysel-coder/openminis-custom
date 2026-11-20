<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# SH · Kabuk sözleşmesi

> **Shell: boru, çıkış kodu, tırnak**

Kademe: **K1** · Öncelik: **1**

## Amaç

Kabuk komutlarının sessizce yanlış sonuç üretmesini engeller. `| head` + pipefail = geçen kontrolün kırmızı görünmesi.

## Ne zaman açılır

Boru, `&&`/`||`, tırnak, değişken genişletme içeren her komutta.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `komut` | çalıştırılan komut | ✅ |
| `kabuk` | sh / bash / hangi bayraklar | ✅ |
| `cikis` | beklenen çıkış kodu + anlamı | ✅ |
| `etki` | yazıyor mu, siler mi (geri alınabilir mi) | — |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| Ş1 | Erken çıkan tüketici | `| head`, `| grep -q` yasak (pipefail) |
| Ş2 | Çıkış kodu | `|| true` yalnız gerçekten önemsizse; değilse hata |
| Ş3 | Tırnak | değişkenler çift tırnakta; boşluk/glob tuzağı |
| Ş4 | Yıkıcılık | rm/sil/mv öncesi hedef doğrulanır |

## Kapı — makine ne denetler

pipefail açık adımda `| head`/`| grep -q` → hata (L0 ci-shell)

## Bu depodan gerçek örnek

`unzip -l "$APK" | grep -q` 43 MB APK'da SIGPIPE 141 üretti

## Yanlış kullanım

`gh release view ... || true` → 401'i 'yayın yok' saydı

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
