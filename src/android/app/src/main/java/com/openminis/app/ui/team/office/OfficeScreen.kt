package com.openminis.app.ui.team.office

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.team.CouncilLiveRegistry
import com.openminis.app.agent.team.RoundReason
import com.openminis.app.ui.team.FloorTab
import com.openminis.app.ui.team.TimelineRoom
import kotlinx.coroutines.delay

/**
 * [F1 · .57] **Sanal Ofis** — tam ekran, canlı, üç çözünürlük.
 *
 * ## Neden ayrı ekran, neden yedinci sekme değil
 *
 * Konsey panelindeki altı oda **raporu** gösterir: sonuç, matris, görevler,
 * kayıt. Hepsi koşu bittikten sonra da doğru, ama koşu **sürerken** "takıldı
 * mı" sorusunu cevaplamıyorlar. Ofis tam olarak o soru için var. Yedinci
 * sekme yapmak, altı odanın arasına "şu an" kavramını sıkıştırmak olurdu;
 * ayrı tam ekran ise kullanıcıya **başka bir moda girdiğini** söyler.
 *
 * ## Neden üç çözünürlük, neden dördüncü yok
 *
 * Her görünüm tek soruya cevap verir:
 *  - **Bakış**  → "takıldı mı, kim çalışıyor" (5 saniye)
 *  - **Kat**    → "kim ne yapıyor" (yerleşim)
 *  - **Kayıt**  → "ne zaman ne oldu" (zaman)
 * Dördüncüsü eklenirse üçü de okunamaz hâle gelir (§6.4 kural 1).
 *
 * ## Neden Kat ve Kayıt yeniden yazılmadı
 *
 * İkisi de konsey panelinde var ve doğru çalışıyor. Ofis için kopyalamak iki
 * kaynak üretirdi: birinde düzeltilen hatanın diğerinde kalması bu depoda
 * defalarca yaşandı. `FloorTab` / `TimelineRoom` `internal` yapıldı ve burada
 * **aynı** composable çağrılıyor.
 *
 * ## Bakış neden cümle içermiyor
 *
 * Koşu sürerken kullanıcı okumak istemiyor, **bakmak** istiyor. Üye başına tek
 * satır: ad · durum · iddia sayısı. Cümle Kat'ta, zaman Kayıt'ta.
 */
@Composable
fun OfficeScreen(
    runId: String,
    onBack: () -> Unit,
    onOpenMember: (String) -> Unit,
) {
    val runs by CouncilLiveRegistry.runs.collectAsState()
    val live = runs[runId]
    var mode by rememberSaveable { mutableStateOf(0) }
    // [§6.3] Durdurma iki dokunuşlu: koşunun yarısını çöpe atan bir iş tek
    // dokunuşta olmamalı. Aynı diyalog konsey panelinde de var; iki yerde
    // iki farklı onay metni olmasın diye metin aynı kaynaktan geliyor.
    var stopAsked by remember(runId) { mutableStateOf(false) }
    if (stopAsked) {
        StopConfirmDialog(
            onConfirm = {
                com.openminis.app.agent.team.CouncilRunRegistry.cancel(runId)
                stopAsked = false
            },
            onDismiss = { stopAsked = false },
        )
    }

    if (live == null) {
        EmptyOffice(onBack = onBack)
        return
    }

    // Süre sayacı: `startedAtMs` + monotonik tik. `System.currentTimeMillis()`
    // her karede okunmaz — saat değişirse (ağ senkronu, kullanıcı ayarı) süre
    // geriye gider ve ekran "koşu 3 saniye önce başladı" der.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live.running) {
        while (live.running) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val elapsedSec = ((nowMs - live.startedAtMs) / 1000).coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        OfficeHeader(
            live = live,
            elapsedSec = elapsedSec,
            onStop = { stopAsked = true },
            onBack = onBack,
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            listOf(
                R.string.office_tab_overview,
                R.string.office_tab_floor,
                R.string.office_tab_log,
            ).forEachIndexed { index, label ->
                SegmentedButton(
                    selected = mode == index,
                    onClick = { mode = index },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                ) { Text(stringResource(label)) }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when (mode) {
                0 -> OverviewTab(live = live, elapsedSec = elapsedSec)
                1 -> FloorTab(live = live, onOpenMember = onOpenMember)
                else -> TimelineRoom(live = live)
            }
        }
    }
}

/** Koşu yoksa: boş oda değil, **dürüst** boş durum. */
@Composable
private fun EmptyOffice(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        }
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.office_empty),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.office_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Üst şerit — "takıldı mı".
 *
 * Üç satır: başlık + butonlar, ölçüler, **tur gerekçesi**. Gerekçe yoksa
 * satır **çizilmez**: olmayan bir sebebe yer açmak, "sebep var ama görünmüyor"
 * izlenimi verir.
 */
@Composable
private fun OfficeHeader(
    live: CouncilLiveRegistry.LiveRun,
    elapsedSec: Long,
    onStop: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    live.teamName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    live.task,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (live.running) {
                IconButton(onClick = onStop) {
                    Icon(
                        Icons.Filled.Stop,
                        contentDescription = stringResource(R.string.office_stop),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.office_round, live.round, live.roundsPlanned),
                style = MaterialTheme.typography.labelMedium,
            )
            if (live.waveSize > 0) {
                Text(
                    stringResource(R.string.office_wave, live.waveSize),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                formatElapsed(elapsedSec),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.office_tokens, live.tokensUsed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Tur gerekçesi. Yalnız **sıradaki tur gerçekten varsa** çizilir:
        // koşu `running` değilse gerekçe geçmişe ait bir açıklamadır ve
        // "tur 3'e geçiliyor" demek yanlış olur.
        val reason = live.roundReason
        if (live.running && reason != null) {
            Spacer(Modifier.height(4.dp))
            com.openminis.app.ui.team.RoundReasonLine(reason)
        }
        if (live.preflightNote.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                live.preflightNote,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatElapsed(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return if (m >= 60) {
        val h = m / 60
        "%d:%02d:%02d".format(h, m % 60, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

/**
 * **Bakış** — 5 saniyelik cevap. Cümle yok, kart yok.
 *
 * Durum **uydurulmaz**: elimizde yalnız dört gerçek var —
 *  - `absent` → gelmedi (+ neden)
 *  - `tool != null` → araç çalıştırıyor (araç adı)
 *  - `claim` dolu ve tur ilerlemiş → cevabını verdi
 *  - dalga sırası (`CouncilPolicy.waves`) → henüz sırası gelmedi
 *
 * "Düşünüyor" yazmıyoruz: hangi üyenin o an modelden yanıt beklediği veride
 * yok. Uydurmak, ileride yanlış teşhis ettirir.
 */
@Composable
private fun OverviewTab(live: CouncilLiveRegistry.LiveRun, elapsedSec: Long) {
    val members = live.members.values.toList()
    val waveSize = live.waveSize.coerceAtLeast(1)
    // Üye başına iddia sayısı: satır içinde `count` çağırmak her karede
    // (iddia × üye) taraması yapar. Bir kez hesapla, O(1) oku.
    val claimsPerMember = remember(live.claims) {
        live.claims.flatMap { it.stances.keys }.groupingBy { it }.eachCount()
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(members) { index, m ->
            val waitingTurn = live.running && live.phase == CouncilLiveRegistry.Phase.POSITIONS &&
                index >= waveSize && m.claim.isBlank() && m.tool == null
            val (dot, label) = when {
                m.absent -> MaterialTheme.colorScheme.error to
                    stringResource(R.string.office_state_absent) +
                    if (m.absentWhy.isNotBlank()) " · ${m.absentWhy}" else ""
                m.tool != null -> MaterialTheme.colorScheme.tertiary to
                    stringResource(R.string.office_state_tool, m.tool!!)
                waitingTurn -> MaterialTheme.colorScheme.outline to
                    stringResource(R.string.office_state_queued, (index / waveSize) + 1)
                m.claim.isNotBlank() -> Color(0xFF34C759) to stringResource(R.string.office_state_answered)
                else -> MaterialTheme.colorScheme.outline to stringResource(R.string.office_state_idle)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(dot, CircleShape),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(m.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Text(
                        if (m.role.isBlank()) label else "${m.role} · $label",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(R.string.office_claims_count, claimsPerMember[m.name] ?: 0),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
