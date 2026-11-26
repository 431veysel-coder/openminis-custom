package com.openminis.app.device

/**
 * [37132 · Donanım] **Cihazın ne olduğu ve bu uygulamanın ona ne yapacağı.**
 *
 * ## Dürüstlük sınırı — bunu başa yazıyorum
 *
 * Android'de **NPU/TPU ölçen genel bir API yok.** `NNAPI` resmen kullanımdan
 * kaldırıldı (Android geliştirici belgeleri: *"NNAPI is deprecated… we expect
 * the majority of devices in the future to use the CPU backend"*). GPU gerçek
 * FLOPS değeri de okunamaz.
 *
 * Bu yüzden her alan **[Measured] / [Inferred] / [Unknown]** olarak işaretli.
 * "Snapdragon 8 Gen 2 → tahmini NPU (üretici beyanı)" doğru; "NPU: 45 TOPS"
 * yalan. Ekranda ikisi **ayrı** görünür.
 *
 * ## Neden iki parça: saf çekirdek + ölçen sarmalayıcı
 *
 * [Facts] Android'siz bir veri sınıfı: kararlar burada, testler burada.
 * [AndroidDeviceProbe] Android API'lerini okuyup [Facts] üretir: kendisi karar
 * vermez. Bu ayrım `.46`'daki `ProjectRules` dersinin aynısı — saf çekirdek
 * dil ve platform bilmez, sarmalayıcı politika bilmez.
 *
 * ## Neden kütüphane yok
 *
 * Ölçüldü: GPU/NPU/termal sorgulayan olgun açık kaynak Android kütüphanesi
 * **yok** (bulunanlar 2018'de durmuş oyuncak depolar, masaüstü araçları veya
 * MCU için). Bu katman kendimiz yazılıyor — 200-400 satır ince sarmalayıcı.
 */
object DeviceProfile {

    /** How a value came to be known. Never collapsed: the screen shows the word. */
    enum class Provenance { MEASURED, INFERRED, UNKNOWN }

    data class Value<T>(val value: T?, val provenance: Provenance) {
        val known: Boolean get() = value != null && provenance != Provenance.UNKNOWN
    }

    /** What the device is. Plain data — no Android types. */
    data class Facts(
        val totalRamMb: Int,
        val lowRamDevice: Boolean,
        /** `/proc/meminfo` MemAvailable, or -1 when unreadable. */
        val availableRamMb: Int,
        /** `Debug.MemoryInfo`/VmRSS of our own process, or -1. */
        val processRssMb: Int,
        val threadCount: Int,
        val mapCount: Int,
        val cpuCores: Int,
        /** Distinct max frequencies in kHz, high→low. Empty when unreadable. */
        val cpuClustersKhz: List<Int>,
        val socModel: String,
        val socManufacturer: String,
        val abis: List<String>,
        /** `GLES20.GL_RENDERER`, or empty. */
        val gpuRenderer: String,
        /** `PowerManager.getCurrentThermalStatus()`; -1 unknown. 0..6. */
        val thermalStatus: Int,
        val batteryPercent: Int,
        val charging: Boolean,
    )

    /** What the app should DO differently. This is the point of measuring. */
    data class Advice(
        /** Concurrent council members. */
        val waveSize: Int,
        /** Council rounds. */
        val maxRounds: Int,
        /** One line a human reads before a run starts. */
        val note: String,
        /**
         * `true` when the advice was cut by heat or memory, not by the device
         * class. The caller must SAY so — silently running fewer members than
         * the user configured reads as a bug.
         */
        val throttled: Boolean,
        val throttleReason: String,
    )

    /** Android thermal status codes that warrant cutting concurrency. */
    const val THERMAL_SEVERE = 4
    const val THERMAL_CRITICAL = 5

    /**
     * Decide. Pure: same facts → same advice, no clock, no Context.
     *
     * @param requestedWave what the user's preset asked for (0 = let us choose)
     * @param requestedRounds same, for rounds
     */
    fun advise(
        f: Facts,
        requestedWave: Int = 0,
        requestedRounds: Int = 0,
    ): Advice {
        // Base size from memory, mirroring CouncilPolicy's own table: one live
        // member is a child agent loop, measured 30–50 MB with history.
        var wave = when {
            f.lowRamDevice || f.totalRamMb in 1 until 4096 -> 2
            f.totalRamMb < 8192 -> 3
            else -> 4
        }
        var rounds = if (wave <= 2) 2 else 3
        var reason = ""

        // Heat first: five parallel model calls are five heaters. A device at
        // SEVERE will throttle the CPU anyway, so the extra members only add
        // latency and battery drain, not answers.
        if (f.thermalStatus >= THERMAL_SEVERE) {
            wave = (wave / 2).coerceAtLeast(1)
            rounds = rounds.coerceAtMost(2)
            reason = "thermal"
        }

        // Memory pressure: available RAM below the cost of one more member.
        val available = f.availableRamMb
        if (available > 0) {
            val perMemberMb = 50
            val fits = (available / perMemberMb).coerceAtLeast(1)
            if (fits < wave) {
                wave = fits
                reason = if (reason.isEmpty()) "memory" else reason + "+memory"
            }
        }

        // On battery and low: a council is a battery event, say it.
        if (!f.charging && f.batteryPercent in 0..15 && reason.isEmpty()) {
            reason = "battery"
        }

        if (requestedWave > 0) wave = minOf(wave, requestedWave)
        if (requestedRounds > 0) rounds = minOf(rounds, requestedRounds)

        return Advice(
            waveSize = wave,
            maxRounds = rounds,
            note = note(f, wave, reason),
            throttled = reason.isNotEmpty(),
            throttleReason = reason,
        )
    }

    /** One sentence, factual. No promises about speed. */
    fun note(f: Facts, wave: Int, reason: String): String = buildString {
        append("${f.totalRamMb} MB RAM")
        if (f.cpuCores > 0) append(" · ${f.cpuCores} çekirdek")
        if (f.gpuRenderer.isNotBlank()) append(" · ${f.gpuRenderer.take(28)}")
        append(" · dalga $wave")
        when (reason) {
            "thermal" -> append(" (ısınma nedeniyle düşürüldü)")
            "memory" -> append(" (boş bellek nedeniyle düşürüldü)")
            "battery" -> append(" (pil düşük)")
        }
    }

    /**
     * Leak watch: is memory growing **monotonically**?
     *
     * A single reading cannot show a leak — a phone that sits at 300 MB is
     * healthy. The signal is the slope. Returns true only when every sample is
     * ≥ the previous one AND the total growth clears [minGrowthMb]; noise in a
     * GC'd process moves both ways, so a genuine climb is easy to separate.
     */
    fun looksLikeLeak(samplesMb: List<Int>, minGrowthMb: Int = 40): Boolean {
        if (samplesMb.size < 4) return false
        for (i in 1 until samplesMb.size) {
            if (samplesMb[i] < samplesMb[i - 1]) return false
        }
        return samplesMb.last() - samplesMb.first() >= minGrowthMb
    }

    /** NPU/TPU: never measured. Inferred from SoC only, and labelled as such. */
    fun npu(f: Facts): Value<String> {
        val soc = (f.socModel + " " + f.socManufacturer).lowercase()
        if (soc.isBlank()) return Value(null, Provenance.UNKNOWN)
        val guess = when {
            "tensor" in soc -> "Google Tensor (Edge TPU sınıfı)"
            "snapdragon" in soc || "qualcomm" in soc -> "Qualcomm Hexagon (SoC beyanı)"
            "exynos" in soc -> "Samsung NPU (SoC beyanı)"
            "dimensity" in soc || "mediatek" in soc -> "MediaTek APU (SoC beyanı)"
            "kirin" in soc || "hisilicon" in soc -> "HiSilicon NPU (SoC beyanı)"
            else -> return Value(null, Provenance.UNKNOWN)
        }
        // Ölçülmedi: yalnız SoC adından çıkarıldı. Provenance bunu taşıyor.
        return Value(guess, Provenance.INFERRED)
    }

    /** Thermal status → a word. 0..6 per PowerManager; -1 unknown. */
    fun thermalWord(status: Int): String = when (status) {
        in 0..1 -> "none"
        2 -> "light"
        3 -> "moderate"
        4 -> "severe"
        5 -> "critical"
        6 -> "emergency"
        else -> "unknown"
    }
}
