package com.openminis.app.backup

/**
 * [OZEL 37082] Otomatik yedek **zamanlaması**.
 *
 * ## Ne ekleniyor, ne eklenmiyor
 *
 * Yedek altyapısı zaten var ve iyi: `BackupRunController` (süreç ömrü boyunca
 * yaşayan tek koşu), `BackupExporter`, `BackupHistory` (30 gün kayıt + budama),
 * `BackupForegroundService`. Eksik olan tek şey **kendiliğinden başlaması**.
 * Bu dosya o boşluğu dolduruyor ve başka hiçbir şeye dokunmuyor: karar saf,
 * uygulama yine `BackupRunController.start()` çağırıyor.
 *
 * ## Neden "her gece 03:00" değil
 *
 * Telefon gece kapalı olabilir, uçak modunda olabilir, şarjı %4 olabilir.
 * Sabit saat, kaçırılan çalışmayı telafi etmez. Bu yüzden iki eşik birlikte:
 * **zaman aralığı** (en erken N saatte bir) + **pencere** (şu saatten sonra).
 * Kaçırılan gece ertesi sabah ilk uygun anda telafi edilir.
 *
 * ## Neden "değişiklik yoksa yedekleme"
 *
 * Aynı veriyi tekrar paketlemek pil ve depolama harcar; kullanıcı bunu
 * "uygulama arka planda çalışıyor" olarak görür. Değişiklik imzası
 * (sohbet sayısı + son güncelleme zamanı) yeterli — içerik hash'i çıkarmak
 * yedeğin kendisi kadar pahalı.
 */
data class AutoBackupConfig(
    val enabled: Boolean = false,
    /** Bu saatten sonra çalışabilir (pencere başı). */
    val windowStartHour: Int = 3,
    /** İki başarılı yedek arasındaki en kısa süre. */
    val minIntervalMs: Long = 22L * 3600 * 1000,
    /** Kaç yedek saklanır; `BackupHistory` kaydından bağımsız (dosya sayısı). */
    val keepCount: Int = 5,
    val wifiOnly: Boolean = false,
    val minBatteryPercent: Int = 15,
    /** Boş alan bu eşiğin altındaysa çalışmaz. */
    val minFreeBytes: Long = 512L * 1024 * 1024,
    val rechargeOnly: Boolean = false,
)

/** Ortam — hepsi çağırandan gelir, bu sınıf ölçmez (test edilebilirlik). */
data class AutoBackupEnv(
    val batteryPercent: Int,
    val charging: Boolean,
    val onWifi: Boolean,
    val freeBytes: Long,
    val changedSinceLast: Boolean,
)

/** Var olan yedek dosyası. */
data class SnapshotRef(val name: String, val createdAtMs: Long, val bytes: Long)

object AutoBackupPolicy {

    /**
     * Şimdi çalışmalı mı? `false` ise [whyNot] sebebi söyler — sessiz atlama
     * yok: kullanıcı "neden yedek yok" diye sorduğunda cevap hazır olmalı.
     */
    fun isDue(
        config: AutoBackupConfig,
        lastSuccessMs: Long?,
        nowMs: Long,
        env: AutoBackupEnv,
        hourOfDay: Int,
    ): Boolean = whyNot(config, lastSuccessMs, nowMs, env, hourOfDay) == null

    fun whyNot(
        config: AutoBackupConfig,
        lastSuccessMs: Long?,
        nowMs: Long,
        env: AutoBackupEnv,
        hourOfDay: Int,
    ): String? {
        if (!config.enabled) return "otomatik yedek kapalı"
        if (hourOfDay < config.windowStartHour) return "pencere başlamadı (${config.windowStartHour}:00)"
        if (lastSuccessMs != null && nowMs - lastSuccessMs < config.minIntervalMs) {
            return "son yedek yakın zamanda alındı"
        }
        if (!env.changedSinceLast) return "değişiklik yok"
        if (config.wifiOnly && !env.onWifi) return "Wi-Fi bekleniyor"
        if (config.rechargeOnly && !env.charging) return "şarj bekleniyor"
        if (env.batteryPercent < config.minBatteryPercent) return "pil %${env.batteryPercent}"
        if (env.freeBytes < config.minFreeBytes) return "boş alan yetersiz"
        return null
    }

    /**
     * Budama: en yeniden [keepCount] tanesi kalır.
     *
     * En yeni korunuyor çünkü geri yükleme senaryosunda işe yarayan odur;
     * dosya adında tarih olmasına rağmen sıralama **tarihe göre** yapılır,
     * ada göre değil (kullanıcı kendi yedeğini elle kopyalayabilir).
     */
    fun pruneCandidates(existing: List<SnapshotRef>, keepCount: Int): List<SnapshotRef> {
        if (keepCount <= 0) return existing.sortedByDescending { it.createdAtMs }
        return existing.sortedByDescending { it.createdAtMs }.drop(keepCount).sortedBy { it.createdAtMs }
    }

    /** Sonraki deneme için kısa bilgi — ayarlar ekranındaki satır. */
    fun nextRunLabel(lastSuccessMs: Long?, config: AutoBackupConfig, nowMs: Long): String {
        if (!config.enabled) return "kapalı"
        val base = (lastSuccessMs ?: nowMs) + config.minIntervalMs
        return if (base <= nowMs) "bir sonraki uygun anda" else "en erken ${(base - nowMs) / 3_600_000} saat sonra"
    }
}
