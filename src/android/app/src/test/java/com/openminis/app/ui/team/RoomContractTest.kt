package com.openminis.app.ui.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37131] Oda sözleşmesi.
 *
 * Neden test: `.22`'de bir oda boş açıldı (veri üretiliyor, tüketilmiyordu);
 * `.46`'da bir oda karar gösterdiği sanıldı (göstermiyordu). İkisinin kökü
 * aynı: oda hakkında yazılı bir iddia yok. Bu test o iddiaları çiviliyor.
 */
class RoomContractTest {

    @Test
    fun hicbir_olay_yetim_degil() {
        // Yetim olay = uretilen ama hicbir odada gosterilmeyen veri = .22 hatasi.
        assertEquals(emptyList<String>(), RoomContract.orphans())
    }

    @Test
    fun her_odanin_bir_sorusu_var() {
        RoomContract.ROOMS.forEach { r ->
            assertTrue("${r.id} sorusuz", r.question.isNotBlank())
            assertTrue("${r.id} sinirsiz", r.claimsNot.isNotBlank())
        }
    }

    @Test
    fun odalar_tekil_ve_bes_tane() {
        val ids = RoomContract.ROOMS.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
        assertEquals(5, ids.size)
    }

    @Test
    fun karsilastirma_odasi_karar_gostermedigini_SOYLER() {
        // .46'da tam bu karisiklik oldu: tablo cizildi, verdict bos kaldi,
        // kullanici tablodan karar bekledi.
        val compare = RoomContract.ROOMS.first { it.id == RoomContract.Id.COMPARE }
        assertTrue(compare.claimsNot.contains("council_not_compare"))
    }

    @Test
    fun diskten_cizilebilen_odalar_isaretli() {
        // Kayit yoksa ekran bunu soylemek zorunda; floor/relations canli veri ister.
        RoomContract.ROOMS.forEach { r ->
            if (r.id == RoomContract.Id.FLOOR || r.id == RoomContract.Id.RELATIONS) {
                assertTrue("${r.id} canli olmali", !r.fromRecord)
            } else {
                assertTrue("${r.id} kayittan cizilebilmeli", r.fromRecord)
            }
        }
    }
}
