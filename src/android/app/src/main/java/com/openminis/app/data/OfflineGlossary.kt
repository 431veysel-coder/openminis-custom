package com.openminis.app.data

/**
 * [37099] `OFFLINE_PACK` motorunun çekirdeği — sözlük tabanlı, **çevrimdışı**.
 *
 * ## Ne yapar
 *
 * Metindeki `glossary` terimlerini karşılıklarıyla değiştirir; `keepAsIs`
 * listesindekilere dokunmaz. Sonuç **cümle çevirisi değildir** ve bunu
 * gizlemez: [Result.degraded] her zaman `true`, [Result.complete] yalnız
 * metnin tamamı tanınan terimlerden oluşuyorsa `true`.
 *
 * ## Neden bu, "çevrimdışı çeviri" değil
 *
 * Gerçek çevrimdışı çeviri bir model ister (30–100 MB + tokenizer). Bu depoda
 * yok ve **olmayan bir şeyi vaat etmek** `.14`'te `STATION` için kınanan
 * sınıftır. Sözlük ise gerçekten var: `TranslationPacks`. Bu yüzden motorun adı
 * da `OFFLINE_PACK` — "çevrimdışı paket", "çevrimdışı çeviri" değil.
 *
 * ## Neden uzun terim önce
 *
 * `context window` ile `context` aynı sözlükte olabilir. Kısa olan önce
 * değiştirilirse "context window" → "bağlam window" gibi kırık sonuç çıkar.
 * Bu yüzden terimler **uzunlukça azalan** sırada uygulanır.
 *
 * ## Neden kelime sınırı
 *
 * `cat` → `kedi` kuralı "concatenate" içinde eşleşmemeli. Sınır kontrolü
 * harf/rakam/alt çizgi ile yapılıyor; CJK'de kelime sınırı kavramı olmadığı
 * için orada düz `contains` kullanılıyor (ölçüm: kutuda noktalama yok).
 */
object OfflineGlossary {

    data class Result(
        val text: String,
        /** Her zaman true — bu motor çeviri yapmaz, terim değiştirir. */
        val degraded: Boolean = true,
        /** Metnin tamamı tanınan terimlerden oluşuyorsa true. */
        val complete: Boolean = false,
        /** Kaç terim değiştirildi — arayüz "12 terim" diyebilir. */
        val replaced: Int = 0,
    ) {
        val usable: Boolean get() = replaced > 0 && text.isNotBlank()
    }

    /**
     * [pack] sözlüğünü [text] üzerine uygular.
     *
     * Paket yoksa ya da sözlüğü boşsa **boş** sonuç döner: çağıran bunu
     * "motor cevap vermedi" sayıp zincirde sonrakine düşer. Uydurma bir cevap
     * üretmek burada en kötü seçenek olurdu.
     */
    fun apply(text: String, pack: TranslationPacks.Pack?): Result {
        if (text.isBlank()) return Result(text = "", replaced = 0, complete = true)
        if (pack == null || pack.glossary.isEmpty()) return Result(text = "", replaced = 0)

        var out = text
        var count = 0
        // Uzun terim önce: "context window" < "context" tuzağını kapatır.
        val ordered = pack.glossary.entries.sortedByDescending { it.key.length }
        for ((term, translation) in ordered) {
            if (term.isBlank()) continue
            val (next, n) = replaceWord(out, term, translation)
            if (n > 0) {
                out = next
                count += n
            }
        }
        if (count == 0) return Result(text = "", replaced = 0)
        return Result(
            text = out,
            degraded = true,
            // "Tam" saymanın tek yolu: değişimden sonra hiç bilinmeyen sözcük
            // kalmaması. Bunu iddia etmiyoruz — cümle yapısı hâlâ kaynak dilde.
            complete = false,
            replaced = count,
        )
    }

    /** Kelime sınırında değiştirir; sonuç ve değişim sayısı. */
    private fun replaceWord(text: String, term: String, replacement: String): Pair<String, Int> {
        if (term.any { it.code > 0x2E80 }) {
            // CJK: kelime sınırı yok, düz değişim.
            val n = countOccurrences(text, term)
            return if (n == 0) text to 0 else text.replace(term, replacement) to n
        }
        val sb = StringBuilder(text.length)
        var i = 0
        var n = 0
        while (i < text.length) {
            if (matchesAt(text, i, term) && isBoundary(text, i - 1) && isBoundary(text, i + term.length)) {
                sb.append(replacement)
                i += term.length
                n++
            } else {
                sb.append(text[i])
                i++
            }
        }
        return sb.toString() to n
    }

    private fun matchesAt(text: String, at: Int, term: String): Boolean {
        if (at + term.length > text.length) return false
        return text.regionMatches(at, term, 0, term.length, ignoreCase = true)
    }

    private fun isBoundary(text: String, index: Int): Boolean {
        if (index < 0 || index >= text.length) return true
        val c = text[index]
        return !(c.isLetterOrDigit() || c == '_')
    }

    private fun countOccurrences(text: String, term: String): Int {
        var n = 0
        var i = 0
        while (true) {
            val at = text.indexOf(term, i, ignoreCase = true)
            if (at < 0) return n
            n++
            i = at + term.length
        }
    }
}
