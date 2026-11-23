package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Lock
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
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.ozel.GrantStorage
import com.openminis.app.policy.ApprovalScope
import com.openminis.app.policy.Grant
import com.openminis.app.policy.GrantCatalog

/**
 * [37099b] **Ayarlar › İzinler** — toplu yetki ekranı.
 *
 * ## Neden var
 *
 * Kullanıcı şikâyeti: "sürekli onay isteyip duruyorsun". Onay sistemi işlem
 * başına soruyordu ve kullanıcı 43 kuralın hangisine izin verdiğini bir yerden
 * göremiyordu. Bu ekran üç şeyi yapar:
 *
 *  1. Verilebilecek izinleri **kategori** hâlinde gösterir, kategori başına
 *     ve tümü için tek dokunuşla verir.
 *  2. Verilemeyenleri de gösterir — **neden** verilemediği yazılı. Gizlemek
 *     "yine soruyor ama neden göstermiyor" sorusunu doğururdu.
 *  3. "Hepsini geri al" tek dokunuş.
 *
 * ## Neden tek gerçek kaynak disk
 *
 * `GrantStorage` tek dosya. Compose state'inde ikinci kopya tutmak iki kaynak
 * üretir; biri bayatlar. Her değişiklik yaz + yeniden oku.
 */
@Composable
fun PermissionGrantsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var byScope by remember { mutableStateOf(GrantStorage.load(context)) }
    val now = remember(byScope) { System.currentTimeMillis() }
    val active = remember(byScope) { GrantCatalog.activeRuleIds(byScope, now) }
    // [37099d] Kullanım sayacı: izin verildi ama hiç kullanılmadıysa da bilgi.
    val uses = remember(byScope) { GrantCatalog.usageCounts(byScope, now) }
    val categories = remember { GrantCatalog.categories() }

    // [37099c] Entegrasyonlar: android-a11y-cli + android-shizuku-cli.
    // Bunlar onay defterine değil `OffloadPermissionManager`'a yazılır —
    // ayrı bir kapı (offload IPC), ayrı bir depo. İkisinin varsayılanı
    // NOT_ALLOWED: başka uygulamaları sürebildikleri için bilinçli.
    var agentLevels by remember {
        mutableStateOf(
            mapOf(
                "a11y_cli" to OffloadPermissionManager.getLevel("a11y_cli"),
                "shizuku_cli" to OffloadPermissionManager.getLevel("shizuku_cli"),
            )
        )
    }

    fun setAgentLevel(tool: String, level: OffloadPermissionManager.PermissionLevel) {
        OffloadPermissionManager.setLevel(tool, level)
        agentLevels = agentLevels + (tool to level)
    }

    fun write(next: Map<String, List<Grant>>) {
        GrantStorage.save(context, next)
        byScope = next
    }

    fun toggle(ruleId: String, on: Boolean) {
        val map = byScope.toMutableMap()
        val current = map[ApprovalScope.Global.key].orEmpty()
            .filterNot { it.ruleId == ruleId }
            .toMutableList()
        if (on) {
            val rule = com.openminis.app.policy.RiskRules.byId(ruleId) ?: return
            current += GrantCatalog.grant(rule, nowMs = System.currentTimeMillis())
        }
        map[ApprovalScope.Global.key] = current
        write(map)
    }

    SettingsScaffold(
        title = stringResource(R.string.permission_grants_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.permission_grants_all_header),
            footer = stringResource(R.string.permission_grants_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.permission_grants_all_grant),
                subtitle = stringResource(
                    R.string.permission_grants_all_grant_subtitle,
                    categories.sumOf { it.grantableItems.size },
                ),
                icon = Icons.Outlined.Lock,
                onClick = {
                    val all = categories.flatMap { it.items }
                    val grants = GrantCatalog.grantsFor(all, nowMs = System.currentTimeMillis())
                    val map = byScope.toMutableMap()
                    map[ApprovalScope.Global.key] = grants
                    write(map)
                },
            )
            SettingsRow(
                title = stringResource(R.string.permission_grants_revoke_all),
                subtitle = stringResource(R.string.permission_grants_revoke_all_subtitle),
                icon = Icons.Outlined.DeleteSweep,
                onClick = { write(GrantCatalog.revokeAll(byScope)) },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.permission_grants_integrations),
            footer = stringResource(R.string.permission_grants_integrations_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.permission_grants_integrations_all),
                subtitle = stringResource(R.string.permission_grants_integrations_all_sub),
                icon = Icons.Outlined.Lock,
                onClick = {
                    setAgentLevel("a11y_cli", OffloadPermissionManager.PermissionLevel.BYPASS)
                    setAgentLevel("shizuku_cli", OffloadPermissionManager.PermissionLevel.BYPASS)
                },
                showDivider = true,
            )
            listOf("a11y_cli" to R.string.permission_grants_a11y, "shizuku_cli" to R.string.permission_grants_shizuku)
                .forEachIndexed { index, (tool, labelRes) ->
                    SettingsSwitchRow(
                        title = stringResource(labelRes),
                        subtitle = stringResource(R.string.permission_grants_agent_level, agentLevels[tool]?.name ?: ""),
                        checked = agentLevels[tool] == OffloadPermissionManager.PermissionLevel.BYPASS,
                        onCheckedChange = { on ->
                            setAgentLevel(
                                tool,
                                if (on) OffloadPermissionManager.PermissionLevel.BYPASS
                                else OffloadPermissionManager.PermissionLevel.NOT_ALLOWED,
                            )
                        },
                        showDivider = index == 0,
                    )
                }
        }

        categories.forEach { cat ->
            SettingsSection(
                header = stringResource(catTitle(cat.id)),
                footer = stringResource(catSubtitle(cat.id)) + " · " + stringResource(
                    R.string.permission_grants_category_state,
                    cat.grantableItems.count { it.rule.id in active },
                    cat.grantableItems.size,
                ),
            ) {
                cat.items.forEachIndexed { index, item ->
                    val isOn = item.rule.id in active
                    SettingsSwitchRow(
                        title = item.rule.id.removePrefix(cat.id + ":"),
                        subtitle = item.blockedWhy ?: buildString {
                            append(item.rule.note)
                            val n = uses[item.rule.id] ?: 0
                            if (n > 0) append(" · $n kez kullanıldı")
                        },
                        icon = null,
                        // Verilemeyen satır kapalı ve dokunulamaz: "kapalı" ile
                        // "burada izin verilemez" farkını anahtarın hiç
                        // açılmaması değil, altyazının söylemesi anlatıyor.
                        enabled = item.grantable,
                        checked = isOn,
                        onCheckedChange = { toggle(item.rule.id, it) },
                        showDivider = index < cat.items.lastIndex,
                    )
                }
            }
        }

        // [37099e · §3.4] Hiç sorulmadan reddedilenler. Anahtar yok: bu
        // sınıfta "evet" bir seçenek değil, ve kullanıcı nedenini görmeli.
        SettingsSection(
            header = stringResource(R.string.permission_grants_deny_header),
            footer = stringResource(R.string.permission_grants_deny_footer),
        ) {
            val rows = remember { GrantCatalog.denyRows() }
            rows.forEachIndexed { i, row ->
                SettingsRow(
                    title = row.id.removePrefix("deny:"),
                    subtitle = row.note,
                    showDivider = i < rows.lastIndex,
                    showChevron = false,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

private fun catTitle(id: String): Int = when (id) {
    "shell" -> R.string.perm_cat_shell
    "git" -> R.string.perm_cat_git
    "pkg" -> R.string.perm_cat_pkg
    "termux" -> R.string.perm_cat_termux
    "net" -> R.string.perm_cat_net
    "device" -> R.string.perm_cat_device
    "sql" -> R.string.perm_cat_sql
    "credential" -> R.string.perm_cat_credential
    else -> R.string.permission_grants_title
}

private fun catSubtitle(id: String): Int = when (id) {
    "shell" -> R.string.perm_cat_shell_sub
    "git" -> R.string.perm_cat_git_sub
    "pkg" -> R.string.perm_cat_pkg_sub
    "termux" -> R.string.perm_cat_termux_sub
    "net" -> R.string.perm_cat_net_sub
    "device" -> R.string.perm_cat_device_sub
    "sql" -> R.string.perm_cat_sql_sub
    "credential" -> R.string.perm_cat_credential_sub
    else -> R.string.permission_grants_title
}
