# SÜRÜM NOTU — 1.15-ozel.22 / 37103

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
