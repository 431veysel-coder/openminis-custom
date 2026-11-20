package com.openminis.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openminis.app.ozel.PolicyGate
import com.openminis.app.policy.ApprovalRequest
import com.openminis.app.policy.ApprovalScope
import com.openminis.app.policy.Decision
import com.openminis.app.policy.Recovery
import com.openminis.app.policy.RiskLevel

/**
 * [OZEL 37080] Onay kartı — ajanın tehlikeli bulduğu çağrı için.
 *
 * ## Neden beş satır, neden tek ekran
 *
 * Kullanıcının istediği: *"risk olan bölüm ne için riskli, geri döndürülebilir
 * mi, amaç ve yetki belirlenip onay isteyecek"*. Kart bunların hepsini
 * kaydırmadan göstermek zorunda — kaydırılan kartı insan okumaz, ilk düğmeye
 * basar. Bu yüzden gerekçe satırları kısa ve etiketli.
 *
 * ## Neden kapsam düğmeleri ikincil
 *
 * Birincil düğme **yalnız bu sefer** (`Bir kez izin ver`). Kalıcı izinler
 * altında, daha küçük. Kullanıcı refleksle en büyük düğmeye basar; en büyük
 * düğmenin en dar yetkiyi vermesi, tasarımın tek savunma hattı.
 *
 * ## Neden "hepsini reddet" var
 *
 * Aynı anda üç kart biriktiyse (konsey koşusu), tek tek reddetmek yerine tek
 * düğme. Bu bir toplu *onay* değil, toplu *ret* — güvenli yönde birleştirme.
 */
@Composable
fun ApprovalSheet() {
    val pending by PolicyGate.pending.collectAsState()
    if (pending.isEmpty()) return

    val request = remember(pending) { pending.first() }
    var showScopeMenu by remember(request.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {
            // Dismissing is a refusal, not a deferral: leaving the card open
            // in the background would let the run hang with no visible reason.
            PolicyGate.answer(request.id, PolicyGate.Answer(Decision.DENY, null, "kullanıcı kartı kapattı"))
        },
        title = {
            Column {
                Text(request.title, fontWeight = FontWeight.SemiBold)
                if (pending.size > 1) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "… ve ${pending.size - 1} bekleyen istek daha",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                request.lines().forEach { line ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Text(
                            line.label,
                            modifier = Modifier.width(96.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            line.value,
                            style = if (line.strong) {
                                MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
                            } else {
                                MaterialTheme.typography.bodySmall
                            },
                            color = if (line.label == "GERİ DÖNÜŞ" && line.strong) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                Text(
                    request.verdict.segment,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                request.persistenceNote()?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                PolicyGate.answer(
                    request.id,
                    PolicyGate.Answer(Decision.ALLOW, ApprovalScope.Session(sessionIdOf(request))),
                )
            }) { Text("Bir kez izin ver") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { showScopeMenu = true }) { Text("Kalıcı…") }
                TextButton(onClick = {
                    PolicyGate.refuseAll("kullanıcı hepsini reddetti")
                }) { Text("Hepsini reddet") }
            }
        },
    )

    if (showScopeMenu) {
        // Offered scopes come from the policy, not from the UI: which scopes a
        // permanent approval may even be offered for is a safety decision
        // (PERMANENT never gets Project/Global), and it must not be re-decided
        // in a layout file.
        AlertDialog(
            onDismissRequest = { showScopeMenu = false },
            title = { Text("Nereye kadar hatırlanacak?") },
            text = {
                Column {
                    request.offeredScopes.forEach { scope ->
                        TextButton(onClick = {
                            PolicyGate.answer(
                                request.id,
                                PolicyGate.Answer(Decision.ALLOW, withId(scope, sessionIdOf(request))),
                            )
                            showScopeMenu = false
                        }) { Text(scopeLabel(scope)) }
                    }
                    if (request.verdict.recovery == Recovery.PERMANENT) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Bu iş geri alınamadığı için yalnız bu sohbette hatırlanabilir.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (request.verdict.level >= RiskLevel.HIGH) {
                        Text(
                            "Yüksek riskli işlemler kapsam dışında da tekrar sorulur.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScopeMenu = false }) { Text("Vazgeç") }
            },
        )
    }
}

private fun scopeLabel(scope: ApprovalScope): String = when (scope) {
    is ApprovalScope.Session -> "Yalnız bu sohbet için"
    is ApprovalScope.Project -> "Bu projede hep"
    ApprovalScope.Global -> "Minis X'te hep"
}

/** Kapsam nesnesi kimlik taşımıyorsa oturumdan tamamlanır. */
private fun withId(scope: ApprovalScope, sessionId: String): ApprovalScope = when (scope) {
    is ApprovalScope.Session -> if (scope.sessionId.isBlank()) ApprovalScope.Session(sessionId) else scope
    else -> scope
}

/** Kartın oturum kimliği, sunduğu ilk Session kapsamından gelir. */
private fun sessionIdOf(request: ApprovalRequest): String =
    request.offeredScopes.filterIsInstance<ApprovalScope.Session>().firstOrNull()?.sessionId.orEmpty()
