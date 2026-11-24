# SÜRÜM NOTU — 1.15-ozel.35 / 37116

> **Yayındaki sürüm:** 1.15-ozel.34 / 37115 · **Bu sürüm derlenmedi**
> (kullanıcı kuralı: derleme yalnız isteyince). Kapılar 10/10 yeşil.

## Neyi çözdü

Kullanıcının iki cümlesi bu sürümün tamamı:

> *"onay mekanizmasını kaldırsak… sadece ayarlardan izin verebiliriz, sohbet bazlı
> izin yerine uygulama genel izin olmalı"*

> *"bazı yerlerde onay istiyorsun ben duyamıyorum… sesli onay olmalı, onay
> bekliyorum diye uyarı verebilirsin"*

Birincisi bir **tasarım** hatasıydı, ikincisi bir **erişilebilirlik** hatası.

## 1 · Onay modu — "sohbette sorma, bir kez Ayarlar'da karar ver"

Yeni `policy/ApprovalMode.kt`, üç mod:

| Mod | Kart ne zaman çıkar |
|---|---|
| `SETTINGS_ONLY` (**varsayılan**) | Yalnız önceden izin verilemeyenler: geri alınamaz (PERMANENT) ya da sandbox dışı (DEVICE / NETWORK / ACCOUNT) |
| `ALLOW_ALL` | Yalnız sabit engeller (`deny:`). Geri alınamazlar da uygulama geneli izinli |
| `ASK_EACH` | Eski davranış — izin verilene kadar her riskli çağrıda kart |

**Karar (benim önerim, uygulandı):** varsayılan `SETTINGS_ONLY`. Sebep: kart
yorgunluğunun kaynağı kartın varlığı değil, **her sohbette yeniden sorulması**.
Bir kez karar verilip bütün sohbetlerde geçerli olması hem daha az kesinti hem
daha tutarlı bir güvenlik duruşu — kullanıcı neye izin verdiğini **tek yerde**
görür.

**Değişmeyen sınır:** `deny:` sınıfı (`rm -rf /`, `mkfs`, disk biçimlendirme)
hiçbir modda açılmaz. Bu bir onay değil, sandbox sınırı: bir ayar anahtarına
bağlanırsa web sayfasından gelen bir komut kullanıcının kendi ayarını
kullanarak sandbox'ı siler. `ApprovalModeTest.deny_sinifi_hicbir_modda_otomatik_izin_almaz`
bunu üç mod × bütün `deny:` kuralları üzerinde çiviliyor.

### Neden "tembel" yazım

`PolicyGate`, kart çıkacakken kuralı `autoGrants` ile süzüyor; geçerse
`Global` kapsamlı izin **o anda** yazılıp iş sessiz geçiyor. Alternatif (modu
seçerken bütün kuralları topluca yazmak) iki durumda bozulurdu: (a) kayıtların
ömrü var — süresi bitince "yine soruyor" olurdu; (b) sonradan eklenen bir kural
eski toplu yazımın içinde olmazdı. Tembel yazım ikisini de kendiliğinden onarır.

## 2 · Sesli onay uyarısı

Yeni `ozel/ApprovalVoiceCore.kt` (saf) + `ozel/ApprovalVoice.kt` (Android köprüsü)
+ `ozel/ApprovalSettings.kt` (disk).

- **Konuşur:** "Onay bekliyorum. Dizin ağacı silinecek. İşlem: rm -rf … Geri
  gelmez. Onaylamak için karta dokun."
- **Tekrarlar:** varsayılan 30 sn'de bir, cevap gelene kadar (sınır 12 duyuru).
- **Titrer:** iki kısa darbe — normal bildirimden ayırt edilir.
- **Bildirim:** `IMPORTANCE_HIGH` kanal; dokununca uygulama öne gelir.
- **Susar:** kart cevaplanınca, zaman aşımına düşünce ya da tur iptal edilince.

### Neden `PolicyGate.pending` dinleniyor

Onay isteği üç yerden doğuyor (shell, dosya yazma, plan önizleme) ve artacak.
Her çağrı yerine bir `announce(...)` satırı eklemek, birini unutmak demek — bu
depoda "dört PRoot kurucusundan birini unutmak" hatası üç kez yaşandı. Tek bir
`StateFlow` abonesi hiçbir çağrı yerini kaçırmaz.

### Neden söylenenler seçiliyor

Sesli duyuru kartın beş satırını okumaz, **kararı değiştiren** üçünü okur:
başlık · geri dönüş · nereye dokunduğu. "Geri alınabilir" cümlesi söylenmez
(1.5 saniye yer kaplar, bilgi vermez); "GERİ GELMEZ" söylenir. Komut 70
karakterde kesilir. `ApprovalVoiceTextTest` bunları çiviliyor.

## 3 · Ayarlar › İzinler ekranı

`ui/settings/PermissionGrantsScreen.kt` başına iki bölüm eklendi:

- **Ne zaman sorulsun** — üç mod, tek seçim.
- **Sesli onay uyarısı** — aç/kapa, bir kez/tekrarla, komutu söyle, titreşim,
  dört tekrar aralığı (15/30/60/120 sn), "Şimdi dene" düğmesi.

Mevcut "Hepsine izin ver" / "Hepsini geri al" / kategori anahtarları
değişmedi; mod onların **yerine** geçmiyor, üstüne oturuyor.

## Test

- `policy/ApprovalModeTest` — 7 test (deny kilidi, grantable süzgeci, parse).
- `ozel/ApprovalVoiceTextTest` — 10 test (cümle, kesme, sessizlik kuralları).

## Kapılar

```
apk-butce 0.3s · ceviri 2.0s · denetim 46.7s · entegrasyon 24.1s · etiket 3.9s
harita 5.6s · korpus 1.9s · kotlin 11.5s · olu-kod 0.6s · yapisal 15.9s
10/10 geçti · 0 uyarı · 46.7s
```

## Bu sürümde kapanan / açılan

| Madde | Durum |
|---|---|
| §3.1 onay kartı yorgunluğu (karttan kural açma) | **kapandı** — mod + Ayarlar tek karar |
| §3.2 kapsam görünürlüğü | **kapandı** — `SETTINGS_ONLY` zaten uygulama geneli yazıyor |
| Yeni: sesli onay | **açıldı ve kapandı** (cihazda denenmeli) |
| §3.3 ret listesi | açık — bu turda dokunulmadı |
| §5.1/5.2 sanal ekran | açık — sıradaki tur |
| §7.1 DNS | açık — `.39` |

## Sırada

Plan `docs/ozel/PLAN-35-45.md`: `.36` görünürlük kapısı (bu turdan kaydı),
`.37/.38` sanal ekran, `.39` Terminal/Termux, `.40` DNS, `.41` kapalı anahtar
kararları, `.42`–`.45` konsey / alt ajan / çeviri / ürün kapanışı.

## Cihazda sınanması gerekenler (senin yapacağın)

1. Ayarlar › İzinler › **Ne zaman sorulsun** → `SETTINGS_ONLY` seçili mi?
2. Tekrar aralığını 15 sn yap, "Şimdi dene" → ses duyuluyor mu?
3. Bir `rm -rf` denemesi: kart çıkmadan geçmeli (geri alınabilir + sandbox içi).
   Bir `device:` kuralı: kart **çıkmalı** + telefon **konuşmalı**.
