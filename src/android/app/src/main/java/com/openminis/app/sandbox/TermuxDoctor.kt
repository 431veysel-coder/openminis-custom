package com.openminis.app.sandbox

/**
 * [H-018] **Termux katmanı teşhisi** — "neden çalışmıyor" sorusunun tek yerde
 * cevabı.
 *
 * ## Neden ayrı bir sınıf
 *
 * Bugüne kadar Termux arızaları **beş ayrı yerde** parça parça yazılıydı:
 * `TermuxLayer.probe()` yükleyiciyi soruyor, `spaceProblem()` yeri soruyor,
 * `ensureAptDirs()` dizinleri açıyor, `TermuxInstaller` sha256'yı doğruluyor,
 * `ExecutionCoordinator` taze süreç gerektiriyor. Kullanıcının gördüğü tek şey
 * "apt çalışmıyor" oluyordu; hangi katmanın düştüğünü söyleyen bir yer yoktu.
 * Bu sınıf o beş kontrolü **tek sıralı listede** toplar.
 *
 * ## Neden saf
 *
 * Girdi [Snapshot], çıktı [Finding] listesi. Dosya sistemi, `Context`, saat
 * yok — hepsi çağıranın işi. Böylece bir arıza senaryosu (yer yok + apt dizini
 * eksik + yükleyici var) JVM testinde aynen kurulabiliyor; "cihazda bir daha
 * deneyelim" turu gerekmiyor.
 *
 * ## Sıralama neden önemli
 *
 * Liste **engelleyici → uyarı → bilgi** sırasında döner. Kullanıcı ekranın
 * başında ilk gördüğü satırı okur; "yer yetersiz" ile "sha256 doğrulanmadı"
 * aynı ağırlıkta gösterilirse yanlış işi yapar. [blocking] alanı bunu
 * çağıranın karar vermesine bırakmadan söyler.
 */
object TermuxDoctor {

    /** Ölçülmüş durum — hepsi çağıran tarafından toplanır. */
    data class Snapshot(
        /** `TermuxLayer.probe()` sonucu. */
        val probe: TermuxLayer.Probe,
        /** Kök dosya sistemi (alpine-rootfs) açılmış mı. */
        val rootfsPresent: Boolean,
        /** Konuk öneki (`…/files/usr`) diskte var mı — yani katman kurulu mu. */
        val layerInstalled: Boolean,
        /** `apt install` için gereken ama **olmayan** dizin sayısı. */
        val missingAptDirs: Int,
        /** Kurulum için boş bayt (yalnız katman kurulu değilken anlamlı). */
        val freeBytes: Long,
        /** Bash/apt gibi temel araçları görebildi mi (kuruluysa). */
        val guestToolsOk: Boolean = true,
    )

    enum class Severity { BLOCKING, WARNING, INFO }

    /**
     * Tek bulgu. [code] makine anahtarı — cümle değil: çekirdek dil bilmez
     * (bu depoda bir kez ihlal edildi, `.28`). Arayüz kodu metne çevirir.
     */
    data class Finding(
        val code: String,
        val severity: Severity,
        /** Arayüzün cümleye koyacağı sayı/yol. Boş olabilir. */
        val detail: String = "",
    )

    /** Kullanıcıya tek satırlık karar: katman bugün kullanılabilir mi. */
    data class Report(
        val findings: List<Finding>,
    ) {
        val blocking: List<Finding> get() = findings.filter { it.severity == Severity.BLOCKING }
        val usable: Boolean get() = blocking.isEmpty()

        /** En önemli bulgu — ekran onu üstte gösterir. */
        val headline: Finding? get() = findings.firstOrNull()
    }

    fun diagnose(s: Snapshot): Report {
        val out = mutableListOf<Finding>()

        // 1. Yükleyici/çalışma zamanı: katman kurulabilir mi sorusunun ön koşulu.
        if (!s.probe.canInstall) {
            s.probe.problems.forEach { out += Finding("termux_loader_missing", Severity.BLOCKING, it) }
        }

        // 2. Kök dosya sistemi yoksa hiçbir şey kurulamaz — ama yükleyici
        //    eksikliğiyle aynı satıra yazılmaz: iki ayrı onarım yolu var.
        if (!s.rootfsPresent) {
            out += Finding("termux_rootfs_missing", Severity.BLOCKING)
        }

        // 3. Yer: yalnız kurulum öncesi anlamlı. Kurulu bir katmanda "yer az"
        //    demek, çalışan bir sistemi şüpheli göstermek olurdu.
        if (s.rootfsPresent && !s.layerInstalled) {
            TermuxLayer.spaceProblem(s.freeBytes, isInstalled = false)?.let {
                out += Finding("termux_space_low", Severity.BLOCKING, it)
            }
        }

        // 4. apt dizinleri: `.33`'te ölçülmüş gerçek arıza sınıfı. `apt-get
        //    update` geçer, `install` düşer — yani katman "kurulu görünürken"
        //    bozuktur. Bu yüzden BLOCKING, ve katman kuruluyken de sorulur.
        if (s.layerInstalled && s.missingAptDirs > 0) {
            out += Finding("termux_apt_dirs_missing", Severity.BLOCKING, s.missingAptDirs.toString())
        }

        // 5. Konuk araçları: katman kurulu ama `bash`/`apt` yoksa bootstrap
        //    yarım açılmıştır. Engelleyici değil çünkü yeniden kurulum
        //    çoğu zaman kendiliğinden onarır.
        if (s.layerInstalled && !s.guestToolsOk) {
            out += Finding("termux_guest_tools_missing", Severity.WARNING)
        }

        // 6. Probe uyarıları en sonda: bilgi, engel değil.
        s.probe.warnings.forEach {
            out += Finding("termux_probe_warning", Severity.INFO, it)
        }

        if (out.isEmpty()) {
            out += Finding(
                if (s.layerInstalled) "termux_ok_installed" else "termux_ok_not_installed",
                Severity.INFO,
            )
        }
        // Sıralama: BLOCKING → WARNING → INFO, kendi içinde ekleme sırası.
        return Report(out.sortedBy { it.severity.ordinal })
    }

    /**
     * Bir sonraki adım: **tek** iş. Kullanıcıya beş öneri listelemek, hiç
     * öneri vermemekle aynı sonucu verir.
     */
    fun nextStep(report: Report): String? {
        val first = report.blocking.firstOrNull() ?: return null
        return when (first.code) {
            "termux_loader_missing" -> "probe"
            "termux_rootfs_missing" -> "open_terminal"
            "termux_space_low" -> "free_space"
            "termux_apt_dirs_missing" -> "repair_apt"
            else -> null
        }
    }
}
