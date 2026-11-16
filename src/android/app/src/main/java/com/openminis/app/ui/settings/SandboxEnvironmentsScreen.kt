package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.SandboxEnvironment
import kotlinx.coroutines.launch

/**
 * [MINIS-OZEL] Settings › Storage › Linux environments.
 *
 * One app, two distributions, and the choice is per installation rather than
 * per build: Alpine ships with the APK because it is 3.9 MB and everything
 * depends on it; Debian is a 95 MB download that only the tasks needing glibc
 * ever touch.
 *
 * The screen states the trade explicitly, because "which Linux" is a question
 * most users have never had to answer and the wrong answer costs 350 MB.
 */
@Composable
fun SandboxEnvironmentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember { RootfsManager.getInstance(context) }

    var installed by remember { mutableStateOf(manager.installedMap()) }
    var activeId by remember { mutableStateOf(manager.activeEnvironment.id) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        installed = manager.installedMap()
        activeId = manager.activeEnvironment.id
    }

    SettingsScaffold(
        title = stringResource(R.string.sandbox_env_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.sandbox_env_section),
            footer = stringResource(R.string.sandbox_env_footer),
        ) {
            SandboxEnvironment.entries.forEachIndexed { index, env ->
                val isInstalled = installed[env] == true
                val isActive = env.id == activeId
                val working = busyId == env.id

                SettingsRow(
                    title = if (isActive) "${env.displayName}  ✓" else env.displayName,
                    subtitle = buildString {
                        append(env.purpose)
                        if (!isInstalled) {
                            if (isNotEmpty()) append(" · ")
                            append(context.getString(R.string.sandbox_env_not_installed))
                            if (!env.bundled) {
                                append(context.getString(R.string.sandbox_env_download_hint, env.extractedMb))
                            }
                        }
                    },
                    icon = Icons.Outlined.Terminal,
                    iconColor = if (isActive) Color(0xFF34C759) else Color(0xFF5E5CE6),
                    onClick = if (isInstalled && !isActive) {
                        { manager.activate(env); refresh(); message = null }
                    } else {
                        null
                    },
                    showDivider = true,
                )

                if (working) {
                    SettingsCardBlock {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (!isInstalled) {
                    SettingsCardBlock {
                        OutlinedButton(
                            onClick = {
                                busyId = env.id
                                progress = 0f
                                message = null
                                scope.launch {
                                    runCatching {
                                        manager.install(env) { f -> progress = f }
                                    }.onSuccess {
                                        refresh()
                                        busyId = null
                                        message = context.getString(
                                            R.string.sandbox_env_installed,
                                            env.displayName,
                                        )
                                    }.onFailure {
                                        busyId = null
                                        message = it.message ?: it.javaClass.simpleName
                                    }
                                }
                            },
                            enabled = busyId == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (env.bundled) {
                                    stringResource(R.string.sandbox_env_install)
                                } else {
                                    stringResource(R.string.sandbox_env_download)
                                },
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Alpine is what the app boots into and what every toolchain
                // installs into; removing it would leave a broken app, so the
                // control exists only for the environments that are optional.
                if (isInstalled && !env.bundled) {
                    SettingsCardBlock {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    runCatching { manager.resetEnvironment(env) }
                                    refresh()
                                    message = context.getString(
                                        R.string.sandbox_env_removed,
                                        env.displayName,
                                    )
                                }
                            },
                            enabled = busyId == null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = Color(0xFFFF3B30),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.sandbox_env_remove),
                                color = Color(0xFFFF3B30),
                            )
                        }
                    }
                }
            }
        }

        message?.let {
            SettingsSection {
                SettingsCardBlock {
                    Text(
                        text = it,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}
