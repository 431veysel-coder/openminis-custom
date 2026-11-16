package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.TranslationPacks
import com.openminis.app.data.TranslationPacks.Pack
import com.openminis.app.ui.components.SectionTextField
import kotlinx.coroutines.launch

/**
 * [MINIS-OZEL] Settings › Translation › Packs.
 *
 * Same shape as the Toolchains screen on purpose: a list of things that can be
 * downloaded, a size, an install and a remove. What is being installed differs
 * (a few kilobytes of terminology rather than a few hundred megabytes of
 * compiler), but the mental model a user needs is identical, and inventing a
 * second one for the same verb would be a cost with no benefit.
 *
 * Nothing here spends money: a pack is a JSON file. The cost it affects is the
 * one already visible on the Translation screen — every translation is a
 * second model request — and a pack does not add a request, it only changes
 * what the existing one is told.
 */
@Composable
fun TranslationPacksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(TranslationPacks.installed(context)) }
    var activeId by remember { mutableStateOf(TranslationPacks.activeId(context)) }
    var catalog by remember { mutableStateOf<List<TranslationPacks.CatalogEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }

    fun refresh() {
        installed = TranslationPacks.installed(context)
        activeId = TranslationPacks.activeId(context)
    }

    val sizeKb = TranslationPacks.installedSizeBytes(context) / 1024L

    SettingsScaffold(
        title = stringResource(R.string.translation_packs_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.translation_packs_installed),
            footer = stringResource(R.string.translation_packs_footer, sizeKb),
        ) {
            if (installed.isEmpty()) {
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.translation_packs_none),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
            installed.forEachIndexed { index, pack ->
                PackRow(
                    pack = pack,
                    active = pack.id == activeId,
                    showDivider = index < installed.lastIndex,
                    onActivate = {
                        TranslationPacks.setActive(
                            context,
                            if (pack.id == activeId) null else pack.id,
                        )
                        refresh()
                        message = null
                    },
                    onRemove = {
                        TranslationPacks.remove(context, pack.id)
                        refresh()
                        message = context.getString(R.string.translation_packs_removed, pack.name)
                    },
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.translation_packs_catalog),
            footer = stringResource(R.string.translation_packs_catalog_footer),
        ) {
            SettingsCardBlock {
                OutlinedButton(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val res = TranslationPacks.catalog()
                            busy = false
                            res.onSuccess {
                                catalog = it
                                if (it.isEmpty()) {
                                    message = context.getString(R.string.translation_packs_empty_catalog)
                                }
                            }.onFailure {
                                message = context.getString(
                                    R.string.translation_packs_catalog_failed,
                                    it.message ?: it.javaClass.simpleName,
                                )
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Outlined.CloudDownload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (busy) {
                            stringResource(R.string.translation_packs_loading)
                        } else {
                            stringResource(R.string.translation_packs_refresh)
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            catalog.forEachIndexed { index, entry ->
                val already = installed.any { it.id == entry.pack.id }
                PackRow(
                    pack = entry.pack,
                    active = false,
                    installedLabel = already,
                    showDivider = index < catalog.lastIndex,
                    onActivate = {
                        if (!already) {
                            busy = true
                            message = null
                            scope.launch {
                                val res = TranslationPacks.installFromUrl(context, entry.url)
                                busy = false
                                res.onSuccess { p ->
                                    refresh()
                                    message = context.getString(
                                        R.string.translation_packs_installed_ok,
                                        p.name,
                                    )
                                }.onFailure {
                                    message = it.message ?: it.javaClass.simpleName
                                }
                            }
                        }
                    },
                    onRemove = null,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.translation_packs_import),
            footer = stringResource(R.string.translation_packs_import_footer),
        ) {
            SettingsCardBlock {
                SectionTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "https://…/pack.json",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            val res = TranslationPacks.installFromUrl(context, url.trim())
                            busy = false
                            res.onSuccess { p ->
                                refresh()
                                url = ""
                                message = context.getString(R.string.translation_packs_installed_ok, p.name)
                            }.onFailure {
                                message = it.message ?: it.javaClass.simpleName
                            }
                        }
                    },
                    enabled = !busy && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Outlined.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.translation_packs_install),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        message?.let { text ->
            SettingsSection {
                SettingsCardBlock {
                    Text(
                        text = text,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PackRow(
    pack: Pack,
    active: Boolean,
    showDivider: Boolean,
    installedLabel: Boolean = false,
    onActivate: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val subtitle = buildString {
        if (pack.targetLanguage.isNotBlank()) append(pack.targetLanguage)
        if (pack.glossary.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append("${pack.glossary.size} terms")
        }
        if (pack.description.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(pack.description)
        }
        if (installedLabel) {
            if (isNotEmpty()) append(" · ")
            append("installed")
        }
    }
    SettingsRow(
        title = if (active) "${pack.name}  ✓" else pack.name,
        subtitle = subtitle.ifBlank { null },
        icon = Icons.Outlined.Language,
        iconColor = if (active) Color(0xFF34C759) else Color(0xFF30B0C7),
        onClick = onActivate,
        showDivider = showDivider,
    )
    if (onRemove != null) {
        // A second, explicit row rather than a swipe: removing a pack changes
        // every future translation of that language, so it should look like a
        // decision and not like an accident.
        SettingsRow(
            title = stringResource(R.string.translation_packs_remove),
            subtitle = null,
            icon = Icons.Outlined.DeleteOutline,
            iconColor = Color(0xFFFF3B30),
            onClick = onRemove,
            showDivider = showDivider,
        )
    }
}
