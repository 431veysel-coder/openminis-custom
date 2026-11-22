package com.openminis.app.sandbox

import java.io.File

/**
 * [37098] Termux katmanı — Termux paket evrenini aynı PRoot kökünde çalıştırma.
 *
 * ## Neden "Termux'u kur" değil, "Termux katmanı"
 *
 * Termux bir uygulamadır: kendi APK'sı, kendi uid'i, kendi paket yöneticisi
 * (`apt` / `pkg`), kendi deposu. Biz onu **kurmayız**; iki şeyi ödünç alırız:
 *
 *  1. **Bootstrap** (Resmî: `termux/termux-packages` release asset'i, ölçüldü:
 *     `bootstrap-aarch64.zip`, 31,6 MB, 3779 dosya, 2026-11-15).
 *  2. **Paket evreni**: `apt` + Termux depoları, `sshd`, `python`, `node`,
 *     `clang`, `ffmpeg`, `openssh`… Alpine'de de var; fark, Termux paketlerinin
 *     Android'e *bionic* üzerinden bağlanması (Termux:API, sensörler, `termux-*`
 *     yardımcıları, `am`/`termux-open` köprüleri).
 *
 * ## Ölçülen kısıt — tasarımın tamamını bu belirliyor
 *
 * Termux ikilileri **bionic** ile derlenmiştir ve ELF yorumlayıcıları
 * `/system/bin/linker64`'tür. Bizim PRoot kökümüz Alpine (musl) ve `-r` ile
 * verilen kök dışında hiçbir şey görünmüyor:
 *
 * ```
 * $ ls /system            -> No such file or directory
 * $ ls /system/bin/linker64 -> No such file or directory
 * ```
 *
 * Yani Termux ikilisi bugünkü yapılandırmada **çalışmaz**; çalıştırmak için
 * `/system` ve `/apex` konuğa bağlanmalı. Bu, uygulamanın bugün yapmadığı bir
 * şey (yalnız `/dev`, `/proc`, `/sys` + oturum/global dizinler bağlı).
 *
 * Termux ikililerinin **sabit öneki** `/data/data/com.termux/files/usr`. Android
 * tarafında bu yol gerçek uygulama verisidir; PRoot kökünde ise gerçek Android
 * `/data` **bağlı değil**, dolayısıyla bu yol kökün içinde sıradan bir dizindir.
 * Bootstrap'ı tam o yola açmak, ikililerin kendi önekini bulmasını sağlar —
 * sembolik bağ hilesi, `proot` yaması veya ikili düzenleme gerekmez.
 *
 * ## Neden "önce ölç, sonra kur"
 *
 * `/system/bin/linker64`'ün varlığı cihazdan cihaza değişir (Android 14+ bazı
 * imajlarda yalnız `/apex/com.android.runtime` altında). Bu yüzden kurulum
 * **koşullu**: [probe] gerçek dosyalara bakar ve gerekçesini yazar. Kapalıyken
 * kullanıcıya "Termux katmanı bu cihazda çalışmıyor: <sebep>" denir — sessizce
 * yarım kurulum yapılmaz. Aynı kural yükleme sonrası da geçerli: [verify]
 * gerçekten `bash --version` çalıştırmadan "kuruldu" demez.
 *
 * ## Neden bu dosya `android.*` import etmiyor
 *
 * Karar mantığı saf: yol listesi, boyut, neden. Böylece `TermuxLayerTest`
 * cihazsız koşar ve "hangi cihazda ne olur" sorusu tahmin değil test olur.
 * Android'e dokunan tek yer `Context.filesDir` → [rootfsDir] parametresi.
 */
object TermuxLayer {

    /** Termux ikililerinin derlenmiş öneki. Değiştirilemez — ikililerin içinde. */
    const val GUEST_PREFIX: String = "/data/data/com.termux/files/usr"

    /** Termux `$HOME` — ikililer bu yolu da sabit bilir. */
    const val GUEST_HOME: String = "/data/data/com.termux/files/home"

    /** Resmî bootstrap asset'i; sürüm sabitlenir, "en son" bir sürüm değildir. */
    const val BOOTSTRAP_NAME: String = "bootstrap-aarch64.zip"
    const val BOOTSTRAP_URL: String =
        "https://github.com/termux/termux-packages/releases/download/" +
            "bootstrap-2026.11.15-r1%2Bapt.android-7/bootstrap-aarch64.zip"

    /** Ölçüldü: 31,6 MB zip → açıldığında ~110 MB, 3779 dosya. */
    const val BOOTSTRAP_ZIP_MB: Int = 32
    const val BOOTSTRAP_UNPACKED_MB: Int = 110

    /**
     * Sabitlenmiş sha256 — GitHub API'nin `digest` alanından alındı.
     *
     * Neden sabit: indirme sırasında GitHub'a "doğru özet ne" diye sormak,
     * doğrulamayı doğrulanacak kaynağa bağlar. Özet burada durur; indirilen
     * dosya bununla karşılaştırılır. Bootstrap sürümü değişirse ikisi birden
     * değişir — bu yüzden URL ve özet aynı yorumda duruyor.
     */
    const val BOOTSTRAP_SHA256: String =
        "388acf91cc247e7062d80cd8aa791fe72f360515fb78dc54ff45742120facae7"
    const val BOOTSTRAP_BYTES: Long = 33_148_806L

    /** `apt update` sonrası liste + ilk kurulumlar için önerilen boş alan. */
    const val RECOMMENDED_FREE_MB: Int = 400

    /**
     * [37098] APK boyut bütçesi.
     *
     * ## Bu sürümün boyuta etkisi — ölçülmüş sınırlar
     *
     * Beyan: **bootstrap APK'ya gömülmez.** Gömülseydi APK +32 MB olurdu ve
     * 43 MB'lık bir paket 75 MB'a çıkardı; telefondaki derleme (aapt2/dex/
     * zipalign) ve CI'daki artefakt aktarımı bundan gerçekten etkilenir.
     * Bu yüzden bootstrap **çalışma anında** indiriliyor: kullanmayan kullanıcı
     * hiçbir bedel ödemiyor, kullanan 31,6 MB'ı bir kez indiriyor.
     *
     * ## Kodun kendisi ne kadar ekler
     *
     * Termux katmanının Kotlin kodu (TermuxLayer + Installer + Prompt + testler)
     * DEX'e giren **~1 500 satır**. DEX sıkıştırılmış hâlde bu, APK'ya
     * **tahmini 30–60 KB** ekler — ölçülecek, iddia edilmeyecek. Aşağıdaki
     * bütçe bu yüzden var: sessiz büyüme, "küçük bir ekleme" diye başlayıp
     * 5 MB'a çıkan onlarca eklemenin toplamıdır.
     */
    const val APK_BUDGET_KB: Int = 120

    /**
     * APK'ya girebilecek **en büyük tek dosya** sınırı.
     *
     * Bootstrap 31,6 MB, `alpine-minirootfs.tar.gz` ~3 MB. Bir gün biri
     * "hazır olsun" diye bootstrap'ı `assets/`e koyarsa bunu
     * `scripts/ozel/apk-butce.py` yakalar — sabiti okuyan gerçek kapı odur
     * (`ozel-guard.yml` + `paket.py`). 37099 öncesi bu yorum yalandı: sabiti
     * hiçbir şey okumuyordu.
     */
    const val MAX_EMBEDDED_ASSET_KB: Int = 4 * 1024

    /** bionic yükleyicinin yaşadığı bilinen konumlar, sırayla. */
    private val LOADER_CANDIDATES = listOf(
        "/system/bin/linker64",
        "/apex/com.android.runtime/bin/linker64",
    )

    /** Bionic çalışma zamanı kitaplıkları (libc, libdl, libm…). */
    private val RUNTIME_DIRS = listOf(
        "/system/lib64",
        "/apex/com.android.runtime/lib64/bionic",
    )

    /**
     * Konuğa bağlanması gereken host dizinleri.
     *
     * `/system` salt-okunur bir bölümdür (dm-verity), bu yüzden `-b` ile
     * yazılabilir bağlanması bir risk taşımaz: yazma denemesi zaten EROFS
     * döner. `/apex` de öyle. **`/data` asla bağlanmaz** — gerçek uygulama
     * verisini konuğa açmak, kendi onay kapımızı kendi elimizle kaldırmak olur.
     */
    fun requiredBinds(): List<Pair<String, String>> = listOf(
        "/system" to "/system",
        "/apex" to "/apex",
    )

    /** Probe sonucu: kurulabilir mi, neden değil, hangi yükleyici bulundu. */
    data class Probe(
        val canInstall: Boolean,
        val loader: String?,
        val runtimeDir: String?,
        val problems: List<String>,
        val warnings: List<String>,
    ) {
        /** Kullanıcıya tek satır: neden çalışmıyor. */
        fun summary(): String = when {
            canInstall && warnings.isEmpty() -> "Termux katmanı bu cihazda kullanılabilir"
            canInstall -> "Kullanılabilir; " + warnings.joinToString("; ")
            else -> "Kullanılamaz: " + problems.joinToString("; ")
        }
    }

    /**
     * Saf ön kontrol. [exists] enjekte edilebilir: testte sahte dosya sistemi.
     */
    fun probe(exists: (String) -> Boolean = { File(it).exists() }): Probe {
        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val loader = LOADER_CANDIDATES.firstOrNull { exists(it) }
        if (loader == null) {
            problems += "bionic yükleyici bulunamadı (" +
                LOADER_CANDIDATES.joinToString(" veya ") + ")"
        }
        val runtime = RUNTIME_DIRS.firstOrNull { exists(it) }
        if (runtime == null) {
            problems += "bionic çalışma zamanı kitaplıkları yok (" +
                RUNTIME_DIRS.joinToString(" veya ") + ")"
        }
        if (loader != null && runtime != null && !exists("/system")) {
            // Yükleyici /apex'te olabilir; /system yoksa bağlama da anlamsız.
            problems += "/system konuğa bağlanamıyor (bölüm görünmüyor)"
        }
        return Probe(
            canInstall = problems.isEmpty(),
            loader = loader,
            runtimeDir = runtime,
            problems = problems,
            warnings = warnings,
        )
    }

    /** Diskte yer var mı — kurulum öncesi son kapı. */
    fun spaceProblem(freeBytes: Long, isInstalled: Boolean): String? {
        if (isInstalled) return null
        val needed = RECOMMENDED_FREE_MB.toLong() * 1024 * 1024
        if (freeBytes >= needed) return null
        val haveMb = freeBytes / (1024 * 1024)
        return "yer yetersiz: $haveMb MB var, en az $RECOMMENDED_FREE_MB MB önerilir"
    }

    /** Konuk yolları — kökün içinde nerede durur. */
    fun guestPrefixDir(rootfsDir: File): File = File(rootfsDir, GUEST_PREFIX.trimStart('/'))
    fun guestHomeDir(rootfsDir: File): File = File(rootfsDir, GUEST_HOME.trimStart('/'))

    /**
     * [37098] Kök dosya sisteminin kurulum dizini adı — **tek kaynak**.
     *
     * Depoda bu ad 8 yerde elle yazılmıştı (`"alpine-rootfs"` string olarak).
     * Ad değişirse (ör. sürümlü kök `/alpine-rootfs-3.22`'ye geçilirse) hepsi
     * sessizce bozulur: bazıları eski dizine bakar, bazıları yenisine.
     * `RootfsManager.rootfsDir` zaten var; Android bağlantılı dosyalar bu
     * sabiti onunla hizalar. `RootfsManager`'ın kendisi tanımı elinde tutar,
     * buradaki sabit yalnız diğer kullanıcılara çıkarılmış addır.
     */
    const val ROOTFS_DIR_NAME: String = "alpine-rootfs"

    /**
     * Bootstrap zip girişinin **açılacağı** yer.
     *
     * Zip girişleri öneğe göre göreli (`bin/bash`, `etc/apt/sources.list`) —
     * ölçüldü: üst düzey dizinler `bin`, `etc`, `include`, `lib`, `libexec`,
     * `share`, `tmp`, `var`. Köke açmak Alpine'in `/bin`'ini ve `/etc`'sini
     * EZER; kök kullanılamaz hâle gelir.
     *
     * Bu fonksiyonun varlık sebebi o hatanın tekrar edilememesi:
     * `TermuxInstaller` buradan geçmek zorunda, `TermuxLayerTest` de bu
     * sözleşmeyi çivliyor. Yol hesabı tek yerde.
     */
    fun extractTarget(rootfsDir: File, entryName: String): File =
        File(guestPrefixDir(rootfsDir), entryName.trimStart('/'))

    /** Katman kurulu mu? Bootstrap'ın son adımı `apt` ikilisini bırakır. */
    fun isInstalled(rootfsDir: File): Boolean =
        File(guestPrefixDir(rootfsDir), "bin/apt").exists() &&
            File(guestPrefixDir(rootfsDir), "etc/apt/sources.list").exists()

    /**
     * Açma adımının tamamlandığını gösteren işaret dosyası (önek içinde).
     *
     * Neden gerekli: `install()` "tekrar başlatılabilir" diyor ama açma adımı
     * her denemede 3 779 dosyayı yeniden yazıyordu. Yarıda kalan bir kurulum
     * sonraki denemede baştan açıyordu — 110 MB yazma, telefonda dakikalar.
     * İşaret yalnız **son girişten sonra** yazılır, yani yarım açma "tamam"
     * sayılmaz.
     */
    const val EXTRACT_MARKER: String = ".minis-extract-complete"

    /** Açma tamamlanmış mı — işaret dosyası öneğin kökünde. */
    fun isExtracted(rootfsDir: File): Boolean =
        File(guestPrefixDir(rootfsDir), EXTRACT_MARKER).exists()

    /**
     * `SYMLINKS.txt` satırını çözer: `hedef←bağ yolu`.
     *
     * ## Neden bu fonksiyon var (ve neden sırası önemli)
     *
     * Biçim Termux'un kendi üreticisinde sabit:
     * `echo "$(readlink "$link")←${link}"` (termux-packages,
     * `create_bootstrap_archive`). Yani **solda hedef, sağda bağ yolu**:
     * `libncursesw.so.6.5←./lib/libncursesw.so.6`.
     *
     * İlk yazımda ikisi tersti: kod `parts[0]`'ı bağ adı sanıp `parts[1]`'i
     * hedef yaptı. Sonuç sessizdi — "symlinks: N kuruldu" diyordu, ama `bash`
     * `libncursesw` bulamıyordu, çünkü bağlar yanlış adlarla ve yanlış hedefe
     * kurulmuştu. Bu yüzden çözümleme saf bir fonksiyon: testi cihazsız koşar.
     *
     * Hedef **olduğu gibi** döner: `readlink` göreli döner ve göreli hedef
     * bağın kendi dizinine göre çözülür — Termux'un kendi kurucusu da aynı
     * şeyi yapar (`Os.symlink(parts[0], parts[1])`).
     *
     * @return `hedef to bağ yolu`; boş/açıklama/bozuk satırda `null`.
     */
    fun parseSymlinkLine(line: String): Pair<String, String>? {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) return null
        val parts = t.split('←')
        if (parts.size != 2) return null
        val target = parts[0].trim()
        val link = parts[1].trim().removePrefix("./")
        if (target.isEmpty() || link.isEmpty()) return null
        // Önek dışına taşan bağ yolu kurulmaz: `../../` ile kökün dışına bağ
        // kurmak, ayrıcalığı olmayan bir yazma yolu açar.
        if (link.startsWith("/") || link.split('/').any { it == ".." }) return null
        return target to link
    }

    /**
     * Kabuk için ortam. `PATH` Termux önekini **öne** alır; Alpine araçları
     * erişilebilir kalır (ikinci katman).
     *
     * `LD_LIBRARY_PATH` yalnız Termux `lib` dizinini içerir: bionic ikilileri
     * musl `lib`'ini görmemeli (karışık yükleme sessiz çökme üretir).
     */
    /**
     * Konuk bionic ikililerinin kitaplık arama yolu — proot'un ana süreç
     * `LD_LIBRARY_PATH`'iyle **birleştirilerek** kullanılır (bkz.
     * `TermuxInstaller.runInGuest`: iki yol da geçerli, tek biri seçilmez).
     */
    const val LD_LIBRARY_PATH_GUEST: String = "$GUEST_PREFIX/lib"

    fun environment(rootfsDir: File, homeOverride: String? = null): Map<String, String> {
        val p = GUEST_PREFIX
        return mapOf(
            "PREFIX" to p,
            "TERMUX_PREFIX" to p,
            "TERMUX_VERSION" to "0.118.3",
            "TERMUX_APP_PID" to "0",
            "TERMUX_MAIN_PACKAGE_FORMAT" to "debian",
            "HOME" to (homeOverride ?: GUEST_HOME),
            "PATH" to "$p/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LD_LIBRARY_PATH" to "$p/lib",
            "TMPDIR" to "$p/tmp",
            "SHELL" to "$p/bin/bash",
            "LANG" to "en_US.UTF-8",
        )
    }

    /**
     * Bu komut Termux katmanını mı gerektiriyor?
     *
     * Kural: `apt`, `pkg`, `dpkg` ve `$PREFIX` altındaki araçlar Termux'a ait;
     * `apk` ve `/usr/local` araçları Alpine'e. Karışık çağrı (ikisi birden)
     * reddedilmez — kullanıcı bilinçli olabilir — ama [layerFor] raporlar.
     */
    fun layerFor(command: String): Layer {
        val t = command.trim()
        val termuxish = Regex("""(^|[\s;&|])(pkg|apt|apt-get|dpkg|termux-[a-z-]+)\s""")
            .containsMatchIn(t) || t.contains("$GUEST_PREFIX/bin/")
        val alpineish = Regex("""(^|[\s;&|])apk\s""").containsMatchIn(t)
        return when {
            termuxish && alpineish -> Layer.MIXED
            termuxish -> Layer.TERMUX
            alpineish -> Layer.ALPINE
            else -> Layer.EITHER
        }
    }

    enum class Layer { ALPINE, TERMUX, MIXED, EITHER }

    /**
     * Kurulum adımları — sırayla, her biri tekrar başlatılabilir.
     *
     * "Tekrar başlatılabilir" önemli: 31 MB indirme telefonda kopabilir.
     * Bir adım tamamlandıysa yeniden çalıştırıldığında atlanır; yarım kalırsa
     * baştan başlamaz.
     */
    fun installPlan(): List<Step> = listOf(
        Step("probe", "Cihaz uygunluğunu ölç", optional = false),
        Step("space", "Boş alanı denetle (≥ $RECOMMENDED_FREE_MB MB)", optional = false),
        Step("download", "Bootstrap indir ($BOOTSTRAP_ZIP_MB MB, resmî release)", optional = false),
        Step("verify", "sha256 doğrula (GitHub'ın bildirdiği özet)", optional = false),
        Step("extract", "Aç ($GUEST_PREFIX)", optional = false),
        Step("symlinks", "SYMLINKS.txt uygula", optional = false),
        Step("home", "$GUEST_HOME hazırla, oturum dizinlerine bağla", optional = false),
        Step("sources", "apt kaynaklarını sabitle (resmî mirror)", optional = false),
        Step("apt-update", "apt update (ilk liste)", optional = true),
        Step("smoke", "bash --version + apt --version çalıştır", optional = false),
    )

    data class Step(val id: String, val title: String, val optional: Boolean)

    /**
     * `apt` kaynaklarını **resmî mirror**'a sabitler.
     *
     * Neden: bootstrap `sources.list` içinde `stable`/`main` yazar; bir gün
     * kullanıcı ya da ajan bunu değiştirirse tüm paketler üçüncü taraftan
     * gelir. Ajanın kendi paket kaynağını değiştirmesi, onay kapısının
     * engellemesi gereken işlerden: `termux:repo` kuralı bunu kart'a bağlar.
     */
    fun defaultSources(): String = buildString {
        appendLine("# [37098] Minis X — Termux katmanı")
        appendLine("# Kaynak değişikliği ajan tarafından yapılırsa onay kartı çıkar.")
        appendLine("deb https://packages.termux.dev/apt/termux-main stable main")
    }

    /** Kurulum sonrası doğrulama: gerçek çıktı, iddia değil. */
    fun smokeCommands(): List<String> = listOf(
        "$GUEST_PREFIX/bin/bash --version",
        "$GUEST_PREFIX/bin/apt --version",
    )
}
