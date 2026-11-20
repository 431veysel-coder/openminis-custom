package com.openminis.app.budget

import java.time.Instant
import java.time.ZoneId

/**
 * [OZEL 37081] Harcama defteri ve tavanlar — **para freni**.
 *
 * ## Neden `quota` paketinden ayrı
 *
 * `com.openminis.app.quota` satıcının kotasını izliyor (429, günlük limit,
 * plan). Bu paket kullanıcının **kendi** tavanını izliyor. İkisi karıştırılırsa
 * "kota doldu" ile "senin belirlediğin tavan doldu" aynı cümleyle anlatılır ve
 * kullanıcı hangisinin onun kararı olduğunu bilemez.
 *
 * ## Neden üç kademe
 *
 * Tek tavan yanlış davranır: günlük tavan koyan kullanıcı ayın sonunda sürpriz
 * yaşar, aylık tavan koyan tek bir kaçak koşuda bütün ayı yakar. Üç kademe
 * (sohbet / gün / ay) her birini kendi ölçeğinde tutar; biri patlarsa hangisi
 * olduğu söylenir.
 *
 * ## Neden tahmin ayrı işaretleniyor
 *
 * Üye ve alt ajanların çoğu gerçek `usage` döndürmüyor (bkz. REPO-DENETIMI:
 * `PromptResult`'ta token yok). Tahmini gerçek sayı gibi göstermek, kullanıcının
 * tavanına uyduğunu sanması demek. Tahmin ayrı alanda yaşar; raporda "≈" ile
 * görünür ve tavana sayılırken **yukarı yuvarlanır** (güvenli yön).
 */
data class BudgetLimits(
    val chatTokens: Long? = null,
    val dayTokens: Long? = null,
    val monthTokens: Long? = null,
    val chatUsd: Double? = null,
    val dayUsd: Double? = null,
    val monthUsd: Double? = null,
    /** Uyarı eşiği; tavanın bu oranında sarıya döner. */
    val warnAt: Double = 0.8,
) {
    val isEmpty: Boolean
        get() = listOfNotNull(chatTokens, dayTokens, monthTokens, chatUsd, dayUsd, monthUsd).isEmpty()
}

/** Tek bir harcama kaydı. [estimated] ise sayı gerçek `usage`'dan değil tahminden. */
data class Spend(
    val scopeKey: String,
    val atMs: Long,
    val tokens: Long,
    val usd: Double? = null,
    val estimated: Boolean = false,
    val modelId: String? = null,
    val label: String = "",
)

enum class BudgetVerdict { OK, WARN, STOP }

data class BudgetState(
    val verdict: BudgetVerdict,
    val chatUsed: Long,
    val dayUsed: Long,
    val monthUsed: Long,
    val chatUsd: Double,
    val dayUsd: Double,
    val monthUsd: Double,
    /** Hangi kademe tetikledi: `chat` / `day` / `month`. */
    val trippedBy: String? = null,
    val estimatedShare: Double = 0.0,
    val unknownCostSpends: Int = 0,
    /** Tetikleyen kademenin doluluk oranı (0..∞). Mesaj bunu kullanır. */
    val worstRatio: Double = 0.0,
) {

    val blocksNewTurn: Boolean get() = verdict == BudgetVerdict.STOP

    /** Kullanıcıya tek cümle. Ekranda ve durdurma kartında aynı metin. */
    fun message(): String = when (verdict) {
        BudgetVerdict.OK -> ""
        BudgetVerdict.WARN -> "Bütçenin %${(worstRatio * 100).toInt()} kadarı kullanıldı ($trippedBy)."
        BudgetVerdict.STOP -> "Bütçe tavanı doldu ($trippedBy). Yeni tur başlatılmadı; yarım kalan iş raporlandı."
    }

}

/**
 * Saf defter: disk yok, saat yok (zaman dışarıdan gelir) — bu yüzden test
 * edilebilir. Kalıcılık [BudgetStore]'un işi.
 */
object BudgetLedger {

    fun evaluate(
        limits: BudgetLimits,
        spends: List<Spend>,
        scopeKey: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): BudgetState {
        val now = Instant.ofEpochMilli(nowMs)
        val today = now.atZone(zone).toLocalDate()
        val monthStart = today.withDayOfMonth(1)

        var chat = 0L
        var day = 0L
        var month = 0L
        var chatUsd = 0.0
        var dayUsd = 0.0
        var monthUsd = 0.0
        var est = 0L
        var unknown = 0

        spends.forEach { s ->
            val local = Instant.ofEpochMilli(s.atMs).atZone(zone).toLocalDate()
            val inMonth = !local.isBefore(monthStart)
            val inDay = local == today
            val inChat = s.scopeKey == scopeKey
            if (!inMonth && !inDay && !inChat) return@forEach

            if (inChat) {
                chat += s.tokens
                if (s.usd == null && !s.estimated) unknown++
                chatUsd += s.usd ?: 0.0
            }
            if (inDay) {
                day += s.tokens
                dayUsd += s.usd ?: 0.0
            }
            if (inMonth) {
                month += s.tokens
                monthUsd += s.usd ?: 0.0
            }
            if (inMonth && s.estimated) est += s.tokens
        }

        fun ratio(used: Long, cap: Long?): Double = if (cap == null || cap <= 0) 0.0 else used.toDouble() / cap
        fun usdRatio(used: Double, cap: Double?): Double = if (cap == null || cap <= 0) 0.0 else used / cap

        val levels = listOf(
            Triple("sohbet", ratio(chat, limits.chatTokens), usdRatio(chatUsd, limits.chatUsd)),
            Triple("gün", ratio(day, limits.dayTokens), usdRatio(dayUsd, limits.dayUsd)),
            Triple("ay", ratio(month, limits.monthTokens), usdRatio(monthUsd, limits.monthUsd)),
        )

        val over = levels.firstOrNull { it.second >= 1.0 || it.third >= 1.0 }
        val near = levels.filter { it.second >= limits.warnAt || it.third >= limits.warnAt }
            .maxByOrNull { maxOf(it.second, it.third) }

        val verdict = when {
            over != null -> BudgetVerdict.STOP
            near != null -> BudgetVerdict.WARN
            else -> BudgetVerdict.OK
        }

        return BudgetState(
            verdict = verdict,
            chatUsed = chat,
            dayUsed = day,
            monthUsed = month,
            chatUsd = chatUsd,
            dayUsd = dayUsd,
            monthUsd = monthUsd,
            trippedBy = over?.first ?: near?.first,
            worstRatio = (over ?: near)?.let { maxOf(it.second, it.third) } ?: 0.0,
            estimatedShare = if (month == 0L) 0.0 else est.toDouble() / month,
            unknownCostSpends = unknown,
        )
    }

    /**
     * Yeni tur başlamadan önce tek kapı. `STOP` ise tur **başlamaz** —
     * ortasında durdurmak, yarıda kalan araç çağrısı ve tutarsız dosya
     * bırakmak demek; başlamamak temiz.
     */
    fun mayStartTurn(state: BudgetState): Boolean = !state.blocksNewTurn

    /** Tavana ulaşınca ne raporlanır. "Sessiz harcama" hissini kapatır. */
    fun stopReport(state: BudgetState, partial: String?): String = buildString {
        appendLine(state.message())
        appendLine("Bugün harcanan: ${state.dayUsed} token (tahmini pay: %${(state.estimatedShare * 100).toInt()})")
        if (state.unknownCostSpends > 0) {
            appendLine("${state.unknownCostSpends} çağrının fiyatı bilinmiyor — toplam dolar tutarı eksik olabilir.")
        }
        partial?.takeIf { it.isNotBlank() }?.let { appendLine("Yarım kalan iş: $it") }
    }
}
