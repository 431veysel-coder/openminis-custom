package com.openminis.app.ozel

import android.content.Context
import android.os.SystemClock
import com.openminis.app.data.OzelFeatures
import com.openminis.app.ozel.health.ChatHealth
import com.openminis.app.ozel.health.ChatHealthTracker
import com.openminis.app.ozel.health.ErrorKind
import com.openminis.app.ozel.health.HealthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * [37096] `ChatHealthTracker`'ı sohbete bağlayan köprü.
 *
 * ## Neden `ozel/` içinde, `ozel/health/` içinde değil
 *
 * `ozel/health/` bir **dilim** (`scripts/slices.json` → `37095`) ve dilim kapısı
 * o dizini tek başına, Android olmadan derliyor. Bu dosya `Context` ve
 * `OzelFeatures` kullanıyor; orada kalsaydı dilim kapısı kırmızı olurdu.
 * Ayrım tesadüf değil, sözleşme: **çekirdek hesap dilimde, Android bağlantısı
 * dışarıda.** `PolicyGate` de aynı sebeple `ozel/` içinde, `policy/` içinde
 * değil.
 *
 * ## Neden bu dosya var
 *
 * `.15`'in sürüm notu "sohbet sağlık göstergesi çekirdeği" diyordu ve bu
 * doğruydu: `ChatHealth.kt` 274 satır, temiz yazılmış, testi var — ama üretim
 * kodunda **tek** referansı kendi tanımıydı. Özellik anahtarı ise varsayılan
 * AÇIK. Kullanıcı ayarlarda açık bir anahtar görüp hiçbir şey görmüyordu;
 * `.14`'te aynı gerekçeyle kapatılan `STATION` anahtarının aynısı.
 *
 * Bu köprü üç işi yapıyor: sağlayıcı çağrısının üç gerçek anını (istek çıktı,
 * ilk parça geldi, tur bitti/hata) izleyiciye aktarmak, oturum başına tek
 * izleyici tutmak ve arayüzün okuyacağı akışı yayınlamak.
 *
 * ## Neden anahtar her turda yeniden okunuyor
 *
 * `OzelFeatures` bir `SharedPreferences` okuması; parça başına çağırmak
 * akışın sıcak yolunda disk erişimi demek. Yalnız `requestStarted` okuyor —
 * yani kullanıcı anahtarı kapatırsa bir sonraki turda susuyor, akış ortasında
 * değil.
 *
 * ## Neden `elapsedRealtime`
 *
 * Duvarsaati değişebilir (doze, saat dilimi, elle ayar) ve negatif süre
 * üretir. Monoton saat, "3.2s" yazarken yalan söylememenin tek yolu.
 */
object ChatHealthBridge {

    private val trackers = ConcurrentHashMap<String, ChatHealthTracker>()

    private val _state = MutableStateFlow<Map<String, ChatHealth>>(emptyMap())

    /** Arayüzün okuduğu anlık görüntüler: oturum kimliği -> sağlık. */
    val state: StateFlow<Map<String, ChatHealth>> = _state.asStateFlow()

    /** Son okunan anahtar değeri; tur ortasında disk okumamak için. */
    @Volatile
    private var enabled = false

    private fun tracker(sessionId: String): ChatHealthTracker =
        trackers.getOrPut(sessionId) { ChatHealthTracker { SystemClock.elapsedRealtime() } }

    private fun publish(sessionId: String, health: ChatHealth) {
        _state.value = _state.value + (sessionId to health)
    }

    /** İstek yola çıktı. Anahtar burada okunur — turda bir kez. */
    fun requestStarted(ctx: Context, sessionId: String): ChatHealth? {
        enabled = OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.CHAT_HEALTH)
        if (!enabled) return null
        val health = tracker(sessionId).requestStarted()
        publish(sessionId, health)
        return health
    }

    /** Sağlayıcıdan ilk parça geldi: TTFB burada ölçülür. */
    fun firstChunk(sessionId: String) {
        if (!enabled) return
        publish(sessionId, tracker(sessionId).firstChunk())
    }

    /**
     * Tur tamamlandı.
     *
     * Hata durumu **korunuyor**: `catch` her zaman `finally`'den önce çalıştığı
     * için, hata işaretini burada "hazır"a çevirmek çipin tek işini boşa
     * çıkarırdı (kullanıcı 429 yediğini hiç görmeyecekti). Yeni tur
     * `requestStarted` ile zaten sıfırlıyor.
     */
    fun finished(sessionId: String, outputTokens: Int = 0) {
        if (!enabled) return
        val tracker = tracker(sessionId)
        val current = tracker.snapshot()
        if (current.state == HealthState.ERROR || current.state == HealthState.OFFLINE) {
            publish(sessionId, current)
            return
        }
        publish(sessionId, tracker.finished(outputTokens = outputTokens))
    }

    /**
     * Tur hata ile bitti.
     *
     * [label] modelin değil sağlayıcının verdiği kısa ad ("TransientError",
     * "429" gibi); etiketi burada uydurmuyoruz, `ChatHealth.shortLabel` zaten
     * varsa onu tercih ediyor.
     */
    fun failed(sessionId: String, label: String? = null, httpStatus: Int? = null) {
        if (!enabled) return
        val kind = when {
            httpStatus == 429 -> ErrorKind.RATE_LIMIT
            httpStatus != null && httpStatus >= 500 -> ErrorKind.SERVER
            httpStatus == 401 || httpStatus == 403 -> ErrorKind.AUTH
            label != null && label.contains("Transient", ignoreCase = true) -> ErrorKind.NETWORK
            label != null && (label.contains("TLS", true) || label.contains("Cert", true)) -> ErrorKind.CERT
            else -> ErrorKind.UNKNOWN
        }
        publish(sessionId, tracker(sessionId).failed(httpStatus = httpStatus, kind = kind))
    }

    /** Oturum kapandı: izleyici de gider, aksi hâlde süreç ömrü boyunca birikir. */
    fun forget(sessionId: String) {
        trackers.remove(sessionId)
        _state.value = _state.value - sessionId
    }

    /** Testler için: durumu sıfırla. */
    fun reset() {
        trackers.clear()
        _state.value = emptyMap()
    }
}
