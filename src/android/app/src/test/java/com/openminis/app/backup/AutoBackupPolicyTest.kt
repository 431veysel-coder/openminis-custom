package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OZEL 37082] Otomatik yedek politikası testleri. */
class AutoBackupPolicyTest {

    private val now = 1_700_000_000_000L
    private val hour = now - (now % 3_600_000)

    private fun env(
        battery: Int = 80,
        charging: Boolean = true,
        wifi: Boolean = true,
        free: Long = 8L * 1024 * 1024 * 1024,
        changed: Boolean = true,
    ) = AutoBackupEnv(battery, charging, wifi, free, changed)

    @Test
    fun `kapaliysa hic calismaz`() {
        assertFalse(AutoBackupPolicy.isDue(AutoBackupConfig(enabled = false), null, now, env(), 4))
    }

    @Test
    fun `degisiklik yoksa yedeklemez`() {
        // KARAR: aynı veriyi tekrar paketlemek pil harcar ve "arka planda
        // çalışıyor" hissi verir.
        val why = AutoBackupPolicy.whyNot(
            AutoBackupConfig(enabled = true), null, now, env(changed = false), 4,
        )
        assertEquals("değişiklik yok", why)
    }

    @Test
    fun `pencere baslamadan calismaz`() {
        val why = AutoBackupPolicy.whyNot(AutoBackupConfig(enabled = true, windowStartHour = 3), null, now, env(), 2)
        assertTrue(why!!.contains("pencere başlamadı"))
    }

    @Test
    fun `son yedek yakin ise atlar`() {
        val why = AutoBackupPolicy.whyNot(
            AutoBackupConfig(enabled = true), now - 3600_000, now, env(), 4,
        )
        assertEquals("son yedek yakın zamanda alındı", why)
    }

    @Test
    fun `kosullar uygun oldugunda calisir`() {
        assertNull(AutoBackupPolicy.whyNot(AutoBackupConfig(enabled = true), now - 30 * 3600_000, now, env(), 4))
    }

    @Test
    fun `pil ve alan esikleri uygulanir`() {
        val cfg = AutoBackupConfig(enabled = true)
        assertEquals("pil %5", AutoBackupPolicy.whyNot(cfg, null, now, env(battery = 5), 4))
        assertEquals("boş alan yetersiz", AutoBackupPolicy.whyNot(cfg, null, now, env(free = 1024), 4))
    }

    @Test
    fun `wifi ve sarj kosullari istege bagli`() {
        val cfg = AutoBackupConfig(enabled = true, wifiOnly = true, rechargeOnly = true)
        assertEquals("Wi-Fi bekleniyor", AutoBackupPolicy.whyNot(cfg, null, now, env(wifi = false), 4))
        assertEquals("şarj bekleniyor", AutoBackupPolicy.whyNot(cfg, null, now, env(charging = false), 4))
    }

    @Test
    fun `budama en yeniyi korur`() {
        // KARAR: geri yükleme senaryosunda işe yarayan en yeni yedek.
        val snaps = (1..7).map { SnapshotRef("b$it.zip", createdAtMs = it * 1000L, bytes = 10) }
        val drop = AutoBackupPolicy.pruneCandidates(snaps, keepCount = 5)
        assertEquals(listOf("b1.zip", "b2.zip"), drop.map { it.name })
    }

    @Test
    fun `budama ada gore degil tarihe gore`() {
        val snaps = listOf(
            SnapshotRef("z-notes.zip", createdAtMs = 9000, bytes = 1),
            SnapshotRef("a-old.zip", createdAtMs = 1000, bytes = 1),
        )
        val drop = AutoBackupPolicy.pruneCandidates(snaps, keepCount = 1)
        assertEquals(listOf("a-old.zip"), drop.map { it.name })
    }

    @Test
    fun `etiket kullaniciya ne olacagini soyler`() {
        val label = AutoBackupPolicy.nextRunLabel(now - 3600_000, AutoBackupConfig(enabled = true), now)
        assertTrue(label.contains("saat sonra"))
        assertEquals("kapalı", AutoBackupPolicy.nextRunLabel(null, AutoBackupConfig(enabled = false), now))
    }
}
