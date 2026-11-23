package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [37099h · §6.1] Paket önerisi — cihazsız koşar.
 *
 * Neden test: öneri bir **tavsiye**dir ve yanlış tavsiye, tavsiye olmamasından
 * kötüdür. Boş listede null dönmesi, eşleşme yokken zengin glossary'ye
 * düşmesi ve boş paketi önermemesi çivileniyor.
 */
class TranslationRecommendTest {

    private fun pack(id: String, lang: String, terms: Int, empty: Boolean = false) = TranslationPacks.Pack(
        id = id,
        name = id,
        targetLanguage = lang,
        glossary = if (empty) emptyMap() else (1..terms).associate { "t$it" to "c$it" },
    )

    @Test
    fun `bos liste oneri uretmez`() {
        assertNull(TranslationPacks.recommendFor(listOf(), "Turkish"))
    }

    @Test
    fun `dil eslesen paket tercih edilir`() {
        val packs = listOf(
            pack("a", "German", 50),
            pack("b", "Turkish", 5),
        )
        assertEquals("b", TranslationPacks.recommendFor(packs, "Turkish")?.id)
    }

    @Test
    fun `eslesme yoksa en zengin glossary secilir`() {
        val packs = listOf(pack("a", "German", 3), pack("b", "French", 9))
        assertEquals("b", TranslationPacks.recommendFor(packs, "Turkish")?.id)
    }

    @Test
    fun `bos paket onerilmez`() {
        // Terimi olmayan paket bir çeviri paketi değil, boş bir dosyadır.
        val packs = listOf(pack("a", "Turkish", 0, empty = true))
        assertNull(TranslationPacks.recommendFor(packs, "Turkish"))
    }
}
