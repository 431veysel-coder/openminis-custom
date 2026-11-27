package com.openminis.app.ui.team

import com.openminis.app.agent.team.CouncilDigest
import com.openminis.app.agent.team.CouncilTimeline
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.Icons
import com.openminis.app.agent.team.RecordedMatrix
import com.openminis.app.agent.team.ClaimMatrix
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
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

    // [37128 · A1] Durdurma yalnız koşarken anlamlı. `finished == null` canlı
    // kaydın kendi tanımı ("koşuyor"); ayrı bir bayrak tutmak iki gerçek
    // kaynağı yaratırdı.
    val running = live?.running == true
    var stopAsked by remember(runId) { mutableStateOf(false) }

    SettingsScaffold(
        title = live?.teamName ?: recorded?.teamName ?: stringResource(R.string.council_title),
        onBack = onBack,
        actions = if (running) {
            {
                IconButton(onClick = { stopAsked = true }) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.council_stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        } else {
            null
        },
    ) {
        // [37128 · A1] Onay istenir: iptal geri alınamaz (yarım koşu kaydedilir,
        // kalan turlar harcanmaz). "Yanlışlıkla bastım" maliyeti, tek dokunuşla
        // ödenen bedelden büyük.
        if (stopAsked) {
            AlertDialog(
                onDismissRequest = { stopAsked = false },
                title = { Text(stringResource(R.string.council_stop_title)) },
                text = { Text(stringResource(R.string.council_stop_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        stopAsked = false
                        // Koşmuyorsa `false` döner; kullanıcıya "durdurdum"
                        // demeyiz — düz ekran zaten durumu gösterir.
                        com.openminis.app.agent.team.CouncilRunRegistry.cancel(runId)
                    }) {
                        Text(
                            stringResource(R.string.council_stop),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { stopAsked = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

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
            // [37127] `live!!` kaldirildi: burada guvenliydi (ustteki null
            // kontrolu), ama delege edilmis bir ozellikte `!!` bir sonraki
            // duzenlemede sessiz NPE'ye acik kapi birakir.
            live?.preflightNote?.takeIf { it.isNotBlank() }?.let { note ->
                SettingsSection {
                    SettingsRow(
                        title = stringResource(R.string.council_preflight_warn),
                        subtitle = note,
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
                3 -> ComparisonRoom(live = live, recorded = recorded?.matrix)
                // [37131] "İletişim" odası "İlişkiler"e katlandı: sorular,
                // sahipsiz işler ve iddia→görev bağları tek soruya cevap
                // veriyor. Boş kalan sekme yerine **Kayıt** geldi.
                4 -> NeedsRoom(live = live)
                else -> TimelineRoom(live = live)
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
        // [37131 · Ofis] "İletişim" + "Bağımlılık" tek odaya birleşti
        // (ilişkiler = aynı sorunun iki yarısı), yerine **Kayıt** geldi:
        // zaman damgalı, filtrelenebilir transcript. Ofisin gerçek kalbi.
        stringResource(R.string.council_room_relations),
        stringResource(R.string.council_tl_title),
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(labels.size) { i ->
            // [37125 · §8.4] `selected` bildirilmezse TalkBack hangi odada
            // olduğunu söylemez; kullanıcı ekranı her seferinde yeniden
            // keşfetmek zorunda kalır. `Role.Tab` da "sekme" diye okutur.
            androidx.compose.material3.TextButton(
                onClick = { onSelect(i) },
                modifier = Modifier.semantics {
                    role = Role.Tab
                    // [37125] `this.` şart: parametre adı `selected` (Int),
                    // semantik özelliği `selected` (Boolean). Gölgeleme
                    // olmadan derleyici parametreye atama yapmaya çalışıyor
                    // ("'val' cannot be reassigned") — CI yakaladı.
                    this.selected = selected == i
                },
            ) {
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
    /**
     * [37125 · §8.1] Boş durumdaki eylemin hedefi. Varsayılan `null`: çağrı
     * yeri bağlamadıysa düğme çizilmez — hedefi olmayan bir düğme, bu depoda
     * kınanan "vaat" sınıfıdır.
     */
    onStartRun: (() -> Unit)? = null,
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
                    // [37125 · §8.1] Boş durum "neden boş" diyordu ama
                    // "ne yapayım" demiyordu: kullanıcı altı odaya girip tek
                    // cümle görüp çıkıyordu. Eylem düğmesi 48 dp hedefle
                    // çiziliyor (M3 varsayılanı) ve etiketi ne yapacağını
                    // söylüyor.
                    SettingsRow(
                        title = stringResource(R.string.council_empty_title),
                        subtitle = stringResource(R.string.council_empty_hint),
                        showDivider = true,
                    )
                    // Hedef yoksa düğme çizilmez: tıklanınca hiçbir şey
                    // yapmayan bir satır, "düğme bozuk" dedirtir.
                    onStartRun?.let { start ->
                        com.openminis.app.ui.settings.SettingsRow(
                            title = stringResource(R.string.council_compare_action),
                            subtitle = null,
                            showDivider = false,
                            showChevron = true,
                            onClick = start,
                        )
                    }
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
private fun ComparisonRoom(
    live: CouncilLiveRegistry.LiveRun?,
    /**
     * [37126] Koşu bittikten sonra da matris çizilebilsin diye diskten gelen
     * tablo. `null` = bu koşu matris kaydetmemiş (eski kayıt) — o zaman boş
     * tablo çizmek değil, dürüstçe söylemek gerekir.
     */
    recorded: ClaimMatrix? = null,
) {
    // [37126] İki farklı liste tipi: canlı tarafta `ClaimLine` (UI modeli),
    // diskte `Claim` (çekirdek modeli). Dönüşüm `RecordedMatrix`'te, saf.
    val claims = live?.claims ?: RecordedMatrix.claimLines(recorded)
    // Sütunlar: yalnız oy vermiş üyeler, kayıttaki sırayla.
    val members = remember(claims) {
        val order = live?.members?.keys?.toList().orEmpty()
        val voted = claims.flatMap { it.stances.keys }.distinct()
        order.filter { it in voted } .ifEmpty { voted }
    }
    // [H-019] Uzun matrisin üstünde tek satır cevap: kaç iddia kapandı, ne
    // açık kaldı. Matris "kim ne dedi"yi, özet "eee sonuç?"u cevaplıyor.
    val digest = remember(claims, live?.round) {
        CouncilDigest.ofLines(
            lines = claims,
            participants = live?.members?.size ?: members.size,
            roundsUsed = live?.round ?: 0,
            roundsPlanned = live?.roundsPlanned ?: 0,
        )
    }

    // [37126] Diskten gelen tabloda "kim oy verdi" ayrımı kayıtta yok; ama
    // matrisin kendisi o üyeyi içeriyorsa sütun çizilir. Uydurma yok: oy
    // vermeyen üye matriste zaten yok.
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
            if (live == null && recorded != null) {
                // Kaynağı söylemek zorunlu: canlı koşuda tablo güncellenirken,
                // buradaki tablo donmuş bir kayıt. Karıştırmak yanlış karar
                // verdirir.
                Text(
                    stringResource(R.string.council_compare_from_record),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // `padding(horizontal=, top=)` diye bir imza YOK (derleyici
                    // yakaladı): ya (horizontal, vertical) ya da dört kenar.
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp),
                )
            }
            // [H-019] Özet satırı. "Açık kalanlar" boşsa koşu kapandı der;
            // değilse iddia kimliklerini tek tek yazar — kullanıcı hangi
            // tartışmanın sürdüğünü tahmin etmek zorunda kalmaz.
            Text(
                text = if (digest.hasOpen) {
                    stringResource(
                        R.string.council_digest_open,
                        digest.settled,
                        digest.total,
                        digest.open.joinToString(", ") { it.id },
                        digest.roundsLabel,
                    )
                } else {
                    stringResource(
                        R.string.council_digest_settled,
                        digest.settled,
                        digest.total,
                        digest.roundsLabel,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (digest.hasOpen) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
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
                                // [37125 · §8.4] Görsel tek karakter; ekran
                                // okuyucu için üye + iddia + duruş tek cümle.
                                val cellLabel = TeamA11y.cellLabel(
                                    member = c.member,
                                    claimId = claim.id,
                                    stance = stance,
                                    supportWord = stringResource(R.string.council_stance_support),
                                    opposeWord = stringResource(R.string.council_stance_oppose),
                                    uncertainWord = stringResource(R.string.council_stance_uncertain),
                                    emptyWord = stringResource(R.string.council_stance_none),
                                )
                                Text(
                                    stance?.mark ?: "·",
                                    modifier = Modifier.width(c.widthDp.dp).semantics {
                                        contentDescription = cellLabel
                                    },
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
 * [37131 · Ofis] **Kayıt odası** — kim ne zaman ne yaptı.
 *
 * ## Neden bu oda ofisin kalbi
 *
 * Diğer beş oda *durumun* fotoğrafı: kim konuşuyor, ne çıktı, kim karşı.
 * Bu oda **zamanın** kendisi. Kullanıcı bir koşu bittikten sonra "bu karar
 * nasıl oluştu" diye sorduğunda cevap burada.
 *
 * ## Neden filtrelenebilir
 *
 * 500 satırlık düz bir liste okunmaz. Üye filtresi ("Ayşe ne demişti") ve
 * tür filtresi ("hangi turda düştü") iki gerçek soruya cevap veriyor.
 *
 * ## Neden kırpma yazılı
 *
 * Kuyruk sınırlı (`CouncilTimeline.MAX_LINES`). Sessiz kırpma, eksik kaydı tam
 * göstermek olurdu — o satır olmadan kullanıcı kaydın bütün olduğunu sanır.
 */
@Composable
private fun TimelineRoom(live: CouncilLiveRegistry.LiveRun?) {
    val tl = live?.timeline ?: CouncilTimeline.State()
    var who by remember(live?.runId) { mutableStateOf<String?>(null) }
    var kind by remember(live?.runId) { mutableStateOf<String?>(null) }

    val members = remember(tl) { CouncilTimeline.perMember(tl).keys.sorted() }
    val kinds = remember(tl) { CouncilTimeline.kinds(tl) }

    val shown = remember(tl, who, kind) {
        tl.lines.filter { l ->
            (who == null || l.who == who) && (kind == null || l.kind == kind)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (live == null) {
            // Kayıt yalnız canlı koşuda tutuluyor. Diske yazılmıyor: 500 satırlık
            // bir transcript her koşu için run.json'ı şişirirdi ve ofis "son
            // koşu" odası, arşiv değil.
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.council_tl_empty),
                    subtitle = stringResource(R.string.council_tl_live_only),
                    showDivider = false,
                )
            }
            return@Column
        }

        if (tl.truncated) {
            Text(
                stringResource(R.string.council_tl_truncated, tl.dropped),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Üye filtresi: yalnız konuşan üyeler (koşu düzeyi olaylar "who" boş).
        if (members.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    TextButton(onClick = { who = null }) {
                        Text(
                            stringResource(R.string.council_tl_all_members),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (who == null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(members.size) { i ->
                    val m = members[i]
                    TextButton(onClick = { who = if (who == m) null else m }) {
                        Text(
                            m,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (who == m) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            SettingsSection {
                SettingsRow(
                    title = stringResource(R.string.council_tl_empty),
                    showDivider = false,
                )
            }
        } else {
            // Sanal liste: 500 satırı bir seferde çizmek jank üretir.
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                items(shown.size) { i ->
                    val line = shown[i]
                    SettingsRow(
                        title = clock(line.atMs) + " · " + kindLabel(line.kind) +
                            if (line.who.isBlank()) "" else " · " + line.who,
                        subtitle = line.detail.ifBlank { null },
                        showDivider = i < shown.size - 1,
                        minHeight = 48.dp,
                    )
                }
            }
        }
    }
}

/** `12:04:31` — tarih değil saat: aynı koşunun içindeyiz. */
private fun clock(ms: Long): String {
    val t = java.util.Calendar.getInstance()
    t.timeInMillis = ms
    return String.format(
        "%02d:%02d:%02d",
        t.get(java.util.Calendar.HOUR_OF_DAY),
        t.get(java.util.Calendar.MINUTE),
        t.get(java.util.Calendar.SECOND),
    )
}

@Composable
private fun kindLabel(kind: String): String = stringResource(
    when (kind) {
        "started" -> R.string.council_kind_started
        "round_started" -> R.string.council_kind_round_started
        "position" -> R.string.council_kind_position
        "absent" -> R.string.council_kind_absent
        "canonicalized" -> R.string.council_kind_canonicalized
        "consensus" -> R.string.council_kind_consensus
        "stable" -> R.string.council_kind_stable
        "synthesized" -> R.string.council_kind_synthesized
        "audited_ok" -> R.string.council_kind_audited_ok
        "audited_fail" -> R.string.council_kind_audited_fail
        else -> R.string.council_kind_finished
    },
)

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

    // [37127 · Baglantilar] Ters yon: hangi iddia ise donustu? Gerev satirinda
    // "C3" yaziyordu ama karsilastirma/karar odasindan bakan kullanici "bu
    // tartisma ise donustu mu" sorusunu cevaplayamiyordu.
    val claimLinks = remember(live?.claims, tasks) {
        com.openminis.app.agent.team.TaskLinks.claimLinks(live?.claims.orEmpty(), tasks)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SettingsSection(
            header = stringResource(R.string.council_needs_links),
            footer = if (claimLinks.isEmpty()) stringResource(R.string.council_needs_links_none) else null,
        ) {
            claimLinks.forEachIndexed { i, link ->
                SettingsRow(
                    title = "${link.claimId} → " + stringResource(
                        R.string.council_needs_link_tasks,
                        link.count,
                    ),
                    subtitle = link.claimText.take(90),
                    showDivider = i < claimLinks.lastIndex,
                )
            }
            if (claimLinks.isEmpty()) {
                SettingsRow(
                    title = stringResource(R.string.council_needs_links_empty),
                    showDivider = false,
                )
            }
        }
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
