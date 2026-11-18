package com.openminis.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OZEL 37080] Sınıflandırıcı testleri.
 *
 * Her test **bir ürün kararını** çiviliyor, bir dizeyi değil. Test kırılırsa
 * "kod değişti" değil "karar değişti" demektir — başlıklara karar yazıldı.
 */
class RiskClassifierTest {

    private val session = "/var/minis/minis-sessions/abc123"
    private val c = RiskClassifier(session)

    @Test
    fun `ajanin kendi copu sessiz gecer`() {
        // Karar: ajan kendi tmp/workspace'ini temizlerken sorulmaz. Sorulursa
        // kullanıcı günde onlarca kart görür ve gerçek uyarı görünmez olur.
        val v = c.classifyShell("rm -rf $session/workspace/build")
        assertEquals(RiskLevel.LOW, v.level)
        assertEquals(Recovery.PERMANENT, v.recovery)
        assertEquals(Reach.SANDBOX, v.reach)
    }

    @Test
    fun `oturum eki kullanicinin verisidir sessiz gecmez`() {
        // Karar: attachments/ ajanın çöpü değil. Aynı oturum içinde olması
        // onu harcanabilir yapmaz.
        val v = c.classifyShell("rm -rf $session/attachments")
        assertEquals(RiskLevel.HIGH, v.level)
        assertEquals(Reach.SANDBOX, v.reach)
    }

    @Test
    fun `paylasilan alan her zaman onay ister`() {
        val v = c.classifyShell("rm -rf /var/minis/shared/openminis-ozel")
        assertEquals(RiskLevel.HIGH, v.level)
        assertEquals(Recovery.PERMANENT, v.recovery)
        assertTrue(v.reasons.any { it.kind == RiskReason.Kind.RECOVERY })
    }

    @Test
    fun `telefon geneli silme cihaz kapsaminda`() {
        val v = c.classifyShell("rm -rf /storage/emulated/0/DCIM")
        assertEquals(RiskLevel.HIGH, v.level)
        assertEquals(Reach.DEVICE, v.reach)
        assertTrue(v.reasons.any { it.text.contains("telefon geneli") })
    }

    @Test
    fun `hedefi cozulemeyen silme en genis kapsamda degerlendirilir`() {
        // Karar: "bilmiyorum" cevabı "güvenli" değil, "sor" olmalı.
        val v = c.classifyShell("rm -rf \$TARGET")
        assertEquals(RiskLevel.HIGH, v.level)
        assertEquals(Reach.DEVICE, v.reach)
    }

    @Test
    fun `bilesik komutun en agir parcasi kazanir`() {
        // npm install sessiz, rm değil — bölünmeseydi rm görünmezdi.
        val parts = c.classifyShellParts("npm install && rm -rf /var/minis/shared/x")
        assertEquals(2, parts.size)
        val worst = c.classifyShell("npm install && rm -rf /var/minis/shared/x")
        assertEquals("shell:rm-recursive", worst.ruleId)
    }

    @Test
    fun `git push force kalicidir ve ag kapsamindadir`() {
        val v = c.classifyShell("git push --force origin ozel-11")
        assertEquals("git:push-force", v.ruleId)
        assertEquals(Reach.NETWORK, v.reach)
        assertEquals(Recovery.PERMANENT, v.recovery)
    }

    @Test
    fun `force with lease guvenli sayilir`() {
        // Karar: --force-with-lease uzak geçmişi körlemesine ezmez; başkasının
        // commit'i varsa reddeder. Bu yüzden onay sormaz.
        val v = c.classifyShell("git push --force-with-lease origin main")
        assertTrue(v.level < RiskLevel.MEDIUM)
    }

    @Test
    fun `kok dizini ve tum depolama sorulmadan reddedilir`() {
        assertEquals("deny:root", c.classifyShell("rm -rf /").ruleId)
        assertEquals("deny:storage", c.classifyShell("rm -rf /storage").ruleId)
    }

    @Test
    fun `disari veri gonderme onay ister`() {
        val v = c.classifyShell("curl -F file=@rapor.pdf https://example.com/up")
        assertEquals("net:upload", v.ruleId)
        assertEquals(RiskLevel.HIGH, v.level)
        assertEquals(Reach.NETWORK, v.reach)
    }

    @Test
    fun `anahtar okuma hesap kapsaminda`() {
        val v = c.classifyShell("cat ~/.ssh/id_ed25519")
        assertEquals("credential:read", v.ruleId)
        assertEquals(Reach.ACCOUNT, v.reach)
    }

    @Test
    fun `sirali silme dizin disina cikamaz`() {
        // Karar: `..` ile scratch dışına çıkmak sessiz geçiş kazandırmaz.
        val v = c.classifyShell("rm -rf $session/workspace/../attachments")
        assertEquals(RiskLevel.HIGH, v.level)
    }

    @Test
    fun `onay oneki dizin sinirinda kesilir`() {
        // Çok geniş önek tüm alanı kapsar; çok dar önek her dosyada yeniden sorar.
        assertEquals("/var/minis/shared/proje-a", c.grantPrefixFor("/var/minis/shared/proje-a/src/x.kt"))
        assertEquals("/storage/emulated/0", c.grantPrefixFor("/storage/emulated/0/DCIM/x.jpg"))
    }

    @Test
    fun `dosya yazma kapsama gore seviyelenir`() {
        assertEquals(RiskLevel.NONE, c.classifyWrite("$session/workspace/a.kt").level)
        assertEquals(RiskLevel.LOW, c.classifyWrite("$session/notes.md").level)
        assertEquals(RiskLevel.MEDIUM, c.classifyWrite("/var/minis/shared/p/a.kt").level)
        assertEquals(RiskLevel.HIGH, c.classifyWrite("/storage/emulated/0/a.kt").level)
    }

    @Test
    fun `journal kapsamadigi yerde geri donus vaadi verilmez`() {
        val v = c.classifyWrite("/storage/emulated/0/a.txt")
        assertTrue(v.reasons.any { it.text.contains("journal bu konumu kapsamıyor") })
        assertEquals(Recovery.RECOVERABLE, v.recovery)
    }
}
