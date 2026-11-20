package com.openminis.app.ozel.health

import kotlin.math.roundToInt

/**
 * [37095] Sohbet sağlık göstergesinin çekirdeği — **Android'siz**.
 *
 * Neden ayrı dosya: aynı hesap üç yerde gerekiyor (chip, ayrıntı kartı, test).
 * Compose içinde hesaplamak onu ne test edilebilir ne de yeniden kullanılabilir
 * yapar; ChatViewModel içinde hesaplamak 20 000 satırlık dosyaya bir kural daha
 * ekler. Burada tek yerde duruyor ve `hide` yok — saf veri.
 *
 * Amaç, kullanıcının sorusunu tek bakışta cevaplamak: "cevap veriyor mu,
 * yavaş mı, koptu mu, tekrar mı deniyor?" Bugün dördü de aynı üç nokta gibi
 * görünüyor.
 */

/** Chip'in göstereceği durum. Her durumun ayrı ikonu var; renk tek başına anlam taşımaz. */
enum class HealthState {
    /** Hiç istek yok ya da son istek bitti. */
    IDLE,

    /** İstek yolda, sağlayıcıdan henüz parça gelmedi. */
    WAITING,

    /** Parça akıyor. */
    STREAMING,

    /** Hata sonrası geri çekilme sayacı işliyor. */
    RETRYING,

    /** Son istek hata ile bitti. */
    ERROR,

    /** Sağlayıcıya hiç ulaşılamıyor (ağ yok / DNS / sertifika). */
    OFFLINE,
}

/**
 * Tek bir anlık görüntü. Hepsi `Long?` ya da ilkel tip: kutu yok, StateFlow
 * eşitlik karşılaştırması ucuz (chip her 250 ms'de bunu okuyor).
 */
data class ChatHealth(
    val state: HealthState = HealthState.IDLE,
    /** Son 20 ölçümün medyanı — titremeyen sayı. */
    val p50TtfbMs: Long? = null,
    /** Son isteğin ilk parça gecikmesi (ham). */
    val lastTtfbMs: Long? = null,
    /** Son isteğin toplam süresi. */
    val lastTotalMs: Long? = null,
    /** Kaçıncı tekrar deneme (0 = yok). */
    val retry: Int = 0,
    val retryMax: Int = 0,
    /** Geri çekilme saniyesi — RETRYING iken canlı sayar. */
    val retryInMs: Long? = null,
    /** Üretim hızı (jeton/sn); yalnız akış bitince bilinir. */
    val tokensPerSec: Double? = null,
    /** HTTP kodu, biliniyorsa (429/503/401...). */
    val httpStatus: Int? = null,
    /** Kısa hata etiketi: "429", "503", "NET", "AUTH", "?". */
    val errorLabel: String? = null,
    /** WAITING/RETRYING sayacının başladığı an (monoton saat). */
    val sinceMs: Long? = null,
    /** Kaç ölçüm birikti (p50'nin güvenilirliği için). */
    val samples: Int = 0,
) {
    /**
     * Chip'in tek satırlık metni. `nowMs` dışarıdan gelir çünkü WAITING
     * durumunda sayı **canlı** artar; veri sınıfının kendi kendine saat okuması
     * onu test edilemez yapardı.
     */
    fun headline(nowMs: Long): String = when (state) {
        HealthState.IDLE -> p50TtfbMs?.let(::fmtLatency) ?: "—"
        HealthState.WAITING -> fmtLatency((nowMs - (sinceMs ?: nowMs)).coerceAtLeast(0))
        HealthState.STREAMING -> buildString {
            append(p50TtfbMs?.let(::fmtLatency) ?: "—")
            // Hız yalnız önceki turdan bilinir; bu turda henüz yok.
            tokensPerSec?.let { append(' ').append(fmtRate(it)) }
        }
        HealthState.RETRYING -> "$retry/$retryMax"
        HealthState.ERROR -> errorLabel ?: "!"
        HealthState.OFFLINE -> "—"
    }
}

/** Süreyi kısa metne çevirir: 820 → "820ms", 3200 → "3.2s", 12500 → "12s". */
fun fmtLatency(ms: Long): String {
    val v = if (ms < 0) 0 else ms
    return when {
        v < 1_000 -> "${v}ms"
        v < 10_000 -> {
            val tenths = v / 100                      // 3200 → 32
            "${tenths / 10}.${tenths % 10}s"
        }
        else -> "${v / 1_000}s"
    }
}

/** Üretim hızı: 14.37 → "14.4t/s". Bir ondalık, çünkü ikincisi gürültü. */
fun fmtRate(tokensPerSec: Double): String {
    val tenths = (tokensPerSec * 10).roundToInt().coerceAtLeast(0)
    return "${tenths / 10}.${tenths % 10}t/s"
}

/** Accessibility metni — renk/ikon görmeyen kullanıcı için durumu cümleyle söyler. */
fun describeHealth(h: ChatHealth): String = when (h.state) {
    HealthState.IDLE -> h.p50TtfbMs?.let { "Hazır, son yanıt $it ms'de başladı" } ?: "Hazır"
    HealthState.WAITING -> "Yanıt bekleniyor"
    HealthState.STREAMING -> h.lastTtfbMs?.let { "Yanıt akıyor, ilk parça $it ms" } ?: "Yanıt akıyor"
    HealthState.RETRYING -> "Tekrar deneniyor ${h.retry}/${h.retryMax}"
    HealthState.ERROR -> "Hata ${h.errorLabel ?: ""}".trim()
    HealthState.OFFLINE -> "Sağlayıcıya ulaşılamıyor"
}

/**
 * Ölçüm toplayıcı. Saat dışarıdan gelir: testte `+1000` diyebilmek için.
 *
 * Tek kural: **her giriş noktası her zaman aynı çıktıyı verir** — aynı olay
 * dizisi aynı [ChatHealth]'i üretir. Rastgelelik, duvar saati, thread yok.
 */
class ChatHealthTracker(
    private val clock: () -> Long,
    private val windowSize: Int = 20,
) {
    private val ttfbWindow = ArrayDeque<Long>()
    private var health = ChatHealth()
    private var startedAtMs: Long = 0
    private var retryStartedAtMs: Long? = null
    private var retryBackoffMs: Long = 0

    val current: ChatHealth get() = health

    fun snapshot(): ChatHealth = health

    /** İstek yola çıktı. Tekrar sayacı korunur: aynı turun ikinci denemesi. */
    fun requestStarted(atMs: Long = clock()): ChatHealth {
        startedAtMs = atMs
        retryStartedAtMs = null
        health = health.copy(
            state = HealthState.WAITING,
            sinceMs = atMs,
            errorLabel = null,
            httpStatus = null,
            retryInMs = null,
        )
        return health
    }

    /** İlk parça geldi — TTFB burada ölçülür. */
    fun firstChunk(atMs: Long = clock()): ChatHealth {
        val ttfb = (atMs - startedAtMs).coerceAtLeast(0)
        ttfbWindow.addLast(ttfb)
        while (ttfbWindow.size > windowSize) ttfbWindow.removeFirst()
        health = health.copy(
            state = HealthState.STREAMING,
            lastTtfbMs = ttfb,
            p50TtfbMs = median(ttfbWindow),
            samples = ttfbWindow.size,
            sinceMs = atMs,
            retryInMs = null,
        )
        return health
    }

    /** Hata sonrası geri çekilme planlandı. `retry` 1'den başlar. */
    fun retryScheduled(
        attempt: Int,
        max: Int,
        backoffMs: Long,
        atMs: Long = clock(),
    ): ChatHealth {
        retryStartedAtMs = atMs
        retryBackoffMs = backoffMs.coerceAtLeast(0)
        health = health.copy(
            state = HealthState.RETRYING,
            retry = attempt.coerceAtLeast(1),
            retryMax = max.coerceAtLeast(attempt),
            retryInMs = retryBackoffMs,
            sinceMs = atMs,
        )
        return health
    }

    /**
     * Geri çekilmeden kalan süre. Chip bunu canlı sayar; veri sınıfı kendi
     * saatini okumadığı için `nowMs` dışarıdan gelir.
     */
    fun retryRemainingMs(nowMs: Long): Long {
        val started = retryStartedAtMs ?: return 0
        return (retryBackoffMs - (nowMs - started)).coerceAtLeast(0)
    }

    /** Akış bitti. `outputTokens` bilinmiyorsa 0 ver — hız hesaplanmaz. */
    fun finished(atMs: Long = clock(), outputTokens: Int = 0): ChatHealth {
        val total = (atMs - startedAtMs).coerceAtLeast(0)
        val rate = if (outputTokens > 0 && total > 0) {
            outputTokens * 1000.0 / total
        } else {
            health.tokensPerSec
        }
        health = health.copy(
            state = HealthState.IDLE,
            lastTotalMs = total,
            tokensPerSec = rate,
            retry = 0,
            retryMax = 0,
            retryInMs = null,
            sinceMs = null,
        )
        return health
    }

    /** İstek hata ile bitti. [kind] kısa etikete çevrilir. */
    fun failed(
        httpStatus: Int? = null,
        kind: ErrorKind = ErrorKind.UNKNOWN,
    ): ChatHealth {
        health = health.copy(
            state = if (kind == ErrorKind.NETWORK) HealthState.OFFLINE else HealthState.ERROR,
            httpStatus = httpStatus,
            errorLabel = shortLabel(kind, httpStatus),
            retryInMs = null,
            sinceMs = null,
        )
        return health
    }

    /** Oturum değişti: sayaçlar sıfır, son ölçümler kalır (geçmiş yalan değil). */
    fun reset(): ChatHealth {
        retryStartedAtMs = null
        health = health.copy(
            state = HealthState.IDLE,
            retry = 0,
            retryMax = 0,
            retryInMs = null,
            sinceMs = null,
            errorLabel = null,
            httpStatus = null,
        )
        return health
    }

    companion object {
        /** Medyan. Çift sayıda alt-orta seçilir: tek bir uç değer ortalamayı kaydırmasın. */
        fun median(values: Collection<Long>): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[(sorted.size - 1) / 2]
        }

        /**
         * p90 — ayrıntı kartında kullanılır. Yüzdelik seçimi bilerek "en yakın
         * sıra": 20 ölçümde 18. sıra. İnterpolasyon, 20 örnekte sahte hassasiyet üretir.
         */
        fun percentile90(values: Collection<Long>): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val idx = ((sorted.size - 1) * 9 + 9) / 10
            return sorted[idx.coerceIn(0, sorted.size - 1)]
        }

        fun shortLabel(kind: ErrorKind, httpStatus: Int?): String = when {
            httpStatus != null -> httpStatus.toString()
            kind == ErrorKind.NETWORK -> "NET"
            kind == ErrorKind.AUTH -> "AUTH"
            kind == ErrorKind.RATE_LIMIT -> "429"
            kind == ErrorKind.CERT -> "TLS"
            else -> "?"
        }
    }
}

/** Hata sınıfı — `LLMError` alt tiplerinin Android'siz karşılığı. */
enum class ErrorKind { NETWORK, AUTH, RATE_LIMIT, SERVER, CERT, UNKNOWN }
