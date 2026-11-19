package com.openminis.app.ozel

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.budget.BudgetLedger
import com.openminis.app.budget.BudgetLimits
import com.openminis.app.budget.BudgetState
import com.openminis.app.budget.BudgetVerdict
import com.openminis.app.budget.Spend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId

/**
 * [37081] Bütçe defterinin **kalıcı** hâli.
 *
 * `BudgetLedger` saf: liste alır, karar döndürür. Burada liste diske yazılıyor,
 * tavanlar ayarlardan okunuyor, sonuç arayüzün okuyacağı akışa konuyor.
 *
 * ## Neden yalnız 40 günlük harcama tutuluyor
 *
 * Aylık tavan ay başını görür; 40 gün her kademeyi kapsar ve dosya küçük kalır.
 * Daha fazlasını tutmak, sınırsız büyüyen bir günlüğe dönüşür — kimse okumaz,
 * ama her turda ayrıştırılırdı.
 *
 * ## Neden `estimated` ayrı işaret
 *
 * Üye/alt ajan çağrılarının çoğu gerçek `usage` döndürmüyor. Tahmini gerçek
 * sayı gibi göstermek "tavanıma uydum" yanılgısı üretir; bu yüzden ayrı alan
 * ve raporda "≈".
 */
object BudgetStore {

    private const val PREFS = "ozel_budget"
    private const val FILE = "ozel-budget.json"
    private const val KEEP_MS = 40L * 24 * 3600 * 1000

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    // ---- tavanlar ------------------------------------------------------

    fun limits(ctx: Context): BudgetLimits {
        val p = prefs(ctx)
        fun longOrNull(key: String): Long? = p.getLong(key, -1L).takeIf { it >= 0 }
        fun doubleOrNull(key: String): Double? = p.getFloat(key, -1f).takeIf { it >= 0f }?.toDouble()
        return BudgetLimits(
            chatTokens = longOrNull("chatTokens"),
            dayTokens = longOrNull("dayTokens"),
            monthTokens = longOrNull("monthTokens"),
            chatUsd = doubleOrNull("chatUsd"),
            dayUsd = doubleOrNull("dayUsd"),
            monthUsd = doubleOrNull("monthUsd"),
        )
    }

    fun setLimits(ctx: Context, limits: BudgetLimits) {
        prefs(ctx).edit().apply {
            fun put(key: String, v: Long?) = if (v == null) remove(key) else putLong(key, v)
            fun putF(key: String, v: Double?) = if (v == null) remove(key) else putFloat(key, v.toFloat())
            put("chatTokens", limits.chatTokens)
            put("dayTokens", limits.dayTokens)
            put("monthTokens", limits.monthTokens)
            putF("chatUsd", limits.chatUsd)
            putF("dayUsd", limits.dayUsd)
            putF("monthUsd", limits.monthUsd)
        }.apply()
    }

    // ---- harcamalar ----------------------------------------------------

    private fun load(ctx: Context): MutableList<Spend> {
        val f = file(ctx)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val cutoff = System.currentTimeMillis() - KEEP_MS
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val at = o.optLong("at")
                if (at < cutoff) return@mapNotNull null
                Spend(
                    scopeKey = o.optString("s"),
                    atMs = at,
                    tokens = o.optLong("t"),
                    usd = if (o.has("u")) o.optDouble("u") else null,
                    estimated = o.optBoolean("e", false),
                    modelId = o.optString("m").takeIf { it.isNotEmpty() },
                )
            }.toMutableList()
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    private fun save(ctx: Context, spends: List<Spend>) {
        runCatching {
            val arr = JSONArray()
            spends.forEach { s ->
                arr.put(
                    JSONObject().apply {
                        put("s", s.scopeKey)
                        put("at", s.atMs)
                        put("t", s.tokens)
                        s.usd?.let { put("u", it) }
                        put("e", s.estimated)
                        s.modelId?.let { put("m", it) }
                    },
                )
            }
            file(ctx).writeText(arr.toString())
        }
    }

    /** Bir istek tamamlandığında çağrılır. Sessiz: bütçe kaydı turu düşürmez. */
    fun record(
        ctx: Context,
        sessionId: String,
        tokens: Long,
        usd: Double? = null,
        estimated: Boolean = false,
        modelId: String? = null,
    ) {
        if (tokens <= 0) return
        runCatching {
            val spends = load(ctx)
            spends += Spend(sessionId, System.currentTimeMillis(), tokens, usd, estimated, modelId)
            save(ctx, spends)
            evaluate(ctx, sessionId)
        }
    }

    // ---- değerlendirme -------------------------------------------------

    private val _state = MutableStateFlow<Map<String, BudgetState>>(emptyMap())
    val state: StateFlow<Map<String, BudgetState>> = _state.asStateFlow()

    fun evaluate(ctx: Context, sessionId: String): BudgetState {
        val st = BudgetLedger.evaluate(
            limits(ctx), load(ctx), sessionId, System.currentTimeMillis(), ZoneId.systemDefault(),
        )
        _state.value = _state.value + (sessionId to st)
        return st
    }

    /**
     * Tur başlamadan önce tek kapı. `false` ise yeni tur **açılmaz** —
     * ortasında durdurmak yarım araç çağrısı ve tutarsız dosya bırakır.
     */
    fun mayStartTurn(ctx: Context, sessionId: String): BudgetState {
        val st = evaluate(ctx, sessionId)
        return st
    }

    fun isBlocking(ctx: Context, sessionId: String): Boolean =
        mayStartTurn(ctx, sessionId).verdict == BudgetVerdict.STOP

    /** Ayarlar ekranı için tek satırlık özet. */
    fun summaryLine(ctx: Context, sessionId: String): String {
        val st = evaluate(ctx, sessionId)
        val l = limits(ctx)
        if (l.isEmpty) return "tavan yok"
        return "sohbet ${st.chatUsed}/${l.chatTokens ?: "∞"} · gün ${st.dayUsed}/${l.dayTokens ?: "∞"} · ay ${st.monthUsed}/${l.monthTokens ?: "∞"}"
    }
}
