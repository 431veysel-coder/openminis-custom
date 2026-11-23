package com.openminis.app.screen

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37099b] Sanal ekran istem bölümünün **gerçekten bağlı** olduğunu çiviyen test.
 *
 * Neden kaynak-olgusu testi: `VirtualScreenPrompt.section` bir Android `Context`
 * istiyor (SharedPreferences + özellik anahtarı), bu cihazda JVM başlamıyor.
 * Ölçülebilen şey, ölü kodun geri gelmemesi: `VirtualScreenHost.brief()` tam bu
 * yüzden bir kez ölü doğdu — tüm depoda tek referansı kendi KDoc'uydu.
 */
class VirtualScreenPromptWiringTest {

    private fun src(path: String): String {
        val f = File(path)
        assertTrue("missing source: ${f.absolutePath}", f.exists())
        return f.readText()
    }

    @Test
    fun `sistem istemi sanal ekran bolumunu cagiriyor`() {
        val chat = src("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue(
            "ChatViewModel must call VirtualScreenPrompt.section(context), " +
                "or the agent never learns that the virtual screen exists",
            chat.contains("com.openminis.app.screen.VirtualScreenPrompt.section(context)"),
        )
        // Üretilen bölüm istem metnine eklenmeli — hesaplanıp atılmamalı.
        assertTrue(
            "the section must be interpolated into the prompt",
            chat.contains("\${termuxSection}\${virtualScreenSection}"),
        )
    }

    @Test
    fun `brief tek kaynaga delege ediyor`() {
        val host = src("src/main/java/com/openminis/app/screen/VirtualScreenHost.kt")
        assertTrue(
            "VirtualScreenHost.brief must delegate to VirtualScreenPrompt.section " +
                "so the documented reader and the prompt cannot drift apart",
            host.contains("VirtualScreenPrompt.section(ctx)"),
        )
    }

    @Test
    fun `metin ayarlari ve kullanim ornegini iceriyor`() {
        // Ürün isteği: yalnız A/B farkı değil, ayar yolu ve örnekler de yazılsın.
        val prompt = src("src/main/java/com/openminis/app/screen/VirtualScreenPrompt.kt")
        for (needle in listOf(
            "Settings › Minis X › Virtual screen",
            "Usage examples",
            "A/B difference",
            "do NOT claim you browsed",
        )) {
            assertTrue("VirtualScreenPrompt must mention: $needle", prompt.contains(needle))
        }
    }
}
