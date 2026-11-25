package com.openminis.app.ui.team

/**
 * [37122] Karşılaştırma odasının **matris yerleşimi** — saf, Android'siz.
 *
 * ## Neden saf
 *
 * Tablo yerleşimi aritmetik. Ama bu depoda JVM/emülatör yok: "dar ekranda
 * taşma yok" iddiasını görerek değil **sayarak** doğrulamak zorundayım. Ayrıca
 * 5 üye × 8 iddia'da sütun genişliğini her yerde yeniden hesaplamak, iki farklı
 * tablo üretir.
 *
 * ## Neden yatay kaydırma, neden sütun daraltma değil
 *
 * Üye adları 96 dp'de kesiliyor (`Ayşe Y.` → `Ayşe…`) ve kesilmiş sütun
 * başlığı, hangi sütunun kime ait olduğunu belirsiz bırakır — o belirsizlik
 * tabloyu okunamaz yapar. Bu yüzden sütun **daraltılmaz**; tablo kayar ve
 * iddia sütunu **sabit** kalır (`claimColumn`), böylece satır kaybolmaz.
 *
 * ## Neden `MIN_COLUMN_DP` var
 *
 * Bir sütuna 40 dp verilirse içine tek karakter sığar ve tablo "boş" görünür.
 * Sütun genişliği alt sınırın altına inmiyorsa tek doğru cevap kaydırmadır.
 */
object ClaimMatrixLayout {

    const val CLAIM_COLUMN_DP = 168
    const val MIN_COLUMN_DP = 88
    const val DEFAULT_COLUMN_DP = 96

    /** Bir üye sütunu. */
    data class Column(val member: String, val widthDp: Int)

    /**
     * Sonuç.
     *
     * @param claimWidthDp iddia sütunu — kaydırırken sabit kalır
     * @param columns üye sütunları, kayıttaki sırayla
     * @param totalWidthDp tablonun tamamı (kaydırılan içerik genişliği)
     * @param needsScroll içerik ekrana sığmıyor mu
     */
    data class Result(
        val claimWidthDp: Int,
        val columns: List<Column>,
        val totalWidthDp: Int,
        val needsScroll: Boolean,
    ) {
        /** Bir iddia satırında bir üyenin hücresinin sol kenarı (iddia sütunundan sonra). */
        fun leftOf(member: String): Int? {
            var x = claimWidthDp
            for (c in columns) {
                if (c.member == member) return x
                x += c.widthDp
            }
            return null
        }
    }

    /**
     * Yerleşimi hesaplar.
     *
     * @param members sütun olacak üyeler (yalnız oy vermiş olanlar — çağıran süzer)
     * @param availableWidthDp ekranın kullanılabilir genişliği
     */
    fun compute(
        members: List<String>,
        availableWidthDp: Int,
        claimWidthDp: Int = CLAIM_COLUMN_DP,
    ): Result {
        val available = availableWidthDp.coerceAtLeast(0)
        if (members.isEmpty()) {
            return Result(claimWidthDp, emptyList(), claimWidthDp, false)
        }

        // Sığdırma denemesi: eşit pay. Her sütun MIN'in altına inerse eşit pay
        // yerine MIN kullanılır ve tablo kaydırılır.
        val room = available - claimWidthDp
        val even = if (room > 0) room / members.size else 0
        val width = when {
            even >= DEFAULT_COLUMN_DP -> even
            even >= MIN_COLUMN_DP -> even
            else -> DEFAULT_COLUMN_DP
        }

        val columns = members.map { Column(it, width) }
        val total = claimWidthDp + columns.sumOf { it.widthDp }
        return Result(
            claimWidthDp = claimWidthDp,
            columns = columns,
            totalWidthDp = total,
            needsScroll = total > available,
        )
    }

    /**
     * Durum işareti ve rengi için tek kaynak.
     *
     * Neden saf: aynı eşleme iki yerde yazılırsa biri "destek", diğeri
     * "support" bekler ve tabloda boş hücre görünür (sessiz hata).
     */
    enum class Stance(val wire: String, val mark: String) {
        SUPPORT("support", "✓"),
        OPPOSE("oppose", "✗"),
        UNCERTAIN("uncertain", "?"),
        ;

        companion object {
            fun of(wire: String?): Stance? = entries.firstOrNull { it.wire == wire }
        }
    }

    /**
     * Cevabın **yaklaşık** eşlendiğini işaretler ([37122 · §1.6]).
     *
     * Protokolde "şu soruya cevap" alanı yok; cevap, hedef üyenin **sonraki
     * turdaki** pozisyonundan eşleniyor. Bu bir çıkarım, kayıt değil. Ekran
     * bunu söylemezse kullanıcı eşlemeyi resmî cevap sanar — ve yanlış üyeye
     * atfedilmiş bir cevap, cevapsızlıktan daha zararlıdır.
     */
    fun answerMark(answeredRound: Int, questionRound: Int): String =
        if (answeredRound <= 0) "" else "≈ tur ${answeredRound} (soru tur ${questionRound})"
}
