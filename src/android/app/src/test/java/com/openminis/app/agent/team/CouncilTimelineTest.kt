package com.openminis.app.agent.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37131 · Ofis] Zaman çizgisi.
 *
 * Neden test: bu özelliğin iki riski var — (a) damgasız olayı uydurma zamanla
 * yazmak, (b) sınırsız büyüyüp bellek sızıntısı olmak. İkisi de burada
 * çivileniyor.
 */
class CouncilTimelineTest {

    private fun state() = CouncilTimeline.State()

    @Test
    fun damgasiz_olay_YAZILMAZ_uydurma_zaman_yok() {
        // atMs = 0 -> yazilmaz. Yanlis saat, eksik satirdan kotudur:
        // eksiklik gorunur, yanlis zaman gorunmez.
        val e = CouncilEvent.RoundStarted(1, 3)
        assertEquals(0L, e.atMs)
        assertNull(CouncilTimeline.toLine(e))
        assertEquals(0, CouncilTimeline.append(state(), e).lines.size)
    }

    @Test
    fun damgali_olay_satira_donusur() {
        val e = CouncilEvent.Positioned(member = "Ayşe", claim = "Derleme geçiyor", absent = false)
        e.stamp(1_700_000_000_000L)
        val line = CouncilTimeline.toLine(e)!!
        assertEquals("Ayşe", line.who)
        assertEquals("position", line.kind)
        assertEquals(1_700_000_000_000L, line.atMs)
        assertTrue(line.detail.contains("Derleme"))
    }

    @Test
    fun katilmayan_uye_ayri_tur() {
        val e = CouncilEvent.Positioned(member = "Mehmet", claim = "", absent = true)
        e.stamp(5L)
        assertEquals("absent", CouncilTimeline.toLine(e)!!.kind)
    }

    @Test
    fun kuyruk_SINIRLI_ve_kirpma_gorunur() {
        var s = state()
        repeat(CouncilTimeline.MAX_LINES + 25) { i ->
            val e = CouncilEvent.RoundStarted(i, 1)
            e.stamp(1_000L + i)
            s = CouncilTimeline.append(s, e)
        }
        assertEquals(CouncilTimeline.MAX_LINES, s.lines.size)
        // Sessiz kirpma YOK: kac satirin dustugu yaziliyor.
        assertEquals(25, s.dropped)
        assertTrue(s.truncated)
        // En eski dustu, en yeni duruyor.
        assertEquals("24", s.lines.first().detail)
    }

    @Test
    fun sinir_altinda_truncated_false() {
        val e = CouncilEvent.RoundStarted(1, 1); e.stamp(1L)
        val s = CouncilTimeline.append(state(), e)
        assertFalse(s.truncated)
        assertEquals(0, s.dropped)
    }

    @Test
    fun uye_basina_sayim_kosu_duzeyi_olaylari_saymaz() {
        var s = state()
        listOf("Ayşe", "Ayşe", "Mehmet").forEach { who ->
            val e = CouncilEvent.Positioned(who, "x", absent = false); e.stamp(1L)
            s = CouncilTimeline.append(s, e)
        }
        val round = CouncilEvent.RoundStarted(1, 1); round.stamp(1L)
        s = CouncilTimeline.append(s, round)

        val per = CouncilTimeline.perMember(s)
        assertEquals(2, per["Ayşe"])
        assertEquals(1, per["Mehmet"])
        // Kosu duzeyi olay (who="") kimseye yazilmaz — yoksa toplamlar yalan.
        assertEquals(3, per.values.sum())
    }

    @Test
    fun tur_ve_filtre_listesi_ilk_gorunum_sirasini_korur() {
        var s = state()
        listOf(
            CouncilEvent.Started("t", listOf("A"), 2),
            CouncilEvent.RoundStarted(1, 1),
            CouncilEvent.Synthesized(500),
        ).forEach { it.stamp(7L); s = CouncilTimeline.append(s, it) }
        assertEquals(listOf("started", "round_started", "synthesized"), CouncilTimeline.kinds(s))
        assertEquals(1, CouncilTimeline.ofKind(s, "synthesized").size)
    }
}
