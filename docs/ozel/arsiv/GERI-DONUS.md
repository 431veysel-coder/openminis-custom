# GERİ DÖNÜŞ (ROLLBACK) — 2-3 sürüm geri, çakışmasız

> İstek: "Bir üst sürüm arızalıysa hemen 1-2 alt sürüme geçip onu düzeltip yeni sürüm
> çıkarabilelim. 2-3 alt sürüme de inebiliriz, üst sürümlere de çıkabiliriz. Paket/sürüm
> çakışması olmayacak."

## 1. Android'in duvarı — ve neden onu aşmıyoruz

`PackageInstaller`, kurulu `versionCode`'dan **küçük** bir APK'yı reddeder:
`INSTALL_FAILED_VERSION_DOWNGRADE`. Bu bir izin değil, işletim sisteminin güncelleme tanımı.

İki yol var, ikisi de kurulacak:

| Yol | Her cihazda | Ne yapar | Veri |
|---|---|---|---|
| **A. Kurtarma derlemesi** | evet | Eski **kodu**, **yüksek** versionCode ile yeniden yayınlar | hiç dokunmaz |
| **B. Doğrudan düşürme** | Shizuku varsa | `pm install -d` ile eski APK'yı kurar | riskli — önce yedek |

**A her zaman doğru cevaptır.** "Alt sürüme geçmek" isteğinin özü *arızalı olmayan kodu
çalıştırmak*tır, *düşük bir sayı taşımak* değil. Numara bir etikettir; kod işi yapar.
Bu yüzden A, Android'in kuralına uyarak istediğini verir: yükleme ileri gider, kod geri gelir.

B yalnızca "hiç yeniden derleme beklemeden, hemen şimdi" durumu için. Ve B veri riski
taşır: yeni sürüm veritabanı şemasını büyüttüyse eski sürüm onu açamaz.

## 2. Kurtarma derlemesi — nasıl çalışır

Arızalı sürüm: `1.15-ozel.11.2` (37029). Sağlam son sürüm: `1.15-ozel.11.1` (37028).

```bash
# Sağlam kodun üstüne, yeni bir numara:
git checkout v1.15-ozel.11.1 -- .          # kod = sağlam
versionCode = 37030                         # numara = yeni
versionName = "1.15-ozel.11.1-r1"           # ad = kurtarma olduğu belli
```

Sonuç: cihazdaki 37029'un üstüne 37030 kurulur — normal güncelleme, tek tık, çakışma yok,
Shizuku gerekmez. Çalışan kod 11.1'dir.

`-r1`, `-r2` eki zorunlu: aynı `versionName` ile ikinci kez yayınlamak release kapısında
durur (aynı etiket iki farklı APK'yı gösteremez). Her kurtarma yeni bir numara alır.

### Kaç sürüm geriye?
Sınırsız — her kurtarma yeni bir `versionCode` üretir, geçmiş kalıcıdır. Pratik sınır:
**son 5 yayın** listelenir (daha eskisi zaten veritabanı şeması uyumsuz olabilir).

## 3. Sürüm çakışması — nasıl imkânsız kılınıyor

Dört kapı, hepsi CI'da:

1. **Aynı `versionCode` iki kez yayınlanamaz.** Var olan release'in asset adından eski
   numara okunur; yeni numara büyük değilse derleme düşer. (Mevcut kapı, korunuyor.)
2. **Aynı etiket iki farklı APK gösteremez.** `v<versionName>` upsert edilir; bu yüzden
   farklı içerik = farklı sürüm adı. Kurtarma bu yüzden `-rN` taşır.
3. **Her yayın öncesi imza + versionCode + app_name + libproot + rootfs elle doğrulanır.**
4. **Yayın sonrası APK indirilip sertifika SHA-256 ve versionCode tekrar okunur** — CI'ın
   kendi çıktısına güvenilmez, artefaktın kendisine bakılır.

## 4. Veri uyumluluğu (B yolu için kritik)

Düşürme yapmadan önce:

- **Otomatik yedek**: `BackupManager` mevcut; düşürme akışı önce yedek alır, yedeğin
  yazıldığını doğrular, sonra kurar.
- **Şema kapısı**: `notes.db` ve `provider.db` sürümleri karşılaştırılır. Hedef sürümün
  `DB_VERSION`'ı kuruludan **küçükse** uyarı: "bu sürüm daha yeni verilerle açılmayabilir".
  Engellemez, ama sessiz de geçmez.
- Yedek yolu kullanıcıya gösterilir, tek dokunuşla geri yüklenebilir.

## 5. Minis Hakkında › Sürümler ekranı

```
Sürümler
├── 1.15-ozel.11.1   37028   16 Kas 2026   76 MB   ● kurulu
│   └── [Sağlam işaretle]  [Kurtarma derlemesi oluştur]
├── 1.15-ozel.11.0   37027   ...           76 MB
└── 1.15-ozel.10.1   37016   ...           75 MB   [Düşür (Shizuku)]
```

Her satırda:
- **Üst sürüm** → "Güncelle" (mevcut yol, tek tık)
- **Alt sürüm** → "Bu sürüme dön" → iki seçenek sunulur:
  - `Kurtarma derlemesi` (önerilen, Shizuku gerekmez, veri korunur)
  - `Doğrudan düşür` (Shizuku varsa; önce yedek + şema uyarısı)
- **"Sağlam işaretle"** → o sürüm kurtarma için önerilen taban olur

### Kurtarma derlemesi nereden tetiklenir?
Ekrandan değil, **GitHub Actions'tan** — çünkü derleme cihazda yapılmıyor. Ekran
`workflow_dispatch` çağırır (`ref`, `base_version`, `reason` girdileriyle), CI eski kodu
yeni numarayla derleyip yayınlar, cihaz `UpdateChecker` ile onu görür ve kurar.
Bu, mevcut yayın akışının aynısı — yeni bir mekanizma değil, yeni bir **girdi**.

## 6. Uygulama sırası

1. ✅ Yayın kapısı (mevcut, sıkılaştırılacak)
2. `rollback.yml` workflow'una `base_version` girdisi + kod geri alma adımı
3. `VersionsScreen` (liste + kur/geri dön)
4. Shizuku düşürme yolu + yedek/şema kapısı
5. Test: 37016 → 37028 yükselt, sonra kurtarma derlemesiyle 37016 koduna dön
