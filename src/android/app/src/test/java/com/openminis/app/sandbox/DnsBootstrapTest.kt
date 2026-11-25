package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [37120] Konuk kökünün DNS onarımı.
 *
 * Neden test: bu kod **kullanıcının yazdığı dosyayı** değiştiriyor. Üç sessiz
 * hasar mümkün ve üçü de yalnız burada yakalanır:
 *
 *  1. Kullanıcının kendi `nameserver` satırını ezmek → o ağda internet kesilir,
 *     sebebi görünmez.
 *  2. Her açılışta blok eklemek → `resolv.conf` büyür, bir süre sonra okunamaz.
 *  3. Yarım blok (yazma sırasında süreç öldü) → ağ tamamen gider.
 */
class DnsBootstrapTest {

    private fun tmpRootfs(): File =
        Files.createTempDirectory("dns").toFile().also { File(it, "etc").mkdirs() }

    @Test
    fun bos_dosyada_auto_blok_yazar() {
        val plan = DnsBootstrap.plan("", DnsBootstrap.Mode.AUTO)
        assertTrue(plan.changed)
        assertTrue(plan.content.contains(DnsBootstrap.BEGIN))
        assertTrue(plan.content.contains(DnsBootstrap.END))
        assertTrue(plan.content.contains("nameserver 1.1.1.1"))
    }

    @Test
    fun kullanicinin_nameserverina_dokunmaz() {
        val existing = "# benim resolver'ım\nnameserver 192.168.1.1\n"
        val plan = DnsBootstrap.plan(existing, DnsBootstrap.Mode.AUTO)
        assertFalse("auto modda var olan resolver korunmalı", plan.changed)
        assertEquals(existing, plan.content)
    }

    @Test
    fun yorumdaki_nameserver_sayilmaz() {
        // Yorum satırı gerçek bir resolver değil; ona bakıp "var" demek,
        // hiç resolver olmayan dosyayı olduğu gibi bırakır demek.
        val existing = "# nameserver 10.0.0.1\n"
        val plan = DnsBootstrap.plan(existing, DnsBootstrap.Mode.AUTO)
        assertTrue(plan.changed)
        assertTrue(plan.content.contains("nameserver 1.1.1.1"))
    }

    @Test
    fun direct_modu_kullanicinin_satirini_korur_ama_blok_yazar() {
        val existing = "nameserver 10.1.2.3\n"
        val plan = DnsBootstrap.plan(existing, DnsBootstrap.Mode.DIRECT)
        assertTrue(plan.changed)
        assertTrue("kullanıcı satırı korunmalı", plan.content.contains("nameserver 10.1.2.3"))
        assertTrue(plan.content.contains("nameserver 1.1.1.1"))
    }

    @Test
    fun ikinci_calistirma_blok_cogaltmaz() {
        val first = DnsBootstrap.plan("", DnsBootstrap.Mode.DIRECT)
        val second = DnsBootstrap.plan(first.content, DnsBootstrap.Mode.DIRECT)
        assertEquals(1, Regex(DnsBootstrap.BEGIN).findAll(second.content).count())
        // DIRECT her zaman yazar (aynı içerikle) — ama blok sayısı artmaz.
        assertEquals(1, Regex(DnsBootstrap.END).findAll(second.content).count())
    }

    @Test
    fun auto_modda_yazilan_blogu_ikinci_kez_yazmaz() {
        val first = DnsBootstrap.plan("", DnsBootstrap.Mode.AUTO)
        val second = DnsBootstrap.plan(first.content, DnsBootstrap.Mode.AUTO)
        assertFalse("kendi bloğumuz 'nameserver var' sayılmalı", second.changed)
        assertEquals(first.content, second.content)
    }

    @Test
    fun fakip_modu_blogu_kaldirir_ve_kullanici_satirini_birakir() {
        val withBlock = DnsBootstrap.plan("nameserver 10.9.9.9\n", DnsBootstrap.Mode.DIRECT)
        val removed = DnsBootstrap.plan(withBlock.content, DnsBootstrap.Mode.FAKEIP)
        assertTrue(removed.changed)
        assertFalse(removed.content.contains(DnsBootstrap.BEGIN))
        assertTrue("kullanıcı satırı kalmalı", removed.content.contains("nameserver 10.9.9.9"))
    }

    @Test
    fun yarim_blok_temizlenir() {
        // END olmadan kalmış blok: süreç yazma sırasında ölmüş.
        val broken = "nameserver 10.0.0.1\n${DnsBootstrap.BEGIN}\nnameserver 1.1.1.1\n"
        val plan = DnsBootstrap.plan(broken, DnsBootstrap.Mode.DIRECT)
        assertEquals(1, Regex(DnsBootstrap.BEGIN).findAll(plan.content).count())
        assertEquals(1, Regex(DnsBootstrap.END).findAll(plan.content).count())
        assertTrue(plan.content.contains("nameserver 10.0.0.1"))
    }

    @Test
    fun bilinmeyen_mod_autoya_duser() {
        // Bozuk tercih kullanıcının resolver'ını ezmemeli.
        assertEquals(DnsBootstrap.Mode.AUTO, DnsBootstrap.Mode.parse(null))
        assertEquals(DnsBootstrap.Mode.AUTO, DnsBootstrap.Mode.parse("bozuk"))
        assertEquals(DnsBootstrap.Mode.DIRECT, DnsBootstrap.Mode.parse("direct"))
        assertEquals(DnsBootstrap.Mode.FAKEIP, DnsBootstrap.Mode.parse(" fakeip "))
    }

    @Test
    fun eksik_dizinde_dosyayi_ve_anne_dizini_yaratir() {
        val rootfs = Files.createTempDirectory("dns2").toFile()
        // etc/ yok — ensure yaratmalı.
        val plan = DnsBootstrap.ensure(rootfs, DnsBootstrap.Mode.AUTO)
        assertTrue(plan.changed)
        val file = DnsBootstrap.resolvPath(rootfs)
        assertTrue(file.isFile)
        assertTrue(file.readText().contains("nameserver"))
    }

    @Test
    fun ayni_icerik_ikinci_kez_yazilmaz_dosya_degismez() {
        val rootfs = tmpRootfs()
        DnsBootstrap.ensure(rootfs, DnsBootstrap.Mode.AUTO)
        val file = DnsBootstrap.resolvPath(rootfs)
        val before = file.lastModified()
        val second = DnsBootstrap.ensure(rootfs, DnsBootstrap.Mode.AUTO)
        assertFalse(second.changed)
        assertEquals(before, file.lastModified())
    }
}
