package com.openminis.app.agent.lesson

/**
 * [OZEL 37086] Ders defteri — hatalardan **öneri** üretmek, kendiliğinden yazmamak.
 *
 * ## Neden otomatik yazmıyor
 *
 * `self-improving-agent` becerisi bu fikri anlatıyor ama uygulamaya gömülü
 * değil. Doğrudan hafızaya yazsaydı, her araç hatası kalıcı bir "kural" olurdu
 * ve model kendi hatalarını kanun hâline getirirdi. Bu yüzden üç adım:
 * **sinyal → öneri → kullanıcı onayı**. Onaylanan öneri normal `memory_write`
 * gibi girer, ayrı bir yol yok.
 *
 * ## Neden kişisel hafıza
 *
 * Proje hafızası (37070–37079) ayrı bir çalışmanın konusu. Bu özellik oraya
 * yazarsa iki çalışma birbirine bağlanır ve biri gecikince diğeri durur.
 * Ders defteri kişisel hafızada yaşar; proje bağlamı gerekiyorsa metnin içinde
 * geçer, dosya yolunda değil.
 *
 * ## Neden üç sinyal
 *
 * Tek sinyal yanlış pozitif üretir: bir araç hatası her zaman ders değil
 * (ağ gitti). Ama **aynı hata + kullanıcı düzeltmesi** ya da **aynı hata üç
 * turda tekrar** gerçek ders. Üçü birleşince güven artıyor; tek tek
 * bakıldığında değil.
 */
data class ToolErrorSignal(
    val tool: String,
    val message: String,
    val turnIndex: Int,
    val command: String = "",
)

data class CorrectionSignal(val text: String, val turnIndex: Int)

data class LoopSignal(val turnIndex: Int, val repeatedCalls: Int)

/** Bir öneri. [key] tekilleştirme için: aynı ders iki kez önerilmez. */
data class LessonProposal(
    val key: String,
    val text: String,
    val evidence: List<String>,
    val confidence: Confidence,
    val suggestedTool: String = "memory_write",
) {
    enum class Confidence { LOW, MEDIUM, HIGH }
}

object LessonDetector {

    /** Aynı hatanın kaç tur içinde tekrarı "ders" sayılır. */
    const val REPEAT_WINDOW = 3

    private val correctionCues = listOf(
        "hayır", "hayir", "değil", "degil", "yanlış", "yanlis", "öyle değil",
        "no,", "not quite", "wrong", "instead", "yerine", "bir daha", "tekrar yapma",
    )

    fun detect(
        errors: List<ToolErrorSignal>,
        corrections: List<CorrectionSignal>,
        loops: List<LoopSignal>,
        /** Daha önce önerilmiş/onaylanmış derslerin anahtarları. */
        knownKeys: Set<String> = emptySet(),
    ): List<LessonProposal> {
        val out = mutableListOf<LessonProposal>()

        // 1) Aynı araç + aynı hata metni, pencerede tekrar ediyor mu?
        errors.groupBy { it.tool to normalize(it.message) }.forEach { (k, list) ->
            val turns = list.map { it.turnIndex }
            val repeats = turns.size >= 2 && (turns.max() - turns.min()) <= REPEAT_WINDOW
            if (!repeats) return@forEach
            val key = "err:${k.first}:${k.second.take(40)}"
            if (key in knownKeys) return@forEach
            out += LessonProposal(
                key = key,
                text = buildString {
                    append("`${k.first}` aracında tekrar eden hata: ${k.second.take(120)}. ")
                    append("Aynı çağrıyı aynen tekrarlamak yerine ")
                    append(suggestionFor(k.first, k.second))
                },
                evidence = list.take(3).map { "tur ${it.turnIndex}: ${it.message.take(80)}" },
                confidence = if (list.size >= 3) LessonProposal.Confidence.HIGH else LessonProposal.Confidence.MEDIUM,
            )
        }

        // 2) Kullanıcı düzeltti mi? Düzeltme metni dersin kendisi olur.
        corrections.forEach { c ->
            if (!correctionCues.any { normalize(c.text).contains(it) }) return@forEach
            val key = "corr:${normalize(c.text).take(60)}"
            if (key in knownKeys) return@forEach
            out += LessonProposal(
                key = key,
                text = "Kullanıcı düzeltmesi: ${c.text.trim().take(200)}".trim(),
                evidence = listOf("tur ${c.turnIndex}: ${c.text.take(80)}"),
                confidence = LessonProposal.Confidence.HIGH,
            )
        }

        // 3) Döngü: aynı turda çok sayıda tekrar. Metni yok, davranışı var.
        loops.filter { it.repeatedCalls >= 3 }.forEach { l ->
            val key = "loop:${l.turnIndex / REPEAT_WINDOW}"
            if (key in knownKeys) return@forEach
            out += LessonProposal(
                key = key,
                text = "Tur ${l.turnIndex}: ${l.repeatedCalls} kez aynı çağrı tekrarlandı. " +
                    "Sonucu okumadan yeniden denemek yerine çıktıyı ayrıştırıp karar verin.",
                evidence = listOf("tur ${l.turnIndex}: ${l.repeatedCalls} tekrar"),
                confidence = LessonProposal.Confidence.LOW,
            )
        }

        return out.distinctBy { it.key }
    }

    /**
     * Hata metninden somut ipucu. Genel tavsiye ("daha dikkatli ol") işe
     * yaramaz; ders ancak bir sonraki denemede ne yapılacağını söylüyorsa ders.
     */
    private fun suggestionFor(tool: String, message: String): String {
        val m = normalize(message)
        return when {
            m.contains("no such file") || m.contains("bulunamadi") -> "önce `file_read`/`ls` ile yolun varlığını doğrulayın"
            m.contains("permission") || m.contains("izin") -> "yolu değiştirin ya da kullanıcıdan izin isteyin; aynı komutu tekrarlamayın"
            m.contains("timeout") || m.contains("zaman asimi") -> "komutu parçalara bölün ya da alt ajana devredin"
            m.contains("json") || m.contains("parse") -> "çıktıyı dosyaya yazıp `file_read` ile okuyun; büyük çıktı doğrudan ayrıştırılmasın"
            tool == "shell_execute" && m.contains("not found") -> "önce `which <araç>` ile kurulu mu bakın, sonra kurun"
            else -> "hata metnini okuyup çağrıyı buna göre değiştirin"
        }
    }

    private fun normalize(s: String): String = s.lowercase()
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        .replace(Regex("""\s+"""), " ")
        .trim()
}
