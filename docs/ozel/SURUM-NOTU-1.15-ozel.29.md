# SÜRÜM NOTU — 1.15-ozel.29 / 37110

> **Derlenmedi.** Kullanıcı talebiyle bu turda APK üretilmedi; kaynak dalda
> hazır. Derleme yapılmadan önce CI'daki `compile` + `unit test` adımları
> koşmalı (bu cihazda gradle/JVM yok).

## Ne kapandı

**Konsey (asıl iş):**
- Hiçbir üye pozisyon üretmediğinde (`no_positions`) sonuç artık **boş metin
  değil**: üye üye `absentWhy` listeleniyor ve "bu bir sonuç değil, arıza
  kaydı" deniyor. Önce sessizce boş dönüyordu.
- **Soru → cevap eşleme**: `QuestionLine.answer` / `answeredRound`. Cevap ayrı
  alanda tutulmuyor; hedef üyenin **sonraki tur** pozisyonundan eşleniyor
  (`matchAnswers`). Aynı turda sorulan soru kendi cevabı sayılmaz; cevap bir
  kez yazılır.
- **Görev odası eylemli**: görev ekle (dialog), üyeye ata, düşür. Boş oda artık
  görünür (önce `return` ediyordu). Yazma `TaskStore`'a gidiyor, sonra canlı
  kayda geçiyor — ekran diski değil belleği gösterirse iki kaynak ayrışır.
- **Dalga bilgisi**: 5 üye / 4 dalga ise durum satırı "5 üye, 2 dalga" yazıyor.

**Termux:**
- Havuzlu kabukta `apt` hâlâ susuyordu: havuz tek süreçtir ve `-0` ile bir kez
  başlar. `ExecutionCoordinator.requiresFreshProcess` paket komutlarını **her
  zaman** taze sürece yönlendiriyor (`-i 10000:10000`). Günlük işler havuzda
  kalır.
- Sınır: **interaktif Terminal** hâlâ tek süreç + `-0`. Orada `apt` reddedilir.
  `YAPILACAKLAR.md` §2.1.

**İzinler:**
- Kullanım sayacı: "… · 7 kez kullanıldı". `Grant.uses` zaten vardı, hiçbir
  ekranda okunmuyordu.
- Süresi geçmiş kayıtların kullanımı sayılmıyor.

## Değişen dosyalar (özet)

| Dosya | Ne |
|---|---|
| `agent/team/CouncilLoop.kt` | boş sonuç → arıza kaydı; `Positioned`'a soru/istek |
| `agent/team/CouncilLiveRegistry.kt` | `QuestionLine.answer`, `NeedLine`, `stances`, `waveSize`, `matchAnswers` |
| `agent/team/AndroidCouncilRuntime.kt` | `memberAsks` + `matchAnswers` bağlandı |
| `ui/team/CouncilScreens.kt` | 6 oda, cevap satırı, dalga satırı |
| `ui/team/TaskHallScreen.kt` | görev ekle/ata/düşür, boş durum |
| `sandbox/ExecutionCoordinator.kt` | Termux komutları taze süreçte |
| `policy/GrantCatalog.kt` | `usageCounts` |
| `ui/settings/PermissionGrantsScreen.kt` | kullanım sayısı |
| `docs/ozel/YAPILACAKLAR.md` | **yeni** kapsamlı plan |

## Test

`CouncilRoomsTest` 10 test (4'ü yeni: cevap eşleme, aynı tur, bir kez, yanlış
üye). `GrantCatalogTest` 10 test (1 yeni: kullanım sayacı).
Kapılar: **8/8 yeşil**, 47 sn.

## Ek (aynı tur, ikinci geçiş)

**Konsey güvenilirliği — çekirdek:**
- `MemberFailure`: üye düşme gerekçesi artık **sınıflandırılıyor**
  (`kota`, `kimlik reddedildi`, `JSON bozuk`, `zaman aşımı`, `akış yuvası yok`,
  `model yok`, `oturum açılamadı`, `boş cevap`, `bilinmeyen`) ve her sınıfın
  **ne yapmalı** satırı var. Önce hepsi tek serbest metindi; kullanıcı kotayı
  mı modeli mi düzelteceğini bilmiyordu.
- `CouncilPreflight`: koşu başlamadan üye üye doğrulama. **Hiç üye hazır
  değilse koşu başlamaz** ve token harcanmaz. Bazıları hazırsa koşu kalanlarla
  sürer, düşenler gerekçesiyle günlüğe yazılır.

**İzinler:**
- `deny:` kuralları (hiç sorulmadan reddedilenler) İzinler ekranında
  **salt-okunur** bölümde, nedenleriyle. Anahtar yok: bu sınıfta "evet" güvenli
  bir cevap değil.

**Altyapı:**
- `scripts/ozel/kotlin-hizli.py` + `kapi.py`'de **9. kapı**. Bu cihazda gradle
  yok; son üç turda CI'ın yakaladığı dört hata bu aracın kapsamındaydı
  (tanımsız değişken sırası hariç — o hâlâ yalnız CI'da çıkıyor).
  Yalnız kesin kurallar kırmızı yapar; yanlış-pozitif üreten denetim "bilgi"
  olarak basılır (ilk sürümde 40+ yanlış-pozitif çıktı, daraltıldı).

**Plan:**
- `YAPILACAKLAR.md` §10: `.23` → `.30` yol haritası, tema + kapanacak madde +
  kanıt. §11: riskler ve alınmış kararlar.

## Test (ek)

`CouncilPreflightTest` 10 test (ön kontrol 5, arıza sınıfı 5).
`GrantCatalogTest` 11 (1 yeni: deny listesi). Kapılar **9/9 yeşil**, ~44 sn.

## .24 ek — görev kanıtı ve ön kontrol uyarısı

**§1.4 görev kanıtı (kapandı):** `TeamTask.canBeDone` kuralı vardı ama
kullanıcı **kanıt ekleyemiyordu**; yani DONE hiç açılmıyor, görev sonsuza kadar
"kanıt yok" kalıyordu. Artık görev odasında:
- **Kanıt** düğmesi → tür (path / url / output) + değer diyaloğu,
- **Bitti** düğmesi — kanıt yoksa **soluk ve kapalı** (kural ekranda görünür).

**§1.2 ön kontrol uyarısı (kapandı):** koşu eksik üyeyle başlıyorsa
`LiveRun.preflightNote` ile ekranın en üstünde "Eksik üyeyle başladı" satırı
görünür. Önce yalnız günlüğe yazılıyordu; günlüğü kimse okumuyor.

**Araç düzeltmesi:** `kotlin-hizli.py` veri sınıfı ayrıştırıcısı iki kez yanlış
pozitif verdi (varsayılan değerdeki parantez, yorum içindeki virgül). İkisi de
düzeltildi: dengeli parantez taraması + yorumları **bölmeden önce** silme.
Bu, "kapı yanlış pozitif üretirse kapatılır" kuralının uygulanması.

**Test:** `CouncilRoomsTest` 13 (3 yeni: kanıtlı kapanış, boş kanıt reddi,
ön kontrol notu). Kapılar 9/9.

## .25 ek — izin/onay tamamlama

**§3.3 reddedilenler görünür:** `GrantCatalog.denialRows` + İzinler ekranında
"Reddettiklerin" bölümü — kural, kaç kez, hangi kapsamda, son gerekçe.
Önce yalnız ALLOW sayılıyordu; kullanıcı "ben bunu reddetmiştim" diyemiyordu.

**§3.2 kapsam görünürlüğü:** `GrantCatalog.offeredScopes(rule)` — oturum her
zaman; proje cihaza taşmayan ve geri alınamaz olmayan için; cihaz geneli yalnız
sandbox + geri alınabilir. Geniş kapsamı önermek, güvenlik kuralını gevşetmek
değil, **var olan seçeneği görünür kılmak**; ama sınır korunuyor.

**Test:** `GrantCatalogTest` 14 (3 yeni: ret listesi, ALLOW dışlanması, kapsam
sınırı). Kapılar 9/9.

## .26 ek — altyapı

**§7.4 harita kendini tazeler:** `kapi.py` artık `harita` kapısından **önce**
`harita.py`'yi koşuyor. Geçen turda bayat harita CI'ı kırdı (`SURUM-NOTU` adı
değişti, harita eski adı taşıyordu); "unutulacak adım" olmaktan çıktı.

**§7.1 DNS doktoru:** `scripts/ozel/dns-doktor.py`. Ölçülen belirti: `getent`
boş dönerken `curl` 200 veriyor — iki ayrı çözümleme yolu (TUN/vekil vs
`/etc/resolv.conf`). Araç **ayar değiştirmez** (auto mod Fake-IP'yi bilerek
kullanıyor; oradan yazmak vekili bozabilir), yalnız durumu ve üç yolu söyler.
`/etc/hosts` yaması varsa "çözümleme yalnız yamayla çalışıyor" der — yoksa
teşhis kendi yamasına kanardı.

## .27 ek — alt ajan görünürlüğü

**§4.1 kalan süre:** `AgentJob.budgetMinutes` + `AgentJobRegistry.budgetClock`
→ kart "7/10 dk". Bütçe bilinmiyorsa **satır çizilmez** (0 = bilinmiyor);
uydurulmuş bir sayı yanlış "kaldı" hissi verirdi. Devam ettirilen işte bütçe 0:
eski argüman elde yok.

**Kapı düzeltmesi (önemli):** `kotlin-hizli.py` artık yalnız **eklenen
satırları** yargılıyor. Önce dosyanın tamamına bakıyordu; `ChatViewModel.kt`'de
tek satır değiştirmek, oradaki yıllanmış Çince yorumu ve kullanılmayan
importları "yeni hata" gibi gösterdi. Bu, sahibi olmadığın borcu ödetmek olur —
ve kapı kapatılır. Hunk başlığı ayrıştırması (`@@ -a +b,n @@`) ile çözüldü.

## .28 ek — çeviri

**§6.1 paket önerisi:** `TranslationPacks.recommendFor(packs, language)` (saf,
test edilebilir) + `recommend(context)` cihaz dilini ölçer. Sıra: dil eşleşmesi
→ en zengin glossary → **null**. Boş paket önerilmez (terimi olmayan paket bir
çeviri paketi değil, boş dosyadır). Ayarlar'da "Önerilen: X" satırı yalnız aktif
paket yokken görünür.

**§6.2 "kalanı çevir" zaten vardı:** `ThinkingTranslator` önbellekten kaldığı
yerden devam ediyor (`done` sayacı, `complete` bayrağı). Ek düğme icat
etmedim — olmayan bir eksiği kapatmak, boş bir düğme eklemek olurdu.

## .29 ek — sanal ekran

**§5.3 bölme başına yakalama bütçesi (kapandı).** Belge "aynı bölme 250 ms'de
bir yakalanmaz" diyordu; kod tek **global** damga tutuyordu. Fark gerçek: üç
bölme açıkken biri yakalanınca diğer ikisi de bütçesini harcamış sayılıyor,
hızlı dönen bir sekme iki durgun sekmeyi aç bırakıyordu. `State.lastCaptureByPane`
+ `markCapturedAll`. Soğuk açılış korunuyor: hiç yakalanmamış bölme istenir.

**Yapılmayan (§5.1, §5.2):** tek tuval çizimi ve `screen` aracı (open/click/
type/scroll). İkisi de gerçek bir yüzey mimarisi kararı ve cihazda yakalama
kanıtı istiyor; bu turda kapatılmış gibi göstermiyorum.
