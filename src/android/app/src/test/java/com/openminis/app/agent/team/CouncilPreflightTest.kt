package com.openminis.app.agent.team

import com.openminis.app.data.team.TeamMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37099e] Konsey ön kontrolü + üye arıza sınıflandırması.
 *
 * İkisi aynı dosyada çünkü aynı sorunun iki yarısı: "başlamadan biliyor muyuz"
 * ve "başladıysa neden düştü". Ayrı dosyalara koymak, birini güncelleyip
 * diğerini unutmayı kolaylaştırırdı.
 */
class CouncilPreflightTest {

    private fun member(name: String, modelId: String? = "m1") =
        TeamMember(name = name, modelId = modelId)

    // ── Ön kontrol ─────────────────────────────────────────────────────────

    @Test
    fun `hepsi hazirsa onay istemez`() {
        val v = CouncilPreflight.check(listOf(member("A"), member("B"))) { true }
        assertTrue(v.canStart)
        assertFalse("hepsi hazırsa soru sormak gürültüdür", v.needsConfirmation)
        assertEquals("2/2 üye hazır", v.summary())
    }

    @Test
    fun `hicbiri hazir degilse baslamaz`() {
        val v = CouncilPreflight.check(listOf(member("A"), member("B"))) { false }
        assertFalse("boş üyeyle koşu token yakar", v.canStart)
        assertTrue(v.report().contains("BAŞLATILMADI"))
        assertTrue(v.report().contains("Token harcanmadı"))
    }

    @Test
    fun `bazilari hazirsa onay ister ve kim dustugu yazilir`() {
        val v = CouncilPreflight.check(listOf(member("A"), member("B", modelId = null))) { m ->
            m.name == "A"
        }
        assertTrue(v.canStart)
        assertTrue("2 kişilik ekipten 1'i düşerse kullanıcı bunu bilmeli", v.needsConfirmation)
        val report = v.report()
        assertTrue(report.contains("1/2 üye hazır"))
        assertTrue(report.contains("B → model yok"))
        assertTrue("öneri satırı olmadan kullanıcı ne yapacağını bilmez", report.contains("öneri:"))
    }

    @Test
    fun `bos ekip baslatilmaz`() {
        val v = CouncilPreflight.check(emptyList()) { true }
        assertFalse(v.canStart)
        assertTrue(v.report().contains("Ekipte üye yok"))
    }

    @Test
    fun `saglayici sorgusu patlarsa uye hazir sayilmaz`() {
        // Depo geç yüklenmiş olabilir; "patladı" ile "hazır" aynı şey değil.
        val v = CouncilPreflight.check(listOf(member("A"))) { error("depo yok") }
        assertFalse(v.canStart)
    }

    // ── Arıza sınıflandırması ──────────────────────────────────────────────

    @Test
    fun `yuva hatasi zaman asimi sanilmaz`() {
        // Metin "waited … for a free stream slot" içerir; sıralama yanlışsa
        // kullanıcı süreyi uzatmaya çalışır — yanlış düzeltme.
        val f = MemberFailure.classify("waited 90s for a free stream slot and never started this round")
        assertEquals(MemberFailure.NO_SLOT, f)
    }

    @Test
    fun `kota kimlik ve json ayrilir`() {
        assertEquals(MemberFailure.QUOTA, MemberFailure.classify("HTTP 429 rate limit"))
        assertEquals(MemberFailure.AUTH, MemberFailure.classify("401 Unauthorized"))
        assertEquals(MemberFailure.BAD_JSON, MemberFailure.classify("JSON bulunamadı"))
        assertEquals(MemberFailure.TIMEOUT, MemberFailure.classify("timed out after 180s"))
        assertEquals(MemberFailure.NO_MODEL, MemberFailure.classify("no usable model for \"A\""))
    }

    @Test
    fun `bilinmeyen hata uydurulmaz`() {
        val f = MemberFailure.classify("beklenmedik bir şey oldu")
        assertEquals(MemberFailure.UNKNOWN, f)
        assertTrue(f.label.isNotBlank())
        assertTrue(f.advice.isNotBlank())
    }

    @Test
    fun `bos gerekce bos cevap sayilir`() {
        assertEquals(MemberFailure.EMPTY, MemberFailure.classify(""))
        assertEquals(MemberFailure.EMPTY, MemberFailure.classify(null))
    }

    @Test
    fun `satir hem sinif hem ham gerekceyi tasir`() {
        val line = MemberFailure.line("HTTP 429 rate limit")
        assertTrue(line.startsWith("kota:"))
        assertTrue("ham gerekçe kaybolmamalı: kullanıcı sağlayıcıya bakacak", line.contains("429"))
    }
}
