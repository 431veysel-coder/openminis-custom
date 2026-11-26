package com.openminis.app.agent.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37126] Duruş matrisinin disk biçimi.
 *
 * Neden test: bu kodek olmadan koşu bittikten sonra "kim neye karşı çıktı"
 * bilgisi kayboluyordu (comparison room `live == null` olunca boşalıyordu).
 * Kodek bozulursa hata sessizdir: tablo boş çizilir, kimse fark etmez.
 */
class ClaimMatrixCodecTest {

    private fun matrix() = ClaimMatrix(
        claims = listOf(
            Claim(
                id = "C1",
                text = "Derleme geçiyor",
                stances = mapOf(
                    "Ayşe" to Stance.SUPPORT,
                    "Mehmet" to Stance.OPPOSE,
                    "Zeynep" to Stance.UNCERTAIN,
                ),
                evidence = listOf("ci/457"),
                needsTask = true,
            ),
            Claim(id = "C2", text = "Kapsam yeterli", stances = mapOf("Ayşe" to Stance.SUPPORT)),
        ),
        dropped = listOf("birleştirilemeyen cümle"),
    )

    @Test
    fun gidis_donus_her_hucreyi_korur() {
        val back = ClaimMatrixCodec.decode(ClaimMatrixCodec.encode(matrix()))
        val m = matrix()
        assertEquals(m.claims.size, back!!.claims.size)
        back!!.claims.forEachIndexed { i, c ->
            assertEquals(m.claims[i].id, c.id)
            assertEquals(m.claims[i].text, c.text)
            assertEquals(m.claims[i].stances, c.stances)
            assertEquals(m.claims[i].evidence, c.evidence)
        }
        assertEquals(listOf("ci/457"), back.claims[0].evidence)
        assertTrue(back.claims[0].needsTask)
        assertEquals(listOf("birleştirilemeyen cümle"), back.dropped)
    }

    @Test
    fun bos_matris_kaydedilir_ve_bos_doner() {
        val back = ClaimMatrixCodec.decode(ClaimMatrixCodec.encode(ClaimMatrix()))
        // Bos tablo bir gercek: "matris yok" (null) ile ayni sey DEGIL.
        assertEquals(0, back!!.claims.size)
    }

    @Test
    fun eski_kayitta_alan_yoksa_null_doner() {
        assertNull(ClaimMatrixCodec.decode(null))
        assertNull(ClaimMatrixCodec.decode(""))
        assertNull(ClaimMatrixCodec.decode("   "))
        assertNull(ClaimMatrixCodec.decode("bu json degil"))
    }

    @Test
    fun bilinmeyen_durus_atilir_kararsiz_denmez() {
        val raw = """{"v":1,"claims":[{"id":"C1","text":"x",
            "stances":{"Ayşe":"support","Mehmet":"belki"}}]}""".trimIndent()
        val m = ClaimMatrixCodec.decode(raw)!!
        assertEquals(1, m.claims.size)
        // Bozuk hucre UYDURULMAZ: Mehmet o iddiada oy vermemis sayilir.
        assertEquals(mapOf("Ayşe" to Stance.SUPPORT), m.claims[0].stances)
    }

    @Test
    fun kimliksiz_iddia_atlanir_metinsiz_kalir() {
        val raw = """{"v":1,"claims":[{"text":"kimlik yok"},{"id":"C9","text":""}]}"""
        val m = ClaimMatrixCodec.decode(raw)!!
        assertEquals(1, m.claims.size)
        assertEquals("C9", m.claims[0].id)
    }

    @Test
    fun kayittan_ekran_satirlari_sayaclari_dogru_tasir() {
        val lines = RecordedMatrix.claimLines(matrix())
        assertEquals(2, lines.size)
        assertEquals("C1", lines[0].id)
        // Sayaclar Claim'den turetilir: 1 destek, 1 karsi, 1 kararsiz.
        assertEquals(1, lines[0].support)
        assertEquals(1, lines[0].oppose)
        assertEquals(1, lines[0].uncertain)
        // Duruşlar tel biciminde ("support"), enum degil — ekran boyle okuyor.
        assertEquals("support", lines[0].stances["Ayşe"])
        assertEquals("oppose", lines[0].stances["Mehmet"])
    }

    @Test
    fun kayit_yoksa_satir_yok_ve_verdict_uydurulmaz() {
        assertEquals(0, RecordedMatrix.claimLines(null).size)
        // Karar sutunu kayitta yok; bos birakilir, uydurulmaz.
        assertEquals("", RecordedMatrix.claimLines(matrix())[0].verdict)
    }
}
