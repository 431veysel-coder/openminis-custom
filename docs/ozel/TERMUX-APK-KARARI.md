# KARAR — Termux: katman mı, ayrı APK mı?

Soru (kullanıcı, 2026-11-22): *"Termux araç zincirleri bölümünde olmalı, tek
tuşla bağlantıya geçmeli. Hatta gerekirse Termux'u kendi bileşenlerinden
biriymiş gibi ekstra bir APK olarak cihaza yükleyebiliriz — içinde gömülü,
açıldığında hem gömülü hem APK olarak kurulabilir, ve burayla otomatik
iletişime geçsin. Mantıklı mı, daha iyi bir yöntem var mı?"*

**Cevap: yarısı doğru, yarısı hedefi öldürüyor.** Doğru olan: Termux araç
zincirleri listesinde olmalı (`.17`'de zaten ilk satır) ve tek dokunuşla
kurulmalı. Yanlış olan: ayrı APK, "Termux'un tüm dosya ve kütüphanelerini
ajanın kabuğunda kullanmak" hedefini **teknik olarak imkânsız** kılar.

---

## 1. Neden ayrı APK o hedefi öldürüyor

Android'de her uygulamanın verisi kendi uid'ine kapalıdır:

```
/data/data/com.termux/files/usr   ← Termux'un özel dizini
```

Bizim konuğumuz `com.minis.x` uid'inde çalışır. Bu dizini **okuyamaz**,
bağlayamaz (`-b` ile bağlanacak olsa çekirdek EACCES verir), kütüphanelerini
yükleyemez. Erişimin tek yolu root ya da Shizuku ile `run-as` — yani hedefi
"cihaz root'lu olsun" şartına bağlamak.

**Doğrudan ölçüm (bu turda):** PRoot konuğunda `ls /data` → *"No such file or
directory"*. Gerçek Android `/data` konuğa **bilerek bağlanmıyor**
(`PRootKernel.buildProotCommand`), çünkü bağlansaydı onay kapısının sandbox
sınırı anlamsızlaşırdı. Yani Termux ayrı APK olsaydı, ajanın göreceği şey
kütüphaneler değil **hiçbir şey** olurdu.

Buna karşılık bootstrap'ı kendi kökümüze açtığımızda (`P1–P2`, tamamlandı):

| | Ayrı APK | Bizim kökümüz |
|---|---|---|
| `$PREFIX` bizim uid'imizde mi | ❌ başka uygulamanın | ✅ bizim dosyamız |
| Ajan `libncursesw` yükleyebilir mi | ❌ | ✅ |
| `/system` + `/apex` bağlanabilir mi | ❌ (zaten Termux'un içinde) | ✅ `-b`, salt-okunur |
| Root gerekir mi | ✅ gerekir | ❌ gerekmez |
| Disk maliyeti | +tam APK | +110 MB bootstrap |

**Sonuç:** "tüm dosyaları, tüm kütüphaneleri kullanmak" cümlesi yalnız
kök-içi bootstrap ile doğru olur. Bu yüzden `.17`'de yazılan mimari
(`data/data/com.termux/files/usr` kökün **içinde** sıradan bir dizin) tercih
değil, zorunluluk.

---

## 2. Ayrı APK'nın gerçekten eklediği şey (ve o dar)

Bootstrap'ın veremediği, APK'nın verebildiği tek sınıf var: **Android'e
dokunan yardımcılar** — `termux-api` (sensör, kamera, SMS, bildirim, pil,
konum), uyanıklık kilidi, `am` köprüleri. Bunlar Termux uygulamasının kendi
süreci olmadan çalışmaz.

Ama bunun için **bizim APK üretmemiz gerekmiyor.** Termux zaten bir intent
API'si sunuyor:

```
action   : com.termux.RUN_COMMAND
izin     : com.termux.permission.RUN_COMMAND
şart     : ~/.termux/termux.properties içinde allow-external-apps=true
```

Yani doğru entegrasyon "kendi plugin APK'mızı yazmak" değil, **kullanıcı
Termux kuruluysa onun API'sine bağlanmak**. Tek seferlik bir ayar
(`allow-external-apps`) ve tek dokunuş yeterli.

### Termux:SU diye bir bileşen yok

Termux'un resmî eklenti APK'ları: `Termux:API`, `Termux:Boot`,
`Termux:Widget`, `Termux:Styling`, `Termux:Tasker`. **`Termux:SU` yok.**
Root için olan şey Termux *içindeki* `tsu` paketi — `su` sarmalayıcısı,
root'suz cihazda hiçbir şey yapmaz. Yani "termuxsu'yu bileşen gibi ekleyelim"
fikri, var olmayan bir bileşeni paketlemek olurdu.

Ayrıca eklenti APK'ları **Termux uygulamasıyla aynı imzayı** taşımak zorunda
(F-Droid derlemesi). Kendi anahtarımızla imzaladığımız bir "eklenti" Termux
tarafından kabul edilmez — yani "kendi bileşenimiz gibi APK" yolu, imza
nedeniyle de kapalı.

---

## 3. Gömülü APK fikri — bütçe zaten bunu yasaklıyor

`TermuxLayer.MAX_EMBEDDED_ASSET_KB = 4 MB` ve `APK_BUDGET_KB = 120`. Sebep
ölçülmüş: bootstrap'ı `assets/`e koymak APK'yı +32 MB yapar; derleme
(aapt2/dex/zipalign) ve CI artefakt aktarımı bundan gerçekten etkilenir.
Aynı mantık 100 MB'lık bir Termux APK'sı için fazlasıyla geçerli.

**Ve bir uyuşmazlık bulundu:** bu iki sabiti **hiçbir şey okumuyordu** —
`TermuxLayer` yorumu "TermuxLayerTest ve CI'daki boyut kapısı bunu okuyor"
diyordu, ikisi de yoktu. Düzeltildi: `scripts/ozel/apk-butce.py` artık
`assets/` altında bootstrap benzeri yük arıyor ve `ozel-guard.yml`'e bağlı.

Doğru çözüm bootstrap'ta zaten uygulandı: **çalışma anında indir, sha256 ile
doğrula**. Aynı yöntem Termux APK'sı için de geçerli — gömme değil, indir +
özet doğrula + kullanıcı onayıyla kur (`REQUEST_INSTALL_PACKAGES` manifestte
zaten var).

---

## 4. Karar: tek satır, iki anahtar

Termux, araç zincirleri listesinde **tek satır** kalır (`termuxGroup`, listede
ilk). Satırın altında iki ayrı yetenek, iki ayrı durum:

| Anahtar | Ne verir | Nasıl |
|---|---|---|
| **Katman (bootstrap)** — *varsayılan* | `apt`/`pkg` evreni, bionic ikililer, ajanın kabuğundan erişilebilir | 10 adımlı kurulum, 32 MB indirme, sha256 sabit |
| **Termux uygulaması** — *isteğe bağlı* | yalnız Android'e dokunan yardımcılar (`termux-api`, uyandırma) | `com.termux` kuruluysa "Bağlan" → RUN_COMMAND izni + `allow-external-apps` kontrolü + `echo` gidiş-dönüşü |

İkisi birbirini **dışlamaz**, biri diğerinin yerine geçmez:

```
ajan kabuğu (bizim PRoot)
├── Alpine (musl, apk)          ← varsayılan
├── Termux katmanı (bionic, apt) ← "tüm kütüphaneler" burada
└── [varsa] Termux APK köprüsü   ← yalnız Android yardımcıları
```

Satır durumları: `Yok` · `Kuruluyor (adım)` · `Hazır` · `Bu cihazda
çalışmaz: <sebep>` (bionic yükleyici yoksa düğme pasif, sebep kartta).

---

## 5. Tek dokunuş için eksik olan ne

Bugün: `ToolchainScreen` → `install(context, "termux")` çağırıyor, düğme
**çalışıyor**; ilerleme çubuğu ve adım satırı var. Eksikler:

1. **Ön kontrol yok.** `refresh()` bütün gruplar için tek bir kabuk betiği
   koşuyor; Termux satırı `$PREFIX/bin/bash --version` ile yoklanıyor. Cihaz
   bionic'i desteklemiyorsa satır yine "Kur" diyor, kullanıcı 32 MB'ı
   indirdikten **sonra** "kullanılamaz" görüyor. Doğrusu: `TermuxLayer.probe()`
   ekran açılırken koşsun, sonuç `Failed` değil **`Unavailable(sebep)`** olsun.
2. **Terminal rozeti.** Oturum başlığında `ALPINE` / `TERMUX` ayrımı yok —
   ajan hangi katmanda olduğunu, kullanıcı hangi `bash`'in çalıştığını
   göremiyor.
3. **APK köprüsü hiç yok.** `com.termux` kurulu mu, `allow-external-apps`
   açık mı, RUN_COMMAND gidip dönüyor mu — üçü de ölçülmüyor.

Bunlar P3/P4 işi; mimari kararı değiştirmiyor.

---

## 6. Reddedilen seçenekler

| Seçenek | Neden reddedildi |
|---|---|
| Termux APK'sını `assets/`e gömüp kurmak | +100 MB APK, bütçe ihlali, imza/güncelleme yükü; kullanıcı kullanmayacaksa bedelini ödüyor |
| Kendi "plugin" APK'mızı yazmak | Termux eklentileri aynı imzayı ister; ayrıca gereksiz — RUN_COMMAND izni yeterli |
| Termux APK'sını zorunlu kılmak | Root'suz cihazda dosya/kütüphane erişimi vermez; hedefi daraltır |
| `/data`'yı konuğa bağlamak | Onay kapısının sandbox sınırını kendi elimizle kaldırmak |
| `termux-api`'yi ilk sürümde katmana koymak | Termux APK'sı yokken "var görünen ama çalışmayan özellik" — `.14`'te `STATION` için kınanan sınıf |

---

## 7. Özet cümle

Termux **araç zinciri satırında** kalmalı ve **tek dokunuşla** kurulmalı —
ama **ayrı APK olarak değil, kendi kökümüzün içinde bir katman** olarak.
Ayrı APK, "tüm dosyaları ve kütüphaneleri kullan" hedefini root şartına
bağlar; kök-içi katman aynı şeyi root'suz verir. APK'nın gerçekten eklediği
tek şey Android yardımcılarıdır ve onun için de kendi APK'mızı yazmak değil,
mevcut Termux'un intent API'sine bağlanmak doğru yol.
