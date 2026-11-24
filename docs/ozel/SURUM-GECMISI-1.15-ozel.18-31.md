# SÜRÜM GEÇMİŞİ — 1.15-ozel.18 → .31

> **Neden bu dosya var (ölçülen eksik).** `.22`'den `.31`'e kadar sürüm notunu
> tek dosya üzerinde yeniden yazdım (`SURUM-NOTU-…-22.md` → `-23.md` → …),
> yani `.22`–`.30` arası **ayrı notlar hiç oluşmadı**. Tek dosyayı `mv` ile
> taşımak geçmişi korumuyor. Bu belge boşluğu **commit mesajlarından** —
> uydurmadan — kapatıyor. Ayrıntılı not yalnız `.31` için var.

## Sürüm ↔ commit ↔ yayın durumu

| Sürüm | versionCode | Yayın |
|---|---|---|
| `.18` | 37099 | yayınlandı (v1.15-ozel.18) |
| `.19` | 37100 | yayınlandı (v1.15-ozel.19) |
| `.20` | 37101 | yayınlandı (v1.15-ozel.20) |
| `.21` | 37102 | yayınlandı (v1.15-ozel.21) — **cihazdaki sürüm** |
| `.22` | 37103 | derlenmedi |
| `.23` | 37104 | derlenmedi |
| `.24` | 37105 | derlenmedi |
| `.25` | 37106 | derlenmedi |
| `.26` | 37107 | derlenmedi |
| `.27` | 37108 | derlenmedi |
| `.28` | 37109 | derlenmedi |
| `.29` | 37110 | derlenmedi |
| `.30` | 37111 | derlenmedi |
| `.31` | 37112 | derlenmedi |

**Kaynak ileride, yayın geride.** `.22`–`.31` arası yedi sürümlük değişiklik
tek bir derlemede doğrulanacak; bu yüzden `compile` adımı hâlâ ilk kez
koşacak.

## Commit geçmişi (son 16)

| Commit | Ne |
|---|---|
| `09eb6be` | fix(37099k): acik maddeler — plan kapisi, terminal uyarisi, sozluk degraded bayragi, rapor render, kuyruk ozeti; kapida 3 kor nokta |
| `f563c3b` | fix(37099j): konsey odalarinda anlamsiz tire yerine gercek bos durum metni (iki dil); .30 |
| `628ad1a` | fix(37099i): sanal ekranda bolme basina yakalama butcesi (global damga durgun bolmeleri acliga sokuyordu); .29 |
| `af1b396` | feat(37099h): ceviri paket onerisi (dil eslesmesi, saf cekirdek + cihaz olcumu); .28 |
| `25641a7` | feat(37099g): alt ajan kartinda butce ilerlemesi; kapi yalniz eklenen satirlari yargilar; .27 |
| `225c94a` | chore(37099f): harita kapisi kendini tazeler, DNS doktoru; .26 |
| `990d7d2` | feat(37099f): ret listesi, kapsam onerisi ve gorunurlugu; .25 |
| `2e5b57a` | feat(37099f): gorev kaniti ekleme + bitti kapisi, konsey on kontrol uyarisi ekranda; .24 |
| `ac4ee84` | chore: harita tazele (surum notu adi degisti) |
| `65d2fab` | feat(37099e): uye ariza siniflandirmasi, konsey on kontrolu, deny listesi, derlemesiz dogrulama kapi + .23-.30 yol haritasi |
| `ba3f5ba` | fix(37099d): konsey soru-cevap, gorev odasi eylemleri, apt havuz yonlendirmesi, izin kullanim sayaci + YAPILACAKLAR |
| `ebc008a` | feat(37099c): konsey alti oda — karsilastirma, iletisim ve bagimlilik hatlari gercek veriyle |
| `11c4421` | fix(37099c): Termux apt kok reddini coz (kok olmayan kimlik) + toplu entegrasyon izni; surum .20 |
| `e5be440` | fix(37099b): derleme hatalari (startedAt kapsami, ekran importu) + surum 1.15-ozel.19/37100 |
| `e8ae730` | feat(37099b): Ayarlar > Izinler toplu yetki ekrani (kategori bazli, geri alinamazlar haric) |
| `8712b51` | fix(37099b): Termux baglari dort kurucuda, sanal ekran istemi, ceviri model secimi, alt ajan saat butcesi |

## Sürüm bazında kapanan ana madde

| Sürüm | Ana iş |
|---|---|
| `.19` | toplu izin ekranı; Termux bionic bağları dört PRoot kurucusunda; çeviri model seçimi; alt ajan saat bütçesi |
| `.20` | Termux `apt` kök reddi (`-i 10000:10000`); toplu entegrasyon izni (a11y + Shizuku) |
| `.21` | konsey altı odası; karşılaştırma/iletişim/bağımlılık gerçek veriyle |
| `.22` | `no_positions` arıza kaydı; soru→cevap eşleme; görev odası eylemleri; havuzda apt; izin kullanım sayacı |
| `.23` | `MemberFailure` sınıflandırması; `CouncilPreflight`; `deny:` salt-okunur listesi; 9. kapı (`kotlin-hizli`) |
| `.24` | görev kanıtı ekleme + "Bitti" kapısı; ön kontrol uyarısı ekranda |
| `.25` | "Reddettiklerin" listesi; kapsam önerisi ve görünürlüğü |
| `.26` | harita kendini tazeler; DNS doktoru |
| `.27` | alt ajan kartında bütçe; kapı yalnız eklenen satırları yargılar |
| `.28` | çeviri paket önerisi |
| `.29` | bölme başına yakalama bütçesi (**latent**: çağıran yok, bkz. §5.1) |
| `.30` | konsey odalarında anlamsız tire yerine gerçek boş durum metni |
| `.31` | açık maddeler: plan kapısı, Terminal uyarısı, sözlük `degraded` bayrağı, rapor render, kuyruk özeti; kapıda 3 kör nokta |

## Ders (kayda geçsin)

Tek dosyayı yeniden adlandırarak "sürüm notu yazmak", not yazmak değildir:
geçmiş sessizce silinir ve kimse fark etmez. Sürüm notu **yeni dosya** olmalı.
