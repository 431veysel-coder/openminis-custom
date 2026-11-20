package com.openminis.app.ozel

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.backup.AutoBackupConfig
import com.openminis.app.backup.AutoBackupEnv
import com.openminis.app.backup.AutoBackupPolicy
import com.openminis.app.backup.BackupCategory
import com.openminis.app.backup.BackupRunController
import com.openminis.app.data.OzelFeatures
import com.openminis.app.logging.AppLogger
import java.io.File
import java.util.Calendar

/**
 * [OZEL 37082] Otomatik yedeğin **tetiği**.
 *
 * `AutoBackupPolicy` karar veriyor (pencere, aralık, pil, alan, değişiklik);
 * bu nesne yalnız ölçülen ortamı toplayıp kararı uyguluyor. Ayrı tutulması
 * bilinçli: politika saf ve test edilebilir, tetik ise Android'e bağlı.
 *
 * ## Neden uygulama açılışında
 *
 * AlarmManager ile gece 03:00'e kurmak cazip ama Android, uygulama zorla
 * durdurulduğunda alarmları düşürür (MinisApp'te bu ders zaten yazılı). Açılış
 * kontrolü kaçırılan geceyi ertesi sabah telafi eder — politika "aralık +
 * pencere" olduğu için bu doğru davranış.
 *
 * ## Neden `try/catch` her şeyin etrafında
 *
 * Yedek, uygulamanın açılışını bekleyen bir iş değil. Bir istisna açılışı
 * düşürürse kullanıcı yedeğini değil uygulamasını kaybeder.
 */
/**
 * [OZEL 37082] Ayarlardan gelen yapılandırma.
 *
 * Şimdilik sabit + tek kullanıcı ayarı (pencere saati). Tam ayar ekranı
 * geldiğinde yalnız burası okur/yazar — politika ve tetik değişmez.
 */
object AutoBackupConfigHolder {

    private const val PREFS = "ozel_auto_backup"
    private const val KEY_HOUR = "windowStartHour"

    fun config(ctx: Context): AutoBackupConfig {
        val hour = ctx.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_HOUR, 3)
        return AutoBackupConfig(enabled = true, windowStartHour = hour)
    }

    fun setWindowHour(ctx: Context, hour: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_HOUR, hour.coerceIn(0, 23)).apply()
    }
}

object AutoBackupTrigger {

    private const val TAG = "AutoBackup"
    private const val PREFS = "ozel_auto_backup"
    private const val KEY_LAST = "lastSuccessMs"
    private const val KEY_LAST_SIG = "lastSignature"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Değişiklik imzası: sohbet dosyalarının sayısı + toplam boyut.
     *
     * İçerik hash'i çıkarmak yedeğin kendisi kadar pahalı; sayı+boyut "bir şey
     * değişti mi" sorusunu bedavaya cevaplıyor. Yanlış pozitif (bir mesaj
     * eklendi, boyut aynı kaldı) yedek alır — zararsız yön. Yanlış negatif
     * olamaz: her yeni mesaj boyutu değiştirir.
     */
    private fun signature(ctx: Context): String {
        val dir = File(ctx.filesDir, "minis-sessions")
        if (!dir.exists()) return "0:0"
        var count = 0
        var bytes = 0L
        runCatching {
            dir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    count++
                    bytes += f.length()
                }
            }
        }
        return "$count:$bytes"
    }

    private fun freeBytes(ctx: Context): Long = runCatching {
        val s = android.os.StatFs(ctx.filesDir.absolutePath)
        s.availableBlocksLong * s.blockSizeLong
    }.getOrDefault(0L)

    private fun battery(ctx: Context): Pair<Int, Boolean> = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        pct to charging
    }.getOrDefault(100 to true)

    private fun onWifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    /** Açılışta bir kez çağrılır. */
    fun maybeRun(ctx: Context) {
        runCatching {
            if (!OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.AUTO_BACKUP)) return
            val p = prefs(ctx)
            val last = p.getLong(KEY_LAST, 0L).takeIf { it > 0 }
            val now = System.currentTimeMillis()
            val hour = Calendar.getInstance().apply { timeInMillis = now }
                .get(Calendar.HOUR_OF_DAY)
            val sig = signature(ctx)
            val changed = sig != p.getString(KEY_LAST_SIG, null)
            val (pct, charging) = battery(ctx)

            val env = AutoBackupEnv(
                batteryPercent = pct,
                charging = charging,
                onWifi = onWifi(ctx),
                freeBytes = freeBytes(ctx),
                changedSinceLast = changed,
            )
            val why = AutoBackupPolicy.whyNot(
                AutoBackupConfigHolder.config(ctx), last, now, env, hour,
            )
            if (why != null) {
                AppLogger.info(TAG, "[auto-backup] not now: $why")
                return
            }
            val started = BackupRunController.start(
                ctx,
                BackupRunController.Request(
                    // Everything the user would miss. Providers/keys are part
                    // of it on purpose: restoring a backup without them gives
                    // an app that cannot answer a single message.
                    categories = BackupCategory.entries.toSet(),
                    maxFileBytes = null,
                    passphrase = null,
                ),
            )
            AppLogger.info(TAG, "[auto-backup] started=$started sig=$sig")
            if (started) p.edit().putString(KEY_LAST_SIG, sig).apply()
        }.onFailure {
            AppLogger.warning(TAG, "[auto-backup] crashed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /** Yedek koşusu başarıyla bitince çağrılır (koşu denetleyicisinden). */
    fun noteSuccess(ctx: Context) {
        runCatching {
            prefs(ctx).edit()
                .putLong(KEY_LAST, System.currentTimeMillis())
                .putString(KEY_LAST_SIG, signature(ctx))
                .apply()
        }
    }
}
