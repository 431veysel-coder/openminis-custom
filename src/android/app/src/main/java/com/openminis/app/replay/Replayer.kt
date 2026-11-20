package com.openminis.app.replay

/**
 * [OZEL 37089] Kayıtlı yeniden oynatma — "model değişti, iyileşti mi?"
 *
 * ## Neden bu, test yazmaktan farklı
 *
 * Birim testler kodu ölçer. Kullanıcının asıl sorusu modelin **davranışını**
 * ölçmek: aynı görev, aynı araç çıktıları, iki farklı model — hangisi daha az
 * turda bitiriyor, hangisi daha az yanlış yapıyor. Bunu ölçmenin tek dürüst
 * yolu, geçmiş bir koşuyu **araç çıktıları dondurulmuş** hâlde yeniden
 * koşturmak. Ağ, dosya sistemi ve saat sabitlenir; değişen tek şey model.
 *
 * ## Neden "kayıt" ayrı bir biçim
 *
 * Sohbet geçmişi bunun için yeterli değil: geçmişte araç çıktıları var ama
 * hangi çağrının hangi çıktıyı ürettiği eşlemesi kaybolabiliyor, ayrıca
 * sıralı yeniden üretim için `assistant → tool → assistant` zincirinin
 * birebir olması gerekiyor. Bu yüzden kayıt, **araç sonucu anahtarlı**:
 * `(araç adı + normalize argüman) → sonuç`. Aynı çağrı tekrar gelirse kayıttan
 * döner, gelmezse [ReplayMiss] üretilir — sessizce canlı çağrı yapmaz.
 *
 * ## Neden canlı çağrıya düşmüyor
 *
 * Düşseydi ölçüm kirlenir: bir model farklı bir komut yazdığında ağdan cevap
 * gelir, iki koşu farklı koşullarda karşılaştırılmış olur. Iskalamayı
 * **sonuç** olarak raporlamak, ölçümü dürüst tutan şey. Iskalama oranı da
 * başlı başına bir metrik: model ne kadar "beklenmedik" davranıyor.
 */
data class ToolCallKey(val tool: String, val argsHash: String) {
    companion object {
        /**
         * Argüman normalizasyonu: yol ayırıcı, boşluk ve sıra farkı anahtarı
         * değiştirmemeli. Aksi hâlde aynı çağrı her koşuda ıskalar.
         */
        fun of(tool: String, args: Map<String, String>): ToolCallKey {
            val canon = args.toSortedMap().entries
                .joinToString("\u0001") { (k, v) -> "$k=${canon(v)}" }
            return ToolCallKey(tool, canon)
        }

        private fun canon(v: String): String = v.trim()
            .replace(Regex("""\s+"""), " ")
            .replace('\\', '/')
    }
}

data class ReplayEntry(
    val key: ToolCallKey,
    val result: String,
    val turnIndex: Int,
    val ok: Boolean = true,
)

data class ReplayRecording(
    val id: String,
    val sessionId: String,
    val createdAtMs: Long,
    val modelId: String?,
    val prompt: String,
    val entries: List<ReplayEntry>,
    /** Orijinal koşunun tur sayısı — yeniden koşuyla karşılaştırılır. */
    val originalTurns: Int,
)

/** Yeniden koşunun sonucu. */
data class ReplayOutcome(
    val replayId: String,
    val modelId: String?,
    val turns: Int,
    val hits: Int,
    val misses: List<ToolCallKey>,
    val completed: Boolean,
    val finalText: String,
) {
    val missRate: Double get() = (hits + misses.size).let { if (it == 0) 0.0 else misses.size.toDouble() / it }

    /** İki koşuyu tek satırda karşılaştırır — kullanıcının gördüğü rapor. */
    fun compareWith(original: ReplayRecording): String = buildString {
        append("${original.modelId ?: "?"} → ${modelId ?: "?"} · ")
        append("tur ${original.originalTurns} → $turns · ")
        append("ıskalama %${(missRate * 100).toInt()} · ")
        append(if (completed) "tamamlandı" else "tamamlanmadı")
    }
}

sealed class ReplayStep {
    data class Serve(val result: String, val ok: Boolean) : ReplayStep()
    data class Miss(val key: ToolCallKey) : ReplayStep()
}

/**
 * Yeniden oynatıcı. Saf: ağ yok, disk yok, saat yok.
 *
 * Tek kural: kayıtta olmayan çağrı **çalıştırılmaz**. Bu, ölçümün tek
 * güvencesi; esnetilirse özellik kendi amacını kaybeder.
 */
class Replayer(private val recording: ReplayRecording) {

    private val index: Map<ToolCallKey, ReplayEntry> = recording.entries.associateBy { it.key }
    private var served = 0
    private val misses = mutableListOf<ToolCallKey>()

    fun step(tool: String, args: Map<String, String>): ReplayStep {
        val key = ToolCallKey.of(tool, args)
        val entry = index[key]
        return if (entry != null) {
            served++
            ReplayStep.Serve(entry.result, entry.ok)
        } else {
            misses += key
            ReplayStep.Miss(key)
        }
    }

    fun outcome(modelId: String?, turns: Int, completed: Boolean, finalText: String): ReplayOutcome =
        ReplayOutcome(recording.id, modelId, turns, served, misses.toList(), completed, finalText)

    /** Kayıt kendi kendini doğrular: aynı anahtar iki kez varsa hangisi geçerli? */
    fun selfCheck(): List<String> {
        val problems = mutableListOf<String>()
        val dupes = recording.entries.groupBy { it.key }.filter { it.value.size > 1 }
        dupes.forEach { (k, v) ->
            problems.add("aynı çağrı ${v.size} kez kaydedilmiş (${k.tool}) — son kayıt kullanılır")
        }
        if (recording.entries.isEmpty()) problems.add("kayıtta hiç araç çağrısı yok, yeniden oynatma anlamsız")
        return problems
    }
}
