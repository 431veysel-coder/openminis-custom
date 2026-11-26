package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.device.AndroidDeviceProbe
import com.openminis.app.device.DeviceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [37132 · Donanım] **Cihazın durumu** — ölçülen, çıkarılan, bilinmeyen.
 *
 * ## Neden bu ekran
 *
 * `ResourceWatermark` thread/map/VmRSS ölçüyordu ama **yalnız loga** yazıyordu
 * (ölçüm: `JankDiag` tek tüketici). Kullanıcı "bellek doluyor mu" sorusunu
 * cevaplayamıyordu. Ölçüm vardı, gösterim yoktu — bu depoda tekrar eden sınıf.
 *
 * ## Neden üç etiket
 *
 * Gerçekten ölçülen ile SoC adından çıkarılan aynı satırda **aynı güvenle**
 * görünürse, ekran yalan söyler. NPU satırı bu yüzden her zaman `tahmini`
 * yazar; GPU'da gerçek FLOPS hiç yok, o yüzden istenmez.
 *
 * ## Neden 5 saniyede bir
 *
 * Tek okuma sızıntı göstermez; **eğim** gösterir (`.52` kararı). Ekran bunun
 * için son 12 örneği tutuyor (~1 dakika) ve eğimi hesaplıyor.
 */
@Composable
fun DeviceResourcesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var facts by remember { mutableStateOf<DeviceProfile.Facts?>(null) }
    var samples by remember { mutableStateOf<List<Int>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        // 12 örnek × 5 sn = 1 dakikalık pencere. Sabit boyut: büyüme yok.
        repeat(12) {
            val f = withContext(Dispatchers.IO) { AndroidDeviceProbe.facts(context) }
            facts = f
            samples = (samples + f.processRssMb).takeLast(12)
            delay(5_000)
        }
    }

    val f = facts
    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSection(
            header = stringResource(R.string.dev_res_header),
            footer = stringResource(R.string.dev_res_footer),
        ) {
            if (f == null) {
                SettingsRow(
                    title = stringResource(R.string.dev_res_loading),
                    showDivider = false,
                )
            } else {
                // --- ÖLÇÜLEN ---
                SettingsRow(
                    title = stringResource(R.string.dev_res_ram),
                    subtitle = "${f.totalRamMb} MB · " +
                        stringResource(R.string.dev_res_free, f.availableRamMb),
                )
                SettingsRow(
                    title = stringResource(R.string.dev_res_process),
                    subtitle = stringResource(
                        R.string.dev_res_process_value,
                        f.processRssMb, f.threadCount, f.mapCount,
                    ),
                )
                SettingsRow(
                    title = stringResource(R.string.dev_res_cpu),
                    subtitle = cpuLine(f),
                )
                SettingsRow(
                    title = stringResource(R.string.dev_res_thermal),
                    subtitle = DeviceProfile.thermalWord(f.thermalStatus) +
                        " · " + stringResource(R.string.dev_res_battery, f.batteryPercent) +
                        if (f.charging) " · " + stringResource(R.string.dev_res_charging) else "",
                )
                SettingsRow(
                    title = stringResource(R.string.dev_res_gpu),
                    // Boşsa "ölçülmedi" — uydurma yok. GL bağlamı olmadan
                    // glGetString null döner ve biz onu boş bıraktık.
                    subtitle = f.gpuRenderer.ifBlank {
                        stringResource(R.string.dev_res_gpu_unknown)
                    },
                )
                SettingsRow(
                    title = stringResource(R.string.dev_res_soc),
                    subtitle = listOf(f.socManufacturer, f.socModel)
                        .filter { it.isNotBlank() }.joinToString(" ")
                        .ifBlank { stringResource(R.string.dev_res_unknown) },
                )

                // --- ÇIKARILAN (etiketli) ---
                val npu = DeviceProfile.npu(f)
                SettingsRow(
                    title = stringResource(R.string.dev_res_npu),
                    subtitle = if (npu.known) {
                        stringResource(R.string.dev_res_inferred, npu.value!!)
                    } else {
                        stringResource(R.string.dev_res_unknown)
                    },
                    showDivider = false,
                )
            }
        }

        // --- SIZINTI EĞİLİMİ ---
        val leak = DeviceProfile.looksLikeLeak(samples)
        SettingsSection(
            header = stringResource(R.string.dev_res_leak_header),
            footer = if (leak) stringResource(R.string.dev_res_leak_warn) else null,
        ) {
            SettingsRow(
                title = if (samples.isEmpty()) {
                    stringResource(R.string.dev_res_leak_collecting)
                } else {
                    stringResource(
                        R.string.dev_res_leak_values,
                        samples.first(),
                        samples.last(),
                        samples.size,
                    )
                },
                subtitle = if (leak) {
                    stringResource(R.string.dev_res_leak_growing)
                } else if (samples.size >= 4) {
                    stringResource(R.string.dev_res_leak_stable)
                } else {
                    null
                },
                titleColor = if (leak) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
                showDivider = false,
            )
        }

        Text(
            text = stringResource(R.string.dev_res_npu_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp),
        )
        androidx.compose.material3.TextButton(
            onClick = { scope.launch { reload++ } },
            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
        ) {
            Text(stringResource(R.string.dev_res_refresh))
        }
    }
}

/** "8 çekirdek · 2800/2400/1800 MHz" — kümeler gerçek `/sys` değerlerinden. */
private fun cpuLine(f: DeviceProfile.Facts): String {
    val cores = f.cpuCores.toString() + " çekirdek"
    if (f.cpuClustersKhz.isEmpty()) return cores
    val mhz = f.cpuClustersKhz.joinToString("/") { (it / 1000).toString() }
    return "$cores · $mhz MHz"
}
