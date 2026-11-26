package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37126] Komuta göre zaman aşımı.
 *
 * Neden test: politika `.0`'dan beri yazılıydı ama **hiçbir çağıran onu
 * sormuyordu** (ölçüldü: 0 çağrı) — `gradle build` de `ls` de 10 dakika
 * alıyordu. Bu sınıf artık `ShellExecutor`'ın varsayılanı; yanlış
 * sınıflandırma ya çalışan bir derlemeyi öldürür ya da ölü bir komutu 30
 * dakika bekletir.
 */
class ShellTimeoutPolicyTest {

    @Test
    fun derleme_komutlari_uzun_butce_alir() {
        assertEquals(
            ShellTimeoutPolicy.BUILD_TIMEOUT_MS,
            ShellTimeoutPolicy.forCommand("gradle assembleDebug"),
        )
        assertTrue(ShellTimeoutPolicy.forCommand("cargo build") > ShellTimeoutPolicy.DEFAULT_TIMEOUT_MS)
    }

    @Test
    fun kurulum_ve_ag_komutlari_kendi_sinifinda() {
        assertEquals(
            ShellTimeoutPolicy.INSTALL_TIMEOUT_MS,
            ShellTimeoutPolicy.forCommand("pip install requests"),
        )
        assertEquals(
            ShellTimeoutPolicy.NETWORK_TIMEOUT_MS,
            ShellTimeoutPolicy.forCommand("curl -s https://example.com"),
        )
    }

    @Test
    fun tarama_hizli_sayilmaz() {
        // rg / grep / find: maliyet komuta degil AGACA bagli. 60 sn vermek
        // calisan bir taramayi "hata" gibi gosterirdi.
        listOf(
            "rg -n foo .",
            "grep -r foo /",
            "find / -name x",
            "sort big.txt",
            "du -sh /var",
        ).forEach {
            assertEquals(
                it,
                ShellTimeoutPolicy.DEFAULT_TIMEOUT_MS,
                ShellTimeoutPolicy.forCommand(it),
            )
        }
    }

    @Test
    fun gercekten_hizli_komutlar_kisa_butce_alir() {
        assertEquals(ShellTimeoutPolicy.QUICK_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("ls -la"))
        assertEquals(ShellTimeoutPolicy.QUICK_TIMEOUT_MS, ShellTimeoutPolicy.forCommand("pwd"))
    }

    @Test
    fun bilinmeyen_komut_temkinli_varsayilan() {
        assertEquals(
            ShellTimeoutPolicy.DEFAULT_TIMEOUT_MS,
            ShellTimeoutPolicy.forCommand("bilinmeyen-arac --x"),
        )
        assertEquals(ShellTimeoutPolicy.DEFAULT_TIMEOUT_MS, ShellTimeoutPolicy.forCommand(""))
    }

    @Test
    fun etiket_logda_okunur() {
        assertEquals("build", ShellTimeoutPolicy.tierOf("gradle build"))
        assertEquals("quick", ShellTimeoutPolicy.tierOf("ls"))
        assertEquals("network", ShellTimeoutPolicy.tierOf("curl x"))
        assertEquals("long-running", ShellTimeoutPolicy.tierOf("npm run dev"))
        assertEquals("default", ShellTimeoutPolicy.tierOf("rg foo"))
    }
}
