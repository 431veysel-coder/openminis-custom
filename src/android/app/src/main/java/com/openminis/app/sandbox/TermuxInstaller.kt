package com.openminis.app.sandbox

import android.content.Context
import android.system.Os
import android.util.Log
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * [37098] Termux katmanını **kuran** nesne — indirme, doğrulama, açma, bağlar.
 *
 * ## Neden `TermuxLayer`'dan ayrı
 *
 * `TermuxLayer` saf karar mantığı ve `android.*` import etmiyor, bu yüzden
 * `TermuxLayerTest` cihazsız koşuyor. Kurulum ise ağ, disk, `Os.symlink` ve
 * ilerleme demek. İkisini aynı dosyaya koymak, test edilebilir çekirdeği
 * Android'e bağlar — bu depoda `PolicyGate`/`ApprovalInbox` ayrımıyla aynı
 * gerekçe.
 *
 * ## Neden adımlar tekrar başlatılabilir
 *
 * 31,6 MB indirme telefonda kopar: ekran kapanır, hücresel veri biter, kullanıcı
 * uygulamadan çıkar. İndirme dosyası hedef boyuta ulaşmışsa yeniden
 * indirilmez; **doğrulama geçmişse** açma tekrar denenmez. Her adım kendi
 * ön koşulunu kontrol eder, "neredeyse bitti" durumundan devam eder.
 *
 * ## Neden sha256 sabit, indirilen özet değil
 *
 * GitHub'a "doğru özet ne" diye sormak, doğrulamayı doğrulanacak kaynağa
 * bağlar. Özet `TermuxLayer.BOOTSTRAP_SHA256`'da duruyor.
 *
 * ## Neden `/data` yazılmıyor
 *
 * Konuk öneki `/data/data/com.termux/files/usr` **kökün içinde** bir yoldur
 * (`rootfsDir/data/data/com.termux/...`). Gerçek Android `/data` asla
 * hedeflenmez — `rootfsDir` dışına çıkan her yol reddedilir.
 */
object TermuxInstaller {

    private const val TAG = "TermuxInstaller"

    /** Adım kimliği + ilerleme. Arayüz bunu çizer, kurulum bunu yayar. */
    data class Progress(
        val stepId: String,
        val title: String,
        val done: Boolean = false,
        val bytesDone: Long = 0,
        val bytesTotal: Long = 0,
        val message: String = "",
        val error: String? = null,
    )

    data class Result(val ok: Boolean, val message: String, val steps: List<Progress>)

    private fun rootfs(ctx: Context) = File(ctx.filesDir, TermuxLayer.ROOTFS_DIR_NAME)

    /** Geçici indirme — kökün İÇİNDE değil, uygulamanın cache'inde. */
    private fun downloadFile(ctx: Context) = File(ctx.cacheDir, "termux-bootstrap-aarch64.zip")

    /**
     * Kurulum. [onProgress] her adımda çağrılır.
     *
     * `suspend` + `Dispatchers.IO`: ağ ve disk işi ana iş parçacığında olmaz.
     * İlerleme geri çağrısı da IO bağlamında çalışır; arayüz kendi
     * `withContext(Main)`'ini yapar (Compose `mutableStateOf` zaten thread-safe
     * değil, o yüzden sınır arayüzde).
     */
    suspend fun install(
        ctx: Context,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val steps = mutableListOf<Progress>()
        fun emit(p: Progress) {
            steps += p
            onProgress(p)
        }
        val plan = TermuxLayer.installPlan()
        fun title(id: String) = plan.firstOrNull { it.id == id }?.title ?: id

        // 1) probe
        val probe = TermuxLayer.probe()
        emit(Progress("probe", title("probe"), done = probe.canInstall, message = probe.summary()))
        if (!probe.canInstall) {
            return@withContext Result(false, probe.summary(), steps)
        }

        // 2) space
        val root = rootfs(ctx)
        if (!root.exists()) {
            emit(
                Progress(
                    "space", title("space"), done = false,
                    error = "Alpine kök dosya sistemi yok; uygulama ilk kez başlatılmalı",
                ),
            )
            return@withContext Result(false, "kök dosya sistemi yok", steps)
        }
        val free = root.usableSpace
        val spaceProblem = TermuxLayer.spaceProblem(free, isInstalled = TermuxLayer.isInstalled(root))
        if (spaceProblem != null) {
            emit(Progress("space", title("space"), done = false, error = spaceProblem))
            return@withContext Result(false, spaceProblem, steps)
        }
        emit(Progress("space", title("space"), done = true, message = "${free / 1048576} MB boş"))

        // 3) download
        val zip = downloadFile(ctx)
        val needDownload = !zip.exists() || zip.length() != TermuxLayer.BOOTSTRAP_BYTES
        if (needDownload) {
            emit(Progress("download", title("download"), bytesTotal = TermuxLayer.BOOTSTRAP_BYTES))
            val err = download(ctx, zip) { done, total ->
                emit(
                    Progress(
                        "download", title("download"),
                        bytesDone = done, bytesTotal = total,
                    ),
                )
            }
            if (err != null) {
                emit(Progress("download", title("download"), error = err))
                return@withContext Result(false, err, steps)
            }
        }
        emit(Progress("download", title("download"), done = true, bytesTotal = TermuxLayer.BOOTSTRAP_BYTES))

        // 4) verify — sabit özet
        val actual = sha256(zip)
        val ok = actual.equals(TermuxLayer.BOOTSTRAP_SHA256, ignoreCase = true)
        emit(
            Progress(
                "verify", title("verify"), done = ok,
                message = if (ok) "sha256 eşleşti" else "sha256 uyuşmadı",
                error = if (ok) null else "özet uyuşmadı: beklenen ${TermuxLayer.BOOTSTRAP_SHA256.take(12)}…, " +
                    "bulunan ${actual.take(12)}… — dosya silindi, yeniden deneyin",
            ),
        )
        if (!ok) {
            // Bozuk dosya kalsaydı sonraki deneme onu "indirilmiş" sayardı.
            zip.delete()
            return@withContext Result(false, "bootstrap doğrulanamadı", steps)
        }

        // 5) extract
        //
        // ⚠️ Bootstrap zip'i ÖNEKE GÖRE göreli: `bin/`, `etc/`, `lib/`,
        // `var/`… Yani köke açılırsa Alpine'in `/bin`, `/etc`, `/lib`
        // dizinlerinin ÜZERİNE yazar ve kök kullanılamaz hâle gelir. İlk
        // yazımda tam olarak bu hatayı yaptım (`unzip(zip, root)`); doğrusu
        // önek dizinine açmak. Zip içeriği ölçüldü: SYMLINKS.txt, bin, etc,
        // include, lib, libexec, share, tmp, var — hiçbiri `data/` ile
        // başlamıyor.
        val prefixDir = TermuxLayer.guestPrefixDir(root)
        val extractErr = runCatching {
            prefixDir.mkdirs()
            if (TermuxLayer.isExtracted(root)) {
                // Önceki denemede açma bitti: 3 779 dosyayı yeniden yazmak
                // yalnız dakika harcar. İşaret yarım açmada yazılmadığı için
                // bu kontrol "yarıda kalmışı atla" demek değil.
                Log.i(TAG, "extract skipped: marker present")
            } else {
                unzip(zip, root)
                File(prefixDir, TermuxLayer.EXTRACT_MARKER).writeText("1\n")
            }
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        emit(
            Progress(
                "extract", title("extract"), done = extractErr == null,
                message = TermuxLayer.GUEST_PREFIX, error = extractErr,
            ),
        )
        if (extractErr != null) return@withContext Result(false, extractErr, steps)

        // 6) symlinks — zip symlink taşımıyor, SYMLINKS.txt taşıyor
        val linkErr = runCatching { applySymlinks(root) }.exceptionOrNull()
            ?.let { it.message ?: it.javaClass.simpleName }
        emit(
            Progress(
                "symlinks", title("symlinks"), done = linkErr == null,
                error = linkErr,
            ),
        )
        // Symlink hatası ölümcül değil: bazı imajlarda Os.symlink kısıtlıdır ve
        // o zaman bash çalışmaz → smoke adımı yakalar. Yalan söylememek için
        // "kuruldu" demeyiz, ama devam ederiz.

        // 7) home
        val home = TermuxLayer.guestHomeDir(root)
        home.mkdirs()
        emit(Progress("home", title("home"), done = true, message = TermuxLayer.GUEST_HOME))

        // 7b) [37099n] apt kendi dizinlerini bizim katmanda kurmuyor.
        // Cihazda ölçüldü: `apt install` "Archives directory … is missing" ile
        // düşüyordu; dizinleri açınca geçti.
        val aptDirsMissing = TermuxLayer.ensureAptDirs(root)
        // [37120] Aynı kurulumda resolver: konuk kökünde DNS yoksa yaz.
        val dnsPlan = runCatching { DnsBootstrap.ensure(root) }.getOrNull()
        if (aptDirsMissing.isNotEmpty()) {
            AppLogger.info(TAG, "[termux] apt dizinleri açılamadı: ${aptDirsMissing.joinToString()}")
        }
        // DNS planı günlüğe yazılır: "yazdım mı, dokunmadım mı" ayrımı sonradan
        // teşhis edilemezse, kullanıcı "yine çözemiyor" dediğinde kör kalınır.
        dnsPlan?.let { AppLogger.info(TAG, "[termux] dns: changed=${it.changed} — ${it.reason}") }

        // 8) sources — resmî mirror'a sabitle
        val sources = File(prefixDir, "etc/apt/sources.list")
        val sourcesErr = runCatching {
            sources.parentFile?.mkdirs()
            sources.writeText(TermuxLayer.defaultSources())
        }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        emit(Progress("sources", title("sources"), done = sourcesErr == null, error = sourcesErr))

        // 9) apt-update (isteğe bağlı) — başarısızlık kurulumu düşürmez
        val updateOut = runCatching { runInGuest(ctx, "apt update -y 2>&1") }.getOrNull()
        emit(
            Progress(
                "apt-update", title("apt-update"),
                done = updateOut?.first == 0,
                message = updateOut?.second?.lineSequence()?.lastOrNull { it.isNotBlank() }?.take(120)
                    ?: "atlandı",
            ),
        )

        // 10) smoke — iddia değil çıktı
        val smokeLines = mutableListOf<String>()
        var smokeOk = true
        for (cmd in TermuxLayer.smokeCommands()) {
            val r = runCatching { runInGuest(ctx, "$cmd 2>&1") }.getOrNull()
            if (r == null || r.first != 0) smokeOk = false
            smokeLines += "${cmd.substringAfterLast('/')}: ${(r?.second ?: "çalışmadı").lineSequence().firstOrNull().orEmpty().take(80)}"
        }
        emit(
            Progress(
                "smoke", title("smoke"), done = smokeOk,
                message = smokeLines.joinToString(" · "),
                error = if (smokeOk) null else "bash/apt çalışmadı — katman kuruldu ama kullanılamaz",
            ),
        )

        if (!smokeOk) {
            return@withContext Result(
                false,
                "katman açıldı ama doğrulama geçmedi: ${smokeLines.joinToString("; ")}",
                steps,
            )
        }
        zip.delete()
        // Kurulum boot'tan sonra olabilir: PATH'i tazele, yoksa ajan `apt`
        // bulamaz. Tazeleme `TermuxInstaller`'ın kendi işi — çağıranın bunu
        // hatırlamasına bırakmak, "kurdum ama çalışmıyor" sınıfı bir hata.
        runCatching { PRootKernel.refreshTermuxPath() }
        Log.i(TAG, "termux layer installed at ${prefixDir.absolutePath}")
        Result(true, "Termux katmanı kuruldu: ${smokeLines.joinToString("; ")}", steps)
    }

    /** Kaldırma — kökün içindeki tek dizin silinir, başka hiçbir şey. */
    /**
     * Kaldırma — kökün içindeki `data/data/com.termux` dizini silinir, başka
     * hiçbir şey.
     *
     * Yolu `guestPrefixDir().parentFile.parentFile` ile türetmek kırılgan:
     * `usr` → `files` → `com.termux` üç adım ve biri değişirse yanlış dizini
     * siler (ör. `files`i silmek Termux ev dizinini de götürür). Sabit yol
     * yazmak, silme işleminde tahmin yürütmemek demek.
     *
     * Ek koruma: hedef kökün **altında** olmak zorunda. `root` dışına çıkan
     * bir yol asla silinmez.
     */
    suspend fun uninstall(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val root = rootfs(ctx)
        val target = File(root, "data/data/com.termux")
        val insideRoot = target.canonicalPath.startsWith(root.canonicalPath + File.separator)
        if (!insideRoot) {
            Log.w(TAG, "uninstall refused: ${target.absolutePath} kökün dışında")
            return@withContext false
        }
        if (!target.exists()) return@withContext false
        val ok = target.deleteRecursively()
        Log.i(TAG, "termux layer removed: $ok (${target.absolutePath})")
        ok
    }

    // ------------------------------------------------------------------ iç

    /**
     * Bootstrap'ı indirir. Ağ hatasında **yanlış boyutlu dosya bırakmaz**:
     * yarım dosya sonraki denemede "indirilmiş" sayılırsa sha256 uyuşmaz ve
     * kullanıcı iki kez 31 MB indirir.
     */
    private fun download(
        ctx: Context,
        target: File,
        onProgress: (Long, Long) -> Unit,
    ): String? {
        val tmp = File(target.parentFile, "${target.name}.part")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(TermuxLayer.BOOTSTRAP_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MinisX-TermuxInstaller")
            }
            val code = conn.responseCode
            if (code !in 200..299) return "indirme başarısız: HTTP $code"
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: TermuxLayer.BOOTSTRAP_BYTES
            tmp.parentFile?.mkdirs()
            var done = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastEmit = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        // 1 MB'ta bir yayın: her 64 KB'da StateFlow güncellemek
                        // arayüzü boğar, hiç yayınlamamak ilerlemeyi görünmez yapar.
                        if (done - lastEmit >= 1_048_576L) {
                            lastEmit = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return "indirme dosyası taşınamadı"
            null
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            "indirme hatası: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Zip'i köke açar — **yol kaçışına karşı** her giriş doğrulanır.
     *
     * `../../etc/x` gibi bir giriş kökün dışına yazabilir; kontrol edilmezse
     * zip-slip olur. Burada her hedef `root.canonicalPath` ile başlamak
     * zorunda.
     */
    private fun unzip(zip: File, root: File) {
        // [37098] Hedef hesabı `TermuxLayer.extractTarget`'ta: zip girişleri
        // öneğe göre göreli, köke açmak Alpine'in /bin'ini ezer. Yol tek
        // yerde, test o sözleşmeyi çiviliyor.
        val prefixDir = TermuxLayer.guestPrefixDir(root)
        val prefixPath = prefixDir.canonicalPath + File.separator
        val prefixSelf = prefixDir.canonicalPath
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = TermuxLayer.extractTarget(root, entry.name)
                val outPath = out.canonicalPath
                if (!outPath.startsWith(prefixPath) && outPath != prefixSelf) {
                    throw SecurityException("zip girişi hedef dışına çıkıyor: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> zis.copyTo(o, 64 * 1024) }
                    // Termux ikilileri 0755 olmalı; zip modu taşımıyor.
                    if (entry.name.startsWith("bin/") || entry.name.startsWith("lib/") ||
                        entry.name.startsWith("libexec/")
                    ) {
                        runCatching { Os.chmod(out.absolutePath, 493) } // 0o755
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /**
     * `SYMLINKS.txt` uygular: her satır `hedef←bağ`.
     *
     * Neden ayrı dosya: zip symlink taşıyamaz. Termux `bash`'i
     * `libncursesw.so.6.5 ← ./lib/libtic.so` gibi bağlara dayanıyor; bu adım
     * atlanırsa bash "libncursesw bulunamadı" der.
     *
     * Hata toleranslı: bir bağ kurulamazsa devam eder (bazı imajlarda
     * `Os.symlink` kısıtlıdır), sonuç smoke adımında görünür.
     */
    private fun applySymlinks(root: File) {
        val prefix = TermuxLayer.guestPrefixDir(root)
        val file = File(prefix, "SYMLINKS.txt")
        if (!file.exists()) {
            Log.w(TAG, "SYMLINKS.txt yok — bootstrap beklenenden farklı")
            return
        }
        var created = 0
        var failed = 0
        file.forEachLine { raw ->
            // Çözümleme `TermuxLayer.parseSymlinkLine`'da: biçim `hedef←bağ`,
            // solda hedef. Burada sırayı ters çevirmek sessiz bir hataydı.
            val parsed = TermuxLayer.parseSymlinkLine(raw) ?: return@forEachLine
            val target = parsed.first
            val linkName = parsed.second
            val linkFile = File(prefix, linkName)
            runCatching {
                linkFile.parentFile?.mkdirs()
                // `Path.isSymbolicLink()` Kotlin'in java.nio eklentisinde YOK
                // (sadece `Files.isSymbolicLink(path)`). CI yakaladı:
                // "Unresolved reference 'isSymbolicLink'".
                if (linkFile.exists() || java.nio.file.Files.isSymbolicLink(linkFile.toPath())) {
                    linkFile.delete()
                }
                Os.symlink(target, linkFile.absolutePath)
                created++
            }.onFailure { failed++ }
        }
        Log.i(TAG, "symlinks: $created kuruldu, $failed başarısız")
        file.delete()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input: InputStream ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Konukta tek komut çalıştırır: `(çıkış kodu, çıktı)`.
     *
     * ## Neden [ShellExecutor] değil de doğrudan proot
     *
     * Kurulum sırasında oturum yok ve bir sohbetin kabuğunu meşgul etmemek
     * gerekiyor.
     *
     * ## Neden `boot()` şart
     *
     * `buildProotCommand` `isBooted` kontrolü yapıyor (`check(isBooted)`).
     * Kurulum, uygulama açılışında henüz kabuk hiç açılmamışken çalışabilir —
     * o durumda çağrı `IllegalStateException` atardı ve son adım (smoke)
     * "kuruldu ama doğrulanamadı" diye yanlış rapor verirdi. `boot()` ayrıca
     * `nativeLibDir`/`prootLoaderPath`'i doldurur; bunlar olmadan proot
     * execve'de SELinux tarafından öldürülür.
     */
    private suspend fun runInGuest(ctx: Context, command: String): Pair<Int, String> {
        PRootKernel.boot(ctx)
        val root = rootfs(ctx)
        val pb = ProcessBuilder(PRootKernel.buildProotCommand(command))
        pb.redirectErrorStream(true)
        val env = pb.environment()
        PRootKernel.customEnvironment.forEach { (k, v) -> env[k] = v }
        // [37098] Sıra bilinçli — iki LD_LIBRARY_PATH çakışması:
        //
        // 1. PROOT'un kendisi (Android ana süreci) kendi yükleyicisini
        //    `nativeLibDir`'den bulur → o yol **kalmalı**.
        // 2. Konuğun bionic ikilileri Termux lib'ini arar → o yol da
        //    konuğa geçmeli.
        //
        // `environment()` önce uygulanır (PATH, HOME, PREFIX…), ardından
        // anahtar host değişkenleri EN SON yazılır: proot'un kitaplığı
        // birinci girdide, Termux lib'i ikincide. Konuk açısından ikinci
        // girdi geçerli bir konuk yoludur; ana sürecin nativeLibDir'i
        // konukta yok sayılır. Tek yönlü yazsaydık ya proot ya bionic
        // kırılırdı — ikisi de sessizce.
        env.putAll(TermuxLayer.environment(root))
        env["LD_LIBRARY_PATH"] =
            listOfNotNull(
                PRootKernel.nativeLibDir.takeIf { it.isNotEmpty() },
                TermuxLayer.LD_LIBRARY_PATH_GUEST,
            ).joinToString(":")
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(ctx).absolutePath
        PRootKernel.prootLoaderPath.takeIf { it.isNotEmpty() }?.let { env["PROOT_LOADER"] = it }
        PRootKernel.prootLoader32Path.takeIf { it.isNotEmpty() }?.let { env["PROOT_LOADER_32"] = it }
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        return code to out
    }
}
