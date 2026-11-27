package com.openminis.app.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [H-016] Çökme özeti testleri.
 *
 * Girdiler **gerçek** dosya başları: 2026-11-27 04:46 SIGSEGV raporu, 11-26
 * Compose `IllegalStateException` raporu ve bir süreç çıkış raporu. Uydurma
 * metinle test etmek, çözümleyicinin gerçek biçimi okuyabildiğini kanıtlamaz.
 */
class CrashDigestTest {

    private val javaCrash = """
        === Minis Java/Kotlin Crash ===
        Time: 2026-11-26_05-13-43
        Version: 1.15-ozel.47 (37127)
        Android: 16 (SDK 36)
        Device: Infinix X6873 (Infinix)

        --- Stack Trace ---
        java.lang.IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints
            at w.a.c(SourceFile:2)
            at Q0.D.d(SourceFile:192)
            at com.openminis.app.ui.settings.TaskHallScreenKt$TaskHallScreen$1.invoke(TaskHallScreen.kt:88)
            at android.view.View.measure(View.java:28674)
        Caused by: java.lang.IllegalStateException: nested scroll
            at com.openminis.app.ui.settings.TaskHallScreenKt.rows(TaskHallScreen.kt:42)
    """.trimIndent()

    private val nativeCrash = """
        === Minis Native Crash ===
        Time: 2026-11-27 04:46:19
        Signal: 11 (SIGSEGV)
        si_code: 1
        Fault addr: 0xdd0
        PID: 23717  TID: 24016  Thread: DefaultDispatch
        backtrace:
              #00 pc 000000000000a28c  /system/lib64/libGLESv1_CM.so (glGetString+44)
              #01 pc 0000000000176ae4  /system/lib64/libandroid_runtime.so (android_glGetString+20)
    """.trimIndent()

    private val exitReport = """
        === Minis Process Exit Report ===
        Collected: 2026-11-27_02-10-36
        Exit time: 2026-11-27_02-10-35
        Reason: CRASH(JAVA)
        Status: 1
        Abort message / description:
        lateinit property backgroundSettingsRepository has not been initialized

        --- Tombstone summary (whole trace) ---
        threads: 41
    """.trimIndent()

    @Test
    fun `java crash keeps our own frame and ignores the obfuscated ones`() {
        val e = CrashDigest.parse("crash-2026-11-26_05-13-43.log", javaCrash)
        assertEquals(CrashDigest.Kind.JAVA, e.kind)
        assertTrue(e.headline.startsWith("java.lang.IllegalStateException"))
        // En önemli alan: "bizim kodumuzda nerede". Çerçeve listesinin ilk
        // elemanı obfuscated (`w.a.c`) olabilir; özet onu göstermez.
        assertEquals(
            "at com.openminis.app.ui.settings.TaskHallScreenKt${'$'}TaskHallScreen${'$'}1.invoke(TaskHallScreen.kt:88)",
            e.appFrame,
        )
        assertEquals("Caused by: java.lang.IllegalStateException: nested scroll", e.cause)
        assertEquals("1.15-ozel.47 (37127)", e.version)
    }

    @Test
    fun `same crash in another build groups together, line numbers do not split it`() {
        val a = CrashDigest.parse("crash-2026-11-26_05-13-43.log", javaCrash)
        val b = CrashDigest.parse(
            "crash-2026-11-26_09-15-54.log",
            javaCrash.replace("TaskHallScreen.kt:88", "TaskHallScreen.kt:91"),
        )
        assertEquals(a.signature, b.signature)
        val groups = CrashDigest.group(listOf(a, b))
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].count)
        // En yeni dosya örnek olur: kullanıcı en son çökmeyi açar.
        assertEquals("crash-2026-11-26_09-15-54.log", groups[0].sample.fileName)
    }

    @Test
    fun `native crash reports the signal and the library frame`() {
        val e = CrashDigest.parse("native-crash-2026-11-27_04-46-19.log", nativeCrash)
        assertEquals(CrashDigest.Kind.NATIVE, e.kind)
        assertTrue(e.headline.contains("SIGSEGV"))
        // Sistem tombstone'unda ofset `+0x…` olarak yazılmaz (PC hâlinde
        // gelir); özet kütüphaneyi söyler, uydurma ofset yazmaz.
        assertEquals("libGLESv1_CM.so", e.appFrame)
        assertTrue(e.cause!!.contains("DefaultDispatch"))
    }

    @Test
    fun `exit report surfaces the abort message, which is the whole point`() {
        val e = CrashDigest.parse("exitinfo-2026-11-27_02-10-36.log", exitReport)
        assertEquals(CrashDigest.Kind.EXIT_INFO, e.kind)
        assertTrue(e.headline.contains("CRASH(JAVA)"))
        assertEquals(
            "lateinit property backgroundSettingsRepository has not been initialized",
            e.cause,
        )
    }

    @Test
    fun `unknown file does not invent a signature and does not throw`() {
        val e = CrashDigest.parse("crash-garbage.log", "hello\nworld")
        assertEquals(CrashDigest.Kind.UNKNOWN, e.kind)
        assertEquals("hello", e.headline)
        assertTrue(e.signature.startsWith("unknown|"))
    }

    @Test
    fun `groups sort by count, then by recency`() {
        val repeated = (1..3).map {
            CrashDigest.parse("crash-2026-11-2$it_10-00-00.log", nativeCrash)
        }
        val once = CrashDigest.parse("crash-2026-11-27_10-00-00.log", javaCrash)
        val groups = CrashDigest.group(repeated + once)
        assertEquals(2, groups.size)
        assertEquals(3, groups[0].count)
        assertEquals(1, groups[1].count)
    }
}
