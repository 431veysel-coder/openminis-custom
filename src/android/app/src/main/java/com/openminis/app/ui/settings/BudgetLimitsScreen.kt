package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.budget.BudgetLimits
import com.openminis.app.ozel.BudgetStore

/**
 * [37081] Settings › Minis X › Spending limits.
 *
 * ## Why numbers are typed, not picked from presets
 *
 * A preset ("1M tokens a day") is someone else's idea of a lot. The point of a
 * cap is that the USER decides where it sits, so the field is empty until they
 * fill it and empty means "no limit" — stated on the screen, not left to be
 * guessed from a zero.
 *
 * ## Why the four numbers are separate rows
 *
 * Chat / day / month answer different questions: "is this conversation getting
 * expensive", "am I burning today", "is this month going to surprise me". One
 * combined field would make the user pick a scope they did not ask about.
 *
 * ## Why there is no dollar field yet
 *
 * A dollar cap needs a price per model, and the catalogue does not carry one
 * for every provider. A dollar field that silently does nothing is worse than
 * no field; token caps work everywhere today. The row is here, disabled, with
 * the reason written on it — a hidden limitation is a bug report waiting to
 * happen.
 */
@Composable
fun BudgetLimitsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { BudgetStore.limits(context) }

    var chat by remember { mutableStateOf(initial.chatTokens?.toString().orEmpty()) }
    var day by remember { mutableStateOf(initial.dayTokens?.toString().orEmpty()) }
    var month by remember { mutableStateOf(initial.monthTokens?.toString().orEmpty()) }
    var saved by remember { mutableStateOf(false) }

    fun parse(s: String): Long? = s.trim().ifEmpty { null }?.toLongOrNull()?.takeIf { it > 0 }

    fun save() {
        BudgetStore.setLimits(
            context,
            BudgetLimits(
                chatTokens = parse(chat),
                dayTokens = parse(day),
                monthTokens = parse(month),
            ),
        )
        saved = true
    }

    SettingsScaffold(
        title = stringResourceCompat(R.string.mx_budget_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResourceCompat(R.string.mx_budget_header),
            footer = stringResourceCompat(R.string.mx_budget_footer),
        ) {
            TokenCapField(
                label = stringResourceCompat(R.string.mx_budget_chat),
                value = chat,
                onChange = { chat = it.filter(Char::isDigit); saved = false },
            )
            TokenCapField(
                label = stringResourceCompat(R.string.mx_budget_day),
                value = day,
                onChange = { day = it.filter(Char::isDigit); saved = false },
            )
            TokenCapField(
                label = stringResourceCompat(R.string.mx_budget_month),
                value = month,
                onChange = { month = it.filter(Char::isDigit); saved = false },
            )
        }

        SettingsSection(footer = stringResourceCompat(R.string.mx_budget_usd_note)) {
            SettingsRow(
                title = stringResourceCompat(R.string.mx_budget_usd),
                subtitle = stringResourceCompat(R.string.mx_budget_usd_reason),
                onClick = null,
                showChevron = false,
                showDivider = false,
                titleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }

        if (saved) {
            SettingsCardBlock {
                Text(
                    stringResourceCompat(R.string.mx_budget_saved),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // Usage today, so the screen answers "where am I" and not only "where is
        // the line". Read from the same ledger the gate reads — no second count.
        SettingsSection(header = stringResourceCompat(R.string.mx_budget_state)) {
            val summary = remember(chat, day, month, saved) {
                BudgetStore.summaryLine(context, sessionId = "")
            }
            SettingsCardBlock {
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
        }

        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            TextButton(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResourceCompat(R.string.mx_budget_save))
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = {
                    chat = ""
                    day = ""
                    month = ""
                    save()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResourceCompat(R.string.mx_budget_clear))
            }
        }
    }
}

/**
 * A single numeric cap. Empty = no limit, and the placeholder says so: a field
 * whose empty state means "zero tokens allowed" would lock the app on first open.
 */
@Composable
private fun TokenCapField(label: String, value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            placeholder = { Text("sınırsız") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Local alias so this file does not need the Compose import twice. */
@Composable
private fun stringResourceCompat(id: Int): String = androidx.compose.ui.res.stringResource(id)
