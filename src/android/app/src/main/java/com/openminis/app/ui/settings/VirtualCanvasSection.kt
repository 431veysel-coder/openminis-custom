package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.screen.VirtualCanvas
import com.openminis.app.screen.VirtualScreen
import com.openminis.app.screen.VirtualScreenCaptureAge

/**
 * [37119] **Tek tuval** — bölmeler ilk kez aynı yüzeyde, gerçek yerleşimle.
 *
 * ## Ne çizer, ne çizmez
 *
 * Çizer: `VirtualCanvas.layout`'un ürettiği dikdörtgenlerin içine bölme adı,
 * adresi/çalışma dizini, kaydırma, taslak uzunluğu ve son yakalama zamanı.
 * Yerleşim, isabet testi ve 320 dp taşma kuralı saf çekirdekte ve testli.
 *
 * Çizmez: canlı sayfa/terminal içeriği. Bölmelerin içinde gerçek bir WebView
 * ya da PTY yok; bu yüzden her kutuda içerik değil **durum** var ve bölümün
 * alt yazısı bunu açıkça söylüyor.
 *
 * ## Neden `offset { IntOffset(...) }`, neden dp çevrimi yok
 *
 * Yerleşim dp ile karar veriyor; çizimde dp'yi tekrar dp'ye çevirmek
 * (`toPx(x, density).dp`) yoğunluğa göre yuvarlama hatası üretir ve iki bitişik
 * bölme arasında 1 px boşluk/çakışma olarak görünür. `offset` piksel alır:
 * yerleşim dp → px bir kez `LocalDensity` ile çevrilir.
 */
@Composable
fun VirtualCanvasSection(
    state: VirtualScreen.State,
    modifier: Modifier = Modifier,
    heightDp: Int = 260,
    nowMs: Long = System.currentTimeMillis(),
) {
    val visible = state.visiblePanes().map { it.pane }
    val density = LocalDensity.current

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
        val layout = VirtualCanvas.layout(
            widthDp = widthDp,
            heightDp = heightDp,
            panes = visible,
            focus = state.focus,
            requested = if (widthDp >= VirtualCanvas.GRID_MIN_WIDTH_DP) {
                VirtualCanvas.Mode.GRID
            } else {
                VirtualCanvas.Mode.SPLIT
            },
        )

        Box(modifier = Modifier.fillMaxWidth().height(heightDp.dp)) {
            if (layout.rects.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Hiçbir bölme açık değil",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Box
            }
            layout.rects.forEach { (pane, rect) ->
                val ps = state.pane(pane)
                val focused = state.focus == pane
                val pxW = with(density) { rect.width.dp.toPx() }.toInt()
                val pxH = with(density) { rect.height.dp.toPx() }.toInt()
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                x = with(density) { rect.left.dp.toPx() }.toInt(),
                                y = with(density) { rect.top.dp.toPx() }.toInt(),
                            )
                        }
                        .size(
                            width = with(density) { pxW.toDp() },
                            height = with(density) { pxH.toDp() },
                        )
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .border(
                            width = if (focused) 2.dp else 1.dp,
                            color = if (focused) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = RoundedCornerShape(10.dp),
                        )
                        .padding(8.dp),
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                pane.id.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.6.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (focused) {
                                Text(
                                    "  · odak",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        val loc = ps?.location?.takeIf { it.isNotBlank() }
                        Text(
                            text = loc ?: when (pane) {
                                VirtualScreen.Pane.BROWSER -> "adres yok"
                                VirtualScreen.Pane.TERMINAL -> "dizin yok"
                                VirtualScreen.Pane.NOTES -> "not yok"
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "kaydırma ${ps?.scrollY ?: 0} · taslak ${ps?.draft?.length ?: 0} kr",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val age = VirtualScreenCaptureAge.text(nowMs, state.lastCaptureByPane[pane])
                        Text(
                            "yakalama: $age",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.lastCaptureByPane[pane] == null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}
