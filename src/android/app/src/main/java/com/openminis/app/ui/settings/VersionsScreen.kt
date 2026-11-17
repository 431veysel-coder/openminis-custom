package com.openminis.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Restore
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.BuildConfig
import com.openminis.app.R
import com.openminis.app.data.UpdateChecker
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.MinisSmallOutlinedButton
import kotlinx.coroutines.launch

/**
 * [MINIS-OZEL 11.1] Settings › About › Versions — every published build, and a
 * way to move between them.
 *
 * ## What "going back" means here, and why it is not a downgrade install
 *
 * Android refuses to install a `versionCode` lower than the one already on the
 * device (`INSTALL_FAILED_VERSION_DOWNGRADE`) — not a permission, the OS's
 * definition of an update. So this screen does NOT pretend to downgrade. Going
 * back is one of two real things:
 *
 *  1. **A recovery build** (the default): the old CODE re-published under a
 *     HIGHER number. Installing it is an ordinary update, works on every
 *     device, and leaves data alone. The running code is the older, working
 *     one — which is what the user actually asked for.
 *  2. **A direct downgrade** (optional, needs Shizuku): the old APK installed
 *     with `pm install -d`. Faster, no rebuild, but it can open a database
 *     written by a newer schema. Offered as the secondary option with that
 *     risk stated, never as the default.
 *
 * ## Why the recovery build is not triggered from here
 *
 * It is a CI job: this app has no GitHub write credential, and embedding one to
 * save a tap would put a repo-wide token inside an APK. The screen opens the
 * workflow page instead — one tap on "Run workflow" there, and the build
 * appears in this list a few minutes later, installable like any update.
 *
 * ## Conflicts
 *
 * The list is the release feed itself, so two builds can never claim one
 * version: the publish gate rejects a reused `versionCode`, and a recovery
 * build always allocates a new one (`-rbN`). "Conflicting versions" is a CI
 * invariant, not something this screen has to police.
 */
@Composable
fun VersionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var releases by remember { mutableStateOf<List<UpdateChecker.CheckResult.UpdateAvailable>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<UpdateChecker.CheckResult.UpdateAvailable?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val installedCode = remember { BuildConfig.VERSION_CODE }
    val installedName = remember { BuildConfig.VERSION_NAME }

    // The updater's own feed, page by page, flattened. Reusing it means this
    // screen can never list a version the updater would not install.
    LaunchedEffect(Unit) {
        loading = true
        error = null
        val collected = ArrayList<UpdateChecker.CheckResult.UpdateAvailable>()
        var pageError: String? = null
        for (page in 1..3) {
            when (val r = UpdateChecker.checkPage(context, page)) {
                is UpdateChecker.PageResult.Page -> collected += r.releases
                UpdateChecker.PageResult.End -> break
                is UpdateChecker.PageResult.Failed -> {
                    pageError = r.message
                    break
                }
            }
        }
        // Newest first by versionCode, so "one below the installed one" is
        // literally the next row down.
        releases = collected.distinctBy { it.versionCode }
            .sortedByDescending { it.versionCode ?: 0 }
        loading = false
        if (collected.isEmpty()) error = pageError
    }

    fun openWorkflow() {
        val url = "https://github.com/${BuildConfig.RELEASE_REPO}/actions/workflows/rollback.yml"
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            AppLogger.warning("Versions", "could not open $url: ${it.javaClass.simpleName}")
            message = context.getString(R.string.versions_open_failed, url)
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.versions_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.versions_installed_header),
            footer = stringResource(R.string.versions_installed_footer),
        ) {
            SettingsCardBlock {
                Text(
                    text = "$installedName  ($installedCode)",
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.versions_all_header),
            footer = stringResource(R.string.versions_all_footer),
        ) {
            if (loading) {
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.versions_loading),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            error?.let {
                SettingsCardBlock {
                    Text(
                        text = stringResource(R.string.versions_load_failed, it),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
            releases.forEachIndexed { index, rel ->
                val code = rel.versionCode ?: 0
                val isInstalled = code == installedCode
                val isNewer = code > installedCode
                SettingsRow(
                    title = rel.versionName + if (isInstalled) "  •" else "",
                    subtitle = buildString {
                        append(code)
                        append(" · ")
                        append("%.1f MB".format(java.util.Locale.US, rel.apkSizeBytes / 1_048_576.0))
                        if (isInstalled) {
                            append(" · ")
                            append(context.getString(R.string.versions_badge_installed))
                        }
                    },
                    icon = if (isNewer) Icons.Outlined.ArrowUpward else Icons.Outlined.History,
                    iconColor = when {
                        isInstalled -> Color(0xFF34C759)
                        isNewer -> Color(0xFF30B0C7)
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    },
                    onClick = null,
                    showDivider = false,
                )
                // One row of actions per version: upgrade is a tap, going back
                // opens the recovery path. A disabled-looking row for an older
                // build would read as "not possible", which is the opposite of
                // what this screen is for.
                SettingsCardBlock {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isInstalled) {
                            Text(
                                text = stringResource(R.string.versions_current),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        } else if (isNewer) {
                            MinisSmallButton(
                                enabled = busy == null,
                                onClick = { pending = rel },
                            ) {
                                Text(
                                    if (busy == rel.versionName) {
                                        stringResource(R.string.versions_downloading)
                                    } else {
                                        stringResource(R.string.versions_install)
                                    },
                                )
                            }
                        } else {
                            MinisSmallOutlinedButton(
                                enabled = busy == null,
                                onClick = { pending = rel },
                            ) {
                                Text(stringResource(R.string.versions_return_to))
                            }
                        }
                    }
                }
                if (index < releases.lastIndex) Spacer(Modifier.height(4.dp))
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
            header = stringResource(R.string.versions_recovery_header),
            footer = stringResource(R.string.versions_recovery_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.versions_recovery_open),
                subtitle = stringResource(R.string.versions_recovery_open_subtitle),
                icon = Icons.Outlined.Restore,
                iconColor = Color(0xFFFF9F0A),
                onClick = { openWorkflow() },
                showDivider = false,
            )
        }
    }

    // ── confirmations ──────────────────────────────────────────────────────

    pending?.let { target ->
        val targetCode = target.versionCode ?: 0
        val isNewer = targetCode > installedCode
        MinisAlertDialog(
            onDismissRequest = { pending = null },
            title = stringResource(
                if (isNewer) R.string.versions_confirm_title else R.string.versions_confirm_back_title,
            ),
            text = if (isNewer) {
                stringResource(R.string.versions_confirm_message, target.versionName)
            } else {
                // The honest sentence: Android will not install this, and what
                // happens instead is old code under a new number.
                stringResource(R.string.versions_confirm_back_message, target.versionName)
            },
            confirmText = stringResource(
                if (isNewer) R.string.versions_install else R.string.versions_recovery_open_short,
            ),
            onConfirm = {
                pending = null
                if (isNewer) {
                    scope.launch {
                        busy = target.versionName
                        val res = UpdateChecker.download(
                            context = context,
                            url = target.apkUrl,
                            versionName = target.versionName,
                            versionCode = target.versionCode,
                            expectedSha256 = target.sha256,
                        )
                        busy = null
                        when (res) {
                            is UpdateChecker.DownloadResult.Success -> {
                                if (UpdateChecker.canInstall(context)) {
                                    UpdateChecker.installApk(context, res.file)
                                } else {
                                    UpdateChecker.openInstallPermissionSettings(context)
                                    message = context.getString(R.string.versions_need_install_permission)
                                }
                            }
                            is UpdateChecker.DownloadResult.Error -> message = res.message
                        }
                    }
                } else {
                    openWorkflow()
                }
            },
        )
    }
}
