<!-- ÜRETİLDİ: scripts/ozel/harita.py — elle düzenlemeyin. -->

# Harita — giriş ve yönlendirme

**Ne arıyorsan, şuraya bak.** Her satır tek bir soruya cevap veriyor.

| Arıyorum | Yer |
|---|---|
| Bir ekranın kodu | `ui/` → `10-KOD.md` |
| Bir özelliğin açma/kapama anahtarı | `40-OZELLIKLER.md` |
| "Bu komut kart açar mı?" | `30-KURALLAR.md` + `scripts/ozel/risk-corpus.py -v "<komut>"` |
| Hangi izin var, neden | `50-IZINLER.md` |
| Bir dilimin kodu/bağımlılığı | `20-DILIMLER.md` + `scripts/slices.json` |
| Test nerede | `80-TESTLER.md` |
| Bir sözleşme ne vaat ediyor | `70-SOZLESMELER.md` + `.ozel/sozlesmeler/` |
| Çeviri eksiği | `60-CEVIRI.md` + `scripts/ozel/ceviri-denetim.py` |
| "Bu belge ne anlatıyor" | `90-BELGELER.md` |
| **Yeni bir şey ekledim, ne güncellenir** | `99-NE-NEREYE.md` |
| Neden bu çatal var | `../ozel/HARITA.md` (insan yazımı, sabit) |

## Bu klasör otomatik

Her dosya `scripts/ozel/harita.py` tarafından **kaynaktan** üretilir. Elle
düzenleme bir sonraki üretimde silinir. Sayılar kaynaktan okunur; bayatlamaz,
çünkü bayatlarsa CI kırmızı olur (`--check`).

```
python3 scripts/ozel/harita.py          # üret
python3 scripts/ozel/harita.py --check  # bayat mı?
```

## Bugünkü ölçüm

| | |
|---|---|
| Kotlin dosyası (main) | 900 · 321 950 satır |
| Ekran | 76 · UI paketi 19 |
| Dilim | 13 |
| Risk kuralı | 43 (DENY 5 · karta düşen 38) |
| Özellik anahtarı | 25 (açık 21) |
| İzin | 27 Android + 2 özel |
| Test | 6 732 birim (782 dosya) + 124 cihaz |
| Çeviri | en 3037 · tr 2596 · eksik 441 (yeni 0) |
| Sözleşme | 13 · kayıt 72 |
