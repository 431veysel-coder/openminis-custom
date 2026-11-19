<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# Sözleşme ailesi — dizin

24 sözleşme, 4 kademe eşlemesi, her biri kendi makine kapısıyla.

**Önce [`00-CEKIRDEK.md`](00-CEKIRDEK.md) okunur** — kanıt biçimi, 
defter (JSONL) ve bitiş kapısı orada; tek tek dosyalar onu tekrar etmez.

## Kullanıcının saydığı 13 madde

| İstenen | Sözleşme | Kapsıyor mu |
|---|---|---|
| mesaj | **M · Mesaj** | Kullanıcıya ne söyleniyor, ne söylenmiyor |
| karar | **K · Karar** | Neyi neden seçtik, nasıl geri alırız |
| görev | **G · Görev** | Bir işin sınırı ve durma koşulu |
| yapılandırma | **A · Ayar** | Yapılandırma: varsayılan, anahtar, kapsam |
| kodlama | **P · Programlama** | Kod: sahiplik, biçim, tekrar, geri alma |
| yapılacaklar | **Y · Yapılacaklar** | Kim, neyi, ne zaman kapatacak |
| derleme | **D · Derleme** | Build: hangi komut, hangi çıktı, hangi log |
| test | **T · Test** | Hangi davranış, hangi sınır, hangi kanıt |
| geri bildirim | **F · Geri bildirim** | Hata → sınıf → önleyici kapı |
| hatırlama | **H · Hatırlama** | Ne hatırlanacak, ne hatırlanmayacak |
| kabuk | **SH · Kabuk** | Shell: boru, çıkış kodu, tırnak |
| terminal | **L · Terminal** | Uzun/etkileşimli süreç: oturum, kesinti, çıktı |
| ortam | **O · Ortam** | Nerede çalışıyoruz: araç, sürüm, sınır |

## Sonradan eklenen 11 sözleşme

Her biri bu depoda **gerçekten yaşanmış** bir kaybın karşılığı:

| Kod | Sözleşme | Neden eklendi |
|---|---|---|
| **S** | Sürüm | yayın geri dönüşsüzdür; yakılan numara geri gelmez |
| **V** | Veri | göç test edilmezse kullanıcı verisi sessizce bozulur |
| **IZN** | Izin | log/artifact sır sızdırmanın en kolay yolu |
| **C** | Claim | iki oturum aynı ağaçta yazıyor; çakışma bugün şansa bağlı |
| **UY** | Arayuz | görsel değişiklik ölçüsüz olunca başlık sıkışır, erişilebilirlik düşer |
| **B** | Bağımlılık | hareketli etiket, yayın hattının kontrolünü başkasına vermektir |
| **OLC** | Olcum | 'daha hızlı' metrik değildir; p50 TTFB metriktir |
| **E** | Emeklilik | her şey birikir; 154 artifact silinince loglar da gitti |
| **N** | Nakil | devir brief'i eksikse alt ajan timeout'a düşer ve iş yeniden yapılır |
| **R** | Risk | yasak listesi olmayan sözleşme ailesi her şeyi izin sanır |
| **Z** | Zaman | bütçesiz iş ya yarım kalır ya sonsuza sürer |

## Tam tablo (öncelik sırası)

| Öncelik | Kod | Sözleşme | Kademe | Kapı |
|---|---|---|---|---|
| 1 | [A](A-Ayar.md) | Ayar | K1 | `okuyan` boşsa hata (ölü anahtar); anahtar var + satır yoksa uyarı |
| 1 | [C](C-Claim.md) | Claim | K1 | Kilitli dosyaya ikinci yazma → hata; çakışma kararsızsa uyarı |
| 1 | [D](D-Derleme.md) | Derleme | K0 | `log` alanı boşsa uyarı: log'suz derleme iddiası doğrulanamaz |
| 1 | [F](F-Geri-bildirim.md) | Geri bildirim | K1 | `onleyici` boşsa kayıt kapanamaz (Ç7) |
| 1 | [G](G-Gorev.md) | Görev | K0 | `durma` alanı bir komut içermiyorsa hata; `disi` boşsa uyarı |
| 1 | [IZN](IZN-Izin.md) | Izin | K2 | Artifact'a log yükleyen işte scrub adımı yoksa uyarı |
| 1 | [K](K-Karar.md) | Karar | K1 | K2 kademeli işte K kaydı yoksa iş kapanamaz; `geri_alma` boşsa hata |
| 1 | [M](M-Mesaj.md) | Mesaj | K0 | Cevapta 'derlendi/çalışıyor/testler geçti' geçiyorsa yanında kanıt biç… |
| 1 | [P](P-Programlama.md) | Programlama | K0 | `deneme` > 3 ise hata; `sonra_hash` == `once_hash` ise kayıt anlamsız … |
| 1 | [R](R-Risk.md) | Risk | K2 | K2 işte `fren` alanı boşsa hata |
| 1 | [S](S-Surum.md) | Sürüm | K2 | Yayın işinde S kaydı yoksa hata; yayındaki numara ≤ yeni numara değils… |
| 1 | [SH](SH-Kabuk.md) | Kabuk | K1 | pipefail açık adımda `| head`/`| grep -q` → hata (L0 ci-shell) |
| 1 | [T](T-Test.md) | Test | K1 | `test` alanındaki ad repoda bulunamazsa hata (uydurma test) |
| 1 | [V](V-Veri.md) | Veri | K2 | Şema sürümü değişmiş ama `schemas/<db>/<n>.json` yoksa hata |
| 1 | [Y](Y-Yapilacaklar.md) | Yapılacaklar | K0 | `sahip` boşsa hata; 7 günden eski açık kayıt uyarı olarak basılır |
| 2 | [B](B-Bagimlilik.md) | Bağımlılık | K1 | Üçüncü taraf action etikette kalmışsa uyarı |
| 2 | [E](E-Emeklilik.md) | Emeklilik | K1 | Yeni artifact'ta `retention-days` yoksa uyarı |
| 2 | [H](H-Hatirlama.md) | Hatırlama | K1 | Kayıtta sır deseni (token/PEM/uzun base64) varsa hata |
| 2 | [L](L-Terminal.md) | Terminal | K1 | Arka plan süreci redirect'siz başlatılırsa uyarı |
| 2 | [N](N-Nakil.md) | Nakil | K1 | `brief` alanı boşsa hata; timeout sonrası `sonuc` zorunlu |
| 2 | [O](O-Ortam.md) | Ortam | K1 | Sır değeri içeren kayıt reddedilir; `arac` sürümsüzse uyarı |
| 2 | [OLC](OLC-Olcum.md) | Olcum | K1 | `kaynak` kod satırı içermiyorsa uyarı (ölçüm uydurma riski) |
| 2 | [UY](UY-Arayuz.md) | Arayuz | K1 | Yeni composable'da `contentDescription` yoksa uyarı |
| 2 | [Z](Z-Zaman.md) | Zaman | K1 | `butce` aşılmış ve `kalan` boşsa uyarı (yarım iş) |

## Kademe eşlemesi

| Kademe | Ne zaman | Açılan sözleşmeler |
|---|---|---|
| **K0** küçük | tek dosya, geri alınabilir | M · G · Y · P · D |
| **K1** normal | çok dosya / test / davranış | M · G · Y · P · D + K · A · T · F · H · SH · L · O · C · UY · B · OLC · E · N · Z |
| **K2** sürüm | `versionCode` / yayın / göç | hepsi + S · V · IZN · R |

## Bir turda hangi sözleşmeler açılır (pratik)

```
K0  → G (görev) + P (kod) + M (mesaj)
K1  → G + P + C (kilit) + T (test) + D (derleme) + M
K2  → K1 + K (karar) + S (sürüm) + V (veri) + İ (izin) + R (risk) + Z (bütçe)
hata → F (geri bildirim) her zaman, kademeden bağımsız
devir → N (nakil) her zaman, alt ajan/oturum varsa
```

## Komutlar

```bash
python3 scripts/sozlesme.py ac    --tur G --is health-37096 --kademe K1 \
    --kapsam "ChatViewModel 5 kanca" --disi "chip, ayar satırı"
python3 scripts/sozlesme.py alan  --id G-health-37096 --alan kilit --deger "ChatViewModel.kt:12224-12240"
python3 scripts/sozlesme.py kanit --id G-health-37096 --deger "test:HealthBridgeTest#maps429"
python3 scripts/sozlesme.py kapat --id G-health-37096 --sonuc TAMAM
python3 scripts/sozlesme.py denetle
python3 scripts/sozlesme.py rapor   --id G-health-37096
```
