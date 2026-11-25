package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.OzelFeatures
import com.openminis.app.data.OzelFeatures.Feature
import com.openminis.app.debug.DevServerController
import com.openminis.app.ui.components.RowLabel

/**
 * [MINIS-OZEL] Settings › Minis X › Features.
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
fun OzelFeaturesScreen(
    onBack: () -> Unit,
    // [MINIS-OZEL 11.1] The hub opens child screens rather than adding a route
    // per feature: one Settings entry, one destination, new features are rows.
    onOpenNotes: () -> Unit = {},
    // [MINIS-OZEL 11.3.1] The journal: what changed and how to take it back.
    onOpenJournal: () -> Unit = {},
    // [MINIS-OZEL 11.3.1] How much the agent may do without asking.
    onOpenScope: () -> Unit = {},
    // [37081] Where the token caps are typed.
    onOpenBudget: () -> Unit = {},
    // [37089] Record a chat and replay it.
    onOpenReplay: () -> Unit = {},
) {
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
        // [MINIS-OZEL] The developer server takes effect immediately. Applying
        // it here (not only at launch) is the whole point of the switch: a
        // user turning it off expects the port closed now.
        if (feature == Feature.DEV_SERVER) {
            DevServerController.applySwitch(context, enabled)
        }
    }

    val devServer by DevServerController.state.collectAsState()

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
                title = stringResource(R.string.ozel_feature_virtual_screen),
                subtitle = stringResource(R.string.ozel_feature_virtual_screen_subtitle),
                checked = values[Feature.VIRTUAL_SCREEN] == true,
                onCheckedChange = { set(Feature.VIRTUAL_SCREEN, it) },
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
            // [MINIS-OZEL 11.1] The Evren preset was removed: it was a second
            // place to configure the same thing, in addition to the ordinary
            // provider mechanism, and the user asked for one place. A provider
            // instance created from it earlier is an ordinary OpenAI-compatible
            // instance by now and keeps working.
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_provider_test),
                subtitle = stringResource(R.string.ozel_feature_provider_test_subtitle),
                checked = values[Feature.PROVIDER_TEST] == true,
                onCheckedChange = { set(Feature.PROVIDER_TEST, it) },
                showDivider = false,
            )
        }

        // ── Notes and vault [MINIS-OZEL 11.1] ────────────────────────────
        SettingsSection(
            header = stringResource(R.string.ozel_features_section_notes),
            footer = stringResource(R.string.ozel_features_notes_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_notes),
                subtitle = stringResource(R.string.ozel_feature_notes_subtitle),
                checked = values[Feature.NOTES] == true,
                onCheckedChange = { set(Feature.NOTES, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_vault),
                subtitle = stringResource(R.string.ozel_feature_vault_subtitle),
                checked = values[Feature.VAULT] == true,
                onCheckedChange = { set(Feature.VAULT, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_journal),
                subtitle = stringResource(R.string.ozel_feature_journal_subtitle),
                checked = values[Feature.JOURNAL] == true,
                onCheckedChange = { set(Feature.JOURNAL, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.ozel_feature_vault_read),
                subtitle = stringResource(R.string.ozel_feature_vault_read_subtitle),
                checked = values[Feature.VAULT_AGENT_READ] == true,
                onCheckedChange = { set(Feature.VAULT_AGENT_READ, it) },
            )
            SettingsRow(
                title = stringResource(R.string.ozel_notes_open),
                subtitle = stringResource(R.string.ozel_notes_open_subtitle),
                icon = Icons.Outlined.Description,
                iconColor = Color(0xFF30B0C7),
                onClick = if (values[Feature.NOTES] == true) onOpenNotes else null,
                showDivider = true,
            )
            SettingsRow(
                title = stringResource(R.string.ozel_scope_open_row),
                subtitle = stringResource(R.string.ozel_scope_open_row_subtitle),
                icon = Icons.Outlined.Shield,
                iconColor = Color(0xFF34C759),
                onClick = onOpenScope,
                showDivider = true,
            )
            SettingsRow(
                title = stringResource(R.string.ozel_journal_open),
                subtitle = stringResource(R.string.ozel_journal_open_subtitle),
                icon = Icons.Outlined.Restore,
                iconColor = Color(0xFF8E8E93),
                onClick = onOpenJournal,
                showDivider = false,
            )
        }

        // ── Station / edit mode [MINIS-OZEL 11.1] ────────────────────────
        //
        // [37121] Bu iki satır **artık yok ve olmayacak**: anahtarların kendisi
        // silindi (`OzelFeatures`), çünkü kapalı bir anahtar var olmayan bir
        // özelliği hâlâ reklam eder. `ui/station/` yazılırsa anahtar panellerle
        // birlikte geri gelir — o gün bu yorum da güncellenir.
        //
        // `CHAT_HEALTH` aynı sınıfın dördüncü örneğiydi: `.15`'te "bağlandı"
        // denildi, ölçüm 0 çağrı gösterdi; `.36`'da anahtar **ölçümle**
        // kapatıldı ve `gorunurluk.py` bu sınıfı artık kapıda yakalıyor.

        // ── Teams / council [11.5] ───────────────────────────────────────
        // The switch existed since 37060 but had NO row, so the only way to turn
        // council off was to edit preferences. `AndroidTeamRuntime` refuses a
        // council run when this is off, which makes the missing row a real
        // functional gap and not a cosmetic one: a team stuck in council mode
        // could not be rescued from the UI.
        SettingsSection(
            header = stringResource(R.string.mx_section_teams),
            footer = stringResource(R.string.mx_teams_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_council),
                subtitle = stringResource(R.string.mx_feature_council_subtitle),
                checked = values[Feature.COUNCIL] == true,
                onCheckedChange = { set(Feature.COUNCIL, it) },
                showDivider = false,
            )
        }

        // ── Safety, money, agent quality [37080–37090] ───────────────────
        SettingsSection(
            header = stringResource(R.string.mx_section_safety),
            footer = stringResource(R.string.mx_safety_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_approval_gate),
                subtitle = stringResource(R.string.mx_feature_approval_gate_subtitle),
                checked = values[Feature.APPROVAL_GATE] == true,
                onCheckedChange = { set(Feature.APPROVAL_GATE, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_budget_cap),
                subtitle = stringResource(R.string.mx_feature_budget_cap_subtitle),
                checked = values[Feature.BUDGET_CAP] == true,
                onCheckedChange = { set(Feature.BUDGET_CAP, it) },
            )
            // The caps are useless without a place to type them; the row is right
            // under the switch rather than in a submenu, because "where do I set
            // the number" is the first question the switch raises.
            SettingsRow(
                title = stringResource(R.string.mx_budget_title),
                subtitle = stringResource(R.string.mx_budget_open_subtitle),
                icon = Icons.Outlined.Shield,
                iconColor = Color(0xFF34C759),
                onClick = if (values[Feature.BUDGET_CAP] == true) onOpenBudget else null,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_auto_backup),
                subtitle = stringResource(R.string.mx_feature_auto_backup_subtitle),
                checked = values[Feature.AUTO_BACKUP] == true,
                onCheckedChange = { set(Feature.AUTO_BACKUP, it) },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.mx_section_agent),
            footer = stringResource(R.string.mx_agent_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_plan_preview),
                subtitle = stringResource(R.string.mx_feature_plan_preview_subtitle),
                checked = values[Feature.PLAN_PREVIEW] == true,
                onCheckedChange = { set(Feature.PLAN_PREVIEW, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_turn_diff),
                subtitle = stringResource(R.string.mx_feature_turn_diff_subtitle),
                checked = values[Feature.TURN_DIFF] == true,
                onCheckedChange = { set(Feature.TURN_DIFF, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_lesson_book),
                subtitle = stringResource(R.string.mx_feature_lesson_book_subtitle),
                checked = values[Feature.LESSON_BOOK] == true,
                onCheckedChange = { set(Feature.LESSON_BOOK, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_write_intent),
                subtitle = stringResource(R.string.mx_feature_write_intent_subtitle),
                checked = values[Feature.WRITE_INTENT] == true,
                onCheckedChange = { set(Feature.WRITE_INTENT, it) },
            )
            // These two change what the model SEES, and the honest default is
            // off until their effect has been measured on real conversations.
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_tool_router),
                subtitle = stringResource(R.string.mx_feature_tool_router_subtitle),
                checked = values[Feature.TOOL_ROUTER] == true,
                onCheckedChange = { set(Feature.TOOL_ROUTER, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_evidence_gate),
                subtitle = stringResource(R.string.mx_feature_evidence_gate_subtitle),
                checked = values[Feature.EVIDENCE_GATE] == true,
                onCheckedChange = { set(Feature.EVIDENCE_GATE, it) },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.mx_feature_replay),
                subtitle = stringResource(R.string.mx_feature_replay_subtitle),
                checked = values[Feature.REPLAY] == true,
                onCheckedChange = { set(Feature.REPLAY, it) },
            )
            SettingsRow(
                title = stringResource(R.string.mx_replay_title),
                subtitle = stringResource(R.string.mx_replay_open_subtitle),
                icon = Icons.Outlined.Restore,
                iconColor = Color(0xFF8E8E93),
                onClick = if (values[Feature.REPLAY] == true) onOpenReplay else null,
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
                    subtitle = if (devServer.running) {
                        stringResource(
                            R.string.ozel_feature_dev_server_running,
                            devServer.address,
                            DevServerController.TIMEOUT_MINUTES,
                        )
                    } else {
                        stringResource(R.string.ozel_feature_dev_server_subtitle)
                    },
                    checked = values[Feature.DEV_SERVER] == true,
                    onCheckedChange = { set(Feature.DEV_SERVER, it) },
                    showDivider = devServer.running,
                )
                if (devServer.running) {
                    // The token used to be reachable only through
                    // `adb shell run-as`, which a release build rejects
                    // ("package not debuggable") — so nothing outside the app
                    // could authenticate. Showing it here is what makes the
                    // surface usable at all.
                    SettingsCardBlock {
                        RowLabel(text = stringResource(R.string.ozel_feature_dev_server_token))
                        SelectionContainer {
                            Text(
                                text = devServer.token,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(
                                R.string.ozel_feature_dev_server_usage,
                                devServer.address,
                            ),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
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
