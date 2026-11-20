#!/usr/bin/env python3
"""Sürüm notu üreticisi — 1.15-ozel.17 için.

`.16` yayınlanmadı; bu not `.16`'nın tamamını **ve** Termux katmanını
birlikte taşıyor. Neden birleşti: yayın tek seferde yapılacak, ve yayınlanmamış
bir sürümün notunu ayrı tutmak "iki bekleyen sürüm var" yanılgısı üretir.

Kullanım: metin olarak bas (release gövdesine ver).
"""

GUVENLIK = """\
## Ne değişti — güvenlik ve doğrulama

### Risk korpusu: kural tablosu artık ölçülüyor
`test/resources/ozel/risk-corpus.txt` tek kaynak — **57 komut**, her biri
`SILENT / ASK / DENY` kararı ve gerekçesiyle. `RiskCorpusTest` gerçek
sınıflandırıcıyı çalıştırıyor; `scripts/ozel/risk-corpus.py` aynı dosyayı JVM
olmadan okuyor. Yeni bir kaçış yolu bulduğunda önce komutu dosyaya yazıyorsun,
test kırmızı oluyor, sonra kuralı ekliyorsun.

### Kapatılan üç kaçış yolu (ölçülmüş)
- **Yorumlayıcı.** `python3 -c "import shutil; shutil.rmtree('/var/minis/shared')"`
  hiçbir kurala uymuyordu (`RM` kalıbı `rm\\s` arıyor, `rmtree` eşleşmiyordu).
  Artık satır içi kod kart açıyor; içinde silme/üzerine-yazma API'si geçiyorsa
  HIGH + geri dönüşsüz — yalnız bu sohbette hatırlanır.
- **Sessiz veri kaybı.** `mv`, `truncate`, `> dosya`, yıkıcı SQL (`drop table`)
  için kural yoktu. Dördü de hedefe göre seviyeleniyor. `> /dev/null` ve
  `/tmp` yazımı sessiz kalıyor, yoksa her komut kart açardı.
- **Tırnak içi bölünme.** Kesici `;`/`|` üzerinde körlemesine bölüyordu, tırnak
  içinde bile. `sh -c "a; <silme> /x"` iki parçaya ayrılıp silme görünmez
  oluyordu. Kesme artık tırnak farkında.

### Kendi kendini silme yasağı düzeltildi
`pm uninstall com.minis.x` **geçiyordu**: yasak listesi `.14`'te değişen paket
adını tanımıyordu, eski ad da `\\b` yüzünden kapsanmıyordu, `pm clear` hiç
yoktu. Üçü de kapandı; `pm disable-user` eklendi (kendini kapatan ajanı geri
açacak kimse kalmaz).

### Onay kutusu test edilebilir oldu
Bir turu beş dakika bekletebilen mantık `PolicyGate` içinde `Context` ile
sarılıydı; hiçbir test dokunamıyordu. `ApprovalInbox` Android'siz: zaman aşımı,
geç gelen cevap, çift cevap, "hepsini reddet", kart birleşmesi — **7 test**.
`answer()` artık `Boolean`: zaman aşımından sonraki dokunuş yutulmuyor.
"""

TERMUX = """\
## Ne değişti — Termux katmanı (yeni)

### Ne alınıyor, ne alınmıyor
Termux bir **uygulama**; onu kurmuyoruz. İki şeyi ödünç alıyoruz: resmî
bootstrap (31,6 MB, 3 779 dosya) ve `apt` paket evreni. Sebep ölçüldü: saf
paket ihtiyacı için Termux gerekmiyor — Alpine `apk` ile `openssh`, `python`,
`node`, `clang` zaten kuruluyor. Termux'un **gerçekten** eklediği şey bionic
tarafı: Android'e özel derlenmiş ikililer.

### Mimari — iki katman, tek kök
```
alpine-rootfs/                    PRoot kökü
├── bin/ usr/ …                   KATMAN A: Alpine (musl, apk)    [varsayılan]
└── data/data/com.termux/files/   KATMAN B: Termux (bionic, apt)  [isteğe bağlı]
```
Termux ikililerinin öneki **sabittir**: `/data/data/com.termux/files/usr`.
PRoot kökümüzde gerçek Android `/data` bağlı olmadığı için bu yol kökün içinde
sıradan bir dizindir — bootstrap tam oraya açılıyor, ikililer kendi önekini
buluyor. Proot yaması, symlink hilesi, ikili düzenleme yok.

Bionic yükleyici için köke iki bağ ekleniyor: `-b /system`, `-b /apex`. İkisi
de dm-verity ile salt-okunur bölüm; bağlamak yeni bir yazma yüzeyi açmıyor.
**`/data` asla bağlanmıyor** — bağlanırsa onay kapısının sandbox sınırı
anlamsızlaşır.

### "Önce ölç, sonra kur"
`/system/bin/linker64` her cihazda aynı yerde değil (Android 14+ bazı imajlarda
yalnız APEX altında). `TermuxLayer.probe()` gerçek dosyalara bakıp gerekçesini
yazıyor; uygun değilse düğme pasif ve **sebep kartta**. Yükleme sonrası da
iddia yetmiyor: `bash --version` çıktısı gösteriliyor.

### Güvenlik — üç yeni yetki, üç kural
| Yetki | Risk | Kural | Seviye |
|---|---|---|---|
| `apt`/`pkg`/`dpkg` | yeni kod, konuk root | `termux:pkg` | MEDIUM, geri alınabilir |
| `sources.list` değişikliği | sonraki her paket yeni kaynaktan | `termux:sources` | **HIGH**, hesap kapsamı |
| `sshd` | kabuk ağa açılır | `termux:sshd` | **HIGH**, geri dönüşsüz |

Kural **sırası** bir güvenlik kararı: `apt install` hem `pkg:install`'a (sessiz)
hem `termux:pkg`'ye (kart) uyar, tablo sıralı ve ilk eşleşen kazanır. İlk
korpus koşusunda sıra ters olduğu için `apt install -y sshpass` **sessizce
geçti** — testin yakaladığı gerçek bir hataydı.

Katman **varsayılan kapalı**. Kurulmadan `apt` yok; `sshd` ayrıca kart istiyor.
`termux-api` ilk sürümde **yok**: Termux APK'sı kurulu değilse çalışmaz ve
"var görünen ama çalışmayan özellik" `.14`'te kınanan sınıfın ta kendisi.

### Testler
`TermuxLayerTest` — **13 test**, cihazsız: uygunluk (linker yok / `/system` /
APEX), yer kontrolü, önek haritası, ortam ayrımı (`LD_LIBRARY_PATH` yalnız
Termux `lib` — musl lib'i bionic ikilisine yüklenirse sessiz çökme), katman
seçimi, plan sırası, sabit bootstrap sürümü.

### Etkiler
| | |
|---|---|
| APK boyutu | **değişmez** (43 MB) — bootstrap çalışma anında iner |
| Disk | +110 MB (+ `apt` listeleri ~40 MB) |
| İlk açılış | değişmez (tembel) |
| Kabuk RAM | ~40 MB → ~55–70 MB |
| Yedek | katman yedeğe **girmez**, yalnız `home` |

Ayrıntı: `docs/ozel/TERMUX-ENTEGRASYONU.md` (mimari, T1–T10 cihaz testi,
ölçüm tablosu, reddedilen seçenekler).
"""

SAGLIK = """\
## Ne değişti — sağlık göstergesi ve çeviri

### Sohbet sağlık göstergesi bağlandı
`.15`'te çekirdek vardı ama üretim kodunda **tek referansı kendi tanımıydı**;
anahtar ise varsayılan **açıktı** — kullanıcı açık bir anahtar görüp hiçbir şey
görmüyordu. Tam olarak `.14`'te `STATION` için kınanan hatanın aynısı.
`ChatHealthWiring` üç gerçek anı bağlıyor (istek çıktı · ilk parça geldi · tur
kapandı) ve Canlı Görevler kartında tek satır oluyor: `820ms`, `3.2s`, `1/3`,
`429`. Bitiş hatayı **ezmiyor** — kullanıcı 429 yediğini görebilmeli.

### Çeviri: kısmi sonuç önbelleğe giriyor
7 parçanın 5'i çevrilip 6.'da ağ koptuysa kullanıcı kısmi metni görüyor ama
pencereyi kapatıp açtığında **baştan ödüyordu**. Kısmi sonuç ayrı anahtara
yazılıyor; tam çeviri gelince siliniyor.

### Ölü anahtar kaldırıldı
`STATION` ayar satırı silindi: `ui/station/` yazılmadı, anahtarın üretim
kodunda okuyucusu yok. Kapatılmış ama **görünen** anahtar da bir vaattir.
"""

DOGRULAMA = """\
## Doğrulama

| Kontrol | Komut | Sonuç |
|---|---|---|
| Risk korpusu | `python3 scripts/ozel/risk-corpus.py --fail-on-mismatch` | **57 komut · 0 sapma · 37 kural** |
| Yapısal denetim | `python3 scripts/ozel/kotlin-denetim.py` | **1 644 dosya · yapısal hata yok** |
| Entegrasyon | `python3 scripts/integration_audit.py` | **10/11 dilim üretimden çağrılıyor** |
| Etiket | `python3 scripts/ozel/tag-denetim.py` | **5 etiket yanlış commit'te** |
| Anlık görüntü | `python3 scripts/ozel/snapshot.py` | zip + git bundle + damga |

Bu cihazda `./gradlew test` koşulamıyor: JVM PRoot altında başlamıyor
(*"Failed to mark memory page as executable"*). `RiskCorpusTest`,
`ApprovalInboxTest`, `TermuxLayerTest` CI'da koşacak; `apk.yml`'deki
`needs: tests` kapısı kırmızıysa sürüm çıkmaz.

## Bilinen sınırlar

- **Kara liste hâlâ kara liste.** Dil içinde dil (base64 kod, `eval`, betiğe
  yazıp çalıştırma) metinden çözülemez. İddia "her şeyi yakalar" değil,
  "bilinen her yol bir kart açar".
- **Termux katmanı P1'de.** Karar mantığı + kurallar + testler hazır; kurulum
  akışı, `PRootKernel` bağları ve arayüz P2–P3'te. Şu an katman **kurulamaz** —
  bu dürüst hâli.
- **Beş yayın etiketi yanlış commit'te** (`.11.3.1`, `.11.4`, `.13`, `.14`,
  `.15` → hepsi `11.2`). Kendi güncelleme kanalı bundan etkilenir. Düzeltme
  planı: `tag-denetim.py --fix-plan`; uygulanmadı (uzak etiket silme geri
  dönüşsüz).
"""

if __name__ == "__main__":
    print(GUVENLIK)
    print()
    print(TERMUX)
    print()
    print(SAGLIK)
    print()
    print(DOGRULAMA)
