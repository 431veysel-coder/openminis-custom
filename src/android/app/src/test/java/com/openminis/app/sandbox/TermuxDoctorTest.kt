package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [H-018] Termux teşhis testleri — gerçek arıza senaryolarıyla.
 *
 * Üçü de bu cihazda **yaşandı**: yükleyici eksikliği (eski Android), yer
 * yetersizliği ve `.33`'teki apt dizini arızası (`apt-get update` geçti,
 * `install` düştü). Dördüncüsü, yarım açılmış bootstrap.
 */
class TermuxDoctorTest {

    private fun probe(ok: Boolean = true, warnings: List<String> = emptyList()) =
        TermuxLayer.Probe(
            canInstall = ok,
            loader = if (ok) "/apex/com.android.runtime/bin/linker64" else null,
            runtimeDir = if (ok) "/apex/com.android.runtime/lib64" else null,
            problems = if (ok) emptyList() else listOf("bionic yükleyici bulunamadı"),
            warnings = warnings,
        )

    private fun snap(
        probe: TermuxLayer.Probe = probe(),
        rootfsPresent: Boolean = true,
        layerInstalled: Boolean = true,
        missingAptDirs: Int = 0,
        freeBytes: Long = 4L * 1024 * 1024 * 1024,
        guestToolsOk: Boolean = true,
    ) = TermuxDoctor.Snapshot(
        probe = probe,
        rootfsPresent = rootfsPresent,
        layerInstalled = layerInstalled,
        missingAptDirs = missingAptDirs,
        freeBytes = freeBytes,
        guestToolsOk = guestToolsOk,
    )

    @Test
    fun `healthy installed layer has no blocking finding`() {
        val r = TermuxDoctor.diagnose(snap())
        assertTrue(r.usable)
        assertEquals("termux_ok_installed", r.headline?.code)
    }

    @Test
    fun `missing apt dirs is blocking even though the layer looks installed`() {
        val r = TermuxDoctor.diagnose(snap(missingAptDirs = 2))
        assertFalse(r.usable)
        assertEquals("termux_apt_dirs_missing", r.blocking.first().code)
        assertEquals("2", r.blocking.first().detail)
        assertEquals("repair_apt", TermuxDoctor.nextStep(r))
    }

    @Test
    fun `low space only matters before install`() {
        val before = TermuxDoctor.diagnose(snap(layerInstalled = false, freeBytes = 10L * 1024 * 1024))
        assertTrue(before.blocking.any { it.code == "termux_space_low" })
        // Kurulu katmanda yer azlığı engel değil: çalışan sistem şüpheli
        // gösterilmemeli.
        val after = TermuxDoctor.diagnose(snap(layerInstalled = true, freeBytes = 10L * 1024 * 1024))
        assertFalse(after.blocking.any { it.code == "termux_space_low" })
    }

    @Test
    fun `loader and missing rootfs are separate repairs, not one line`() {
        val r = TermuxDoctor.diagnose(
            snap(probe = probe(ok = false), rootfsPresent = false, layerInstalled = false),
        )
        val codes = r.blocking.map { it.code }
        assertTrue(codes.contains("termux_loader_missing"))
        assertTrue(codes.contains("termux_rootfs_missing"))
        // İlk bulgu yükleyici: onarımı en ucuz olan değil, **en temel** olan.
        assertEquals("termux_loader_missing", r.blocking.first().code)
    }

    @Test
    fun `guest tools missing is a warning, not a blocker`() {
        val r = TermuxDoctor.diagnose(snap(guestToolsOk = false))
        assertTrue(r.usable)
        assertEquals(TermuxDoctor.Severity.WARNING, r.findings.first { it.code == "termux_guest_tools_missing" }.severity)
    }

    @Test
    fun `blocking findings sort before info`() {
        val r = TermuxDoctor.diagnose(
            snap(probe = probe(warnings = listOf("bilgi")), guestToolsOk = false, missingAptDirs = 1),
        )
        val severities = r.findings.map { it.severity }
        assertEquals(severities.sortedBy { it.ordinal }, severities)
        assertEquals(TermuxDoctor.Severity.INFO, severities.last())
    }
}
