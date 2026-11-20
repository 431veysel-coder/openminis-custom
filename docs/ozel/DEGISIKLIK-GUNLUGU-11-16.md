# Değişiklik günlüğü — `.11.3.1` → `.16`

> `FORK.md` §10 günlüğü `.10.1`'de duruyordu; kod beş sürüm ilerideydi. Bu dosya
> o boşluğu kapatıyor ve bundan sonrasının tek kaynağı olmayı hedefliyor.
> `FORK.md` §10 artık buraya yönlendiriyor.

---

## `1.15-ozel.16` / 37097 — 2026-11-20

**Konu:** `.15`'te ölçülen üç sessiz boşluğu kapatmak ve kural tablosunu
test edilebilir hâle getirmek.

### Güvenlik — risk kapısı

- **`RiskCorpus` (yeni).** `test/resources/ozel/risk-corpus.txt`: 48 komut,
  her biri `SILENT/ASK/DENY` kararı ve gerekçesiyle. `RiskCorpusTest` gerçek
  sınıflandırıcıyı koşar. `scripts/ozel/risk-corpus.py` aynı dosyayı JVM
  olmadan okur. Karar artık tek yerde.
- **`shell:interpreter`** — `python3 -c`, `node -e`, `perl -e`, `ruby -e`,
  `php -r`, `osascript -e` … satır içi kod çalıştıran her çağrı kart açar.
  Ölçülen boşluk: `python3 -c "import shutil; shutil.rmtree('/var/minis/shared')"`
  hiçbir kurala uymuyordu.
- **`shell:interpreter-destroy`** — aynı parçada silme/üzerine-yazma API'si
  (`rmtree`, `os.remove`, `unlink`, `rmSync`, `open(...,'w')`, `os.system`,
  `subprocess`) geçiyorsa HIGH + PERMANENT (yalnız bu sohbette hatırlanır).
  Kalıp tek parça: `rg "shutil.rmtree" src/` gibi bir *arama* kart açmaz.
- **`shell:truncate`, `shell:redirect-overwrite`, `shell:move`,
  `shell:chmod-recursive`, `sql:destructive`** — silmeden veri götüren yollar.
  `> /dev/null` ve `/tmp` yazımı sessiz kalır (gürültü olmasın), `/tmp` kökünün
  kendisi sessiz kalmaz (`rm -rf /tmp` başka oturumların alanıdır).
- **Tırnak farkında `splitSegments`.** Eskiden `;`/`|` tırnak içinde de
  bölünüyordu; `sh -c "a; rm -rf /x"` iki parçaya ayrılıp `rm` görünmez
  oluyordu. Artık tırnak dışı bölünüyor.
- **`deny:pm-all` düzeltildi.** `pm uninstall com.minis.x` **geçiyordu**: yasak
  listesi `.14`'te değişen paket adını tanımıyordu. Üç ad (`com.minis.x`,
  `com.veysel.minis*`, `com.openminis.app`) + `pm clear` kapsanıyor.
  **`deny:deny-self`** eklendi: `pm disable-user <kendisi>` — kendini kapatan
  ajanı geri açacak kimse kalmaz.
- **`isTemp` ayrımı** ve hedefi çözülemeyen `mv` için MEDIUM (HIGH değil:
  göreli taşıma oturum dizininde kalır, ama sessiz de değildir).

### Test edilebilirlik

- **`ApprovalInbox` (yeni, Android'siz).** Bir turu 5 dakika bekletebilen
  mantık `PolicyGate` içinde `Context` ile sarılıydı, hiçbir test
  dokunamıyordu. Zaman aşımı, geç gelen cevap, çift cevap, "hepsini reddet",
  aynı kimlikli kartın birleşmesi — `ApprovalInboxTest` (7 test).
- **Davranış değişikliği:** `PolicyGate.answer()` artık `Boolean` dönüyor.
  Zaman aşımından sonra gelen dokunuş yutulmuyor, görülebiliyor.
- **`PolicyGate.cancelPending()`** eklendi — iptal edilen turun kartı ekranda
  kalmıyor.

### Sohbet sağlık göstergesi — bağlandı

- **`ChatHealthBridge` (yeni).** `.15`'te `ChatHealthTracker` vardı ama üretim
  kodunda tek referansı kendi tanımıydı; anahtar varsayılan **açıktı** — yani
  kullanıcı açık bir anahtar görüp hiçbir şey görmüyordu. Tam olarak `.14`'te
  kınanan hatanın aynısı.
- `ChatViewModel`: tur başlangıcı, ilk parça, tur kapanışı ve hata noktası
  köprüye bağlandı.
- `LiveTasksScreen`: canlı kartın alt satırında `820ms`, `3.2s`, `1/3`, `429`.
- Hata durumu bitişte **ezilmiyor** — kullanıcı 429/503 yediğini görebilmeli.

### Çeviri

- **Kısmi çeviri önbelleğe giriyor.** 7 parçanın 5'i çevrilip 6.'da ağ
  koptuysa kullanıcı kısmi metni görüyor ama pencereyi kapatıp açtığında
  baştan ödüyordu. Kısmi sonuç `:partial` anahtarına yazılıyor; tam çeviri
  gelince siliniyor. Tamamlanmış çeviri asla yarım kayıttan okunmuyor.

### Arayüz

- **`STATION` ayar satırı silindi.** `ui/station/` hâlâ yazılmadı ve anahtarın
  üretim kodunda okuyucusu yok. Kapatılmış ama **görünen** bir anahtar da bir
  vaattir. Paneller gelince satır panellerle döner.

### Araçlar (yeni)

- **`scripts/ozel/risk-corpus.py`** — kural tablosunun JVM'siz aynası.
- **`scripts/ozel/tag-denetim.py`** — yayın etiketi ↔ sürüm denetimi.
- **`scripts/ozel/kotlin-denetim.py`** — derleyici olmadan yapısal denetim
  (denge, çift imza, kullanılmayan import).

### Sürüm

`versionCode 37097`, `versionName 1.15-ozel.16`. 37093–37096 yakılmadı.

---

## `1.15-ozel.15` / 37092 — 2026-11-20

Gönderilen: sohbet sağlık göstergesi **çekirdeği** (arayüzü yok), sözleşme
ailesi (24 sözleşme + bağ grafiği + kapı), uzaktan konsol jeton modeli
(uygulaması yok), kayıtlı yeniden oynatma, yazma niyeti + çakışma sicili,
kanıtlı "bitti" kapısı, ders defteri, araç kapısı, tur özeti diff'i, plan
önizleme kapısı, otomatik yedek zamanlaması, bütçe tavanı, onay kapısı
çekirdeği, proje bazlı hafıza, `team_run` iki döngü, Ekip + Projeler girişleri.

**Sonradan ölçülen:** `CHAT_HEALTH` ve `STATION` anahtarlarının üretim kodunda
okuyucusu yok; `FORK.md` günlüğü `.10.1`'de; `.13`/`.14`/`.15` etiketlerinin
üçü de `11.2` commit'ini gösteriyor.

## `1.15-ozel.14` / 37091 — 2026-11-19

Paket adı `com.veysel.minisx` → **`com.minis.x`** (yeni kurulum, veri taşınmaz,
Shizuku/erişilebilirlik izinleri yeniden verilmeli). **Compare (FANOUT)
gerçekten çalışıyor** — `TeamRunner` yalnız `COUNCIL`'e bakıyordu, Compare
seçen kullanıcı sessizce sıralı orchestration (bayrak yarışı) alıyordu.
Davranışsız iki anahtar kapatıldı: `STATION` ve `EDIT_MODE` (silindi).

## `1.15-ozel.13` / 37090 — 2026-11-19

11 özellik dilimi tek APK'da. Güvenlik ve maliyet (onay kapısı, harcama tavanı,
otomatik yedek), ajan kalitesi (plan önizleme, tur özeti, ders defteri, kanıtlı
bitti, yazma niyeti, araç kapısı, yeniden oynatma). Dilim kapısı: 11/11 yeşil,
105 test.

## `1.15-ozel.11.4` / 37033 ve `1.15-ozel.11.3.1`

Ekip çekirdeği (`team_run` iki döngü), `FanoutRunner` düzeltmesi, dilim
kapısı sertleştirmesi. Ayrıntı: `FORK.md` §10 ve release notları.

---

## Bilinen açık işler

| Öncelik | İş | Not |
|---|---|---|
| Yüksek | **Etiketlerin yeniden hedeflenmesi** | `.13`/`.14`/`.15` yanlış commit'te; `scripts/ozel/tag-denetim.py --fix-plan` planı yazar. Kendi güncelleme kanalı bundan etkilenir. |
| Yüksek | CI'da `RiskCorpusTest` + `ApprovalInboxTest` | Bu cihazda JVM başlamıyor; ilk gerçek doğrulama CI'da. |
| Orta | Compare koşularının `TeamRunStore`'a yazılması | `CouncilOutcome` tipi uymuyor; sahte boş konsensüs yerine kayıt hiç yazılmıyor. |
| Orta | `ui/station/` | Paneller yazılınca `STATION` satırı geri gelir. |
| Orta | `FORK.md` §5/§6 güncel değil | Kimlik ve sürüm şeması `.14`'te değişti. |
