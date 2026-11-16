package com.openminis.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.provider.FreeModelCatalog
import com.openminis.app.provider.FreeTierLimits

/**
 * [MINIS-OZEL 11.1] The "free" / "limit tracked" chip on a model row.
 *
 * ## Why two different chips instead of one
 *
 * They answer different questions. "Free" means the provider bills nothing for
 * this model — safe to use without watching anything. "Limit tracked" means the
 * model IS billed but the account has an included daily/token allowance that
 * this app counts against — using it costs nothing extra only until the
 * allowance runs out. Collapsing the two into one green badge would tell the
 * user the second case is the first, which is exactly how a metered allowance
 * turns into an unexplained failure at the end of the day.
 *
 * ## Why it renders nothing most of the time
 *
 * The facts come from a scan the user ran ([com.openminis.app.provider.FreeModelScanner])
 * and from a limit they entered. Without both, there is nothing true to say, and
 * a chip that says nothing is better than a chip that guesses.
 */
@Composable
fun FreeModelTag(
    instanceId: String,
    modelId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Cheap: a small string set in SharedPreferences, read once per recomposition
    // group keyed by instance. The picker draws a handful of providers, not
    // thousands of rows, so this never shows up in a trace.
    val freeIds = remember(instanceId) { FreeModelCatalog.freeIds(context, instanceId) }
    val limits = remember(instanceId) { FreeTierLimits.limitsFor(context, instanceId) }

    // The id convention is checked unconditionally: `:free` is OpenRouter's
    // marker and is part of the model's real id, so a match is never a
    // coincidence. Resolving the vendor profile here would mean a repository
    // read per row for one string comparison.
    val free = modelId in freeIds || FreeModelCatalog.isFreeByName(modelId, ":free")
    when {
        free -> Tag(
            text = stringResource(R.string.model_tag_free),
            fg = Color(0xFF1E8E3E),
            bg = Color(0xFF1E8E3E).copy(alpha = 0.12f),
            modifier = modifier,
        )
        !limits.isEmpty -> Tag(
            text = stringResource(R.string.model_tag_limited),
            fg = Color(0xFFB26A00),
            bg = Color(0xFFFFB300).copy(alpha = 0.16f),
            modifier = modifier,
        )
    }
}

@Composable
private fun Tag(
    text: String,
    fg: Color,
    bg: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
        color = fg,
        fontSize = 10.sp,
        style = MaterialTheme.typography.labelSmall,
    )
}
