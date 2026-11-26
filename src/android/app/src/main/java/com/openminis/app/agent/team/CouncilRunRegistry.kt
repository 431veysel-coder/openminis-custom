package com.openminis.app.agent.team

/**
 * [37128 · A1] **Çalışan koşuların tutamacı** — durdurabilmek için.
 *
 * ## Neden ayrı bir kayıt defteri
 *
 * `CouncilLiveRegistry` bir **görüntü** kaydıdır: `StateFlow<LiveRun>`, ekranlar
 * onu okur. İptal bir **kontrol** işidir. İkisini aynı nesneye koymak, geçen
 * turda iki farklı liste tipini birleştirmeye çalışıp derlemeyi düşürmemin
 * aynısı olurdu — ve daha kötüsü: bir `StateFlow` güncellemesi ile bir iptal
 * aynı kilit altında yarışırsa, iptal sessizce kaybolur.
 *
 * ## Neden `Job` değil `() -> Unit`
 *
 * Test edilebilirlik. Bu sınıf `kotlinx.coroutines` bilmiyor: kaydedilen şey
 * "iptal et" eylemi. Böylece birim testi gerçek bir coroutine kurmadan,
 * sayaca bakan bir lambda ile iptalin **çağrıldığını** doğrular. Üretimde
 * kaydedilen lambda `job::cancel`.
 *
 * ## Neden kayıt defteri boşalır
 *
 * `forget` çağrılmazsa her koşu için bir lambda tutulur ve süreç ömrü boyunca
 * birikir — tam olarak bu özelliğin önlemeye çalıştığı sızıntı. `run`'ın
 * `finally` bloğu her üç yolda da (normal, iptal, hata) `forget` çağırır.
 */
object CouncilRunRegistry {

    private val cancels = HashMap<String, () -> Unit>()

    /** Publish a handle. Re-registering the same run replaces it (idempotent). */
    @Synchronized
    fun register(runId: String, cancel: () -> Unit) {
        if (runId.isBlank()) return
        cancels[runId] = cancel
    }

    /**
     * Ask a run to stop.
     *
     * @return true if a handle existed and was invoked, false if the run had
     *   already finished. The two are different answers and the UI must not
     *   claim it stopped something that was not running.
     */
    @Synchronized
    fun cancel(runId: String): Boolean {
        val handle = cancels[runId] ?: return false
        // Remove BEFORE invoking: a cancel handler that synchronously reaches
        // back into this object (it does not today, but a future one might)
        // must not deadlock on the monitor we are holding.
        cancels.remove(runId)
        return try {
            handle()
            true
        } catch (e: Exception) {
            // A failed cancel is still a cancel attempt — the run may or may not
            // stop. Report false so the caller does not claim success.
            false
        }
    }

    @Synchronized
    fun isRunning(runId: String): Boolean = cancels.containsKey(runId)

    /** Called from `run`'s finally, whatever the outcome. */
    @Synchronized
    fun forget(runId: String) {
        cancels.remove(runId)
    }

    @Synchronized
    fun activeCount(): Int = cancels.size

    /** Test-only / process reset. */
    @Synchronized
    fun clear() {
        cancels.clear()
    }
}
