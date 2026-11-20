<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# M · Mesaj sözleşmesi

> **Kullanıcıya ne söyleniyor, ne söylenmiyor**

Kademe: **K0** · Öncelik: **1**

## Amaç

Bir turda kullanıcıya verilen cevabın kapsamını, kesinliğini ve sınırlarını sabitler. 'Yaptım' demekle 'şu kanıtla yaptım' demek arasındaki farkı zorunlu kılar.

## Ne zaman açılır

Her kullanıcı turunda; özellikle bir iş bitmiş gibi sunulacaksa.

## Zorunlu ve isteğe bağlı alanlar

| Alan | Ne | Zorunlu |
|---|---|---|
| `kapsam` | bu turda ne söylendi (tek cümle) | ✅ |
| `kanit` | her iddianın kanıt biçimi (Ç1) | ✅ |
| `soylenmeyen` | bilerek söylenmeyen / ertelenen şey | ✅ |
| `belirsizlik` | emin olunmayan nokta + neden | — |

## Alt bölümler

| Kod | Ad | Kural |
|---|---|---|
| M1 | Kesinlik | ölçülmemiş şey 'ölçülmedi' diye yazılır; tahmin '(tahmin)' etiketli |
| M2 | Kapsam dürüstlüğü | istenmeyen ama yapılan iş ayrıca söylenir |
| M3 | Sessizlik | bir şey yapılmadıysa 'yapılmadı' — boşluk bırakmak yalandır |
| M4 | Uzunluk | cevap işin boyutuyla orantılı; 1 satırlık iş 20 satır anlatılmaz |

## Kapı — makine ne denetler

Cevapta 'derlendi/çalışıyor/testler geçti' geçiyorsa yanında kanıt biçimi yoksa uyarı

## Bu depodan gerçek örnek

37094: 'log kalıcılığı çalışıyor' denmedi; 'debug koşu 44622129907 success, 4 artifact dolu' dendi

## Yanlış kullanım

'Her şey hazır' — hangi şey, hangi kanıtla

---

Çekirdek kurallar (kanıt biçimi, defter, bitiş kapısı) için: 
[`00-CEKIRDEK.md`](00-CEKIRDEK.md)
