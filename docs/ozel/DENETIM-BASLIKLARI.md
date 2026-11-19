# DENETİM BAŞLIKLARI — 13 eksen

Kullanıcının verdiği başlıklar: görev paylaşımı · kodlama · yazılım · güvenlik ·
sağlık · performans · uyumluluk · alt iskelet · üst iskelet · geri bildirim ·
sağlayıcılar (ping / hata / takip / tamir).

Her başlık için **ölçüm**, **bugünkü durum** ve **eksik**. "İyi" demek yerine
"şu sayı şu" demek — denetimin tek değeri bu.

---

## 1. Görev paylaşımı

| Ne | Durum |
|---|---|
| Alt ajan (`subagent_task`) | ✅ var. Derinlik 1, ebeveyn başına 3 eşzamanlı |
| Ekip (`team_run`) | ✅ var. HANDOFF / FANOUT / COUNCIL üç mod |
| Konsey | ✅ var. Dalga 2/3/4, tur 2-4, durma: tur/süre/token/yakınsama |
| Üye → üye iş verme | ✅ `assign_task` (tur 2, açık 15) + tahta |
| Üye → akran sorusu | ✅ `ask_teammate` (tur 3, koşu 8) |
| **Kuyruk görünürlüğü** | ⚠️ 90 sn gözcüsü var, **ekranda gösterilmiyor** |
| **FANOUT** | 🔴 `TeamMode.FANOUT` kaydediliyor ama `TeamRunner` onu handoff gibi koşuyor |

**Eksik:** FANOUT gerçekten paralel dağıtım yapmıyor. Kullanıcı "fanout" seçip
sıralı sonuç alıyor — sessiz bir yanlış vaat.

---

## 2. Kodlama / 3. Yazılım

| Ne | Ölçüm |
|---|---|
| Kaynak | 741 test dosyası, 6.481 test metodu, 0 `androidTest` |
| Bu fork'un dilimleri | 30 kaynak + 11 test dosyası, 105 test |
| Derleme | `kotlinc 2.1.0`, JDK 17, `jvmTarget 17` |
| Dilim kapısı | 11/11 yeşil (ayrı derleme, ayrı test, ayrı rapor) |
| Preflight | 10 sn, XML kaçışı + `R.string` + parantez + import |
| **Statik analiz** | 🔴 ktlint / detekt **yok**. `preflight` derleyici değil |
| **Kod kapsamı** | 🔴 jacoco **yok**. Hangi satır test edilmiyor bilinmiyor |

---

## 4. Güvenlik

| Ne | Durum |
|---|---|
| İmza | v2 + v3, sertifika `3e62d262…` doğrulandı (`scripts/apk_cert_check.py`) |
| Sır taraması | ✅ her push'ta (`Secret Scan` işi) |
| İmza anahtarı yoksa | ✅ release **kırılır** (eskiden debug anahtarıyla yayınlıyordu) |
| HTTPS pinning | ✅ `NetworkSecurityConfig` testi var |
| **Komut risk kapısı** | ✅ 37080 — 21 kural + 4 mutlak ret |
| **Yedek şifreleme** | ⚠️ `BackupCrypto` var, otomatik yedek **şifresiz** yazıyor |
| **Uzaktan erişim** | ⛔ 37090 yalnız model, uygulama yok (doğru karar) |

**Eksik:** otomatik yedek açıkken paket şifrelenmiyor. Depolama alanına düz ZIP.

---

## 5. Sağlık (uygulamanın)

| Ne | Durum |
|---|---|
| Çökme yakalama | ✅ `crash/`, `CrashFrequencyDetector` güvenli mod |
| Donma tespiti | ✅ `HangDetector`, `JankDiag`, `StreamJitterProbe` |
| Kaynak izleme | ✅ `ResourceWatermark`, `diagnostics/` |
| Zombi temizliği | ✅ `TeamRunStore.sweepOrphanSpaces`, `unregisterRun` |
| **Pil** | ⚠️ ölçüm yok. Otomatik yedek varsayılan kapalı ama etkisi ölçülmedi |
| **APK boyutu** | 43,4 MB (release, R8). Debug 73,4 MB |

---

## 6. Performans

Bu sürümde ölçülen (gerçek koşu süreleri):

| İş | Önce | Sonra | Kazanç |
|---|---|---|---|
| Compile kapısı | yoktu (5 dk test beklenirdi) | **47 sn** | hata 6× erken |
| Unit tests | 5 dk | **2m30s** | Gradle cache paylaşımı |
| Native | 1m22 | **48 sn** | rootfs + Go cache |
| **Toplam** | ~13 dk | **~7 dk** | ~%45 |

**Neden:** Gradle cache iki işte **farklı anahtardaydı** — birincil anahtarlar
hiç eşleşmiyordu, build her seferinde sıfırdan derliyordu. Tek anahtar + `app/build`
dahil edildi.

---

## 7. Uyumluluk

| Ne | Durum |
|---|---|
| minSdk 26 / targetSdk 35 / compileSdk 36 | ✅ |
| Dil dosyası | 23 dil, `app_name` hepsi "Minis X" |
| **Arayüz metinleri** | ⚠️ yeni `mx_*` metinleri **yalnız TR + EN**. Diğer 21 dil İngilizce görür |
| Room | 🔴 `schemas/…/15.json` **yok** — release öncesi zorunlu |
| Erişilebilirlik | ✅ `contentDescription`'lar var |
| **RTL** | ⚠️ Arapça/Farsça var, yeni ekranlarda RTL testi yok |

---

## 8. Alt iskelet

| Katman | Durum |
|---|---|
| Sandbox (PRoot + Alpine) | ✅ rootfs APK içinde, `libproot*` doğrulandı |
| Kabuk | ✅ `PersistentShell`, `FreshProcessShell`, `GlobalShellThrottle` |
| Dosya sistemi | ✅ `FsSpace`, oturum/proje izolasyonu |
| **JVM** | 🔴 PRoot'ta çalışmıyor (`Failed to mark memory page as executable`) |
| Derleme araçları | ⚠️ `ToolchainManager` var ama Gradle/JDK kurulumu denenmedi |

---

## 9. Üst iskelet

| Katman | Durum |
|---|---|
| Compose UI | ✅ Material 3, tek `AppNavigation` |
| ViewModel yaşam döngüsü | ✅ `ChatViewModelStore`, `holdWhileViewed`, havuz |
| Eşzamanlılık | ✅ `SessionConcurrencyManager` 6 slot + `loanOutSlot` |
| Arka plan | ✅ `ForegroundService`, `AlarmManager` (zamanlanmış görevler) |
| **Rota testi** | 🔴 yok. Yeni 2 rota (`ozel_budget_limits`, `ozel_replay`) elle denendi, otomatik değil |

---

## 10. Geri bildirim

| Ne | Durum |
|---|---|
| Hata gösterimi | ✅ snackbar + `error` akışı |
| Tur özeti | ✅ 37084 yeni — "3 dosya değişti" |
| Bütçe durdurma | ✅ 37081 yeni — nedeniyle birlikte |
| Ders önerisi | ✅ 37086 yeni — tur başına 1 |
| **Sesli geri bildirim** | ⚠️ TTS var, yeni kartlar için kullanılmıyor |
| **Titreşim** | ⚠️ onay kartında yok — cep telefonunda fark edilmez |

---

## 11-13. Sağlayıcılar: ping / hata / takip / tamir

| Ne | Durum |
|---|---|
| Bağlantı testi | ✅ `PROVIDER_TEST` — tek GET + **neden** başarısız |
| Kota izleme | ✅ `quota/` — 429, plan, günlük limit |
| Hata sınıflandırma | ✅ `TlsFailure.Kind`, `LLMError` |
| Yedek sağlayıcı | ✅ `FallbackStrategy`, `FallbackCandidate` |
| **Otomatik tamir** | 🔴 yok. Model 404 dönerse kullanıcı elle düzeltir |
| **Sağlık geçmişi** | ⚠️ `UsageReport` var, "bu sağlayıcı son 7 günde %12 hata verdi" yok |
| **Ping** | ⚠️ test var, **periyodik** değil — yalnız kullanıcı basınca |

**Eksik:** kullanıcı "bu sağlayıcı bozuk mu" sorusunu ancak mesaj gönderip
hata alınca öğreniyor. Sağlayıcı başına sağlık sayacı yok.

---

## Özet: gerçekten eksik olan 9 şey

| # | Eksik | Önem |
|---|---|---|
| 1 | `schemas/15.json` | 🔴 release bloklayıcı |
| 2 | FANOUT gerçekten paralel değil | 🔴 sessiz yanlış vaat |
| 3 | Otomatik yedek şifresiz | 🟡 |
| 4 | Yeni metinler 21 dilde yok | 🟡 |
| 5 | Sağlayıcı sağlık sayacı yok | 🟡 |
| 6 | Statik analiz / kapsam ölçümü yok | 🟡 |
| 7 | Rota testi yok | 🟡 |
| 8 | Onay kartında titreşim yok | 🟢 |
| 9 | JVM sandbox'ta çalışmıyor | 🟢 (bilinen sınır) |
