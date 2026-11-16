package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.OzelFeatures
import com.openminis.app.data.OzelFeatures.Feature

/**
 * [MINIS-OZEL] Settings › Minis Özel › Features.
 *
 * One screen for every switch this fork added, so a feature that turns out to
 * be in the way can be put away without uninstalling the app or waiting for a
 * release. The rules each switch follows are documented on [OzelFeatures]:
 * hide the entry point, never delete data.
 *
 * The developer-server switch is the one exception to "off is harmless": it is
 * a local HTTP surface that can read provider keys and drive the UI, so it
 * carries its own warning line and defaults to off.
 */
@Composable
fun OzelFeaturesScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // A snapshot map rather than a value per feature: one source of truth, and
    // a new flag does not need a new `remember` line here.
    val values = remember {
        mutableStateMapOf<Feature, Boolean>().apply {
            OzelFeatures.snapshot(context).forEach { (feature, enabled) -> put(feature, enabled) }
        }
    }

    fun set(feature: Feature, enabled: Boolean) {
        values[feature] = enabled
        OzelFeatures.setEnabled(context, feature, enabled)
    }

    SettingsScaffold(
        title = stringResource(R.string.ozel_features_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.ozel_features_section_interface),
            footer = stringResource(R.string.ozel_features_interface_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_live_tasks),
                subtitle = stringResource(R.string.ozel_feature_live_tasks_subtitle),
                checked = values[Feature.LIVE_TASKS] == true,
                onCheckedChange = { set(Feature.LIVE_TASKS, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_toolchains),
                subtitle = stringResource(R.string.ozel_feature_toolchains_subtitle),
                checked = values[Feature.TOOLCHAINS] == true,
                onCheckedChange = { set(Feature.TOOLCHAINS, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_translation),
                subtitle = stringResource(R.string.ozel_feature_translation_subtitle),
                checked = values[Feature.TRANSLATION] == true,
                onCheckedChange = { set(Feature.TRANSLATION, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_background_gates),
                subtitle = stringResource(R.string.ozel_feature_background_gates_subtitle),
                checked = values[Feature.BACKGROUND_GATES] == true,
                onCheckedChange = { set(Feature.BACKGROUND_GATES, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_qs_tile),
                subtitle = stringResource(R.string.ozel_feature_qs_tile_subtitle),
                checked = values[Feature.QS_TILE] == true,
                onCheckedChange = { set(Feature.QS_TILE, it) },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.ozel_features_section_providers),
            footer = stringResource(R.string.ozel_features_provider_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_evren),
                subtitle = stringResource(R.string.ozel_feature_evren_subtitle),
                checked = values[Feature.EVREN_PRESET] == true,
                onCheckedChange = { set(Feature.EVREN_PRESET, it) },
                showDivider = false,
            )
        }

        // Only meaningful in a build that ships the server; a switch for a
        // capability this APK does not have would be a lie either way round.
        if (BuildConfig.DEV_TOOLS) {
            SettingsSection(
                header = stringResource(R.string.ozel_features_section_developer),
                footer = stringResource(R.string.ozel_feature_dev_server_footer),
            ) {
                SettingsSwitchRow(
                    title = stringResource(R.string.ozel_feature_dev_server),
                    subtitle = stringResource(R.string.ozel_feature_dev_server_subtitle),
                    checked = values[Feature.DEV_SERVER] == true,
                    onCheckedChange = { set(Feature.DEV_SERVER, it) },
                    showDivider = false,
                )
            }
        }

        SettingsSection(footer = stringResource(R.string.ozel_features_reset_footer)) {
            SettingsCardBlock {
                TextButton(
                    onClick = {
                        OzelFeatures.resetToDefaults(context)
                        values.clear()
                        OzelFeatures.snapshot(context).forEach { (f, v) -> values[f] = v }
                    },
                ) {
                    Icon(
                        Icons.Outlined.Restore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ozel_features_reset),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
