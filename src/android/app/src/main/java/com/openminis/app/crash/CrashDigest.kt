package com.openminis.app.crash

/**
 * [H-016] **Çökme raporunu okunur hâle getiren saf çözümleyici.**
 *
 * Neden: `filesDir/logs/crash-*.log` dosyaları vardı ama kullanıcı için
 * okunamazdı — 200 satır logcat, obfuscated çerçeveler (`w.a.c`, `Q0.D.d`) ve
 * dosya adı dışında hiçbir özet. "Hangi hata, kaç kez, ilk nerede" sorusu
 * yalnız dosyaları tek tek açıp gözle tarayarak cevaplanıyordu.
 *
 * Bu sınıf Android'e bağımlı **değil**: girdi metin, çıktı [CrashEntry].
 * Böylece JVM testinde gerçek bir çökme metniyle sınanabilir (bkz.
 * `CrashDigestTest`). UI (`CrashDigestSection`) yalnız gösterir.
 *
 * Üç yazıcı biçimi var, üçü de burada:
 *  - Java/Kotlin → `CrashFileReporter.CrashFileSender`
 *  - Yerel sinyal → `cpp/crash_handler.cpp`
 *  - Süreç çıkışı → `ExitInfoCollector`
 */
object CrashDigest {

    enum class Kind { JAVA, NATIVE, EXIT_INFO, UNKNOWN }

    /** Bir dosyanın özeti. [signature] aynı hatayı gruplayan anahtar. */
    data class CrashEntry(
        val fileName: String,
        val kind: Kind,
        val time: String?,
        val version: String?,
        val headline: String,
        val appFrame: String?,
        val topFrames: List<String>,
        val cause: String?,
        val signature: String,
    )

    /** Aynı [CrashEntry.signature] altındaki dosyalar. */
    data class CrashGroup(
        val signature: String,
        val sample: CrashEntry,
        val fileNames: List<String>,
    ) {
        val count: Int get() = fileNames.size
    }

    private val APP_PREFIX = "com.openminis.app"
    private val AT_FRAME = Regex("""^\s*at\s+([\w$.<>]+)\(([^)]*)\)""")
    /**
     * İki biçim var: bizim yazıcımız `#00 0x1234 libfoo.so+0x40 …`, sistem
     * tombstone'u ise `#00 pc 0000000000a28c /system/lib64/libGLESv1_CM.so (…)+44`.
     * İkisini de kabul etmek zorunlu: aynı ekranda iki kaynak var.
     */
    private val NATIVE_FRAME = Regex(
        "#\\d+\\s+(?:pc\\s+)?(?:0x)?[0-9a-fA-F]{4,}\\s+\\S*?([\\w.+-]+\\.so)(?:\\+0x([0-9a-fA-F]+))?",
    )

    fun parse(fileName: String, text: String): CrashEntry {
        val kind = when {
            text.contains("=== Minis Java/Kotlin Crash ===") -> Kind.JAVA
            text.contains("=== Minis Native Crash ===") -> Kind.NATIVE
            text.contains("=== Minis Process Exit Report ===") -> Kind.EXIT_INFO
            else -> Kind.UNKNOWN
        }
        val lines = text.lineSequence().toList()
        return when (kind) {
            Kind.JAVA -> parseJava(fileName, lines)
            Kind.NATIVE -> parseNative(fileName, lines)
            Kind.EXIT_INFO -> parseExit(fileName, lines)
            Kind.UNKNOWN -> CrashEntry(
                fileName = fileName,
                kind = Kind.UNKNOWN,
                time = field(lines, "Time:"),
                version = null,
                headline = lines.firstOrNull { it.isNotBlank() }?.take(160) ?: "(boş dosya)",
                appFrame = null,
                topFrames = emptyList(),
                cause = null,
                signature = "unknown|" + fileName.substringBeforeLast('.'),
            )
        }
    }

    private fun parseJava(fileName: String, lines: List<String>): CrashEntry {
        val start = lines.indexOfFirst { it.trim() == "--- Stack Trace ---" }
        val stack = if (start >= 0) lines.drop(start + 1) else lines
        val headline = stack.firstOrNull { it.isNotBlank() }?.trim()
            ?: "(yığın izi yazılmamış)"
        val frames = stack.mapNotNull { line ->
            AT_FRAME.find(line)?.let { "at ${it.groupValues[1]}(${it.groupValues[2]})" }
        }
        val appFrame = frames.firstOrNull { it.contains(APP_PREFIX) }
        val cause = stack.firstOrNull { it.trimStart().startsWith("Caused by:") }
            ?.trim()?.take(200)
        // Grup anahtarı: istisna sınıfı+mesajın ilk satırı ve **bizim** ilk
        // çerçevemiz. Satır numarası anahtara girmez: aynı hata kod
        // kaydıkça yeni bir grup açmamalı.
        val sigHead = headline.substringBefore(':').take(120)
        val sigFrame = appFrame?.substringBefore('(') ?: frames.firstOrNull()?.substringBefore('(') ?: "-"
        return CrashEntry(
            fileName = fileName,
            kind = Kind.JAVA,
            time = field(lines, "Time:"),
            version = field(lines, "Version:"),
            headline = headline.take(300),
            appFrame = appFrame,
            topFrames = frames.take(8),
            cause = cause,
            signature = "java|$sigHead|$sigFrame",
        )
    }

    private fun parseNative(fileName: String, lines: List<String>): CrashEntry {
        val signal = field(lines, "Signal:") ?: "?"
        val thread = field(lines, "PID:") // "PID: 1  TID: 2  Thread: name"
        val frames = lines.mapNotNull { line ->
            NATIVE_FRAME.find(line)?.let { m ->
                val lib = m.groupValues[1]
                val off = m.groupValues[2]
                if (off.isNotEmpty()) "$lib+0x$off" else lib
            }
        }
        val abort = lines.firstOrNull { it.startsWith("Abort message:") }
            ?.removePrefix("Abort message:")?.trim()?.take(200)
        val headline = buildString {
            append("Yerel çökme · ").append(signal)
            if (abort != null) append(" · ").append(abort)
        }
        return CrashEntry(
            fileName = fileName,
            kind = Kind.NATIVE,
            time = field(lines, "Time:"),
            version = null,
            headline = headline.take(300),
            appFrame = frames.firstOrNull(),
            topFrames = frames.take(8),
            cause = thread?.let { "Thread: ${it.substringAfter("Thread:", "").trim()}" },
            signature = "native|$signal|${frames.firstOrNull() ?: "-"}",
        )
    }

    private fun parseExit(fileName: String, lines: List<String>): CrashEntry {
        val reason = field(lines, "Reason:") ?: "?"
        val status = field(lines, "Status:")
        val desc = lines.indexOfFirst { it.startsWith("Abort message / description:") }
            .let { i -> if (i >= 0) lines.drop(i + 1).firstOrNull { it.isNotBlank() }?.trim() else null }
            ?.takeIf { it != "(none provided by the platform)" }
        val summaryLine = lines.firstOrNull { it.contains("Tombstone summary") }
        return CrashEntry(
            fileName = fileName,
            kind = Kind.EXIT_INFO,
            time = field(lines, "Exit time:") ?: field(lines, "Collected:"),
            version = null,
            headline = buildString {
                append("Süreç ölümü · ").append(reason)
                if (!status.isNullOrBlank()) append(" (status ").append(status).append(")")
                if (desc != null) append(" · ").append(desc)
            }.take(300),
            appFrame = summaryLine?.take(160),
            topFrames = emptyList(),
            cause = desc,
            signature = "exit|$reason|${status ?: "-"}",
        )
    }

    /** `Anahtar: değer` satırı; ilk eşleşme. */
    private fun field(lines: List<String>, key: String): String? =
        lines.firstOrNull { it.startsWith(key) }
            ?.removePrefix(key)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Aynı imzayı grupla; **en çok tekrar eden** üstte, eşitse en yeni dosya
     * üstte. Dosya adları `…_yyyy-MM-dd_HH-mm-ss` ile bittiği için ada göre
     * ters sıralama zamana göre ters sıralamadır (mevcut ekran da böyle
     * sıralıyor).
     */
    fun group(entries: List<CrashEntry>): List<CrashGroup> =
        entries.groupBy { it.signature }
            .map { (sig, list) ->
                val sorted = list.sortedByDescending { it.fileName }
                CrashGroup(signature = sig, sample = sorted.first(), fileNames = sorted.map { it.fileName })
            }
            .sortedWith(
                compareByDescending<CrashGroup> { it.count }
                    .thenByDescending { it.sample.fileName },
            )
}
