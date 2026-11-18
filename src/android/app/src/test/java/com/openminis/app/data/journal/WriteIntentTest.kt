package com.openminis.app.data.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OZEL 37088] Yazma niyeti testleri.
 *
 * Not: bu sınıf süreç belleğinde tek örnek (`object`), bu yüzden her test
 * kendi oturum kimliğiyle çalışır — testler birbirinin niyetini görmesin.
 */
class WriteIntentTest {

    private val t0 = 1_000_000L

    @Test
    fun `ayni dosyaya iki ajan cakisir`() {
        val c = WriteIntent.claim("agent-a", "/var/minis/workspace/a.kt", "düzeltme", t0)
        assertTrue(c.isEmpty()) // ilk gelen çakışma görmez

        val c2 = WriteIntent.claim("agent-b", "/var/minis/workspace/a.kt", "refactor", t0 + 100)
        assertEquals(1, c2.size)
        assertTrue(c2.single().text().contains("agent-a".take(8)))
    }

    @Test
    fun `ayni ajan kendi niyetiyle cakismaz`() {
        WriteIntent.claim("agent-c", "/var/minis/workspace/b.kt", "ilk", t0)
        val again = WriteIntent.claim("agent-c", "/var/minis/workspace/b.kt", "ikinci", t0 + 10)
        assertTrue(again.isEmpty())
    }

    @Test
    fun `farkli dosyalar cakismaz`() {
        WriteIntent.claim("agent-d", "/var/minis/workspace/x.kt", "iş", t0)
        val c = WriteIntent.claim("agent-e", "/var/minis/workspace/y.kt", "iş", t0 + 5)
        assertTrue(c.isEmpty())
    }

    @Test
    fun `sure dolan niyet cakisma uretmez`() {
        // KARAR: ölü ajanın niyeti sonsuza kadar meşgul göstermemeli.
        WriteIntent.claim("agent-f", "/var/minis/workspace/z.kt", "iş", t0, ttlMs = 1000)
        val c = WriteIntent.claim("agent-g", "/var/minis/workspace/z.kt", "iş", t0 + 5000)
        assertTrue(c.isEmpty())
    }

    @Test
    fun `is bitince niyet kalkar`() {
        WriteIntent.claim("agent-h", "/var/minis/workspace/q.kt", "iş", t0)
        WriteIntent.release("agent-h", "/var/minis/workspace/q.kt")
        val c = WriteIntent.claim("agent-i", "/var/minis/workspace/q.kt", "iş", t0 + 10)
        assertTrue(c.isEmpty())
    }

    @Test
    fun `uyari metni yeniden okumayi soyler`() {
        // KARAR: uyarı bir eylem cümlesi olmalı, "dikkat" demek yetmez.
        WriteIntent.claim("agent-j", "/var/minis/workspace/w.kt", "test düzeltmesi", t0)
        val c = WriteIntent.claim("agent-k", "/var/minis/workspace/w.kt", "başka iş", t0 + 1)
        val msg = WriteIntent.warningFor(c, t0 + 1)
        assertTrue(msg.contains("yeniden okuyun"))
        assertTrue(msg.contains("test düzeltmesi"))
    }

    @Test
    fun `cakisma sicili birikir`() {
        WriteIntent.clearConflicts()
        WriteIntent.claim("agent-l", "/var/minis/workspace/r.kt", "iş", t0)
        WriteIntent.claim("agent-m", "/var/minis/workspace/r.kt", "iş", t0 + 1)
        assertEquals(1, WriteIntent.conflicts().size)
    }
}
