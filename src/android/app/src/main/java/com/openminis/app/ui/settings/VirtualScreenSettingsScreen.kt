package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.OzelFeatures
import com.openminis.app.screen.VirtualScreen
import com.openminis.app.screen.VirtualScreenHost
import com.openminis.app.screen.VirtualScreenStore

/**
 * [37099] Sanal ekran paneli — **durum ekranı**, çizim yüzeyi değil.
 *
 * ## Ne yapar
 *
 * Hangi bölme açık, hangisi önde; ajanın "nerede çalışıyorum" satırı ne diyor;
 * düzeni sıfırla. Durum `VirtualScreenStore` üzerinden diske yazılır, yani
 * uygulama kapansa da düzen korunur.
 *
 * ## Ne YAPMAZ (ve neden bunu söylüyoruz)
 *
 * Bölmeleri yan yana çizmez. Çizim `SANAL-EKRAN.md` §4 P1'de ve
 * `browser/HeadlessRenderHost` (tarayıcı) + `sandbox/PersistentShell` (terminal)
 * tarafında. Burada çizim varmış gibi bir önizleme koymak, `.14`'te `STATION`
 * için kınanan hatanın aynısı olurdu: var görünen ama olmayan bir yüzey.
 * Bu ekran **düzeni** yönetir; ekranın kendisi geldiğinde aynı durumu okuyacak.
 *
 * ## Neden tek gerçek kaynak disk
 *
 * Compose state'inde ikinci bir kopya tutup diske yazmak iki kaynak üretir ve
 * biri kaçınılmaz olarak bayatlar. Bu yüzden her değişiklik `save` + yeniden
 * okuma; `remember` yalnız son okunan değeri taşır.
 */
@Composable
fun VirtualScreenSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(VirtualScreenStore.load(context)) }
    // [37119] Yakalama sonucu — sessiz kalmak "düğme çalışmıyor" dedirtir.
    var captureMsg by remember { mutableStateOf<String?>(null) }
    var featureOn by remember {
        mutableStateOf(OzelFeatures.isEnabled(context, OzelFeatures.Feature.VIRTUAL_SCREEN))
    }

    fun apply(next: VirtualScreen.State) {
        VirtualScreenStore.save(context, next)
        state = next
    }

    SettingsScaffold(
        title = stringResource(R.string.virtual_screen_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.virtual_screen_section_master),
            footer = stringResource(R.string.virtual_screen_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.virtual_screen_enabled),
                subtitle = stringResource(R.string.virtual_screen_enabled_subtitle),
                icon = Icons.Outlined.Web,
                checked = featureOn,
                onCheckedChange = {
                    featureOn = it
                    OzelFeatures.setEnabled(context, OzelFeatures.Feature.VIRTUAL_SCREEN, it)
                },
                showDivider = false,
            )
        }

        // [37119] Tek tuval: bölmeler aynı yüzeyde, gerçek yerleşimle.
        // Canlı içerik yok (durum görünümü) — alt yazı bunu söylüyor.
        if (featureOn) {
            SettingsSection(
                header = stringResource(R.string.virtual_screen_section_canvas),
                footer = stringResource(R.string.virtual_screen_canvas_footer),
            ) {
                VirtualCanvasSection(
                    state = state,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.virtual_screen_section_panes),
            footer = stringResource(R.string.virtual_screen_panes_footer),
        ) {
            VirtualScreen.Pane.entries.forEachIndexed { index, pane ->
                val visible = state.pane(pane)?.visible == true
                SettingsSwitchRow(
                    title = stringResource(paneTitle(pane)),
                    subtitle = if (state.focus == pane) {
                        stringResource(R.string.virtual_screen_focused)
                    } else {
                        null
                    },
                    icon = paneIcon(pane),
                    // Anahtar kapalıyken düzen de değiştirilemez: ajan hiçbir
                    // şey görmediği bir yüzeyin düzeniyle uğraşmak yanıltıcı.
                    enabled = featureOn,
                    checked = visible,
                    onCheckedChange = { on ->
                        apply(if (on) VirtualScreen.show(state, pane) else VirtualScreen.hide(state, pane))
                    },
                    showDivider = index < VirtualScreen.Pane.entries.lastIndex,
                )
            }
        }

        SettingsSection(header = stringResource(R.string.virtual_screen_section_state)) {
            // [37119] Kanıt düğmesi. `screen` aracının `capture` eylemiyle
            // aynı yolu kullanır (`ScreenTool.frameSource` → canvas karesi):
            // ekranda gördüğün kare ile ajanın aldığı kare ayrışmasın.
            SettingsRow(
                title = stringResource(R.string.virtual_screen_capture_now),
                subtitle = stringResource(R.string.virtual_screen_capture_now_sub),
                icon = Icons.Outlined.Refresh,
                onClick = if (featureOn) {
                    {
                        val panes = state.visiblePanes().map { it.pane }
                        val source = com.openminis.app.tools.ScreenTool.frameSource
                        val captured = panes.filter { pane ->
                            source?.frame(pane) != null
                        }
                        if (captured.isNotEmpty()) {
                            // Damga düşülsün: kare üretildi demek, ekranda
                            // "yakalama: just now" görünmesi demek.
                            VirtualScreenHost.markCaptured(context, captured)
                        }
                        // Durum diskten yeniden okunur: ekranda görünen tek
                        // gerçek kaynak disk, bellekte ikinci kopya yok.
                        state = VirtualScreenStore.load(context)
                        captureMsg = if (captured.isEmpty()) {
                            context.getString(R.string.virtual_screen_capture_failed)
                        } else {
                            context.getString(R.string.virtual_screen_capture_done, captured.size)
                        }
                    }
                } else {
                    null
                },
                showDivider = true,
            )

            // Sonuç satırı: düğmeye basıp hiçbir şey görmemek, "çalışmıyor"
            // dedirtir (bu depoda ölçülmüş sınıf: sessiz düğme).
            captureMsg?.let { msg ->
                Text(
                    text = msg,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsRow(
                title = stringResource(R.string.virtual_screen_reset),
                subtitle = stringResource(R.string.virtual_screen_reset_subtitle),
                icon = Icons.Outlined.Refresh,
                // SettingsRow'da `enabled` yok; kapalıyken tıklanabilirliği
                // `onClick = null` ile kaldırıyoruz (depodaki kalıp bu).
                onClick = if (featureOn) ({ apply(VirtualScreenStore.reset(context)) }) else null,
                showDivider = false,
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

private fun paneTitle(pane: VirtualScreen.Pane): Int = when (pane) {
    VirtualScreen.Pane.BROWSER -> R.string.virtual_screen_pane_browser
    VirtualScreen.Pane.TERMINAL -> R.string.virtual_screen_pane_terminal
    VirtualScreen.Pane.NOTES -> R.string.virtual_screen_pane_notes
}

private fun paneIcon(pane: VirtualScreen.Pane) = when (pane) {
    VirtualScreen.Pane.BROWSER -> Icons.Outlined.Web
    VirtualScreen.Pane.TERMINAL -> Icons.Outlined.Terminal
    VirtualScreen.Pane.NOTES -> Icons.Outlined.Article
}
