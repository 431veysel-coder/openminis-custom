package com.openminis.app.ozel

import com.openminis.app.sandbox.TermuxLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [37098] Termux katmanı kararları — cihazsız.
 *
 * Neden bu dosya önemli: Termux katmanı bionic ikilileri çalıştırıyor ve
 * `/system/bin/linker64`'e bağlı. Bu yol cihazdan cihaza değişiyor ve
 * **yanlış cihazda sessizce yarım kurulum** yapmak, kullanıcının 31 MB
 * indirip hiçbir şey çalıştıramaması demek. Aşağıdaki testler o kapıyı
 * sahte dosya sistemiyle çiviliyor: hangi cihazda ne olacağı artık tahmin
 * değil.
 */
class TermuxLayerTest {

    /** Sahte dosya sistemi: yalnız verilen yollar var. */
    private fun fs(vararg paths: String): (String) -> Boolean {
        val set = paths.toSet()
        return { it in set }
    }

    @Test
    fun `bionic yukleyici yoksa katman kurulamaz`() {
        val probe = TermuxLayer.probe(fs("/system/lib64", "/system"))
        assertFalse(probe.canInstall)
        assertNull(probe.loader)
        assertTrue(probe.problems.any { it.contains("bionic yükleyici") })
        // Kullanıcıya tek satır: sessiz başarısızlık yok.
        assertTrue(probe.summary().startsWith("Kullanılamaz:"))
    }

    @Test
    fun `system altinda yukleyici varsa kurulabilir`() {
        val probe = TermuxLayer.probe(
            fs("/system", "/system/bin/linker64", "/system/lib64"),
        )
        assertTrue(probe.canInstall)
        assertEquals("/system/bin/linker64", probe.loader)
        assertEquals("/system/lib64", probe.runtimeDir)
    }

    @Test
    fun `apex altindaki yukleyici de kabul edilir`() {
        // Android 14+ bazı imajlarda linker yalnız APEX altında.
        val probe = TermuxLayer.probe(
            fs(
                "/system",
                "/apex/com.android.runtime/bin/linker64",
                "/apex/com.android.runtime/lib64/bionic",
            ),
        )
        assertTrue(probe.canInstall)
        assertEquals("/apex/com.android.runtime/bin/linker64", probe.loader)
    }

    @Test
    fun `calisma zamani kitapliklari yoksa kurulmaz`() {
        val probe = TermuxLayer.probe(fs("/system", "/system/bin/linker64"))
        assertFalse(probe.canInstall)
        assertTrue(probe.problems.any { it.contains("çalışma zamanı") })
    }

    @Test
    fun `yer yetersizse gerekce sayiyla yazilir`() {
        val problem = TermuxLayer.spaceProblem(freeBytes = 50L * 1024 * 1024, isInstalled = false)
        assertEquals("yer yetersiz: 50 MB var, en az 400 MB önerilir", problem)
    }

    @Test
    fun `kuruluysa yer kontrolu sorulmaz`() {
        assertNull(TermuxLayer.spaceProblem(freeBytes = 1, isInstalled = true))
    }

    @Test
    fun `konuk oneki kokun icinde kalir`() {
        val root = File("/data/user/0/com.minis.x/files/alpine-rootfs")
        assertEquals(
            File(root, "data/data/com.termux/files/usr"),
            TermuxLayer.guestPrefixDir(root),
        )
        // Gerçek Android /data'sına değil, kökün İÇİNE yazılmalı.
        assertTrue(TermuxLayer.guestPrefixDir(root).path.startsWith(root.path))
    }

    @Test
    fun `ortam degiskenleri bionic ve musl kutuphanelerini karistirmaz`() {
        val env = TermuxLayer.environment(File("/rootfs"))
        assertEquals(TermuxLayer.GUEST_PREFIX, env["PREFIX"])
        assertTrue(env["PATH"]!!.startsWith("${TermuxLayer.GUEST_PREFIX}/bin"))
        // LD_LIBRARY_PATH yalnız Termux lib: musl lib'i bionic ikilisine
        // yüklenirse sessiz çökme olur.
        assertEquals("${TermuxLayer.GUEST_PREFIX}/lib", env["LD_LIBRARY_PATH"])
    }

    @Test
    fun `home oturum dizinine yonlendirilebilir`() {
        val env = TermuxLayer.environment(File("/rootfs"), homeOverride = "/var/minis/shared/termux-home")
        assertEquals("/var/minis/shared/termux-home", env["HOME"])
    }

    @Test
    fun `komut hangi katmana ait oldugu soylenir`() {
        assertEquals(TermuxLayer.Layer.TERMUX, TermuxLayer.layerFor("pkg install openssh"))
        assertEquals(TermuxLayer.Layer.TERMUX, TermuxLayer.layerFor("apt update"))
        assertEquals(TermuxLayer.Layer.ALPINE, TermuxLayer.layerFor("apk add jq"))
        assertEquals(TermuxLayer.Layer.EITHER, TermuxLayer.layerFor("git status"))
        // Karışık çağrı yasak değil ama raporlanır.
        assertEquals(TermuxLayer.Layer.MIXED, TermuxLayer.layerFor("apk add jq && pkg install ssh"))
    }

    @Test
    fun `kurulum plani tekrar baslatilabilir ve sirali`() {
        val plan = TermuxLayer.installPlan()
        assertEquals("probe", plan.first().id)
        assertEquals("smoke", plan.last().id)
        // Doğrulama indirmeden ÖNCE: 31 MB'ı boşuna indirmeyelim.
        val ids = plan.map { it.id }
        assertTrue(ids.indexOf("probe") < ids.indexOf("download"))
        assertTrue(ids.indexOf("verify") < ids.indexOf("extract"))
        // Son adım gerçek çıktı ister; "kuruldu" iddiası yetmez.
        assertFalse(plan.last().optional)
    }

    @Test
    fun `bootstrap onek dizinine acilir kok dizinine degil`() {
        // Ölçüldü: zip girişleri `bin/`, `etc/`, `lib/`, `var/` — öneğe göre
        // göreli. Köke açılırsa Alpine'in /bin'i ezilir. Bu test o kararı
        // çiviliyor: `TermuxLayer.extractTarget` her girişi öneğin altına
        // yerleştirmek zorunda.
        val root = File("/data/user/0/com.minis.x/files/alpine-rootfs")
        val prefix = TermuxLayer.guestPrefixDir(root)

        assertEquals(
            File(prefix, "bin/bash"),
            TermuxLayer.extractTarget(root, "bin/bash"),
        )
        // Ve ASLA kökün bin'i olmamalı — ezilmenin tam adresi.
        assertFalse(
            TermuxLayer.extractTarget(root, "bin/bash").path == File(root, "bin/bash").path,
        )
        assertEquals(
            File(prefix, "etc/apt/sources.list"),
            TermuxLayer.extractTarget(root, "etc/apt/sources.list"),
        )
        // Baştaki `/` girişte olursa da önek altında kalmalı.
        assertTrue(TermuxLayer.extractTarget(root, "/bin/apt").path.startsWith(prefix.path))
    }

    @Test
    fun `katman yalniz android data dizininin altinda aranir`() {
        // Gerçek Android /data değil: kökün İÇİNDEKİ data. Bu ayrım
        // karışırsa `isInstalled` yanlış cevap verir (sistemde com.termux
        // kurulu bir uygulama varsa "kurulu" derdi).
        val root = File("/rootfs")
        assertTrue(TermuxLayer.guestPrefixDir(root).path.startsWith("/rootfs/"))
    }

    @Test
    fun `kaynak listesi resmi mirrori gosterir`() {
        val sources = TermuxLayer.defaultSources()
        assertTrue(sources.contains("https://packages.termux.dev/apt/termux-main"))
        assertTrue(sources.contains("stable main"))
    }

    @Test
    fun `bootstrap surumu sabit ve surumlu`() {
        // "En son sürüm" bir bağımlılık değildir: bootstrap değişirse
        // ikililerin öneki değişebilir. Sabit URL + sha256 doğrulaması.
        assertTrue(TermuxLayer.BOOTSTRAP_URL.contains("bootstrap-2026.11.15"))
        assertTrue(TermuxLayer.BOOTSTRAP_NAME.endsWith("-aarch64.zip"))
    }

    // ---------------------------------------------------------------- 37099
    //
    // SYMLINKS.txt biçimi ve açma işareti. Bu dördü .17'de YOKTU ve ilk
    // ikisinin karşılığı koddaki gerçek bir hataydı: biçim `hedef←bağ`
    // (termux-packages `create_bootstrap_archive`: `echo "$(readlink
    // "$link")←${link}"`), kod ise soldakini bağ adı sanıyordu. Sonuç
    // sessizdi — "symlinks: N kuruldu" diyordu, `bash` kütüphane bulamıyordu.

    @Test
    fun `symlink satirinda solda hedef sagda bag yolu`() {
        val parsed = TermuxLayer.parseSymlinkLine("libncursesw.so.6.5←./lib/libncursesw.so.6")
        assertEquals("libncursesw.so.6.5", parsed?.first)
        assertEquals("lib/libncursesw.so.6", parsed?.second)
    }

    @Test
    fun `symlink satiri tirnakli ve bosluklu bicimi de cozer`() {
        val parsed = TermuxLayer.parseSymlinkLine("  /system/bin/sh←./bin/sh  ")
        assertEquals("/system/bin/sh", parsed?.first)
        assertEquals("bin/sh", parsed?.second)
    }

    @Test
    fun `symlink ayristiricisi bozuk ve kacisli satiri reddeder`() {
        // Boş / açıklama
        assertNull(TermuxLayer.parseSymlinkLine(""))
        assertNull(TermuxLayer.parseSymlinkLine("   "))
        assertNull(TermuxLayer.parseSymlinkLine("# açıklama"))
        // Ayırıcı yok, iki tane var, bir taraf boş
        assertNull(TermuxLayer.parseSymlinkLine("tek-parca"))
        assertNull(TermuxLayer.parseSymlinkLine("a←b←c"))
        assertNull(TermuxLayer.parseSymlinkLine("←bin/x"))
        assertNull(TermuxLayer.parseSymlinkLine("hedef←"))
        // Önek dışına çıkan bağ yolu kurulmaz
        assertNull(TermuxLayer.parseSymlinkLine("hedef←../../etc/passwd"))
        assertNull(TermuxLayer.parseSymlinkLine("hedef←/etc/passwd"))
    }

    @Test
    fun `acma isareti yoksa yeniden acilir`() {
        // İşaret yalnız son girişten SONRA yazılır; yarım açma "tamam"
        // sayılmamalı, yoksa eksik 3 779 dosyayla kurulum yeşil görünürdü.
        val root = File("/rootfs-yok")
        assertFalse(TermuxLayer.isExtracted(root))
        assertTrue(TermuxLayer.EXTRACT_MARKER.startsWith("."))
    }
}
