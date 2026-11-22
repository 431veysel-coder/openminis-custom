package com.openminis.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37099] Çeviri zinciri ve çevrimdışı sözlük — saf, cihazsız.
 *
 * Kullanıcının istediği davranış: *"kullanıcı hangisini öne alırsa ilk olarak o
 * yanıt vermezse ikinciyi"*. Bu dosya o cümlenin sınanabilir hâli.
 */
class TranslationChainTest {

    private fun pack(glossary: Map<String, String>) = TranslationPacks.Pack(
        id = "test",
        name = "Test",
        targetLanguage = "Turkish",
        glossary = glossary,
    )

    // ── zincir sırası ──────────────────────────────────────────────────────

    @Test
    fun `kayit yoksa varsayilan sira`() {
        assertEquals(
            listOf(
                TranslationChain.Engine.OFFLINE_PACK,
                TranslationChain.Engine.MODEL_PACK,
                TranslationChain.Engine.MODEL_ONLY,
            ),
            TranslationChain.parse(null),
        )
    }

    @Test
    fun `kayitli sira korunur`() {
        // Kullanıcı modeli öne aldıysa çevrimdışı önce denenmez.
        val plan = TranslationChain.parse("model_with_pack,offline_pack")
        assertEquals(
            listOf(TranslationChain.Engine.MODEL_PACK, TranslationChain.Engine.OFFLINE_PACK),
            plan,
        )
    }

    @Test
    fun `bilinmeyen ad atlanir tekrar tekillesir`() {
        val plan = TranslationChain.parse("model_only, editor, model_only, offline_pack")
        assertEquals(
            listOf(TranslationChain.Engine.MODEL_ONLY, TranslationChain.Engine.OFFLINE_PACK),
            plan,
        )
    }

    @Test
    fun `bozuk kayit varsayilana doner`() {
        // Sıra ayarı yüzünden çevirinin tamamen susması kabul edilemez.
        assertEquals(TranslationChain.DEFAULT, TranslationChain.parse("zzz,,,,"))
    }

    @Test
    fun `sonraki motor ve zincir sonu`() {
        val plan = TranslationChain.DEFAULT
        assertEquals(TranslationChain.Engine.MODEL_PACK, TranslationChain.next(plan, TranslationChain.Engine.OFFLINE_PACK))
        assertNull(TranslationChain.next(plan, TranslationChain.Engine.MODEL_ONLY))
    }

    // ── düşme davranışı ────────────────────────────────────────────────────

    @Test
    fun `bos cevap sonraki motora duser`() = runBlocking {
        val seen = mutableListOf<TranslationChain.Engine>()
        val out = TranslationChain.run(TranslationChain.DEFAULT) { e ->
            seen += e
            if (e == TranslationChain.Engine.MODEL_PACK) {
                TranslationChain.Outcome(e, text = "tam çeviri", complete = true)
            } else {
                TranslationChain.Outcome(e, text = "", reason = "yok")
            }
        }
        assertEquals(TranslationChain.Engine.MODEL_PACK, out?.engine)
        assertEquals("tam çeviri", out?.text)
        assertTrue(seen.contains(TranslationChain.Engine.OFFLINE_PACK))
    }

    @Test
    fun `degraded sonuc saklanir ama sonraki denenir`() = runBlocking {
        val out = TranslationChain.run(TranslationChain.DEFAULT) { e ->
            when (e) {
                TranslationChain.Engine.OFFLINE_PACK ->
                    TranslationChain.Outcome(e, text = "sözlük sonucu", degraded = true)
                else -> TranslationChain.Outcome(e, text = "", reason = "kota bitti")
            }
        }
        // Hiç model cevap vermedi: "hiç yok" yerine kısmi sonuç döner.
        assertEquals("sözlük sonucu", out?.text)
        assertTrue(out!!.degraded)
    }

    @Test
    fun `tam sonuc zinciri durdurur`() = runBlocking {
        val seen = mutableListOf<TranslationChain.Engine>()
        TranslationChain.run(TranslationChain.DEFAULT) { e ->
            seen += e
            TranslationChain.Outcome(e, text = "tam", complete = true)
        }
        // İlk motor tam cevap verdiyse modele hiç gidilmez (token harcanmaz).
        assertEquals(listOf(TranslationChain.Engine.OFFLINE_PACK), seen)
    }

    // ── sözlük motoru ──────────────────────────────────────────────────────

    @Test
    fun `sozluk terimleri degistirir`() {
        val r = OfflineGlossary.apply(
            "The context window is small.",
            pack(mapOf("context window" to "bağlam penceresi", "small" to "küçük")),
        )
        assertEquals("The bağlam penceresi is küçük.", r.text)
        assertEquals(2, r.replaced)
        assertTrue(r.degraded)
        // Cümle çevrilmedi: "The"/"is" duruyor. Bunu tam saymak yalan olurdu.
        assertFalse(r.complete)
    }

    @Test
    fun `uzun terim once uygulanir`() {
        // "context" önce değişirse "context window" bozulur.
        val r = OfflineGlossary.apply(
            "Open the context window.",
            pack(mapOf("context" to "bağlam", "context window" to "bağlam penceresi")),
        )
        assertEquals("Open the bağlam penceresi.", r.text)
    }

    @Test
    fun `kelime siniri korunur`() {
        // "cat" -> "kedi" kuralı "concatenate" içinde eşleşmemeli (n|cat sınır değil).
        val r = OfflineGlossary.apply("concatenate the cat", pack(mapOf("cat" to "kedi")))
        assertEquals("concatenate the kedi", r.text)
        assertEquals(1, r.replaced)
    }

    @Test
    fun `paket yoksa bos doner`() {
        // Uydurma cevap üretmek yerine "cevap yok" demek: zincir sonrakine düşer.
        assertFalse(OfflineGlossary.apply("metin", null).usable)
        assertFalse(OfflineGlossary.apply("metin", pack(emptyMap())).usable)
    }

    @Test
    fun `eslesme yoksa bos doner`() {
        val r = OfflineGlossary.apply("hiçbir terim yok", pack(mapOf("zzz" to "yyy")))
        assertFalse(r.usable)
        assertEquals(0, r.replaced)
    }
}
