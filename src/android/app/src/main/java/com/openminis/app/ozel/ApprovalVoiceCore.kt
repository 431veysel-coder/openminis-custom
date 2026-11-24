package com.openminis.app.ozel

import com.openminis.app.policy.ApprovalRequest
import com.openminis.app.policy.Reach
import com.openminis.app.policy.Recovery

/**
 * [37116] Onay sesi — **Android'siz** çekirdek.
 *
 * ## Neden var
 *
 * Kullanıcı: *"bazı yerlerde onay istiyorsun ben duyamıyorum… sesli onay olmalı,
 * onay bekliyorum diye uyarı verebilirsin"*. Kart ekranda bekliyordu ama
 * kullanıcı telefona bakmıyorsa 5 dakika sonra tur sessizce düşüyordu. Bu
 * dosya "ne söylenecek" ve "ne zaman tekrar söylenecek" sorusunun tek cevabı.
 *
 * ## Neden ayrı sınıf
 *
 * `ApprovalInbox` nasıl Android'siz ise bu da öyle: cümle kurma ve tekrar
 * takvimi saf fonksiyon, JVM'de test edilebilir. Ses motoru, titreşim ve
 * bildirim [ApprovalVoice] köprüsünde.
 *
 * ## Neden cümle kısa
 *
 * TTS 3 saniyede bitmeli: uzun cümle hem tekrarı geciktirir hem kullanıcı
 * ikinci yarısını kaçırır. Bu yüzden kartın beş satırı **okunmaz**, ilk üç
 * bilgi okunur (başlık · geri dönüş · nereye dokunuyor). Komut metni yalnız
 * isteğe bağlı (çok uzun olabilir, 70 karakterde kesilir).
 */
enum class VoiceMode(val key: String) {
    OFF("off"),
    ONCE("once"),
    REPEATING("repeating"),
    ;

    companion object {
        /**
         * Bilinmeyen/boş değer **REPEATING**'e düşer, OFF'a değil: kullanıcı
         * bu ayarı zaten açtıysa bozuk bir değer yüzünden sessizleşmek, onun
         * istediğinin tersidir. (Aynı sınıf: `.33`'te `absentWhy` etiketi.)
         */
        fun parse(raw: String?): VoiceMode =
            entries.firstOrNull { it.key == raw?.trim() } ?: REPEATING
    }
}

/**
 * Kullanıcının seçtiği ses davranışı.
 *
 * `repeatSeconds`ın alt sınırı 10: daha sık tekrar konuşmayı kekemeliğe
 * çevirir ve TTS motorunu kuyruğa boğar. Üst sınır 300: 5 dakikalık onay
 * penceresinde en az iki duyuru garantisi.
 */
data class VoiceSettings(
    val mode: VoiceMode = VoiceMode.REPEATING,
    val repeatSeconds: Int = DEFAULT_REPEAT_SECONDS,
    val vibrate: Boolean = true,
    val speakOperation: Boolean = true,
) {

    /** Kapalı modda ne konuşulur ne titreşilir. */
    val enabled: Boolean get() = mode != VoiceMode.OFF

    val repeatMs: Long get() = repeatSeconds.coerceIn(MIN_REPEAT_SECONDS, MAX_REPEAT_SECONDS) * 1_000L

    companion object {
        const val DEFAULT_REPEAT_SECONDS = 30
        const val MIN_REPEAT_SECONDS = 10
        const val MAX_REPEAT_SECONDS = 300

        /** Ayarlar ekranındaki hazır seçenekler — serbest metin yok. */
        val REPEAT_CHOICES: List<Int> = listOf(15, 30, 60, 120)

        /**
         * Bir kart için en fazla kaç duyuru. 12 × 30 sn = 6 dakika; onay
         * penceresi 5 dakika olduğu için üst sınır pratikte ulaşılmaz, ama
         * sayaç olmadan iptal edilmeyen bir tur sonsuza kadar konuşurdu.
         */
        const val MAX_ANNOUNCEMENTS = 12
    }
}

/**
 * Kartı sese çeviren saf fonksiyonlar.
 *
 * Ayrı nesne: aynı cümle hem sesle okunur hem bildirim gövdesinde yazılır;
 * iki yerde iki farklı metin üretmek "ekran başka, ses başka" derdine yol açar.
 */
object ApprovalVoiceText {

    /**
     * Duyuru cümlesi.
     *
     * @param pending kuyrukta bekleyen kart sayısı (1'den büyükse söylenir)
     * @param repeat ikinci ve sonraki duyurular — "hâlâ" ile başlar, çünkü
     *   aynı cümleyi aynen tekrarlamak kullanıcıya "yeni bir istek geldi"
     *   izlenimi verir.
     */
    fun line(
        request: ApprovalRequest,
        pending: Int = 1,
        repeat: Boolean = false,
        speakOperation: Boolean = true,
    ): String {
        val parts = mutableListOf<String>()
        parts += if (repeat) "Hâlâ onay bekliyorum." else "Onay bekliyorum."
        parts += "${request.title}."
        if (speakOperation) operation(request)?.let { parts += "$it." }
        recoveryPhrase(request)?.let { parts += "$it." }
        reachPhrase(request)?.let { parts += "$it." }
        if (pending > 1) parts += "$pending istek bekliyor."
        parts += if (repeat) "Karta dokunup karar ver." else "Onaylamak için karta dokun."
        return parts.joinToString(" ")
    }

    /** Bildirim gövdesi — ses cümlesinin yazı hâli, tek satır. */
    fun notificationBody(request: ApprovalRequest, pending: Int = 1): String =
        line(request, pending = pending, repeat = false, speakOperation = true)

    /**
     * Komutun kendisi. 70 karakterde kesilir: daha uzunu okunamaz, ve sesli
     * duyurunun işi komutu **okumak** değil, ne olduğunu hatırlatmak.
     */
    fun operation(request: ApprovalRequest): String? {
        val seg = request.verdict.segment.trim().replace(WHITESPACE, " ")
        if (seg.isEmpty()) return null
        val clipped = if (seg.length <= MAX_OPERATION_CHARS) seg else seg.take(MAX_OPERATION_CHARS - 1) + "…"
        return "İşlem: $clipped"
    }

    /**
     * Geri dönüş bilgisi. Yalnız **kritik** olanı söyler: "geri alınabilir"
     * demek 1.5 saniye harcamaya değmez (kullanıcı zaten tehlikeli olmadığını
     * varsayar), "geri gelmez" demek değer.
     */
    fun recoveryPhrase(request: ApprovalRequest): String? = when (request.verdict.recovery) {
        Recovery.PERMANENT -> "Geri gelmez"
        Recovery.RECOVERABLE -> "Kendiliğinden geri gelmez"
        Recovery.UNDOABLE -> null
    }

    /** Hedef sandbox dışına çıkıyorsa söylenir; sandbox içi sessiz kalır. */
    fun reachPhrase(request: ApprovalRequest): String? = when (request.verdict.reach) {
        Reach.DEVICE -> "Telefon genelini etkiler"
        Reach.NETWORK -> "Bu cihazın dışını etkiler"
        Reach.ACCOUNT -> "Hesabını etkiler"
        Reach.SANDBOX, Reach.SESSION -> null
    }

    /**
     * Titreşim deseni — iki kısa darbe. Tek uzun darbe bildirim titreşimiyle
     * karışır; iki kısa darbe "onay" demenin en ayırt edilebilir hâli.
     */
    fun vibrationPattern(): LongArray = longArrayOf(0, 180, 120, 180)

    private const val MAX_OPERATION_CHARS = 70
    private val WHITESPACE = Regex("""\s+""")
}
