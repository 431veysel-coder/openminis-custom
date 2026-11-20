# 37094 — yayın hattı denetimi (push öncesi)

> Kapsam: `.github/workflows/apk.yml` + çevresindeki 6 akış.
> Yöntem: ölçüm (rg/actionlint/preflight/bash -n), tahmin değil.
> Durum: **push edilmedi.** Yerel commit hazır.

---

## 0. Önce en önemli düzeltme: kendi değişikliğimin tuzağı

`pipefail` eklemeden önce dosyada 10 `| head` ve 8 `| grep -q` vardı.
`pipefail` ile bunların bir kısmı **geçen kontrolü kırmızıya çevirir**.

Dürüst nüans: SIGPIPE yalnız üretici tüketiciden **sonra** yazmaya devam ederse
oluşur. Küçük çıktıda (birkaç satır) üretici zaten bitmiştir → sorun çıkmaz.
Bu yüzden "hepsi patlar" demek yanlış olur. Ama şu ikisi gerçek:

| Yer | Neden gerçek risk |
|---|---|
| `unzip -l "$APK" \| grep -q lib/arm64-v8a/...` | 43 MB APK'da binlerce satır; `grep -q` ilk eşleşmede çıkar, `unzip` yazmaya devam eder → **141** → adım kırmızı |
| `gradlew ... \| tee` (benim eklediğim) | `tee` erken çıkmaz, ama **pipefail olmadan** `tee`'nin 0'ı gradle'ın hatasını yutar → iş yeşil görünür |

İkisi de kapatıldı. Kural artık makinede: `preflight.py` → `L0 ci-shell`.

## 1. Bulunan ve düzeltilen gerçek hatalar

| # | Yer | Ne | Sonuç (düzeltilmeden) | Düzeltme |
|---|---|---|---|---|
| 1 | apk.yml APK doğrulama | `unzip -l \| grep -q` ×4 | pipefail altında yayın turunda kırmızı | tek `unzip -l` + bash `case` |
| 2 | apk.yml kabuk | `bash -eo pipefail` (belgelenmemiş bayraklar) | profil kaynaklanabilir, ölçülemez davranış | `bash --noprofile --norc -eo pipefail` (dokümanla birebir) |
| 3 | apk.yml sürüm kapısı | `gh release view ... \|\| true` | **fail-open**: 401/403 "sürüm yok" sayılıyordu → yakılmış numara sessizce tekrar yayınlanabilir | 404 ile diğer hata ayrıldı, diğerinde **red** |
| 4 | apk.yml yayın kapısı | aynı fail-open | aynı | aynı |
| 5 | apk.yml yayın kapısı | `CODE` boşsa `[ "" -le N ]` | anlaşılmaz çıkış | erken `::error` |
| 6 | rollback.yml | `set -euo pipefail` + `git tag \| head -50` | 50+ etikette geri alma kırılır (bugün 13) | `sed -n '1,50p'` |
| 7 | rollback.yml | 4 × `\| head -1` | aynı sınıf | `sed -n '1p'` |
| 8 | apk.yml | build-tools yolu `36.0.0` sabit | SDK güncellenirse sessiz "aapt2 yok" | yol yoksa en yenisini seç + `[ -x ]` kontrolü |

## 2. Kendi eklediğim kontrolün açtığı yeni yüzey (ve kapatılışı)

"Her şeyi logla + artifact yap" iki yeni sorun doğuruyordu:

1. **Sır sızıntısı.** GitHub sırları yalnız **canlı log akışında** maskeler.
   `tee` ile diske yazılan kopya maskelenmez, artifact indirildiğinde ham
   değer oradadır. → `scripts/scrub_logs.py` her yüklemeden önce çalışır:
   literal sırlar (imza parolası, keystore base64) + biçim desenleri
   (`ghp_`, PEM, AWS, uzun base64). **sha256 özetleri bilerek korunur**
   (APK doğrulaması onlara bakıyor) — desen en az bir büyük harf/`+`/`/` ister,
   hex özet uymaz. Yerelde test edildi: hex kaldı, token ve base64 gitti.
2. **İzin genişliği.** Akış düzeyinde `contents: write` **altı işin hepsine**
   yazma jetonu veriyordu; dördü yalnız kod okuyor. → akış `contents: read`,
   yazma yalnız `build` işinde.

## 3. Ölçülen boyutlar

| Boyut | Bulgu | Karar |
|---|---|---|
| **Tamir** | 8 hata (yukarıda) | düzeltildi |
| **Denge** | rapor ömrü 14 gün (audit/slice) ↔ log 90 gün | raporlar 90'a çıkarıldı; `native-deps` 7 (ara ürün, doğru), APK 30 (asıl kalıcı kopya release) |
| **Ömür** | Gradle cache anahtarı 3 işte **aynı** (eski bug gitmiş), `restore-keys` eski adı da kapsıyor | dokunulmadı, doğru |
| **Ömür** | `hashFiles('src/android/**/*.toml')` boş küme (depoda .toml yok) | zararsız; sürümler `*.gradle.kts` içinde, o desen zaten var |
| **Sağlık** | 4 iş × `timeout-minutes`, hepsi makul (5/20/40/30/60) | dokunulmadı |
| **Sağlık** | koşu özeti yoktu; 20 000 satır log açmadan sürüm/boyut/hash görünmüyordu | `Run summary` adımı (`$GITHUB_STEP_SUMMARY`) |
| **İlişki** | artifact adları ↔ indiren iş eşleşiyor (`native-deps`, `run-logs-*`) | doğru |
| **İlişki** | `concurrency.cancel-in-progress: true` yayın sırasında iptal edebilir | **bırakıldı**: iptal yayın öncesi olursa numara yanmaz, yayın sonrası olursa zararsız. Tek risk `release` oluşturulup asset yüklenmeden iptal → yeni kapı bunu "asset yok, yayınla" diye ele alıyor |
| **Bağımlılık** | 9 action; 8'i hareketli major etiket (`@v4`), 1'i üçüncü taraf | `softprops/action-gh-release` **SHA'ya sabitlendi** (`3bb1273…`, v2): tek üçüncü taraf ve `contents: write` alan tek adım. GitHub'a ait 8'i major etikette bırakıldı (gerekçe: kendi ürünleri, SHA sabitleme bakım yükü; istenirse yapılır) |
| **Kütüphane** | JDK 17 temurin, NDK 27 (build) / 28 (native), CMake 3.22.1, Go 1.25, Gradle 8.11.1 | pinli, çakışma yok |

## 4. Doğrulama (hepsi gerçekten koştu)

| Kontrol | Sonuç |
|---|---|
| `python3 scripts/preflight.py --selftest` | 9 vaka geçti (yeni `ci-shell` 5 vakası dahil) |
| `python3 scripts/preflight.py` | **temiz** (10–12 s) |
| `python3 scripts/check_workflow_shell.py` | 30 `run:` bloğu `bash -n` → 0 sorun |
| `actionlint` (6 akış) | 0 error / 0 warning (yalnız 14 shellcheck-info, hepsi eski SC2012/SC2086) |
| YAML parse (6 akış) | OK |
| kapı mantığı yerel testi | 404 → izin, 401 → ret |
| `scrub_logs.py` yerel testi | hex korundu, token/base64/parola gitti |
| CI'da koştu mu | **HAYIR** — henüz hiçbir iş koşmadı |

## 5. Bilerek yapılmayanlar

1. **GitHub'a ait 8 action'ı SHA'ya sabitlemek.** Yapılabilir; 3. maddedeki
   gerekçeyle ertelendi. Karar senin.
2. **`build_digest.py` (diğer oturumun dosyası).** Ben yazmadım, commit etmiyorum.
   Akışa bağlanmadı; bağlanırsa "son 15 koşunun hataları" repoya gömülür.
3. **`cancel-in-progress` değişikliği.** Yukarıdaki gerekçeyle gereksiz.
4. **`concurrency` grubuna `build_type` eklemek.** Debug dispatch ile release
   push aynı grupta; biri diğerini iptal edebilir. Gerçek ama nadir; ölçüm
   olmadan değiştirmek istemedim.

## 6. Sıradaki (benim önerim)

1. **`workflow_dispatch` + `build_type=debug`** ile bir koşu: 37092 yanmaz,
   yayın yapılmaz, `run-logs-*` artifact'ları ve `Run summary` görünür.
   "Çalışıyor" demenin tek dürüst yolu bu.
2. Koşu yeşilse `ozel-11.5` dalına push.
