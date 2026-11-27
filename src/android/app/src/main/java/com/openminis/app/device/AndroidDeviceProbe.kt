package com.openminis.app.device

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

import com.openminis.app.diagnostics.ResourceWatermark
import java.io.File

/**
 * [37132 · Donanım] **Android'den okuyan katman** — karar vermez.
 *
 * Her okuma `runCatching` ile korunuyor: bozuk bir `/sys` dosyası ya da izin
 * yoksunluğu, **tanılama ekranını düşürmemeli**. Bu kural
 * `ResourceWatermark`'tan miras: "a diagnostic must keep working in a process
 * that is already failing to allocate, and must never be the thing that throws".
 *
 * Karar [DeviceProfile.advise] içinde, saf ve testli. Burada yalnız gerçek var.
 */
object AndroidDeviceProbe {

    fun facts(context: Context): DeviceProfile.Facts {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        var totalMb = 0
        var lowRam = false
        var availMb = -1
        runCatching {
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            totalMb = (mi.totalMem / (1024L * 1024L)).toInt()
            lowRam = am?.isLowRamDevice == true
            availMb = (mi.availMem / (1024L * 1024L)).toInt()
        }

        // Our own process — the number a leak actually moves.
        var rssMb = -1
        var threads = -1
        var maps = -1
        runCatching {
            val s = ResourceWatermark.sample()
            rssMb = (s.vmRssKb / 1024L).toInt()
            threads = s.threads
            maps = s.maps
        }

        var thermal = -1
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            thermal = pm?.currentThermalStatus ?: -1
        }

        var battery = -1
        var charging = false
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            charging = st == BatteryManager.BATTERY_STATUS_CHARGING ||
                st == BatteryManager.BATTERY_STATUS_FULL
        }

        return DeviceProfile.Facts(
            totalRamMb = totalMb,
            lowRamDevice = lowRam,
            availableRamMb = availMb,
            processRssMb = rssMb,
            threadCount = threads,
            mapCount = maps,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            cpuClustersKhz = cpuClustersKhz(),
            socModel = runCatching { Build.SOC_MODEL }.getOrDefault(""),
            socManufacturer = runCatching { Build.SOC_MANUFACTURER }.getOrDefault(""),
            abis = runCatching { Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList()),
            gpuRenderer = gpuRenderer(),
            thermalStatus = thermal,
            batteryPercent = battery,
            charging = charging,
        )
    }

    /**
     * Distinct max frequencies per cluster, high→low.
     *
     * Big.LITTLE phones expose one `cpufreq` directory per core; the *max* is
     * the cluster's rated ceiling. Grouping equal ceilings recovers the cluster
     * count without root — `/sys` is world-readable for these files.
     */
    fun cpuClustersKhz(root: File = File("/sys/devices/system/cpu")): List<Int> {
        val dirs = runCatching {
            root.listFiles { f -> f.isDirectory && f.name.matches(Regex("cpu\\d+")) }
                ?.sortedBy { it.name.removePrefix("cpu").toIntOrNull() ?: 0 }
        }.getOrNull() ?: return emptyList()
        val maxes = LinkedHashSet<Int>()
        dirs.forEach { d ->
            runCatching {
                val f = File(d, "cpufreq/cpuinfo_max_freq")
                if (f.canRead()) f.readText().trim().toIntOrNull()?.let { maxes += it }
            }
        }
        return maxes.sortedDescending()
    }

    /**
     * GPU name. `GLES20.glGetString` needs a current GL context, which we do
     * not have off-screen — so this returns "" rather than lying. A real value
     * comes from the renderer's own context on a screen that has one; that
     * hand-off is deliberately not faked here.
     */
    private fun gpuRenderer(): String {
        // [H-014] Çıplak `GLES20.glGetString` çağrısı, bağlam yokken null
        // döndürmüyor: sürücüde SIGSEGV ile süreci öldürüyordu (Ayarlar →
        // Cihaz kaynakları açılışında). `runCatching` bir sinyali yakalayamaz.
        // Tek doğru yol: kendi headless EGL bağlamımızı kurup orada okumak.
        return GpuQuery.renderer()
    }
}
