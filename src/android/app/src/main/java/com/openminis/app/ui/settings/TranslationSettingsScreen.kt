package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.TranslationPrefs
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.ui.chat.ThinkingTranslator

/**
 * [MINIS-OZEL] Settings › Agent Runtime › Translation.
 *
 * Everything the thinking-block translate action needs: whether it is on, what
 * language it targets, and which model pays for it. Kept in its own screen
 * rather than folded into Appearance because the model choice is a spending
 * decision, and a spending decision deserves to be visible as one.
 */
@Composable
fun TranslationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val config by app.providerRepository.config.collectAsState()

    val entries: List<ModelEntry> = remember(config) {
        config.modelEntries.filter { !it.isHidden }
    }

    var enabled by remember { mutableStateOf(TranslationPrefs.enabled(context)) }
    var targetLanguage by remember { mutableStateOf(TranslationPrefs.targetLanguage(context)) }
    var modelEntryId by remember { mutableStateOf(TranslationPrefs.modelEntryId(context)) }
    var autoTranslate by remember { mutableStateOf(TranslationPrefs.autoTranslate(context)) }
    var cacheBytes by remember { mutableStateOf(ThinkingTranslator.cacheSizeBytes(context)) }

    // Resolved OUTSIDE any lambda: stringResource is @Composable and cannot be
    // called from a `let { }` passed as an argument.
    val effectiveEntryId = remember(config, modelEntryId) {
        TranslationPrefs.effectiveModelEntryId(
            context,
            available = entries.map { it.id },
            loopEntries = config.agentLoopModelEntryIds,
        )
    }
    val effectiveModelId: String? = entries.firstOrNull { it.id == effectiveEntryId }?.baseModel?.id
    val autoSubtitle = if (effectiveModelId != null) {
        stringResource(R.string.translation_model_effective, effectiveModelId)
    } else {
        stringResource(R.string.translation_no_models)
    }
    val cacheSubtitle = stringResource(
        R.string.translation_cache_size,
        (cacheBytes / 1024L).coerceAtLeast(0L),
    )

    SettingsScaffold(
        title = stringResource(R.string.settings_translation),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.translation_section_general),
            footer = stringResource(R.string.translation_general_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.translation_enabled),
                subtitle = stringResource(R.string.translation_enabled_subtitle),
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    TranslationPrefs.setEnabled(context, it)
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.translation_auto),
                subtitle = stringResource(R.string.translation_auto_subtitle),
                checked = autoTranslate,
                enabled = enabled,
                onCheckedChange = {
                    autoTranslate = it
                    TranslationPrefs.setAutoTranslate(context, it)
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.translation_section_language),
            footer = stringResource(R.string.translation_language_footer),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.translation_target_language))
                SectionTextField(
                    value = targetLanguage,
                    onValueChange = {
                        targetLanguage = it
                        TranslationPrefs.setTargetLanguage(context, it)
                    },
                    placeholder = TranslationPrefs.resolvedTargetLanguage(context),
                    singleLine = true,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.translation_section_model),
            footer = stringResource(R.string.translation_model_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.translation_model_auto),
                subtitle = autoSubtitle,
                icon = Icons.Outlined.Memory,
                iconColor = Color(0xFF5856D6),
                trailing = {
                    if (modelEntryId.isBlank()) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                onClick = {
                    modelEntryId = ""
                    TranslationPrefs.setModelEntryId(context, "")
                },
            )
            entries.forEachIndexed { index, entry ->
                SettingsRow(
                    title = entry.baseModel.id,
                    subtitle = entry.baseModel.displayName
                        .takeIf { it.isNotBlank() && it != entry.baseModel.id },
                    icon = Icons.Outlined.Translate,
                    iconColor = Color(0xFF5E5CE6),
                    trailing = {
                        if (modelEntryId == entry.id) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    onClick = {
                        modelEntryId = entry.id
                        TranslationPrefs.setModelEntryId(context, entry.id)
                    },
                    showDivider = index < entries.size - 1,
                )
            }
        }

        SettingsSection(header = stringResource(R.string.translation_section_cache)) {
            SettingsRow(
                title = stringResource(R.string.translation_clear_cache),
                subtitle = cacheSubtitle,
                icon = Icons.Outlined.DeleteSweep,
                iconColor = Color(0xFFFF3B30),
                onClick = {
                    ThinkingTranslator.clearCache(context)
                    cacheBytes = 0L
                },
                showDivider = false,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * [MINIS-OZEL] Settings › Agent Runtime › Thinking.
 *
 * How the reasoning block behaves. The auto-expand switch is the same pref
 * Appearance owns — it is surfaced here too because "how thinking behaves" is
 * one question, and having half of it on another screen is what made it hard
 * to find.
 */
@Composable
fun ThinkingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var autoExpand by remember { mutableStateOf(autoExpandThinkingEnabled(context)) }
    var translateButton by remember { mutableStateOf(TranslationPrefs.enabled(context)) }
    var autoTranslate by remember { mutableStateOf(TranslationPrefs.autoTranslate(context)) }

    SettingsScaffold(
        title = stringResource(R.string.settings_thinking),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.thinking_section_display),
            footer = stringResource(R.string.appearance_auto_expand_thinking_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.appearance_auto_expand_thinking_title),
                checked = autoExpand,
                onCheckedChange = {
                    autoExpand = it
                    getAppearancePrefs(context).edit()
                        .putBoolean(KEY_AUTO_EXPAND_THINKING, it).apply()
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.thinking_section_translation),
            footer = stringResource(R.string.thinking_translation_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.translation_enabled),
                subtitle = stringResource(R.string.thinking_translate_button_subtitle),
                icon = Icons.Outlined.Language,
                iconColor = Color(0xFF007AFF),
                checked = translateButton,
                onCheckedChange = {
                    translateButton = it
                    TranslationPrefs.setEnabled(context, it)
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.translation_auto),
                subtitle = stringResource(R.string.thinking_auto_translate_subtitle),
                checked = autoTranslate,
                enabled = translateButton,
                onCheckedChange = {
                    autoTranslate = it
                    TranslationPrefs.setAutoTranslate(context, it)
                },
                showDivider = false,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
