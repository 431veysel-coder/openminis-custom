package com.openminis.app.agent.team

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [37128 · A1] Durdurma tutamacı.
 *
 * Neden test: `CouncilStop.CANCELLED` enum'da tanımlıydı ama **ağaçta üreten
 * yoktu** — yani "durdur" diye bir sonuç hiç oluşmuyordu. Bu kayıt defteri o
 * enum'u ilk kez üreten yolun kapısı. İptal yanlış çalışırsa kullanıcı
 * durdurduğunu sanır, koşu arkada token harcamaya devam eder.
 */
class CouncilRunRegistryTest {

    @Before fun setUp() = CouncilRunRegistry.clear()
    @After fun tearDown() = CouncilRunRegistry.clear()

    @Test
    fun kayitli_kosu_iptal_edilir_ve_haber_true_doner() {
        var cancelled = 0
        CouncilRunRegistry.register("run_1") { cancelled++ }
        assertTrue(CouncilRunRegistry.isRunning("run_1"))

        assertTrue(CouncilRunRegistry.cancel("run_1"))
        assertEquals(1, cancelled)
        // Tutamak birakildi: ikinci kez iptal etmek "durdurdu" demez.
        assertFalse(CouncilRunRegistry.isRunning("run_1"))
    }

    @Test
    fun bilinmeyen_kosu_iptali_false_doner_uydurmaz() {
        // Kosmayan bir sey icin "durdurdum" demek, UI'in yalan soylemesi olur.
        assertFalse(CouncilRunRegistry.cancel("yok"))
    }

    @Test
    fun ikinci_iptal_tekrar_cagirmaz() {
        var cancelled = 0
        CouncilRunRegistry.register("run_2") { cancelled++ }
        assertTrue(CouncilRunRegistry.cancel("run_2"))
        assertFalse(CouncilRunRegistry.cancel("run_2"))
        assertEquals(1, cancelled)
    }

    @Test
    fun forget_tutamagi_birakir_sizinti_olmaz() {
        var cancelled = 0
        CouncilRunRegistry.register("run_3") { cancelled++ }
        CouncilRunRegistry.forget("run_3")
        assertEquals(0, CouncilRunRegistry.activeCount())
        assertFalse(CouncilRunRegistry.cancel("run_3"))
        assertEquals(0, cancelled)
    }

    @Test
    fun firlatan_tutamak_kaydi_bozmaz() {
        CouncilRunRegistry.register("run_4") { throw IllegalStateException("boom") }
        // Istisna disari sizmamali: UI'in dugmesi cokmemeli.
        assertFalse(CouncilRunRegistry.cancel("run_4"))
        // Ve tutamak yine de birakilmis olmali.
        assertFalse(CouncilRunRegistry.isRunning("run_4"))
    }

    @Test
    fun bos_kimlik_kaydedilmez() {
        CouncilRunRegistry.register("") { }
        assertEquals(0, CouncilRunRegistry.activeCount())
    }

    @Test
    fun ayni_kimlik_ikinci_kayitta_ezilir() {
        var first = 0
        var second = 0
        CouncilRunRegistry.register("run_5") { first++ }
        CouncilRunRegistry.register("run_5") { second++ }
        assertEquals(1, CouncilRunRegistry.activeCount())
        CouncilRunRegistry.cancel("run_5")
        assertEquals(0, first)
        assertEquals(1, second)
    }
}
