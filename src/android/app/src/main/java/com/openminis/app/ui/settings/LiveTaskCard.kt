package com.openminis.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R

/**
 * [MINIS-OZEL] One task, expandable into the steps the model actually took.
 *
 * This is the "watch it work" surface: the collapsed header answers "which task
 * and is it still going", and opening it replays each tool call in order, with
 * the argument and whatever came back. A step with no result yet is drawn as
 * running, which is how a live turn becomes visible instead of a spinner.
 */
@Composable
fun LiveTaskCard(
    title: String,
    statusLabel: String,
    steps: List<AgentStep>,
    running: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenChat: (() -> Unit)?,
) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val runningCount = steps.count { it.running }
    val failedCount = steps.count { it.success == false }

    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface,
                RoundedCornerShape(12.dp),
            ),
    ) {
        // ── Header: always visible, tap to expand ─────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Live dot: pulsing during a turn is a job for the progress ring,
            // a plain dot is enough to say "this one is not idle".
            Box(
                Modifier
                    .size(9.dp)
                    .background(
                        when {
                            failedCount > 0 -> Color(0xFFFF3B30)
                            running -> Color(0xFF34C759)
                            else -> Color(0xFF8E8E93)
                        },
                        RoundedCornerShape(50),
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusLabel,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                    if (steps.isNotEmpty()) {
                        Text(
                            text = " · " + stringResource(R.string.live_steps_count, steps.size),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    if (runningCount > 0) {
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = Color(0xFF34C759),
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
            ) {
                if (steps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.live_steps_none),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    steps.forEach { step ->
                        StepRow(step)
                    }
                }
                if (onOpenChat != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.live_steps_open_chat),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onOpenChat)
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }
}

/** One tool call. Collapsed shows name + headline; tap reveals input/output. */
@Composable
private fun StepRow(step: AgentStep) {
    var open by remember(step.toolUseId) { mutableStateOf(false) }
    val headline = remember(step.input) { AgentStepExtractor.headline(step.input) }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    step.running -> CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.6.dp,
                        color = Color(0xFF34C759),
                    )
                    step.success == false -> Icon(
                        Icons.Outlined.Close, null,
                        modifier = Modifier.size(13.dp),
                        tint = Color(0xFFFF3B30),
                    )
                    else -> Icon(
                        Icons.Outlined.Check, null,
                        modifier = Modifier.size(13.dp),
                        tint = Color(0xFF34C759),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = step.name,
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (step.running) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.live_steps_running),
                            fontSize = 10.sp,
                            color = Color(0xFF34C759),
                        )
                    }
                }
                if (headline.isNotBlank()) {
                    Text(
                        text = headline,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        maxLines = if (open) 6 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (step.description != null && step.description != headline) {
                    Text(
                        text = step.description,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        maxLines = if (open) 3 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(visible = open) {
            Column(Modifier.padding(start = 24.dp, top = 6.dp)) {
                if (headline.isNotBlank()) {
                    Block(stringResource(R.string.live_steps_input), step.input)
                }
                val out = step.output
                if (out != null) {
                    Spacer(Modifier.height(6.dp))
                    Block(
                        if (step.success == false) {
                            stringResource(R.string.live_steps_error)
                        } else {
                            stringResource(R.string.live_steps_output)
                        },
                        out,
                        tint = if (step.success == false) Color(0xFFFF3B30) else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun Block(label: String, body: String, tint: Color? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f),
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            fontSize = 9.5.sp,
            color = tint ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
        Text(
            text = body.ifBlank { "—" },
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
    }
}
