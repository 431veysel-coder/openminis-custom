package com.openminis.app.sandbox

import android.content.Context
import android.system.Os
import android.util.Log
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

    private fun rootfs(ctx: Context) = File(ctx.filesDir, "alpine-rootfs")

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
        val prefixDir = TermuxLayer.guestPrefixDir(root)
        val extractErr = runCatching {
            prefixDir.mkdirs()
            unzip(zip, root)
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
        Log.i(TAG, "termux layer installed at ${prefixDir.absolutePath}")
        Result(true, "Termux katmanı kuruldu: ${smokeLines.joinToString("; ")}", steps)
    }

    /** Kaldırma — kökün içindeki tek dizin silinir, başka hiçbir şey. */
    suspend fun uninstall(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val root = rootfs(ctx)
        val target = TermuxLayer.guestPrefixDir(root).parentFile?.parentFile // …/files
        val ok = target != null && target.exists() && target.path.startsWith(root.path)
        if (ok) target.deleteRecursively()
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
        val rootPath = root.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val dest = File(root, entry.name)
                val destPath = dest.canonicalPath
                if (!destPath.startsWith(rootPath) && destPath != root.canonicalPath) {
                    throw SecurityException("zip girişi kök dışına çıkıyor: ${entry.name}")
                }
                if (entry.isDirectory) {
                    dest.mkdirs()
                } else {
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { out -> zis.copyTo(out, 64 * 1024) }
                    // Termux ikilileri 0755 olmalı; zip modu taşımıyor.
                    if (entry.name.startsWith("bin/") || entry.name.startsWith("lib/")) {
                        runCatching { Os.chmod(dest.absolutePath, 493) } // 0o755
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
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split("←")
            if (parts.size != 2) return@forEachLine
            val linkName = parts[0].trim()
            val target = parts[1].trim().removePrefix("./")
            if (linkName.isEmpty()) return@forEachLine
            val linkFile = File(prefix, linkName)
            runCatching {
                linkFile.parentFile?.mkdirs()
                if (linkFile.exists() || linkFile.toPath().isSymbolicLink()) linkFile.delete()
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
     * Neden [ShellExecutor] değil de doğrudan proot: kurulum sırasında oturum
     * yok ve shell oturum açılışına bağlanmak istemiyoruz. Kullanıcı katmanı
     * kurarken bir sohbetin kabuğunu meşgul etmemeli.
     */
    private fun runInGuest(ctx: Context, command: String): Pair<Int, String> {
        val root = rootfs(ctx)
        val pb = ProcessBuilder(PRootKernel.buildProotCommand(command))
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(ctx).absolutePath
        PRootKernel.nativeLibDir.takeIf { it.isNotEmpty() }?.let { env["LD_LIBRARY_PATH"] = it }
        PRootKernel.prootLoaderPath.takeIf { it.isNotEmpty() }?.let { env["PROOT_LOADER"] = it }
        PRootKernel.prootLoader32Path.takeIf { it.isNotEmpty() }?.let { env["PROOT_LOADER_32"] = it }
        PRootKernel.customEnvironment.forEach { (k, v) -> env[k] = v }
        // Katman ortamı EN SON: PATH'i Termux önekiyle başlatır, böylece `bash`
        // ve `apt` Alpine'in değil Termux'un ikilileridir.
        env.putAll(TermuxLayer.environment(root))
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        return code to out
    }
}
