package com.openminis.app.ui.team

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.team.CouncilLiveRegistry
import com.openminis.app.agent.team.TaskState as TeamTaskState
import com.openminis.app.agent.team.TeamRunStore
import androidx.compose.ui.res.stringResource
import com.openminis.app.ui.settings.SettingsRow
import com.openminis.app.ui.settings.SettingsScaffold
import com.openminis.app.ui.settings.SettingsSection

/**
 * [MINIS-OZEL 11.5 · 37064] The council run screen: agenda, floor, decisions.
 *
 * ## The three tabs, and why not one screen
 *
 * The user opens a council in two moods: "what came out" (five seconds) and "why
 * did it come out that way" (thirty seconds). Those want opposite densities, and
 * a single screen that satisfies both satisfies neither. So:
 *
 *  - **Kürsü** (floor) — what is happening right now. Member cards with the tool
 *    each one is running, which is the answer to "why has it been quiet for forty
 *    seconds".
 *  - **Gündem** (agenda) — the numbered claims and their vote counts. This is
 *    where "who is arguing what" lives, and where a task will attach in 37056.
 *  - **Karar** (decision) — the synthesised answer, the dissent verbatim, and the
 *    stop reason.
 *
 * ## Where the data comes from
 *
 * [CouncilLiveRegistry] for a running (or just-finished) run,
 * [TeamRunStore] for the report of one that ended in an earlier session. The
 * screen never re-derives anything: if it is not in one of those two, it does not
 * exist, and inventing a placeholder for it would be a lie about what happened.
 */
@Composable
fun CouncilScreen(
    runId: String,
    onBack: () -> Unit,
    onOpenMember: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val runs by CouncilLiveRegistry.runs.collectAsState()
    val live = runs[runId]
    // Read the finished record once; it does not change while the screen is open.
    val recorded = remember(runId) { TeamRunStore.list(context).firstOrNull { it.runId == runId } }
    val report = remember(runId) { TeamRunStore.report(context, runId) }

    var tab by remember { mutableStateOf(0) }

    SettingsScaffold(
        title = live?.teamName ?: recorded?.teamName ?: stringResource(R.string.council_title),
        onBack = onBack,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: the one line that answers "is it still going".
            SettingsSection {
                SettingsRow(
                    title = statusLine(live, recorded),
                    subtitle = live?.task?.take(200) ?: recorded?.let { it.rounds.toString() + " tur" },
                    showDivider = false,
                )
            }

            // [37099f · §1.2] Ön kontrol uyarısı: koşu eksik üyeyle başladıysa
            // kullanıcı bunu **baştan** görür, sonuç boş geldiğinde değil.
            if (!live?.preflightNote.isNullOrBlank()) {
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.council_preflight_warn),
                        subtitle = live!!.preflightNote,
                        showDivider = false,
                    )
                }
            }

            TabStrip(selected = tab, onSelect = { tab = it })

            // [37099c] Altı oda — kullanıcının sırası: sohbet, görev, sonuç,
            // karşılaştırma, iletişim, bağımlılık. "Gündem" sekmesi kaldırıldı:
            // gösterdiği tek şey iddia + oy sayısıydı, ikisi de karşılaştırma
            // odasında üye üye var. İki ekranın aynı soruya iki farklı cevap
            // vermesi, bu depoda kınanan sınıf.
            when (tab) {
                0 -> FloorTab(live = live, onOpenMember = onOpenMember)
                1 -> RunTasksSection(runId = runId)
                2 -> DecisionTab(
                    answer = report,
                    dissent = live?.dissent.orEmpty(),
                    recorded = recorded,
                )
                3 -> ComparisonRoom(live = live)
                4 -> CommsRoom(live = live)
                else -> NeedsRoom(live = live)
            }
        }
    }
}

@Composable
private fun statusLine(
    live: CouncilLiveRegistry.LiveRun?,
    recorded: TeamRunStore.Header?,
): String = when {
    live?.finished != null ->
        "${live.finished.stoppedBy} · ${live.finished.rounds} tur · ${live.finished.tokensUsed} token"
    live != null -> buildString {
        append(phaseLabel(live.phase))
        append(" · tur ${live.round}/${live.roundsPlanned}")
        // [37099d] Dalga bilgisi: 5 üyeli ekipte aynı anda 4'ü konuşur.
        // Söylenmezse "biri geç yazdı, koşu bozuk" okunur.
        if (live.waveSize > 0 && live.members.size > live.waveSize) {
            val waves = (live.members.size + live.waveSize - 1) / live.waveSize
            append(" · ${live.members.size} üye, $waves dalga")
        }
        append(" · ${live.tokensUsed} token")
    }
    recorded != null -> recorded.statusLine()
    else -> stringResource(R.string.council_run_gone)
}

private fun phaseLabel(phase: CouncilLiveRegistry.Phase): String = when (phase) {
    CouncilLiveRegistry.Phase.STARTING -> "hazırlanıyor"
    CouncilLiveRegistry.Phase.POSITIONS -> "üyeler yazıyor"
    CouncilLiveRegistry.Phase.CANONICALIZING -> "başkan maddeleri birleştiriyor"
    CouncilLiveRegistry.Phase.DEBATING -> "tartışma"
    CouncilLiveRegistry.Phase.SYNTHESIZING -> "sentez yazılıyor"
    CouncilLiveRegistry.Phase.VERIFYING -> "atıflar denetleniyor"
    CouncilLiveRegistry.Phase.DONE -> "bitti"
}

@Composable
private fun TabStrip(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf(
        stringResource(R.string.council_room_chat),
        stringResource(R.string.council_room_tasks),
        stringResource(R.string.council_room_result),
        stringResource(R.string.council_room_compare),
        stringResource(R.string.council_room_comms),
        stringResource(R.string.council_room_needs),
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(labels.size) { i ->
            androidx.compose.material3.TextButton(onClick = { onSelect(i) }) {
                Text(
                    labels[i],
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected == i) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The floor: one card per member.
 *
 * The tool badge is the whole point. Without it a member that has been in a
 * browser for forty seconds looks identical to one that crashed, and the user's
 * only recourse is to kill a run that is working fine.
 */
@Composable
private fun FloorTab(
    live: CouncilLiveRegistry.LiveRun?,
    onOpenMember: (String) -> Unit,
) {
    if (live == null) {
        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.council_run_gone),
                subtitle = stringResource(R.string.council_run_gone_hint),
                showDivider = false,
            )
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        items(live.members.values.toList()) { member ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (member.absent) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                ),
                onClick = { if (member.sessionId != null) onOpenMember(member.name) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(member.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            member.tool ?: if (member.absent) "yok" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (member.role.isNotBlank()) {
                        Text(
                            member.role,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (member.claim.isNotBlank()) {
                        Text(
                            member.claim,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (member.absent) {
                        Text(
                            stringResource(R.string.council_member_absent),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DecisionTab(
    answer: String?,
    dissent: List<String>,
    recorded: TeamRunStore.Header?,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp)) {
        item {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (recorded != null && !recorded.verified) {
                    Text(
                        stringResource(R.string.council_unverified),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // [37099k · §8.2] Rapor düz metin değil: başlık/madde/atıf
                // ayrılıyor. Önce tek `Text`'ti; modelin yazdığı `## başlık`
                // ve `- madde` ekranda ham görünüyordu.
                if (answer.isNullOrBlank()) {
                    Text(
                        stringResource(R.string.council_no_report),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    ReportBody(answer)
                }
                if (dissent.isNotEmpty()) {
                    Text(
                        stringResource(R.string.council_dissent),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    dissent.forEach {
                        Text(
                            "• $it",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * [MINIS-OZEL 11.5 · 37064] Every council run the user can open.
 *
 * Running runs first (they are what the user is waiting on), then the recorded
 * ones newest first. A run that is in the registry is NOT read from disk — the
 * disk copy does not exist until the run finishes, so showing it would mean
 * showing yesterday's file for today's run id.
 */
@Composable
fun CouncilRunsScreen(
    onBack: () -> Unit,
    onOpenRun: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val live by CouncilLiveRegistry.runs.collectAsState()
    val recorded = remember(live.size) {
        runCatching { TeamRunStore.list(context) }.getOrDefault(emptyList())
    }
    val liveRuns = live.values.sortedByDescending { it.startedAtMs }
    val liveIds = liveRuns.map { it.runId }.toSet()

    SettingsScaffold(
        title = stringResource(R.string.council_title),
        onBack = onBack,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (liveRuns.isNotEmpty()) {
                SettingsSection(header = stringResource(R.string.council_section_running)) {
                    liveRuns.forEachIndexed { i, run ->
                        SettingsRow(
                            title = run.teamName,
                            subtitle = "${phaseLabel(run.phase)} · tur ${run.round}/${run.roundsPlanned}",
                            showDivider = i < liveRuns.lastIndex,
                            onClick = { onOpenRun(run.runId) },
                        )
                    }
                }
            }
            val past = recorded.filter { it.runId !in liveIds }
            SettingsSection(
                header = stringResource(R.string.council_section_past),
                footer = if (past.isEmpty()) stringResource(R.string.council_empty_hint) else null,
            ) {
                if (past.isEmpty()) {
                    SettingsRow(
                        title = stringResource(R.string.council_empty_title),
                        subtitle = null,
                        showDivider = false,
                    )
                } else {
                    past.forEachIndexed { i, run ->
                        SettingsRow(
                            title = run.teamName,
                            subtitle = run.statusLine(),
                            showDivider = i < past.lastIndex,
                            onClick = { onOpenRun(run.runId) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * [37099c] **Karşılaştırma odası** — satırlar maddeler, sütunlar üyeler.
 *
 * ## Neden ayrı oda
 *
 * "Gündem" oyu topluyordu (`3✓ 2✗`), yani "kaç kişi" sorusunu cevaplıyordu.
 * Kullanıcının asıl sorusu "kim" ve "hangi maddede": 3'e 2 bölünmüş bir
 * konseyde kimin ne dediğini görmeden kararı okumak, raporun neden öyle
 * çıktığını açıklamıyor.
 *
 * ## Neden uydurma yok
 *
 * Duruşlar `Claim.stances` içinden geliyor; kayıtta olmayan üye sütunu
 * **çizilmez**. Oy veren üye listesi kayıtta yoksa tablo çizilmez, boş
 * durum yazılır — "herkes katıldı" varsayımı bir yalandır.
 */
@Composable
private fun ComparisonRoom(live: CouncilLiveRegistry.LiveRun?) {
    val claims = live?.claims.orEmpty()
    // Sütunlar: yalnız oy vermiş üyeler, kayıttaki sırayla.
    val members = remember(claims) {
        val order = live?.members?.keys?.toList().orEmpty()
        val voted = claims.flatMap { it.stances.keys }.distinct()
        order.filter { it in voted } .ifEmpty { voted }
    }
    if (claims.isEmpty() || members.isEmpty()) {
        SettingsSection {
            SettingsRow(
                title = stringResource(R.string.council_compare_empty),
                showDivider = false,
            )
        }
        return
    }
    // [37122 · §1.5] Gerçek matris: satır = iddia, sütun = **oy vermiş** üye.
    //
    // Neden yatay kaydırma: 5 üye × 96 dp = 480 dp, 320 dp'lik ekrana sığmaz.
    // Sütunu daraltmak (40 dp) hücreleri boş gösterir ve oy veren üyeyi yok
    // sayar; tek doğru cevap kaydırmadır. Yerleşim `ClaimMatrixLayout`'ta,
    // saf ve testli.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availableDp = maxWidth.value.toInt()
        val layout = ClaimMatrixLayout.compute(members, availableDp)
        val scroll = rememberScrollState()

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(R.string.council_compare_legend),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            if (layout.needsScroll) {
                Text(
                    stringResource(R.string.council_compare_scroll_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                )
            }
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    stringResource(R.string.council_compare_claim_col),
                    modifier = Modifier.width(layout.claimWidthDp.dp),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                )
                Row(modifier = Modifier.horizontalScroll(scroll)) {
                    layout.columns.forEach { c ->
                        Text(
                            c.member,
                            modifier = Modifier.width(c.widthDp.dp),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                items(claims) { claim ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Column(modifier = Modifier.width(layout.claimWidthDp.dp)) {
                            Text(
                                claim.id,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                claim.text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${claim.support}✓ ${claim.oppose}✗ ${claim.uncertain}?",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(modifier = Modifier.horizontalScroll(scroll)) {
                            layout.columns.forEach { c ->
                                val stance = ClaimMatrixLayout.Stance.of(claim.stances[c.member])
                                Text(
                                    stance?.mark ?: "·",
                                    modifier = Modifier.width(c.widthDp.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = when (stance) {
                                        ClaimMatrixLayout.Stance.SUPPORT -> MaterialTheme.colorScheme.primary
                                        ClaimMatrixLayout.Stance.OPPOSE -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/** Duruşu tek karaktere indirir; renk yanında taşındığı için okunur kalır. */
private fun stanceMark(wire: String): String = when (wire) {
    "support" -> "✓"
    "oppose" -> "✗"
    else -> "?"
}

/**
 * [37099c] **İletişim hattı** — üyeden üyeye sorular.
 *
 * Kaynak: `Position.questions` → `CouncilLiveRegistry.QuestionLine`.
 * Sorular cevapla birlikte gelmez; cevap, hedef üyenin **sonraki turdaki**
 * pozisyonunda görünür. Bu yüzden ekran soruyu ve hedefi yazar, cevabı
 * uydurmaz.
 */
@Composable
private fun CommsRoom(live: CouncilLiveRegistry.LiveRun?) {
    val lines = live?.questions.orEmpty()
    SettingsSection(
        header = stringResource(R.string.council_comms_header),
        footer = if (lines.isEmpty()) stringResource(R.string.council_comms_empty) else null,
    ) {
        if (lines.isEmpty()) {
            SettingsRow(title = stringResource(R.string.council_empty_comms), showDivider = false)
        } else {
            lines.forEachIndexed { i, q ->
                SettingsRow(
                    title = "${q.from} → ${q.to}",
                    subtitle = buildString {
                        append(q.question)
                        if (q.answer.isNotBlank()) {
                            // [37099d] Cevap, hedefin sonraki tur pozisyonundan
                            // eşlenir — kesin bir alıntı değil.
                            // [37122 · §1.6] Yaklaşıklık artık işaretle yazılıyor:
                            // yanlış üyeye atfedilmiş bir cevap, cevapsızlıktan
                            // daha zararlı.
                            append("\n↳ ")
                            append(
                                ClaimMatrixLayout.answerMark(
                                    answeredRound = q.answeredRound,
                                    questionRound = q.round,
                                ).ifBlank { "tur ${q.answeredRound}" },
                            )
                            append(": ")
                            append(q.answer.take(240))
                        } else {
                            append("\n↳ cevap bekleniyor (hedefin sonraki turu)")
                        }
                    },
                    showDivider = i < lines.lastIndex,
                )
            }
        }
    }
}

/**
 * [37099c] **Bağımlılık / ihtiyaçlar hattı** — kim kime bağlı, ne eksik.
 *
 * Üç gerçek kaynak, üç ayrı soru:
 *  1. `Position.tasks` → bir üyenin "bunu sen al" isteği (panoya dönüşmemiş
 *     olabilir; kaybolan istek sessiz bir iştir).
 *  2. Sahipsiz görevler — kimsenin işi olmayan iş, yapılmayan iştir.
 *  3. Kanıtsız bitirilemeyen görevler — `TeamTask.canBeDone` false olan DONE
 *     değil, OPEN/DOING görevler için "kanıt eklersen kapanır" bilgisi.
 */
@Composable
private fun NeedsRoom(live: CouncilLiveRegistry.LiveRun?) {
    val needs = live?.needs.orEmpty()
    val tasks = live?.tasks?.values.orEmpty()
    val unowned = tasks.filter {
        it.owner.isBlank() && it.state != TeamTaskState.DONE && it.state != TeamTaskState.DROPPED
    }
    val blocked = tasks.filter {
        !it.canBeDone && (it.state == TeamTaskState.OPEN || it.state == TeamTaskState.DOING)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSection(
            header = stringResource(R.string.council_needs_requests),
            footer = if (needs.isEmpty()) stringResource(R.string.council_needs_empty) else null,
        ) {
            if (needs.isEmpty()) {
                SettingsRow(title = stringResource(R.string.council_empty_needs), showDivider = false)
            } else {
                needs.forEachIndexed { i, n ->
                    SettingsRow(
                        title = "${n.from} → ${n.title}",
                        subtitle = listOf(n.why, n.expect).filter { it.isNotBlank() }
                            .joinToString(" · ")
                            .ifBlank { "tur ${n.round}" },
                        showDivider = i < needs.lastIndex,
                    )
                }
            }
        }
        SettingsSection(
            header = stringResource(R.string.council_needs_unowned),
            footer = if (unowned.isEmpty()) null else stringResource(R.string.council_needs_unowned_hint),
        ) {
            if (unowned.isEmpty()) {
                SettingsRow(title = stringResource(R.string.council_empty_unowned), showDivider = false)
            } else {
                unowned.forEachIndexed { i, task ->
                    SettingsRow(
                        title = task.title,
                        subtitle = task.line(),
                        showDivider = i < unowned.lastIndex,
                    )
                }
            }
        }
        SettingsSection(header = stringResource(R.string.council_needs_blocked)) {
            if (blocked.isEmpty()) {
                SettingsRow(title = stringResource(R.string.council_empty_blocked), showDivider = false)
            } else {
                blocked.forEachIndexed { i, task ->
                    SettingsRow(
                        title = task.title,
                        subtitle = task.doneRefusal().take(200),
                        showDivider = i < blocked.lastIndex,
                    )
                }
            }
        }
    }
}
