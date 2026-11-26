package com.openminis.app.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37132 · Donanım] Karar katmanı.
 *
 * Neden test: bu sınıfın çıktısı **kullanıcının koşusunu** değiştiriyor (kaç
 * üye paralel çalışacak). Yanlış karar ya cihazı boğar ya da kullanıcının
 * kurduğu heyeti sessizce küçültür. İkincisi sessiz olursa "konsey bozuk"
 * dedirtir — bu yüzden `throttled`/`throttleReason` ayrı alanlar ve testli.
 */
class DeviceProfileTest {

    private fun facts(
        ram: Int = 8192,
        lowRam: Boolean = false,
        avail: Int = 3000,
        thermal: Int = 0,
        battery: Int = 80,
        charging: Boolean = true,
        soc: String = "SM8550",
        vendor: String = "Qualcomm",
    ) = DeviceProfile.Facts(
        totalRamMb = ram, lowRamDevice = lowRam, availableRamMb = avail,
        processRssMb = 250, threadCount = 60, mapCount = 4000,
        cpuCores = 8, cpuClustersKhz = listOf(2800000, 2400000, 1800000),
        socModel = soc, socManufacturer = vendor, abis = listOf("arm64-v8a"),
        gpuRenderer = "Adreno (TM) 740", thermalStatus = thermal,
        batteryPercent = battery, charging = charging,
    )

    @Test
    fun buyuk_cihaz_tam_dalga_alir() {
        val a = DeviceProfile.advise(facts())
        assertEquals(4, a.waveSize)
        assertEquals(3, a.maxRounds)
        assertFalse(a.throttled)
    }

    @Test
    fun kucuk_cihaz_kucuk_dalga() {
        val a = DeviceProfile.advise(facts(ram = 3072))
        assertEquals(2, a.waveSize)
        assertEquals(2, a.maxRounds)
    }

    @Test
    fun low_ram_bayragi_rami_yener() {
        // 8 GB gorunup low-ram isaretli olan cihazlar var; bayrak kazanir.
        val a = DeviceProfile.advise(facts(ram = 8192, lowRam = true))
        assertEquals(2, a.waveSize)
    }

    @Test
    fun isinma_dalgayi_dusurur_ve_SEBEBI_SOYLER() {
        val a = DeviceProfile.advise(facts(thermal = DeviceProfile.THERMAL_SEVERE))
        assertEquals(2, a.waveSize)
        assertTrue(a.throttled)
        assertEquals("thermal", a.throttleReason)
        assertEquals(2, a.maxRounds)
    }

    @Test
    fun bos_bellek_dalgayi_bogar() {
        // 120 MB bos, uye basi ~50 MB -> 2 uye sigar, 4 sigmaz.
        val a = DeviceProfile.advise(facts(avail = 120))
        assertEquals(2, a.waveSize)
        assertTrue(a.throttled)
        assertTrue(a.throttleReason.contains("memory"))
    }

    @Test
    fun isinma_ve_bellek_birlikte_sebep_birlesir() {
        val a = DeviceProfile.advise(facts(thermal = 5, avail = 60))
        assertTrue(a.throttleReason.contains("thermal"))
        assertTrue(a.throttleReason.contains("memory"))
    }

    @Test
    fun kullanici_daha_kucuk_istediyse_saygi_duyulur() {
        // Kullanici 2 istediyse 4 vermek onun kararini ezmek olur.
        val a = DeviceProfile.advise(facts(), requestedWave = 2, requestedRounds = 1)
        assertEquals(2, a.waveSize)
        assertEquals(1, a.maxRounds)
    }

    @Test
    fun pil_dusukse_sebep_yazilir_ama_dalga_kuculmez() {
        // Pil uyarisi bir bilgi; kosuyu engellemek kullanicinin karari.
        val a = DeviceProfile.advise(facts(battery = 8, charging = false))
        assertEquals(4, a.waveSize)
        assertEquals("battery", a.throttleReason)
    }

    @Test
    fun NPU_ASLA_olculmus_denmez() {
        // Android'de NPU olcen API yok. Cikarim da olsa isaretli olmali.
        val v = DeviceProfile.npu(facts())
        assertEquals(DeviceProfile.Provenance.INFERRED, v.provenance)
        val unknown = DeviceProfile.npu(facts(soc = "", vendor = ""))
        assertEquals(DeviceProfile.Provenance.UNKNOWN, unknown.provenance)
        assertNull(unknown.value)
    }

    @Test
    fun bilinmeyen_soc_cikarim_yapmaz() {
        val v = DeviceProfile.npu(facts(soc = "ZZ999", vendor = "Acme"))
        assertEquals(DeviceProfile.Provenance.UNKNOWN, v.provenance)
    }

    @Test
    fun sizinti_egim_ister_tek_olcum_yetmez() {
        // Tek okuma sizinti gostermez: 300 MB'da duran telefon sagliklidir.
        assertFalse(DeviceProfile.looksLikeLeak(listOf(300)))
        assertFalse(DeviceProfile.looksLikeLeak(listOf(300, 300, 300, 300)))
        // Dalgali sey sizinti degil (GC).
        assertFalse(DeviceProfile.looksLikeLeak(listOf(300, 320, 310, 330)))
        // Monoton tirmanis + esik -> sizinti.
        assertTrue(DeviceProfile.looksLikeLeak(listOf(300, 320, 340, 360)))
        // Tirmaniyor ama esik altinda -> henuz degil.
        assertFalse(DeviceProfile.looksLikeLeak(listOf(300, 305, 310, 315)))
    }

    @Test
    fun isi_kelimesi_insan_dilinde() {
        assertEquals("severe", DeviceProfile.thermalWord(4))
        assertEquals("none", DeviceProfile.thermalWord(0))
        assertEquals("unknown", DeviceProfile.thermalWord(-1))
    }
}
