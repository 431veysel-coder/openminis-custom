package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DeveloperMode
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
                val enabled = busyGroup == null

                val subtitle = when {
                    installing -> state?.let { (it as? ToolchainManager.InstallState.Installing)?.line }
                        ?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.toolchain_installing)
                    failed != null -> stringResource(R.string.toolchain_failed, failed.message)
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

        Spacer(Modifier.height(24.dp))
    }
}

private fun iconFor(id: String): ImageVector = when (id) {
    "c" -> Icons.Outlined.Code
    "python" -> Icons.Outlined.Pets
    "node" -> Icons.Outlined.Computer
    "go" -> Icons.Outlined.Terminal
    "rust" -> Icons.Outlined.Memory
    "java" -> Icons.Outlined.DeveloperMode
    "media" -> Icons.Outlined.Movie
    else -> Icons.Outlined.DeveloperMode
}

private fun colorFor(id: String): Color = when (id) {
    "c" -> Color(0xFF5E5CE6)
    "python" -> Color(0xFF30B0C7)
    "node" -> Color(0xFF34C759)
    "go" -> Color(0xFF00ACD7)
    "rust" -> Color(0xFFFF9500)
    "java" -> Color(0xFFE76F00)
    "media" -> Color(0xFFFF3B30)
    else -> Color(0xFF8E8E93)
}
