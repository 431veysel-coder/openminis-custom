<!-- ÜRETİLDİ: scripts/sozlesme_uret.py — elle düzenlemeyin -->

# Sözleşmeler arası bağlar

24 sözleşme bağımsız değil. Bu dosya **hangi sözleşmenin onsuz anlamsız ya da yanlış sonuç ürettiğini** söyler. `denetle` bu grafiği okur: zorunlu bağ açılmamışsa iş kapanamaz.

## 1 · ZORUNLU bağlar (A → B: A açıksa B de açık olmalı)

| Açılan | Gerektirir | Neden (bu depoda yaşanmış) |
|---|---|---|
| **S** Sürüm | **K** Karar | Onaysız yayın: 1 kez oldu, imza/anahtar hatasıyla geri dönüşsüz |
| **S** Sürüm | **V** Veri | Göç test edilmeden yayın: kullanıcı verisi sessizce bozulur |
| **S** Sürüm | **R** Risk | Yayının freni yazılı olmalı; 'onaysız yayın' kırmızı çizgi |
| **S** Sürüm | **IZN** Izin | Yayın izni (contents: write) en dar işte olmalı |
| **V** Veri | **T** Test | 14→15 göçü test edilmedi; şema dosyası 3 sürüm boyunca yoktu |
| **V** Veri | **K** Karar | Yıkıcı göç (kolon düşürme/veri dönüşümü) karar kaydı ister |
| **A** Ayar | **Y** Yapılacaklar | COUNCIL anahtarı vardı, ayar satırı yoktu → kullanıcı kurtaramadı |
| **F** Geri bildirim | **T** Test | Hata düzeltildi ama kapı eklenmedi → aynı hata tekrar eder |
| **P** Programlama | **C** Claim | İki oturum aynı ağaçta; kilit olmadan yazma birbirini ezer |
| **D** Derleme | **P** Programlama | 'Derlendi' iddiası hangi kod kaydına ait belli değilse doğrulanamaz |
| **T** Test | **P** Programlama | Test hangi davranışı sabitlediği bilinmeden sayı olmaktan çıkmaz |
| **UY** Arayuz | **OLC** Olcum | Chip 96dp/10sp sınırı ölçülmeden 'küçük oldu' denemez |
| **IZN** Izin | **E** Emeklilik | Artifact süresi belirsizse sır ne kadar yayında kalacağı bilinmez |
| **N** Nakil | **Z** Zaman | Bütçesiz devir: 16 dk timeout, çıktı yok, iş yeniden |
| **M** Mesaj | **G** Görev | Görev sınırı yoksa cevabın kapsamı da belirsiz |

## 2 · TAMAMLAYICI bağlar (⇄ biri olmadan diğeri yarım)

| A | B | Neden |
|---|---|---|
| **G** Görev | **M** Mesaj | Görev sınırı cevabın dürüstlüğünü, cevap görevin kapsamını sınar |
| **G** Görev | **P** Programlama | Görev sınırı kodun kapsamını, kod görevin sınırını sınar |
| **K** Karar | **R** Risk | Karar riski, risk kararı gerektirir |
| **OLC** Olcum | **UY** Arayuz | Ölçüm göstergeyi, gösterge ölçümü haklı çıkarır |
| **E** Emeklilik | **D** Derleme | Artifact ömrü derleme çıktısına bağlı |
| **B** Bağımlılık | **O** Ortam | Bağımlılık ortama, ortam bağımlılığa bağlı (araç sürümü) |
| **Z** Zaman | **N** Nakil | Bütçe devri, devir bütçeyi sınar |
| **SH** Kabuk | **L** Terminal | Kabuk komutu terminal oturumunda yaşar |

## 3 · ÇELİŞKİ bağları (✗ karar zorunlu)

İkisi aynı işte açıksa zıt şey söyleyebilirler; `K` kaydı olmadan iş kapanamaz.

| A | B | Neden |
|---|---|---|
| **R** Risk | **Z** Zaman | 'Yapmayacağız' ile 'bütçeyi artır' çelişirse K kaydı zorunlu |
| **S** Sürüm | **E** Emeklilik | Yayın kalıcı, budama geçici — aynı işte ikisi açıksa karar gerekir |
| **H** Hatırlama | **IZN** Izin | Hatırlamaya sır yazılmamalı; İzin sır taşır → çelişki kontrolü |

## 4 · Kademe → asgari küme

Bir iş başlarken hangi sözleşmelerin **zorunlu** açılacağı:

- **K0**: `G` Görev · `P` Programlama · `C` Claim · `M` Mesaj
- **K1**: `G` Görev · `P` Programlama · `C` Claim · `T` Test · `D` Derleme · `M` Mesaj
- **K2**: `G` Görev · `P` Programlama · `C` Claim · `T` Test · `D` Derleme · `M` Mesaj · `K` Karar · `S` Sürüm · `V` Veri · `IZN` Izin · `R` Risk · `Z` Zaman

## 5 · Zincir örneği — "sürüm çıkar" işi

```
G  görev:        kapsam 'sürüm 37092', dışı 'yeni özellik'
  → K  karar:     versionCode 37091→37092, gerekçe + geri alma
  → V  veri:      şema 15 göçü + schemas/15.json commit
    → T test:     MigrationTestHelper 14→15
  → İ  izin:      contents: write yalnız build işinde
    → E emeklilik: artifact ömrü 90 gün
  → R  risk:      'onaysız yayın yok' freni yazılı
    ✗ Z zaman:    bütçe artırımı risk freniyle çelişebilir → K
  → S  sürüm:     imza SHA, önceki yayın, geri alma = YENİ numara
  → D  derleme:   assemble + log artifact
  → M  mesaj:     'yayınlandı' iddiasının kanıtı: run id + APK sha256
```

Bu zincirde `V` atlanırsa `denetle` şunu der: 
`S açık ama V yok → göç test edilmemiş APK yayınlanabilir`.

## 6 · Bakım kuralları

| # | Kural | Neden |
|---|---|---|
| B1 | Bağ eklendiğinde `sozlesme_baglar.py` + `sozlesme_uret.py --check` | belge ile kapı ayrışmasın |
| B2 | Döngü yasak (A→B→A), `denetle` reddeder | döngüde 'önce hangisi' cevapsız kalır |
| B3 | Her bağın `GEREKCE` satırı olmalı | gerekçesiz bağ, kaldırılması gereken bağdır |
| B4 | Sözleşme silinmez, `emekli` işaretlenir | bağı olan sözleşmeyi silmek zinciri kırar |
| B5 | Yeni sözleşme eklerken en az bir bağ kurulur | bağsız sözleşme ölü ağırlıktır |
