package com.openminis.app.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OZEL 37089] Yeniden oynatma testleri. */
class ReplayerTest {

    private fun rec(vararg entries: Pair<Map<String, String>, String>): ReplayRecording =
        ReplayRecording(
            id = "r1", sessionId = "s1", createdAtMs = 0L, modelId = "model-a",
            prompt = "3 dosyayı düzelt ve testleri çalıştır",
            entries = entries.mapIndexed { i, (args, result) ->
                ReplayEntry(ToolCallKey.of("shell_execute", args), result, i)
            },
            originalTurns = 6,
        )

    @Test
    fun `kayitli cagri kayittan doner`() {
        val r = Replayer(rec(mapOf("command" to "ls") to "a.txt\nb.txt"))
        val step = r.step("shell_execute", mapOf("command" to "ls"))
        assertTrue(step is ReplayStep.Serve)
        assertEquals("a.txt\nb.txt", (step as ReplayStep.Serve).result)
    }

    @Test
    fun `arguman sirasi ve bosluk anahtari degistirmez`() {
        // Aksi hâlde aynı çağrı her koşuda ıskalar ve ölçüm çöp olur.
        val r = Replayer(rec(mapOf("command" to "ls  -la", "timeout" to "5") to "ok"))
        val step = r.step("shell_execute", mapOf("timeout" to "5", "command" to " ls -la "))
        assertTrue(step is ReplayStep.Serve)
    }

    @Test
    fun `kayitta olmayan cagri calistirilmaz`() {
        // KARAR: sessizce canlı çağrıya düşmek ölçümü kirletir.
        val r = Replayer(rec(mapOf("command" to "ls") to "ok"))
        val step = r.step("shell_execute", mapOf("command" to "rm -rf /tmp/x"))
        assertTrue(step is ReplayStep.Miss)
    }

    @Test
    fun `iskalama orani raporlanir`() {
        val r = Replayer(rec(mapOf("command" to "ls") to "ok"))
        r.step("shell_execute", mapOf("command" to "ls"))
        r.step("shell_execute", mapOf("command" to "pwd"))
        val out = r.outcome("model-b", turns = 4, completed = true, finalText = "bitti")
        assertEquals(0.5, out.missRate, 0.001)
        assertTrue(out.compareWith(rec(mapOf("command" to "ls") to "ok")).contains("tur 6 → 4"))
    }

    @Test
    fun `bozuk kayit kendini bildirir`() {
        val r = Replayer(
            rec(
                mapOf("command" to "ls") to "bir",
                mapOf("command" to "ls") to "iki",
            ),
        )
        assertTrue(r.selfCheck().any { it.contains("2 kez") })
    }

    @Test
    fun `bos kayit anlamsizdir`() {
        val empty = ReplayRecording("r", "s", 0L, null, "p", emptyList(), 0)
        assertTrue(Replayer(empty).selfCheck().any { it.contains("hiç araç çağrısı yok") })
    }
}
