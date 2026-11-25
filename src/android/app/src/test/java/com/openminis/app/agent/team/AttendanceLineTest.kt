package com.openminis.app.agent.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37123] "Kaç üye cevap verdi / kim düştü" ve "kaç iş sırada" satırları.
 *
 * Neden test: bu satırlar **sessiz bir arızayı görünür** yapmak için var.
 * Yanlış kurulurlarsa sessizlik geri gelir; fazla konuşurlarsa gerçek eksilme
 * satırlar arasında kaybolur.
 */
class AttendanceLineTest {

    @Test
    fun herkes_katildiysa_satir_yazilmaz() {
        // Her koşuda "5/5 katıldı" yazmak, gerçek eksilme olduğunda satırın
        // fark edilmemesine yol açar.
        assertNull(AttendanceLine.line(total = 5, answered = 5, absent = emptyList()))
    }

    @Test
    fun eksik_katilim_ve_dusenlerin_gerekcesi_yazilir() {
        val line = AttendanceLine.line(
            total = 5,
            answered = 2,
            absent = listOf("Mehmet" to "kota: rate limited", "Can" to "auth: 401"),
        )
        assertTrue(line != null)
        assertTrue(line!!.startsWith("2/5 üye cevap verdi"))
        assertTrue(line.contains("Mehmet"))
        assertTrue(line.contains("kota"))
        assertTrue(line.contains("Can"))
    }

    @Test
    fun gerekcesiz_dusen_icin_uydurma_yapilmaz() {
        val line = AttendanceLine.line(3, 2, listOf("Zeynep" to "   "))
        assertTrue(line != null)
        assertTrue("ad yazılmalı", line!!.contains("Zeynep"))
        assertFalse(line.contains("—"))
    }

    @Test
    fun sifir_uyede_satir_yok() {
        assertNull(AttendanceLine.line(0, 0, emptyList()))
        assertNull(AttendanceLine.line(-1, 0, emptyList()))
    }

    @Test
    fun kuyruk_uclu_ayrimi_sinirin_altina_inmez() {
        // 3 çalışıyor, 2 bekliyor, sınır 3 → hepsi "başlıyor" değil: sıfır boş
        // yer var, ikisi de sırada.
        val c = AttendanceLine.split(running = 3, pending = 2, limit = 3)
        assertEquals(3, c.running)
        assertEquals(0, c.starting)
        assertEquals(2, c.waiting)
    }

    @Test
    fun kuyruk_hesabi_negatif_uretemez() {
        listOf(
            Triple(5, 0, 3) to (0 to 0),
            Triple(0, 5, 3) to (3 to 2),
            Triple(2, 1, 3) to (1 to 0),
            Triple(-2, -3, 3) to (0 to 0),
        ).forEach { (input, expected) ->
            val (r, p, l) = input
            val c = AttendanceLine.split(r, p, l)
            assertEquals("running $r/$p/$l", r.coerceAtLeast(0), c.running)
            assertEquals("starting $r/$p/$l", expected.first, c.starting)
            assertEquals("waiting $r/$p/$l", expected.second, c.waiting)
        }
    }

    @Test
    fun sifir_sayida_kuyruk_satiri_yok() {
        assertNull(AttendanceLine.queue(0, 0, 0))
        assertNull(AttendanceLine.queue(-1, 0, 0))
    }

    @Test
    fun kuyruk_satiri_turkce_ve_sayilari_tasir() {
        val line = AttendanceLine.queue(2, 1, 3)
        assertTrue(line != null)
        assertTrue(line!!.contains("2 çalışıyor"))
        assertTrue(line.contains("1 başlıyor"))
        assertTrue(line.contains("3 sırada"))
    }

    @Test
    fun sinir_detayi_sadece_bekleyen_varsa_yazilir() {
        assertNull(AttendanceLine.queueDetail(0, 3))
        assertNull(AttendanceLine.queueDetail(2, 0))
        val detail = AttendanceLine.queueDetail(2, 3)
        assertTrue(detail != null)
        assertTrue(detail!!.contains("2"))
        assertTrue(detail.contains("3"))
    }
}
