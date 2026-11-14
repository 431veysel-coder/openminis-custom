# Katkı Rehberi

> **Not:** Upstream'in `CONTRIBUTING.md` dosyası "pull request kabul etmiyoruz"
> diyor — çünkü orası özel bir ağaçtan yayınlanan bir **ayna**. Bu depo öyle
> değil: burası gerçek bir çatal, bakımcısı var ve PR kabul ediyor. Bu yüzden
> bu dosya upstream'inkiyle aynı metni taşımıyor.

Bu depo [OpenMinis](https://github.com/OpenMinis/OpenMinis)'in kişisel bir
çatalı. Katkı akışı upstream'den farklı: burada **kendi ihtiyacımıza göre
büyütme** önceliği var, o yüzden büyük değişiklikler önce konuşulmalı.

Amaç ve ilkeler: [FORK.md](FORK.md) §1. Başlamadan önce onu oku — özellikle
**değişmez ilkeler** kısmını.

---

## 1. Ne kabul edilir?

| Kabul | Kabul değil |
|---|---|
| Somut bir ihtiyacı kapatan özellik | "Daha güzel olsun" yeniden tasarımları |
| Hata düzeltmesi (yeniden üretme adımlarıyla) | Upstream'de zaten çözülmüş sorunların yeniden çözümü |
| Belge düzeltmesi, çeviri | Kullanılmayan kod, ileride lazım olur dosyaları |
| Performans iyileştirmesi (ölçümle) | Ölçümsüz "optimizasyon" |
| Erişilebilirlik düzeltmesi | Yeni bağımlılık (gerekçesiz) |

**Önce issue aç.** Küçük bir yazım düzeltmesi dışında her şey için. Bir şey
üzerinde çalışmaya başlamadan önce yaklaşımın doğru mu, öğren.

---

## 2. Geliştirme ortamı

Yerelde Android SDK kurmak **zorunlu değil** — CI derliyor. Ama hızlı döngü
istiyorsan:

```sh
git clone https://github.com/431veysel-coder/openminis-custom.git
cd openminis-custom/src/android

# JDK 17 gerekli
./gradlew :app:assembleGithubDebug
```

İmzalama yapılandırması: `keystore.properties` yoksa **debug anahtarına düşer**.
Yani derleme asla anahtar yüzünden durmaz.

> **Not:** Repo `src/android/` altında. Kökte `gradlew` yok — `cd src/android`
> yapmadan Gradle komutu çalışmaz.

---

## 3. Kod kuralları

### 3.1 Çatal işaretlemesi

Bu çatala özgü **her yeni dosya** ve **her değiştirilmiş upstream dosyası**
işaretlenir:

```kotlin
/**
 * [MINIS-OZEL] One line saying what this is.
 *
 * Why it exists — what breaks or is missing without it. This is the part
 * that matters: a future merge needs to know whether the change is still
 * needed, and a reader needs to know why the code is shaped this way.
 */
```

Inline değişikliklerde:

```kotlin
// [MINIS-OZEL] What and why, in one sentence.
```

**İşaretsiz çatal kodu kabul edilmez.** Sebep: upstream merge sırasında neyin
bize ait olduğu bilinmezse hiçbir şey güvenle taşınamaz.

### 3.2 Yorumlar neden'i anlatır

Ne yaptığını kod zaten söylüyor. Yorum, **neden** öyle olduğunu söyler —
özellikle bir şeyi *yapmama* kararını:

```kotlin
// Good: explains a non-obvious decision
// Not a toggle: it reports STATE_INACTIVE always, since any on/off state
// would go stale as soon as the user navigates.

// Bad: restates the code
// Set the state to inactive.
```

### 3.3 Upstream'e dokunurken

- **En küçük diff.** Bir satır yetiyorsa bir satır yaz.
- **Davranışı değiştirme**, yalnız gerekli olanı değiştir.
- **Yeni dosya açmayı tercih et.** Upstream dosyasını değiştirmek merge'de
  çakışma demek; yeni dosya çakışmaz.

### 3.4 Metinler iki dilde

Kullanıcıya görünen her metin **hem** `values/strings.xml` **hem**
`values-tr/strings.xml` içinde olmalı. Diğer 21 dil upstream'den devralınır;
eksik çeviri İngilizceye düşer, kırılmaz.

Hardcoded string **yasak** — `stringResource()` kullan.

> Türkçe metinlerde kesme işareti: XML'de `\'` yaz, yoksa resource
> birleştirme patlar. Bir kez oldu.

### 3.5 Kimlik sabitleri

`scripts/check_identity.sh` her derlemede çalışır. Bir sabiti değiştirirsen
hangisinin `applicationId`, hangisinin `namespace` olması gerektiğini
[FORK.md §5](FORK.md) tablosundan doğrula.

> `AppIconRepository.PACKAGE_NAME` **namespace** olmalı, `applicationId`
> değil. Yanlış yapılırsa derleme **geçer**, uygulama açılır, simge değiştirme
> çalışma anında patlar.

### 3.6 İzinler

Yeni bir Android izni veya yeni bir çalışma zamanı izni gerekiyorsa, o izni
**isteme akışı da** o özelliğin parçasıdır. Kullanıcı izni vermeyi bilmiyorsa
özellik çalışmıyor demektir. Örnek: `diagnostics/BackgroundGates.kt`.

---

## 4. Commit'ler

### Biçim

```
<tip>(<kapsam>): <özet, 72 karakteri geçmesin>

Gövde: neden bu değişiklik gerekti. Ne bozuktu, neyi düzelttin,
neyi bilinçli olarak yapmadın.

Kapanış: kısa sürüm notu.
```

Tipler: `feat`, `fix`, `docs`, `ci`, `build`, `refactor`, `perf`, `test`,
`chore`.

### Kural: tek commit, tek konu

Upstream güncellemesi alırken commit'ler **tek tek cherry-pick** edilir. Bu
yüzden:

- Bir commit iki özellik içermez.
- Belge ve kod ayrı commit'lerde olabilir (ama olmak zorunda değil).
- CI düzeltmesi özellikle ayrı — bağımsız olarak geri alınabilmeli.

### Sürümü elle artırma

`versionCode` / `versionName` yalnızca **yayın commit'inde** artırılır. Bir
özellik eklediğinde dokunma; sürüm [FORK.md §6](FORK.md) şemasına göre
verilir.

---

## 5. Pull Request

1. **Issue'ya bağla** — "Fixes #12".
2. **Ne yaptığını yaz**, ne yapmadığını da.
3. **Nasıl test ettiğini yaz.** CI geçmesi yeterli değil; elle ne denedin?
4. **Ekran görüntüsü** — arayüze dokunan her değişiklikte.
5. **CI yeşil olmalı.** Kırmızı PR incelenmez.

PR şablonu doldurulduğunda bunları zaten sorar.

---

## 6. Yayın akışı (bakımcı için)

```sh
# 1. Sürümü artır (FORK.md §6)
#    versionCode = 37007 → 37008
#    versionName = "1.15-ozel.8"

# 2. FORK.md §10'a changelog satırı ekle

# 3. Commit + tag + push
git commit -am "chore: 1.15-ozel.8 (build 37008)"
git tag -a v1.15-ozel.8 -m "Minis Özel 1.15-ozel.8"
git push origin main --tags
```

`v*` etiketi CI'yı tetikler; APK Release'e yüklenir. Uygulama içi güncelleyici
o Release'i okur.

**Tag'i atlamayın.** `main` push'u APK üretir ama Release oluşturmaz — yani
uygulama içinden görünmez.

---

## 7. Kod incelemesi — neye bakılır

Bir PR'ı incelerken sırayla:

1. **İşaretlenmiş mi?** `[MINIS-OZEL]` var mı?
2. **Diff en küçük mü?** Upstream dosyasında gereksiz değişiklik var mı?
3. **İki dilde mi?** TR + EN string var mı?
4. **Kimlik sabitleri doğru mu?** `check_identity.sh` geçiyor mu?
5. **Neden yorumu var mı?** Özellikle "yapmama" kararlarında.
6. **İzin akışı tam mı?** Yeni izin varsa isteme yolu var mı?
7. **Merge edilebilir mi?** Yeni dosya ağırlıklı mı?

---

## 8. Lisans

Katkı göndererek çalışmanın **GPL-3.0** altında yayınlanmasına izin verirsin —
upstream ile aynı lisans. Bu depo public kalır; GPL uyumu için bu şart.
