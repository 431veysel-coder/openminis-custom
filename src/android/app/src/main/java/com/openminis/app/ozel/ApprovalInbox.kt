package com.openminis.app.ozel

import com.openminis.app.policy.ApprovalRequest
import com.openminis.app.policy.ApprovalScope
import com.openminis.app.policy.Decision
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

    /**
     * [37099] Kaç çağrı aynı kimliği bekliyor.
     *
     * Neden gerekli: aynı kimlikli iki `ask` tek `Deferred` paylaşır. İlk
     * çağıran `finally`'de kaydı silerse, hâlâ bekleyen ikinci çağrının
     * cevabı hiçbir yere ulaşmaz. Kart ancak son bekleyen çıkarken kalkar.
     */
    private val joinCount = ConcurrentHashMap<String, Int>()

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
        // [37099] GERÇEK HATA: burada `waiters[id] = CompletableDeferred()`
        // yazıyordu, yani aynı kimlikle ikinci bir `ask` birincinin
        // `Deferred`'ını **eziyordu**. Birinci çağıran artık kimsenin
        // tamamlamadığı bir deferred'ı bekliyordu: `timeoutMs = 0` ise sonsuza
        // kadar asılı kalıyor (süresiz bekleme testlerde ve elle onayda
        // kullanılıyor), sonluysa zaman aşımına kadar boşuna bekliyordu.
        //
        // Sınıfın kendi belgesi bunu zaten doğru yazıyordu ("bekleyen ilk
        // çağrı aynı Deferred'ı beklemeye devam eder") — kod belgeyi
        // uygulamıyordu. Test yakaladı: `ayni kimlikli kart kuyrukta birlesir`
        // CI'da kırmızı. Yerelde JVM olmadığı için ancak CI görebildi.
        //
        // Düzeltme: var olan deferred PAYLAŞILIR, sayılır ve son bekleyen
        // çıkarken temizlenir. Sayaç şart — ilk çağıran `finally`'de kaydı
        // silseydi, hâlâ bekleyen ikincinin cevabı kaybolurdu.
        val shared = CompletableDeferred<Answer>()
        val def = waiters.putIfAbsent(request.id, shared) ?: shared
        val isOwner = def === shared
        joinCount.merge(request.id, 1, Int::plus)
        if (isOwner) {
            _pending.value = (_pending.value.filterNot { it.id == request.id } + request)
        }
        try {
            return if (timeoutMs <= 0L) def.await() else withTimeoutOrNull(timeoutMs) { def.await() }
        } finally {
            // Kart yalnız SON bekleyen çıkarken kalkar; biri zaman aşımına
            // uğradı diye diğerinin kartını ekrandan düşürmek yanlış olurdu.
            val left = joinCount.merge(request.id, -1, Int::plus) ?: 0
            if (left <= 0) {
                joinCount.remove(request.id, left)
                waiters.remove(request.id, def)
                _pending.value = _pending.value.filterNot { it.id == request.id }
            }
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
        // [37099] Sayaç da sıfırlanmalı: temizlikten sonra bir `ask`
        // `joinCount`'ta 1 görüp kartı ekrana koymayabilirdi (sayaç 0'a
        // düşünce kartı kaldıran mantık, hiç eklenmemiş kartı aramaya devam
        // ederdi).
        joinCount.clear()
        _pending.value = emptyList()
        return n
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 5 * 60 * 1000
    }
}
