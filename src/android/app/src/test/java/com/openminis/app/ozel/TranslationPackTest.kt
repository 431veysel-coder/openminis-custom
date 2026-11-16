package com.openminis.app.ozel

import com.openminis.app.data.TranslationPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MINIS-OZEL] The pack format and its prompt contribution.
 *
 * Blocking test (`com.openminis.app.ozel.*` runs in CI before anything is
 * published). The parser is the part that can turn a typo in a downloaded file
 * into an app-level crash, so it is the part worth pinning: unknown fields are
 * ignored, a missing name is refused, and a pack with no rules is refused
 * rather than installed as prompt noise.
 */
class TranslationPackTest {

    private fun valid() = """
        {
          "id": "tr-test",
          "name": "Test",
          "targetLanguage": "Turkish",
          "version": 3,
          "style": "Kısa cümle.",
          "glossary": { "context window": "bağlam penceresi" },
          "keepAsIs": ["Minis", "API"],
          "examples": [{ "source": "Hello", "target": "Merhaba" }]
        }
    """.trimIndent()

    @Test
    fun `parses a full pack`() {
        val p = TranslationPacks.parse(valid(), sourceUrl = "https://example/x.json")
        assertEquals("tr-test", p.id)
        assertEquals("Turkish", p.targetLanguage)
        assertEquals(3, p.version)
        assertEquals("bağlam penceresi", p.glossary["context window"])
        assertEquals(listOf("Minis", "API"), p.keepAsIs)
        assertEquals("Merhaba", p.examples.first().second)
        assertEquals("https://example/x.json", p.sourceUrl)
    }

    @Test
    fun `ignores unknown fields`() {
        val json = """{"id":"a","name":"A","targetLanguage":"Turkish","futureField":{"x":1}}"""
        val p = TranslationPacks.parse(json, null)
        assertEquals("a", p.id)
        assertTrue(p.glossary.isEmpty())
    }

    @Test
    fun `blank target language is kept blank rather than guessed`() {
        // A pack without a language must never match a translation: applying a
        // Turkish glossary to a German request is worse than no pack at all.
        val p = TranslationPacks.parse("""{"id":"a","name":"A","style":"x"}""", null)
        assertEquals("", p.targetLanguage)
    }

    @Test
    fun `a pack with no id or no name is rejected`() {
        assertFalse(runCatching { TranslationPacks.parse("""{"name":"A"}""", null) }.isSuccess)
        assertFalse(runCatching { TranslationPacks.parse("""{"id":"a"}""", null) }.isSuccess)
    }

    @Test
    fun `a pack carrying no rules reports itself empty`() {
        val p = TranslationPacks.parse("""{"id":"a","name":"A","targetLanguage":"Turkish"}""", null)
        assertTrue(p.isEmpty)
    }

    @Test
    fun `prompt fragment is empty for no pack`() {
        assertEquals("", TranslationPacks.promptFragment(null))
    }

    @Test
    fun `prompt fragment carries style, glossary and keep-list`() {
        val p = TranslationPacks.parse(valid(), null)
        val f = TranslationPacks.promptFragment(p)
        assertTrue(f.contains("Kısa cümle."))
        assertTrue(f.contains("context window -> bağlam penceresi"))
        assertTrue(f.contains("Minis, API"))
        assertTrue(f.contains("SOURCE: Hello"))
        assertTrue(f.contains("TARGET: Merhaba"))
    }

    @Test
    fun `prompt fragment is bounded`() {
        // An unbounded glossary would cost more than the text being translated.
        val terms = (1..500).joinToString(",") { "\"k$it\":\"v$it\"" }
        val json = """{"id":"big","name":"Big","targetLanguage":"Turkish","glossary":{$terms}}"""
        val f = TranslationPacks.promptFragment(TranslationPacks.parse(json, null))
        assertTrue("fragment grew to ${f.length} chars", f.length < 12_000)
    }
}
