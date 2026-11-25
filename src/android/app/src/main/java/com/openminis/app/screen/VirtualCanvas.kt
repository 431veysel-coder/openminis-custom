package com.openminis.app.screen

/**
 * [37119] **Tek tuval yerleşimi** — saf, Android'siz.
 *
 * ## Neden saf
 *
 * Ekranın nerede duracağı bir ölçü problemi, bir Android problemi değil. Bu
 * cihazda Compose önizlemesi yok, emülatör yok: yerleşimin doğruluğu ancak
 * sayı üzerinden test edilebilir. Ayrıca "aynı bölme iki kez çizilmesin",
 * "320 dp'de taşma olmasın" gibi kurallar görsel inceleme istemez — aritmetik.
 *
 * ## Neden `Float` değil `Int` (dp)
 *
 * `Float` birikimi iki farklı yolda iki farklı piksel üretir ve "kapat-aç
 * yapınca 1 px kaydı" hatası doğar. Tüm hesap tam sayı dp; piksele çevrim tek
 * yerde ([toPx]).
 *
 * ## Düzen üç mod, çünkü üç bölme üç farklı genişlik ister
 *
 * - [Mode.SINGLE]: odaklı bölme tam ekran, diğerleri çizilmez (dar telefon).
 * - [Mode.SPLIT]: iki bölme yan yana, üçüncüsü altta (yatay/telefon).
 * - [Mode.GRID]: iki sütun ızgara — genişlik ≥ [GRID_MIN_WIDTH_DP].
 *
 * Bölme sayısı 1 ise SPLIT/GRID anlamsız: tek bölme tam ekran olur. Bunu
 * çağırana bırakmak, her çağrı yerinde aynı `if`i yazmak demek.
 */
object VirtualCanvas {

    /** Yerleşim modu. */
    enum class Mode { SINGLE, SPLIT, GRID }

    /** Bir bölmenin tuvalde kapladığı dikdörtgen — dp, sol-üst köşe dahil. */
    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height

        /** Nokta içeride mi? Sağ/alt sınır dışarıda: bitişik iki bölme çakışmasın. */
        fun contains(x: Int, y: Int): Boolean =
            x >= left && x < right && y >= top && y < bottom

        fun area(): Int = width.coerceAtLeast(0) * height.coerceAtLeast(0)
    }

    /** Yerleşim sonucu: bölme → dikdörtgen. Sıra çizim sırasıdır. */
    data class Layout(
        val mode: Mode,
        val rects: List<Pair<VirtualScreen.Pane, Rect>>,
        /** Tuvalin tamamı — ölçüm ve ekran görüntüsü boyutu için. */
        val bounds: Rect,
    ) {
        fun rectOf(pane: VirtualScreen.Pane): Rect? = rects.firstOrNull { it.first == pane }?.second

        /**
         * Bir noktaya hangi bölme düşüyor? Çakışma varsa **üstteki** (sırada
         * sonra gelen) kazanır — çizim sırası ile aynı kural.
         */
        fun hitTest(x: Int, y: Int): VirtualScreen.Pane? =
            rects.lastOrNull { it.second.contains(x, y) }?.first

        /** Bölme dikdörtgenleri birbirine değiyor mu? (Taşma testi için.) */
        fun overlaps(): List<Pair<VirtualScreen.Pane, VirtualScreen.Pane>> {
            val out = mutableListOf<Pair<VirtualScreen.Pane, VirtualScreen.Pane>>()
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    val a = rects[i].second
                    val b = rects[j].second
                    val disjoint = a.right <= b.left || b.right <= a.left ||
                        a.bottom <= b.top || b.bottom <= a.top
                    if (!disjoint) out += rects[i].first to rects[j].first
                }
            }
            return out
        }
    }

    const val MIN_WIDTH_DP = 320
    const val GRID_MIN_WIDTH_DP = 600

    /** Bölmeler arası boşluk ve dış kenar payı. */
    const val GAP_DP = 4
    const val PADDING_DP = 0

    /**
     * Yerleşimi hesaplar.
     *
     * @param widthDp tuvalin genişliği
     * @param heightDp tuvalin yüksekliği
     * @param panes **görünür** bölmeler, çizim sırasında
     * @param focus odaklı bölme; `SINGLE` modda hangisinin tam ekran olacağı
     * @param requested istenen mod; sığmıyorsa düşürülür
     */
    fun layout(
        widthDp: Int,
        heightDp: Int,
        panes: List<VirtualScreen.Pane>,
        focus: VirtualScreen.Pane? = null,
        requested: Mode = Mode.SPLIT,
    ): Layout {
        val w = widthDp.coerceAtLeast(0)
        val h = heightDp.coerceAtLeast(0)
        val bounds = Rect(0, 0, w, h)
        if (panes.isEmpty() || w <= 0 || h <= 0) {
            return Layout(Mode.SINGLE, emptyList(), bounds)
        }

        // Mod düşürme: istenen mod bu genişlikte/bu sayıda anlamlı mı?
        val mode = when {
            panes.size == 1 -> Mode.SINGLE
            requested == Mode.GRID && w < GRID_MIN_WIDTH_DP -> Mode.SPLIT
            requested == Mode.SINGLE -> Mode.SINGLE
            else -> requested
        }

        return when (mode) {
            Mode.SINGLE -> {
                // Tam ekran bölme: odak varsa o, yoksa ilk görünür.
                val hero = focus?.takeIf { it in panes } ?: panes.first()
                Layout(Mode.SINGLE, listOf(hero to bounds), bounds)
            }

            Mode.SPLIT -> split(w, h, panes, bounds)
            Mode.GRID -> grid(w, h, panes, bounds)
        }
    }

    /**
     * İki sütun yerleşim: ilk iki bölme yan yana, üçüncü tam genişlik altta.
     *
     * Neden bu sıra: kullanıcının en sık baktığı iki bölme (tarayıcı + terminal)
     * yan yana olmalı; notlar alt şeritte kısa bir metin alanıdır.
     */
    private fun split(
        w: Int,
        h: Int,
        panes: List<VirtualScreen.Pane>,
        bounds: Rect,
    ): Layout {
        val rects = mutableListOf<Pair<VirtualScreen.Pane, Rect>>()
        when (panes.size) {
            1 -> rects += panes[0] to bounds
            2 -> {
                val each = (w - GAP_DP) / 2
                rects += panes[0] to Rect(0, 0, each, h)
                rects += panes[1] to Rect(each + GAP_DP, 0, w - each - GAP_DP, h)
            }
            else -> {
                val each = (w - GAP_DP) / 2
                // Üst şerit: iki bölme. Alt şerit: kalan bölmeler eşit paylaşır.
                val topH = h * 2 / 3
                val bottomH = h - topH - GAP_DP
                rects += panes[0] to Rect(0, 0, each, topH)
                rects += panes[1] to Rect(each + GAP_DP, 0, w - each - GAP_DP, topH)
                val rest = panes.drop(2)
                if (rest.isNotEmpty()) {
                    val rw = (w - GAP_DP * (rest.size - 1)) / rest.size
                    rest.forEachIndexed { i, pane ->
                        val left = i * (rw + GAP_DP)
                        val width = if (i == rest.size - 1) w - left else rw
                        rects += pane to Rect(left, topH + GAP_DP, width, bottomH)
                    }
                }
            }
        }
        return Layout(Mode.SPLIT, rects, bounds)
    }

    /** İki sütun ızgara: satır sayısı bölme sayısından çıkar. */
    private fun grid(
        w: Int,
        h: Int,
        panes: List<VirtualScreen.Pane>,
        bounds: Rect,
    ): Layout {
        val cols = 2
        val rows = (panes.size + cols - 1) / cols
        val cellW = (w - GAP_DP * (cols - 1)) / cols
        val cellH = (h - GAP_DP * (rows - 1)) / rows
        val rects = panes.mapIndexed { i, pane ->
            val r = i / cols
            val c = i % cols
            val left = c * (cellW + GAP_DP)
            val top = r * (cellH + GAP_DP)
            // Son sütun/satır: kalan pikseli al (yuvarlama boşluğu kenarda
            // kalmasın, iki bölme arasında 1 px çizgi olarak görünmesin).
            pane to Rect(
                left = left,
                top = top,
                width = if (c == cols - 1) w - left else cellW,
                height = if (r == rows - 1) h - top else cellH,
            )
        }
        return Layout(Mode.GRID, rects, bounds)
    }

    /**
     * dp → px. Tek çevrim noktası: yerleşim tam sayı dp ile karar verir, piksel
     * yalnız çizimde doğar.
     */
    fun toPx(dp: Int, density: Float): Int = (dp * density).toInt()

    /**
     * 320 dp'de taşma var mı? Kapı/test için: her mod, 1..3 bölme, iki yükseklik.
     *
     * Neden ayrı fonksiyon: aynı soruyu testte ve ekranda iki kez yazmak,
     * iki farklı cevap üretir.
     */
    fun overflowCases(): List<String> {
        val out = mutableListOf<String>()
        val panes = VirtualScreen.Pane.entries
        for (n in 1..panes.size) {
            for (mode in Mode.entries) {
                for (h in listOf(480, 800)) {
                    val l = layout(MIN_WIDTH_DP, h, panes.take(n), panes.first(), mode)
                    if (l.overlaps().isNotEmpty()) {
                        out += "n=$n mode=$mode h=$h overlaps=${l.overlaps()}"
                    }
                    val outside = l.rects.filter {
                        it.second.left < 0 || it.second.top < 0 ||
                            it.second.right > MIN_WIDTH_DP || it.second.bottom > h
                    }
                    if (outside.isNotEmpty()) {
                        out += "n=$n mode=$mode h=$h outside=${outside.map { it.first }}"
                    }
                }
            }
        }
        return out
    }
}
