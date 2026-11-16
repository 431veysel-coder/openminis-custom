package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * ## What changed in 11.1
 *
 * The screen used to be empty on a fresh install until the user pressed
 * "check the catalogue", and it stayed empty forever on any network where
 * `raw.githubusercontent.com` is blocked — which is the default state on
 * Turkish mobile networks. The packs themselves were fine; they simply were
 * not in the APK and nothing told the user why they were missing.
 *
 * Now: the packs ship inside the APK, the ones matching the device language are
 * listed first, and the remote index is only ever an ADDITION. The screen
 * therefore never shows "no pack" for a language this build knows, and the
 * catalogue refresh is a background nicety rather than a prerequisite.
 */
@Composable
fun TranslationPacksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installed by remember { mutableStateOf(TranslationPacks.installed(context)) }
    var activeId by remember { mutableStateOf(TranslationPacks.activeId(context)) }
    var catalog by remember { mutableStateOf<List<TranslationPacks.CatalogEntry>>(emptyList()) }
    var online by remember { mutableStateOf(true) }
    var catalogError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(TranslationPacks.hiddenIds(context)) }

    val deviceLanguage = remember { TranslationPacks.deviceLanguageName(context) }
    val builtIn = remember { TranslationPacks.builtIn(context) }

    fun refresh() {
        installed = TranslationPacks.installed(context)
        activeId = TranslationPacks.activeId(context)
        hidden = TranslationPacks.hiddenIds(context)
    }

    fun loadCatalog() {
        busy = true
        scope.launch {
            val result = TranslationPacks.catalog(context)
            busy = false
            catalog = result.entries
            online = result.online
            catalogError = result.error
        }
    }

    // Automatic: a pack list that needs a button to appear is a list that
    // never appears.
    LaunchedEffect(Unit) { loadCatalog() }

    val sizeKb = TranslationPacks.installedSizeBytes(context) / 1024L
    val catalogueOnly = catalog.filter { entry -> installed.none { it.id == entry.pack.id } }
    val hiddenPacks = builtIn.filter { it.id in hidden }

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
                    device = TranslationPacks.matchesDeviceLanguage(context, pack),
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

        if (hiddenPacks.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.translation_packs_hidden)) {
                hiddenPacks.forEachIndexed { index, pack ->
                    SettingsRow(
                        title = pack.name,
                        subtitle = stringResource(R.string.translation_packs_hidden_subtitle),
                        icon = Icons.Outlined.Restore,
                        iconColor = Color(0xFF30B0C7),
                        onClick = {
                            TranslationPacks.restoreBuiltIn(context, pack.id)
                            refresh()
                        },
                        showDivider = index < hiddenPacks.lastIndex,
                    )
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.translation_packs_catalog),
            footer = if (online) {
                stringResource(R.string.translation_packs_catalog_footer)
            } else {
                stringResource(
                    R.string.translation_packs_catalog_offline,
                    catalogError ?: "—",
                )
            },
        ) {
            SettingsCardBlock {
                OutlinedButton(
                    onClick = { loadCatalog() },
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
            if (catalogueOnly.isEmpty()) {
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.translation_packs_nothing_new),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
            }
            catalogueOnly.forEachIndexed { index, entry ->
                PackRow(
                    pack = entry.pack,
                    active = false,
                    device = TranslationPacks.matchesDeviceLanguage(context, entry.pack),
                    installedLabel = false,
                    showDivider = index < catalogueOnly.lastIndex,
                    onActivate = {
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
                                message = context.getString(
                                    R.string.translation_packs_installed_ok,
                                    p.name,
                                )
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

        SettingsSection(
            header = stringResource(R.string.translation_packs_device_header),
            footer = stringResource(R.string.translation_packs_device_footer, deviceLanguage),
        ) {
            SettingsCardBlock {
                Text(
                    text = stringResource(R.string.translation_packs_device_value, deviceLanguage),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                )
            }
        }
    }
}

@Composable
private fun PackRow(
    pack: Pack,
    active: Boolean,
    device: Boolean,
    showDivider: Boolean,
    installedLabel: Boolean = false,
    onActivate: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    val deviceLabel = stringResource(R.string.translation_packs_device)
    val bundledLabel = stringResource(R.string.translation_packs_bundled)
    val installedText = stringResource(R.string.translation_packs_installed_label)
    val subtitle = buildString {
        if (device) append(deviceLabel)
        if (pack.targetLanguage.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(pack.targetLanguage)
        }
        if (pack.glossary.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append("${pack.glossary.size} terms")
        }
        if (pack.builtIn) {
            if (isNotEmpty()) append(" · ")
            append(bundledLabel)
        }
        if (pack.description.isNotBlank()) {
            if (isNotEmpty()) append(" · ")
            append(pack.description)
        }
        if (installedLabel) {
            if (isNotEmpty()) append(" · ")
            append(installedText)
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
