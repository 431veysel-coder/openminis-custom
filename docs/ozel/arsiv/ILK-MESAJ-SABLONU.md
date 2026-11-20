> **ARŞİV — geçersiz.** Bu belge 2026-11-19'da **24 sözleşmeye** genişletildi ve
> yerini `docs/ozel/sozlesmeler/` aldı. Buradaki `Z0…Z9` biçimi artık
> kullanılmıyor; iki rakip biçim tutmak "belge yalan söyler" hatasıdır.
>
> Güncel olan: [`sozlesmeler/DIZIN.md`](../sozlesmeler/DIZIN.md) (dizin),
> [`sozlesmeler/00-CEKIRDEK.md`](../sozlesmeler/00-CEKIRDEK.md) (kanıt kuralı,
> defter, bitiş kapısı), [`sozlesmeler/BAGLANTI.md`](../sozlesmeler/BAGLANTI.md)
> (24 sözleşme arası bağlar).
>
> Kayıt silinmez, **emekli edilir** (E sözleşmesi). Bu dosya yalnız tarihsel
> nedenle duruyor.

# İLK MESAJ — boş şablon (kopyala, doldur, `docs/ozel/durum/<konu>.md` olarak kaydet)

```
KONU: <tek satır, ölçülebilir hedef>
TARİH: 2026-11-19 18:40 (Europe/Istanbul)

Z0 — KİMLİK VE SINIR
  Sürüm        : 370xx / 1.15-ozel.xx   [ ✓ yandı | ✗ yanmadı ]
  Dal / taban  : <dal> @ <hash>   (origin ile aynı: evet/hayır)
  Kapsam İÇİ   : 1) ...  2) ...  3) ...        <- her biri kanıtlanabilir
  Kapsam DIŞI  : <dosya/paket listesi>
  Durma koşulu : <hangi komut yeşil olunca>
  Süre bütçesi : <dk>   (aşılırsa Z9'a "bırakıldı" yazılır)

Z1 — OKUNAN
  | yol | sha256[:12] | neden | ne zaman | satır |
  |-----|-------------|-------|----------|-------|
  |     |             |       |          |       |

Z2 — İŞLENEN (kilitli)
  | yol | sahip | aralık | kilit zamanı | bitiş tahmini |
  |-----|-------|--------|--------------|---------------|

Z3 — DÜZELTİLEN / ÜRETİLEN
  | yol | önce | sonra | dilim | kanıt (test/komut) | geri alma |
  |-----|------|-------|-------|--------------------|-----------|

Z4 — EKSİK
  - [ŞEMA|TEST|İZİN|KARAR] ne → sahip: <kim>

Z5 — HATA
  - #<n> komut: ... | çıktı: ... | sınıf: DERLEME/TEST/CI/ORTAM/MANTIK | deneme: k/3 | çözüm: ...

Z6 — ENGEL
  - [ANAHTAR|İZİN|KOTA|KARAR] ne | yaş: <gün> | son deneme: <saat> | kim açacak

Z7 — ÇAKIŞMA
  - yol | benim: <hash> | şimdiki: <hash> | kim: <?> | karar: onlarınki/benimki/birleştir

Z8 — KARARLAR
  - karar | gerekçe (sayı varsa sayı) | neden alternatif değil | geri alma

Z9 — SIRADAKİ (en fazla 3)
  1) <fiil> + <dosya> + <kanıt>
  2)
  3)
```

## Doldurma kontrolü (commit'ten önce)
- [ ] Z0'daki her "kapsam içi" maddesi bir komutla doğrulanabiliyor
- [ ] Z1'de hash'siz satır yok
- [ ] Z2'de sahipsiz satır yok
- [ ] Z3'te "kanıt" alanı boş satır yok
- [ ] Z4'te "sahip" alanı boş satır yok
- [ ] Z5'te aynı hata 3'ten fazla tekrarlanmıyor
- [ ] Z6'da yaşsız engel yok
- [ ] Z7 boş değilse kararı verilmiş
- [ ] Z3 doluysa Z9 dolu
