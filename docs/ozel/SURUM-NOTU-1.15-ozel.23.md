# SÜRÜM NOTU — 1.15-ozel.23 / 37104

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
