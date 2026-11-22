package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.sandbox.ToolchainManager
import kotlinx.coroutines.launch

/**
 * [MINIS-OZEL] Settings › Storage › Toolchains.
 *
 * The APK ships a bare Alpine rootfs on purpose (see [ToolchainManager]); this
 * is where the user fills it in, one toolchain at a time, without having to
 * know that `apk add gcc` is the incantation. Installed packages live in
 * `filesDir/alpine-rootfs`, so they survive app updates and are paid for once.
 */
@Composable
fun ToolchainScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val states by ToolchainManager.states.collectAsState()
    var busyGroup by remember { mutableStateOf<String?>(null) }
    var lastLine by remember { mutableStateOf("") }
    var installedBytes by remember { mutableStateOf(0L) }
    // [MINIS-OZEL] custom package install state
    var customInput by remember { mutableStateOf("") }
    var customBusy by remember { mutableStateOf<String?>(null) }
    var customLine by remember { mutableStateOf("") }
    var customResult by remember { mutableStateOf<Boolean?>(null) }
    // [MINIS-OZEL] What installPackages() actually installed. Read on entry so
    // "what did I add?" survives a restart, and re-read after every change so
    // the list is never a stale snapshot.
    var addedPackages by remember { mutableStateOf(ToolchainManager.customPackages(context)) }

    LaunchedEffect(Unit) {
        ToolchainManager.refresh(context)
        installedBytes = ToolchainManager.installedBytes(context)
    }

    val installedCount = ToolchainManager.groups.count {
        states[it.id] is ToolchainManager.InstallState.Present
    }
    val summary = stringResource(
        R.string.toolchain_summary,
        installedCount,
        ToolchainManager.groups.size,
        installedBytes / (1024L * 1024L),
    )
    // [MINIS-OZEL] What installing everything would cost. The groups look
    // small one at a time; the total is 1.4 GB and was nowhere on screen.
    val totalMb = ToolchainManager.groups.sumOf { it.sizeMb }

    SettingsScaffold(
        title = stringResource(R.string.toolchain_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.toolchain_section_installed),
            footer = stringResource(R.string.toolchain_footer),
        ) {
            SettingsRow(
                title = summary,
                subtitle = stringResource(R.string.toolchain_summary_subtitle),
                icon = Icons.Outlined.DeveloperMode,
                iconColor = Color(0xFF34C759),
                showChevron = false,
                showDivider = true,
            )
            SettingsRow(
                title = stringResource(R.string.toolchain_total_all, totalMb),
                subtitle = stringResource(R.string.toolchain_total_all_subtitle),
                icon = Icons.Outlined.Storage,
                iconColor = Color(0xFFFF9500),
                showChevron = false,
                showDivider = false,
            )
        }

        if (busyGroup != null) {
            SettingsSection(header = stringResource(R.string.toolchain_section_progress)) {
                SettingsCardBlock {
                    LinearProgressIndicator(modifier = Modifier.height(4.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = lastLine.ifBlank { stringResource(R.string.toolchain_starting) },
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.toolchain_section_available),
            footer = stringResource(R.string.toolchain_available_footer),
        ) {
            ToolchainManager.groups.forEachIndexed { index, group ->
                val state = states[group.id]
                val present = state is ToolchainManager.InstallState.Present
                val installing = state is ToolchainManager.InstallState.Installing
                val failed = state as? ToolchainManager.InstallState.Failed
                // [MINIS-OZEL] A group can be half there. Showing it as
                // "install" would offer to install what partly exists, and as
                // "installed" would claim tools the user does not have; the
                // row names what is missing instead.
                val partial = state as? ToolchainManager.InstallState.Partial
                val enabled = busyGroup == null

                val subtitle = when {
                    installing -> state?.let { (it as? ToolchainManager.InstallState.Installing)?.line }
                        ?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.toolchain_installing)
                    failed != null -> stringResource(R.string.toolchain_failed, failed.message)
                    partial != null -> partial.missing.joinToString(", ")
                    present -> stringResource(R.string.toolchain_installed)
                    state is ToolchainManager.InstallState.Unknown ->
                        stringResource(R.string.toolchain_checking)
                    else -> stringResource(R.string.toolchain_approx_size, group.sizeMb)
                }

                SettingsRow(
                    title = stringResource(group.titleRes),
                    subtitle = subtitle,
                    icon = iconFor(group.id),
                    iconColor = colorFor(group.id),
                    trailing = {
                        when {
                            installing -> Text(
                                text = "…",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            present -> androidx.compose.material3.Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF34C759),
                            )
                            partial != null -> Text(
                                text = stringResource(R.string.toolchain_partial_action),
                                fontSize = 13.sp,
                                color = Color(0xFFFF9500),
                            )
                            else -> Text(
                                text = stringResource(R.string.toolchain_install),
                                fontSize = 13.sp,
                                color = if (enabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                },
                            )
                        }
                    },
                    onClick = if (!enabled || installing) null else {
                        {
                            scope.launch {
                                busyGroup = group.id
                                lastLine = ""
                                // Partial → finish the install (apk add is
                                // idempotent); only a complete group removes.
                                if (present) {
                                    ToolchainManager.uninstall(context, group.id) { lastLine = it }
                                } else {
                                    ToolchainManager.install(context, group.id) { lastLine = it }
                                }
                                installedBytes = ToolchainManager.installedBytes(context)
                                busyGroup = null
                                lastLine = ""
                            }
                        }
                    },
                    showDivider = index < ToolchainManager.groups.size - 1,
                )
            }
        }

        // [MINIS-OZEL] Custom packages: the groups are coarse. Someone who
        // needs `jq` and `sqlite` should not pay 35 MB for the whole shell
        // group, and someone installing everything should not have to.
        SettingsSection(
            header = stringResource(R.string.toolchain_custom_header),
            footer = stringResource(R.string.toolchain_custom_footer),
        ) {
            SettingsCardBlock {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = { Text(stringResource(R.string.toolchain_custom_label), fontSize = 13.sp) },
                    placeholder = { Text("jq sqlite", fontSize = 13.sp) },
                    singleLine = true,
                    enabled = customBusy == null && busyGroup == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        enabled = customBusy == null && busyGroup == null && customInput.isNotBlank(),
                        onClick = {
                            customBusy = customInput
                            customLine = ""
                            scope.launch {
                                val packages = customInput
                                    .split(' ', ',', '\n')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                val first = packages.firstOrNull() ?: ""
                                val ok = ToolchainManager.installPackages(
                                    context = context,
                                    packages = packages,
                                    probe = first,
                                    onLine = { customLine = it },
                                )
                                customResult = ok
                                installedBytes = ToolchainManager.installedBytes(context)
                                addedPackages = ToolchainManager.customPackages(context)
                                ToolchainManager.refresh(context)
                                customBusy = null
                                if (ok) customInput = ""
                            }
                        },
                    ) {
                        Text(
                            text = stringResource(
                                if (customBusy != null) R.string.toolchain_custom_running
                                else R.string.toolchain_custom_action
                            ),
                            fontSize = 13.sp,
                        )
                    }
                }
                if (customLine.isNotBlank()) {
                    Text(
                        text = customLine,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                customResult?.let { ok ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(
                            if (ok) R.string.toolchain_custom_ok else R.string.toolchain_custom_failed
                        ),
                        fontSize = 12.sp,
                        color = if (ok) Color(0xFF34C759) else MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // [MINIS-OZEL] Packages added one by one. Before this list existed the
        // only record of a free-form install was the user's memory of what they
        // typed, and the only way back was `apk del` in a terminal.
        if (addedPackages.isNotEmpty()) {
            SettingsSection(header = stringResource(R.string.toolchain_custom_installed_header)) {
                addedPackages.forEachIndexed { index, pkg ->
                    SettingsRow(
                        title = pkg,
                        subtitle = null,
                        icon = Icons.Outlined.Build,
                        iconColor = Color(0xFF8E8E93),
                        trailing = {
                            Text(
                                text = stringResource(R.string.toolchain_custom_remove),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = if (customBusy != null || busyGroup != null) null else {
                            {
                                customBusy = pkg
                                customLine = ""
                                scope.launch {
                                    val ok = ToolchainManager.uninstallPackages(
                                        context = context,
                                        packages = listOf(pkg),
                                        onLine = { customLine = it },
                                    )
                                    customResult = ok
                                    addedPackages = ToolchainManager.customPackages(context)
                                    installedBytes = ToolchainManager.installedBytes(context)
                                    ToolchainManager.refresh(context)
                                    customBusy = null
                                }
                            }
                        },
                        showDivider = index < addedPackages.lastIndex,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

private fun iconFor(id: String): ImageVector = when (id) {
    // [37099] Termux satırı varsayılana düşüyordu ve Java'nın ikonunu
    // (DeveloperMode) alıyordu: iki ayrı araç zinciri aynı görünüyordu.
    "termux" -> Icons.Outlined.Terminal
    "c" -> Icons.Outlined.Code
    "python" -> Icons.Outlined.Pets
    "node" -> Icons.Outlined.Computer
    "go" -> Icons.Outlined.Terminal
    "rust" -> Icons.Outlined.Memory
    "java" -> Icons.Outlined.DeveloperMode
    "media" -> Icons.Outlined.Movie
    "devtools" -> Icons.Outlined.Build
    "network" -> Icons.Outlined.Wifi
    "scripting" -> Icons.Outlined.DataObject
    "android" -> Icons.Outlined.PhoneAndroid
    else -> Icons.Outlined.DeveloperMode
}

private fun colorFor(id: String): Color = when (id) {
    // [37099] Termux katmanı bionic tarafı — Alpine (mavi) değil, ayrı renk.
    "termux" -> Color(0xFF2E7D32)
    "c" -> Color(0xFF5E5CE6)
    "python" -> Color(0xFF30B0C7)
    "node" -> Color(0xFF34C759)
    "go" -> Color(0xFF00ACD7)
    "rust" -> Color(0xFFFF9500)
    "java" -> Color(0xFFE76F00)
    "media" -> Color(0xFFFF3B30)
    "devtools" -> Color(0xFF8E8E93)
    "network" -> Color(0xFF007AFF)
    "scripting" -> Color(0xFFAF52DE)
    "android" -> Color(0xFF3DDC84)
    else -> Color(0xFF8E8E93)
}
