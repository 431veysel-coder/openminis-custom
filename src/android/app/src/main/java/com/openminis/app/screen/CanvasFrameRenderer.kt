package com.openminis.app.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.openminis.app.logging.AppLogger
import java.io.ByteArrayOutputStream

/**
 * [37119] Tuvalin **kare üreticisi** — `ScreenTool.capture` için kanıt.
 *
 * ## Ne üretir, ne üretmez (dürüstlük sözleşmesi)
 *
 * Üretir: bölmelerin **durumundan** çizilmiş gerçek bir PNG. Her bölme kendi
 * dikdörtgenine çizilir; içinde bölme adı, adresi/çalışma dizini, kaydırma
 * konumu, taslak uzunluğu ve yakalama zamanı yazar.
 *
 * Üretmez: **canlı sayfa görüntüsü.** Tarayıcı bölmesinde gerçek bir WebView
 * karesi, terminalde gerçek bir PTY çıktısı yok — bölme içeriği henüz canlı bir
 * yüzeye bağlı değil (`SANAL-EKRAN.md` §4 P1).
 *
 * Bu ayrım neden yazılı: kare üretildiğinde model "ekranı gördüm" diye
 * düşünür. Durum karesi bunun için yeterli **değil**; bu yüzden araç sonucu
 * `state frame` ibaresini taşır ve karenin altında da yazar — modelin sayfayı
 * okuduğunu sanmasını engelleyen tek kelime.
 *
 * ## Neden `android.graphics`, Compose değil
 *
 * Compose yakalamak `GraphicsLayer`/`PixelCopy` ister ve ikisi de görünür bir
 * pencere bekler. `android.graphics.Canvas` bir Bitmap üstünde çalışır, pencere
 * istemez, arka planda üretilebilir. Bedeli: canlı içerik yok.
 */
class CanvasFrameRenderer(private val context: Context) {

    private val background = Paint().apply { color = Color.parseColor("#111318") }
    private val paneFill = Paint().apply { color = Color.parseColor("#1B1F26") }
    private val paneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C434F")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val focusStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5B8DEF")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6E9EF")
        textSize = 34f
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8B0BD")
        textSize = 26f
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B7280")
        textSize = 22f
    }

    /** Kare boyutu (piksel). Kare bağlama girmiyor, diske de yazılmıyor. */
    var widthPx: Int = 720
    var heightPx: Int = 1280

    /**
     * Bölmenin durum karesi. `null` = üretilemedi.
     *
     * Her çağrıda tuval **tamamı** çizilir (tek bölme istense bile): modelin
     * gördüğü şey "bu bölme" değil "ekranın hâli" olmalı, yoksa komşu bölmenin
     * yerini bilemez ve iki bölmeyi karıştırır.
     */
    fun frame(pane: VirtualScreen.Pane): ByteArray? = runCatching {
        val state = VirtualScreenHost.state(context)
        if (state.pane(pane)?.visible != true) return null
        val density = 2f
        val wDp = (widthPx / density).toInt()
        val hDp = (heightPx / density).toInt()
        val visible = state.visiblePanes().map { it.pane }
        val layout = VirtualCanvas.layout(wDp, hDp, visible, state.focus, VirtualCanvas.Mode.SPLIT)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), background)

        layout.rects.forEach { (p, r) ->
            val box = RectF(
                r.left * density,
                r.top * density,
                r.right * density,
                r.bottom * density,
            )
            canvas.drawRect(box, paneFill)
            canvas.drawRect(box, if (state.focus == p) focusStroke else paneStroke)

            val ps = state.pane(p)
            var y = box.top + 44f
            val focusNote = if (state.focus == p) "  (focus)" else ""
            canvas.drawText("${p.id.uppercase()}$focusNote", box.left + 24f, y, titlePaint)
            y += 42f
            val loc = ps?.location?.takeIf { it.isNotBlank() }
            val locLine = when {
                loc != null -> "at: $loc"
                p == VirtualScreen.Pane.BROWSER -> "at: (no address yet)"
                p == VirtualScreen.Pane.TERMINAL -> "cwd: (not set)"
                else -> "note: (none)"
            }.take(64)
            canvas.drawText(locLine, box.left + 24f, y, bodyPaint)
            y += 36f
            canvas.drawText("scroll: ${ps?.scrollY ?: 0}", box.left + 24f, y, bodyPaint)
            y += 36f
            canvas.drawText("draft: ${ps?.draft?.length ?: 0} chars", box.left + 24f, y, bodyPaint)
            y += 36f
            val age = VirtualScreenCaptureAge.text(System.currentTimeMillis(), state.lastCaptureByPane[p])
            canvas.drawText("capture: $age", box.left + 24f, y, hintPaint)
        }

        // Alt şerit: durum karesi olduğunu **karenin içinde** de söyle.
        canvas.drawText(
            "state frame — live page/terminal content is not rendered yet",
            24f,
            heightPx - 28f,
            hintPaint,
        )

        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }.onFailure { AppLogger.warning("CanvasFrame", "frame failed: ${it.message}") }.getOrNull()
}

/**
 * [37119] "Son yakalama ne zaman" — ekran ve kare aynı metni kullansın.
 *
 * Neden ayrı: iki yerde iki farklı yaş metni ("3 sn önce" / "just now") aynı
 * ekranı iki farklı anlatır.
 */
object VirtualScreenCaptureAge {

    fun text(nowMs: Long, lastMs: Long?): String {
        if (lastMs == null || lastMs <= 0L) return "never"
        val secs = (nowMs - lastMs).coerceAtLeast(0L) / 1000
        return when {
            secs < 2 -> "just now"
            secs < 60 -> "${secs}s ago"
            secs < 3600 -> "${secs / 60}m ago"
            else -> "${secs / 3600}h ago"
        }
    }
}
