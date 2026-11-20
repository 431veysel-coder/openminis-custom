package com.openminis.app.ozel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * [37096] Onay kartlarının bekleme odası — **Android'siz**.
 *
 * ## Neden ayrı sınıf
 *
 * `.15`'e kadar bu mantık `PolicyGate`'in içindeydi ve o nesne `Context`,
 * `OzelFeatures` ve `RiskClassifier` ile birlikte geliyordu; yani bekleyen
 * bir turu 5 dakika kilit tutan kod, hiçbir testin dokunamayacağı bir yerde
 * duruyordu. Buradaki her şey saf: `CompletableDeferred`, bir `StateFlow`,
 * bir sayaç. Zaman aşımı, geç gelen cevap, iki kez cevap, "hepsini reddet" —
 * dördü de doğrudan test edilebilir.
 *
 * ## Neden kuyrukta aynı soru birleşiyor
 *
 * Anahtar `ApprovalRequest.id` (kural + parça). Aynı komut iki kez sorulursa
 * ikinci bir kart açmak kullanıcıya "iki farklı şey soruyor" der; oysa tek
 * karar yeter. `ask` mevcut kaydı yenisiyle değiştirir, bekleyen ilk çağrı
 * aynı `Deferred`'ı beklemeye devam eder.
 *
 * ## Neden zaman aşımı sessiz iptal değil
 *
 * `null` döner ve çağıran (PolicyGate) bunu **gerekçeli bir `Deny`**'ye
 * çevirir. Sessizce "geçti" demek, kullanıcı cevap vermediği için komutun
 * çalışması demek olurdu; bu, onay mekanizmasını tersine çevirir.
 */
class ApprovalInbox(
    /** Cevap bekleme süresi. 0 ya da negatif = süresiz (testler için). */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    data class Answer(
        val decision: Decision,
        val scope: ApprovalScope?,
        val note: String = "",
    )

    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Answer>>()

    private val _pending = MutableStateFlow<List<ApprovalRequest>>(emptyList())

    /** Arayüzün çizdiği kartlar; kuyruk sırası korunur. */
    val pending: StateFlow<List<ApprovalRequest>> = _pending.asStateFlow()

    val pendingCount: Int get() = _pending.value.size

    /** Zaman aşımı gerekçesi — kart metniyle aynı sayıyı kullanır. */
    fun timeoutReason(): String =
        if (timeoutMs <= 0L) "kullanıcı cevap vermedi"
        else "kullanıcı ${timeoutMs / 60_000} dakika içinde cevap vermedi"

    /**
     * Kartı kuyruğa koyar ve cevabı bekler. Zaman aşımında `null`.
     *
     * `finally` her üç yolda da (cevap, zaman aşımı, iptal) temizler: iptal
     * edilmiş bir turun kartı ekranda kalmamalı — kullanıcı cevaplar, kimse
     * dinlemez, ve "neden hiçbir şey olmadı" sorusu doğar.
     */
    suspend fun ask(request: ApprovalRequest): Answer? {
        val def = CompletableDeferred<Answer>()
        waiters[request.id] = def
        _pending.value = (_pending.value.filterNot { it.id == request.id } + request)
        try {
            return if (timeoutMs <= 0L) def.await() else withTimeoutOrNull(timeoutMs) { def.await() }
        } finally {
            waiters.remove(request.id)
            _pending.value = _pending.value.filterNot { it.id == request.id }
        }
    }

    /**
     * Arayüzden çağrılır. `false` dönerse kart artık yok (zaman aşımı ya da
     * iptal) — cevap yutulmaz, çağıran loglayabilir.
     *
     * İkinci cevap ilkini ezmez: `complete` zaten tamamlanmış `Deferred`'da
     * yok sayılır. Bu, çift dokunuşun kararı değiştirmesini engeller.
     */
    fun answer(requestId: String, answer: Answer): Boolean {
        val def = waiters[requestId] ?: return false
        return def.complete(answer)
    }

    /** "Hepsini reddet" — kaç kart kapatıldığını döndürür. */
    fun refuseAll(note: String = "kullanıcı hepsini reddetti"): Int {
        var closed = 0
        _pending.value.map { it.id }.forEach { id ->
            val def = waiters[id] ?: return@forEach
            if (def.complete(Answer(Decision.DENY, null, note))) closed += 1
        }
        return closed
    }

    /** Tur iptal edildiğinde: bekleyen her kartı düşür, cevap üretme. */
    fun clear(): Int {
        val n = waiters.size
        waiters.clear()
        _pending.value = emptyList()
        return n
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5 * 60 * 1000
    }
}
