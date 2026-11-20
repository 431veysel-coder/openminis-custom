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

# İLK MESAJ SÖZLEŞMESİ — v1

> Bir işe başlarken yazılan **tek mesaj**. Amacı: ajan (veya insan) işin ortasında
> bağlamı kaybettiğinde, ilk mesaja bakarak **nerede kaldığını, neyi okuduğunu, neyi
> bozduğunu, neyin eksik olduğunu** tek bakışta görebilsin. Sohbet geçmişi kaybolabilir;
> bu mesaj kaybolmaz, çünkü **dosyaya** yazılır ve commit'lenir.

---

## 0. Neden var — üç somut kayıp

| Kayıp | Bugün nasıl oluyor | İlk mesaj ne yapıyor |
|---|---|---|
| **Bağlam kaybı** | Oturum/konuşma kapanınca "hangi dosyaya dokundum" bilgisi gidiyor; sonraki tur aynı dosyayı sıfırdan okuyor | Z1/Z3 envanteri yol + sha256 tutuyor; tekrar okuma gereksiz |
| **Tekrar** | Aynı düzeltme 2-3 kez yapılıyor, biri diğerini eziyor | Sahiplik kilidi (R2) + "zaten yapıldı mı" kapısı (R3) |
| **Sessiz çakışma** | İki oturum/iki yazar aynı dosyaya yazıyor, ikincisi birincinin işini siliyor | Z7 çakışma bölümü: hash değişti ama ben yazmadım → dur |

Üçü de **ölçülebilir** alanlara dönüşür: yol, hash, zaman, sahip, deneme sayısı.

---

## 1. İlk mesaj nedir / ne değildir

**Nedir:** Bir çalışma turunun *sözleşmesi*. Girdi envanteri + kapsam + kabul ölçütü +
risk listesi. Hem insanın okuyacağı kadar açık, hem `rg` ile denetlenebilecek kadar düzenli.

**Değildir:**
- Bir plan romanı değil (plan ayrı belgede yaşar: `KONSEY-MIMARISI.md` gibi).
- Bir özür/özgeçmiş değil ("şunu yaptım, sonra şu oldu..." anlatısı **yasak**).
- Test raporu değil (o CI'ın işi).
- Sohbet özeti değil.

**Kural:** İlk mesaj **tek ekranda** okunur. Uzarsa bölünür: özet ilk mesajda, ayrıntı
`durum/<konu>.md` dosyasında ve ilk mesaj ona **bağlantı** verir.

---

## 2. Sabit iskelet — Z0…Z9

Bölüm adları **değişmez** (harf sırası anlamlıdır, atlanan bölüm "boş" yazılır, silinmez).
Boş bölüm silinirse "unutulmuş" ile "gerçekten yok" ayırt edilemez.

### Z0 — KİMLİK ve SINIR
```
Sürüm     : 37092 / 1.15-ozel.15        (yayınlanmadıysa "yanmadı" yaz)
Dal       : ozel-11.5
Taban     : <commit kısa hash> (origin/main ile aynı mı: evet/hayır)
Kapsam İÇİ: 3 madde, her biri tek cümle, hepsi ölçülebilir
Kapsam DIŞI: dokunulmayacak dosyalar/paketler (açıkça)
Durma koşulu: "X yeşil olunca dur" — süre değil, kanıt
Süre bütçesi: N dk (aşılırsa Z9'a "bırakıldı" yazılır, yarım iş bırakılmaz)
```
Kural: **Kapsam İÇİ maddesi "iyileştir/düzenle/temizle" gibi ölçüsüz fiille yazılmaz.**
Yanlış: "sağlayıcı kodunu iyileştir". Doğru: "`provider/HealthProbe.kt` eklensin, 3 test
geçsin, hiçbir sağlayıcı isteği 30 sn'yi aşmasın".

### Z1 — GİRDİ ENVANTERİ (okunan dosyalar)
Tablo, her satır bir dosya:
```
yol | sha256[:12] | neden okundu | ne zaman | kaç satır
```
Kurallar:
- **Bir dosya bir kez okunur.** İkinci okuma yalnız hash değiştiyse (R1).
- Hash yoksa "okundu" sayılmaz — okumak = içeriği sabitlemek.
- "Neden okundu" alanı boş bırakılamaz; boşsa dosya envanterden çıkarılır.
- Dizin taraması (`rg`, `ls`) da buraya girer: `rg -n 'Feature.X' src/main | 42 satır`.

### Z2 — İŞLENEN (değişecek dosyalar) + SAHİPLİK
```
yol | sahip (kim/oturum) | hangi aralık | kilit zamanı | tahmini bitiş
```
Kural: **Yazmadan önce kilitle, bitince bırak.** Kilit, "ben bu dosyaya yazacağım"
demektir; aynı dosyaya ikinci yazan Z7'ye düşer, yazmaz.
Büyük ortak dosyalar (`ChatViewModel.kt`) için kilit **satır aralığı** düzeyinde tutulur.

### Z3 — DÜZELTİLEN / ÜRETİLEN
```
yol | önce sha256[:12] | sonra sha256[:12] | dilim (370xx) | kanıt (test/komut) | geri alma
```
Kurallar:
- Her satır **tek bir dilime** bağlıdır (dilim yoksa satır yok).
- "Kanıt" alanı ya test adı ya da çalıştırılmış komuttur; **"derlendi" kanıt değildir**.
- Geri alma: commit hash ya da "journal#id".
- **Hash aynıysa satır yazılmaz** — dokunulmuş ama değişmemiş dosya Z3'e girmez, Z1'de kalır.

### Z4 — EKSİK
Bilinen ama var olmayan şeyler. Dört tür, ayrı ayrı işaretlenir:
```
[ŞEMA]  schemas/.../15.json yok        → ilk CI derlemesinden sonra commit
[TEST]  FsSpaceTest yok                 → 37070 diliminde
[İZİN]  WRITE_CONTACTS tanımlı değil     → AndroidManifest
[KARAR] 37090 "hayır" mı "evet" mi      → ürün kararı, sahibi: kullanıcı
```
Kural: her satırda **kim kapatacak** yazar. Sahibi olmayan eksik, eksik değil **süs**tür.

### Z5 — HATA
```
#  komut (kısaltılmış) | çıktının can alıcı 2 satırı | sınıf | kaçıncı deneme | çözüm
```
Sınıflar: `DERLEME / TEST / CI / ORTAM / MANTIK`.
Kurallar:
- **Aynı hata 3. kez yazılıyorsa çözüm yanlış** — 3. denemede yaklaşım değişir (R5).
- Çıktı kırpılır ama **kırpıldığı yazılır** ("… 40 satır atlandı").
- Çözülen hata silinmez, `→ çözüm` eklenir (tekrar ederse tanınsın).

### Z6 — ENGEL
Dışarıdan bekleyen, benim çözemediğim:
```
tür | ne | kaç zamandır | son deneme | kim açacak
[ANAHTAR]  API anahtarı geçersiz (401)   | 3 gün | bugün 12:40 | kullanıcı
[İZİN]     Shizuku yok                    | —     | —          | kullanıcı
[KOTA]     402 spending-limit             | 2 gün | —          | kullanıcı
```
Kural: engel **yaşıyla** yazılır. Yaşsız engel, unutulmuş engeldir.

### Z7 — ÇAKIŞMA
```
yol | benim hash | şimdiki hash | kim yazdı (biliniyorsa) | karar
```
Tespit üç yoldan biriyle: (a) Z2 kilidi başkasında, (b) hash beklediğimden farklı,
(c) commit'te benim yazmadığım satırlar var.
Kural: **çakışmada otomatik "benim sürümüm doğru" denmez.** Karar üç seçenekten biri:
`onlarınki korunur` / `benimki uygulanır (gerekçe)` / `hunk hunk birleştirilir (satır listesi)`.

### Z8 — KARARLAR (mini ADR)
```
karar | gerekçe (ölçüm varsa sayı) | alternatifler ve neden değil | geri alma
```
Kural: **ölçümsüz karar** yazılabilir ama `(tahmin)` etiketi zorunlu.

### Z9 — SIRADAKİ
En fazla **3** madde. Her biri: fiil + dosya + kanıt.
Kural: Z3 boş değilse Z9 boş olamaz (iş bitmedi demektir).

---

## 3. Defter — ilk mesajın kalıcı hali

İlk mesaj sohbette yaşar ve kaybolur. Bu yüzden **iki dosyaya** yazılır:

1. `docs/ozel/durum/<konu>.md` — insan için, Z0…Z9 başlıklarıyla, commit'lenir.
2. `.ozel/durum.jsonl` — makine için, **append-only**, her satır bir olay:
```json
{"t":"2026-11-19T18:40:12Z","op":"read","path":"provider/HealthProbe.kt","sha":"a1b2c3d4e5f6","by":"oturum-A","why":"sağlık probu tasarımı"}
{"t":"2026-11-19T18:44:03Z","op":"claim","path":"provider/HealthProbe.kt","range":"1-220","by":"oturum-A"}
{"t":"2026-11-19T18:52:31Z","op":"write","path":"provider/HealthProbe.kt","before":"a1b2…","after":"9f8e…","slice":37092,"proof":"HealthProbeTest#timeout"}
```
Neden JSONL: satır eklemek çakışmaz (aynı dosyaya iki yazar bile farklı satırlara yazar;
git'te birleşme neredeyse her zaman temiz). Markdown tablo ise her yazmada tüm dosyayı
yeniden yazar → çakışma üretir.

`scripts/durum_defteri.py` (yazılacak) iki yönlü çalışır:
- `ekle` → JSONL'e olay yazar
- `rapor` → JSONL'den `docs/ozel/durum/<konu>.md` üretir (Z1/Z3/Z5/Z7 tabloları otomatik)

Yani **tablo elle yazılmaz**, olaydan üretilir. Elle yazılan tablo yalan söyler; üretilen söyleyemez.

---

## 4. Tekrarı öldüren 7 kural

| # | Kural | Ölçüt / mekanizma |
|---|---|---|
| R1 | **Tek okuma** | Dosya hash'i Z1'de. Hash aynıysa tekrar okuma yasak; `file_read` çağrısı yerine defterdeki satır kullanılır |
| R2 | **Sahiplik** | Yazmadan önce `claim`. Kilit başkasındaysa yazma yok, Z7'ye satır |
| R3 | **İdempotans** | Her işlem önce "zaten var mı" kontrolü: `rg -c 'HealthProbe'` ≠ 0 → atla |
| R4 | **Önce ölç, sonra tek geçiş** | `rg -n` ile tüm dokunma noktalarını bul, sonra **tek** düzeltme turu. Dosya dosya dolaşıp 5 kez düzeltme yasak |
| R5 | **3 kuralı** | Aynı dosya/hunk 3'ten fazla düzeltildiyse **dur**, tasarımı değiştir (bugün FanoutRunner 3 kez kırıldı — tam bu) |
| R6 | **Geri alma izi** | Her yazma journal'a; Z3'te `geri alma` alanı boş olamaz |
| R7 | **Bitiş kapısı** | Z3 doluysa Z9 zorunlu; Z4'te sahibi olmayan satır varsa iş "bitti" sayılmaz |

Ek ölçüm (bugünkü gerçek): `scripts/integration_audit.py` "derlendi" ile "bağlandı"yı
ayırıyor. Aynı mantık buraya da girer: **Z3 "dokundum" demez, "şu kanıtla değişti" der.**

---

## 5. Build log'ları neden kayboluyor — ve çözüm

Bugünkü gerçek (ölçüldü, `apk.yml`):
- Hiçbir adım **log dosyası üretmiyor** (`tee` yok) — log yalnız Actions arayüzünde.
- `upload-artifact` adımları **`if: always()` taşımıyor** → iş kırılınca yükleme çalışmıyor,
  yani **en çok log'a ihtiyaç duyulan anda log yüklenmiyor**.
- Artifact saklama 7/30 gün; public repoda Actions log'u 90 gün. Sonra ikisi de gider.
- Dün 154 artifact silindi (~3,8 GB → 206 MB) — **loglar da o temizliğin içindeydi**.
  Yani "log kayboldu" hissi doğru, sebebi: log hiç kalıcı değildi.

Çözüm üç katman:

1. **Adım içi kalıcılık:** her `run:` bloğu `2>&1 | tee -a build/logs/step-<n>.log`,
   ve `set -o pipefail` (aksi halde `tee` hata kodunu yutar).
2. **Her koşulda yükleme:** `upload-artifact` adımlarına `if: always()`; yeni
   `run-logs` artifact'ı (`build/logs/**`, `build/reports/**`) 90 gün.
   Retention 90 gün = public repoda kullanılabilir üst sınır.
3. **Kalıcı özet:** `build_digest.py` zaten var → her koşuda `build/logs/ozet.md`
   (hangi iş, kaç dk, ilk kırmızı adım, ilk 20 hata satırı) üretilsin ve **release
   gövdesine** eklensin. Release gövdesi silinmez → log'un özü kalıcı.

Ek: yerel ayna — `/var/minis/shared/openminis-ozel/loglar/<run-id>/run.log`
(`gh run view <id> --log`). Cihazda durur, GitHub kotası yemez.

---

## 6. Doldurulmuş örnek (kısa) — 37092

```
Z0  Sürüm 37092 / 1.15-ozel.15 (yanmadı) · Dal ozel-11.5 · Taban 4f21ac9
    İÇİ: (1) provider/HealthProbe.kt + 3 test  (2) sağlayıcı hata sınıflandırması
         (3) ReleaseScreen'de sağlık rozeti — tek satır
    DIŞI: ChatViewModel.kt, team/, room şeması
    Durma: HealthProbeTest 3/3 + compileGithubDebugKotlin yeşil
Z1  provider/ProviderRepository.kt a1b2c3d4e5f6 (412 satır) "mevcut hata yolu"
    provider/Models.kt 9f8e7d6c5b4a (88 satır) "model kaydı"
    rg -n 'retry|backoff' → 11 eşleşme
Z2  provider/HealthProbe.kt — yeni dosya, sahip oturum-A, kilit 18:40
Z3  (boş — henüz yazılmadı)
Z4  [ŞEMA] schemas/15.json → ilk CI, sahip: CI
    [KARAR] 37090 remote/ kodunun kaderi → sahip: kullanıcı
Z5  (boş)
Z6  [KOTA] xAI 402 — 2 gün, sahip: kullanıcı
Z7  (boş)
Z8  Prob ayrı dosyada, ProviderRepository'ye gömülmedi (gerekçe: 412 satır, R5 riski)
Z9  1) HealthProbe.kt iskeleti + 3 test  2) ReleaseScreen rozeti  3) preflight
```

---

## 7. Yazım kuralları (kısa liste)

- Fiiller **ölçülebilir**: "eklendi", "3/3 geçti", "hash X→Y". "İyileştirildi", "düzeltildi",
  "temizlendi" → ölçüt yoksa yasak.
- Geçmiş zaman anlatısı yasak ("önce şunu denedim, olmadı, sonra...") — o Z5'in işi, tek satır.
- Sayı yoksa tahmin etiketi: `(tahmin)`.
- Dosya yolu her zaman **depo köküne göre** ve tam.
- Başlık numaraları (Z0…Z9) asla değişmez; sıra atlanmaz.
- Her bölüm en fazla 15 satır. Uzuyorsa ayrı dosyaya taşı, buraya bağlantı koy.

---

## 8. Uygulama sırası (kod tarafı)

| Dilim | İş |
|---|---|
| 37092 | `docs/ozel/durum/` + `.ozel/durum.jsonl` şablonu; ilk mesaj bu dosyadan üretilir |
| 37093 | `scripts/durum_defteri.py` (`ekle` / `rapor` / `denetle`) + 6 test |
| 37094 | CI: `tee` + `if: always()` + `run-logs` artifact (90 gün) + release gövdesine özet |
| 37095 | `preflight.py`'ye yeni kural: Z4'te sahibi olmayan satır → uyarı; Z3+boş Z9 → hata |

Numaralar **planlanan**dır, yakılmadı; uygulanan sıraya göre kayar.
