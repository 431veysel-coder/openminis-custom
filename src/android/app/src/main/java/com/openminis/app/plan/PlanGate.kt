package com.openminis.app.plan

/**
 * [OZEL 37083] Plan önizleme kapısı — pahalı işten **önce** tek kart.
 *
 * ## Neden her işte değil
 *
 * Kullanıcıya "5 saniyelik iş için de kart mı" dedirtmek, özelliği ilk günde
 * kapatmanın en hızlı yolu. Bu yüzden eşik var ve eşik **ölçülebilir**:
 * süre, token, geri dönüşsüz işlem sayısı. Eşiğin altındaki iş doğrudan
 * başlar; kullanıcı hiçbir şey görmez.
 *
 * ## Neden "onay" değil "önizleme"
 *
 * Onay, işi durdurur. Önizleme, işi **doğru tanımlar**: kaç model, kaç tur,
 * hangi dosyalar, ne kadar süre. Kullanıcının itirazı genelde "yapma" değil,
 * "yanlış şeyi yapıyorsun" oluyor — bu yüzden kartta planın kendisi görünür,
 * tek düğme "böyle devam et"tir, altında "kapsamı değiştir" vardır.
 *
 * ## Eşik neden sabit değil
 *
 * Aynı 40.000 token bir sohbette 2 dakika, bir konsey koşusunda 20 dakika.
 * Ölçü süre olduğunda eşik cihaza göre değişmeli (yavaş cihazda daha çabuk
 * sor). Bu yüzden [PlanThresholds] dışarıdan veriliyor; varsayılan makul,
 * cihaz profili (bkz. `CouncilPolicy` RAM tespiti) düşürebilir.
 */
data class PlanThresholds(
    val minMinutes: Int = 3,
    val minTokens: Long = 40_000,
    /** Geri dönüşsüz işlem varsa süre/token eşiği yarıya iner. */
    val irreversibleShrinks: Boolean = true,
)

/** Tahmin — modelin kendisi verir; vermezse araç geçmişinden çıkarılır. */
data class RunEstimate(
    val minutes: Int,
    val tokens: Long,
    val rounds: Int = 1,
    val models: Int = 1,
    val filesTouched: Int = 0,
    val irreversibleOps: Int = 0,
    val touchesDeviceOrNetwork: Boolean = false,
    /** Ajanın kendi cümlesiyle plan ("3 dosya düzeltilecek, testler koşacak"). */
    val steps: List<String> = emptyList(),
    /** Aynı plan bu oturumda onaylandıysa hash'i. */
    val planHash: String = "",
)

data class PreviewCard(
    val title: String,
    val bullets: List<String>,
    val warning: String?,
    val approveLabel: String,
    val adjustLabel: String,
)

object PlanGate {

    /**
     * Bu koşu için kart gösterilmeli mi?
     *
     * Dönen `null` = kart yok, iş başlasın. Gerekçeyi de döndürüyoruz çünkü
     * "neden kart çıktı" sorusu ayarlarda cevaplanabilmeli.
     */
    fun shouldPreview(
        estimate: RunEstimate,
        thresholds: PlanThresholds,
        alreadyApprovedHashes: Set<String> = emptySet(),
    ): String? {
        if (estimate.planHash.isNotEmpty() && estimate.planHash in alreadyApprovedHashes) return null

        val shrink = thresholds.irreversibleShrinks && estimate.irreversibleOps > 0
        val minMinutes = if (shrink) maxOf(1, thresholds.minMinutes / 2) else thresholds.minMinutes
        val minTokens = if (shrink) thresholds.minTokens / 2 else thresholds.minTokens

        return when {
            estimate.minutes >= minMinutes -> "${estimate.minutes} dakika sürecek"
            estimate.tokens >= minTokens -> "${estimate.tokens} token harcayacak"
            estimate.irreversibleOps > 0 -> "${estimate.irreversibleOps} geri dönüşsüz işlem içeriyor"
            else -> null
        }
    }

    fun card(estimate: RunEstimate, reason: String): PreviewCard {
        val bullets = buildList {
            add("$reason · ${estimate.rounds} tur · ${estimate.models} model")
            if (estimate.filesTouched > 0) add("${estimate.filesTouched} dosya değişecek")
            if (estimate.touchesDeviceOrNetwork) add("cihaz veya ağ üzerinde işlem var")
            addAll(estimate.steps.take(5))
        }
        val warning = when {
            estimate.irreversibleOps > 0 ->
                "${estimate.irreversibleOps} işlem geri alınamaz. Yine de ara adımlarda onay istenecek."
            estimate.tokens > 200_000 ->
                "Bu koşu bütçenin büyük bir kısmını kullanabilir."
            else -> null
        }
        return PreviewCard(
            title = "Plan: ${estimate.steps.firstOrNull() ?: "uzun bir görev"}",
            bullets = bullets,
            warning = warning,
            approveLabel = "Böyle devam et",
            adjustLabel = "Kapsamı değiştir",
        )
    }
}
