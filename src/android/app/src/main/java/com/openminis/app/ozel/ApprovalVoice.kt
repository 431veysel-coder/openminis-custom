package com.openminis.app.ozel

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.openminis.app.MainActivity
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.policy.ApprovalRequest
import com.openminis.app.speech.TextToSpeechManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * [37116] Sesli onay uyarısı — Android köprüsü.
 *
 * ## Neden gerekli (kullanıcının cümlesi)
 *
 * *"bazı yerlerde onay istiyorsun ben duyamıyorum… onay bekliyorum diye uyarı
 * verebilirsin"*. Kart ekranda duruyor, kullanıcı telefona bakmıyor, 5 dakika
 * sonra `PolicyGate` `Deny` dönüyor ve tur **sebepsiz düşmüş gibi** görünüyor.
 * Bu sınıf kartı duyulabilir yapar: konuşur, titrer, bildirim bırakır, cevap
 * gelene kadar tekrarlar.
 *
 * ## Neden `PolicyGate.pending`'i dinliyor da çağrı yerine kanca koymuyor
 *
 * Onay isteği üç yerden doğabiliyor (shell, dosya yazma, plan önizleme) ve
 * gelecekte daha fazlası olacak. Her çağrı yerine bir `announce(...)` satırı
 * eklemek, birini unutmak demek — bu depoda dört PRoot kurucusundan birini
 * unutma hatası üç kez yaşandı. Tek bir `StateFlow` abonesi, kaç çağrı yeri
 * olursa olsun **hiçbirini** kaçırmaz.
 *
 * ## Neden tekrar var, neden sınırlı
 *
 * Tek duyuru cebimdeki telefonda kaçar. Tekrar, "bekleyen iş var" bilgisini
 * canlı tutar. Sınırsız tekrar ise iptal edilmemiş bir turda sonsuza kadar
 * konuşur — [VoiceSettings.MAX_ANNOUNCEMENTS] bunu keser (12 × 30 sn = 6 dk,
 * onay penceresi 5 dk olduğu için pratikte ulaşılmaz).
 *
 * ## Neden kendi `TextToSpeech` örneği değil, [TextToSpeechManager]
 *
 * Motor bağlanana kadar gelen metni tamponlayan, dil çözümlemesini yapan ve
 * `speak` reddini (`rc != SUCCESS`) yakalayan tek yer o. İkinci bir motor
 * örneği, iki ayrı sesin aynı anda konuşması demek olurdu (okuma sesi + onay
 * sesi), ki bu tam olarak kullanıcının şikâyet ettiği "duyamıyorum"un bir
 * başka biçimi.
 */
object ApprovalVoice {

    private const val TAG = "ApprovalVoice"
    private const val CHANNEL_ID = "minis_approval_voice"
    private const val NOTIFICATION_TAG = "approval-voice"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Kimlik başına tekrar işi — aynı kart için iki döngü olmaz. */
    private val repeats = ConcurrentHashMap<String, Job>()

    /** Duyuru sayacı: aynı kart en fazla [VoiceSettings.MAX_ANNOUNCEMENTS] kez. */
    private val announced = ConcurrentHashMap<String, Int>()

    @Volatile
    private var bound = false

    private var appContext: Context? = null
    private var tts: TextToSpeechManager? = null

    /**
     * `MinisApp.onCreate`'ten bir kez çağrılır. İkinci çağrı sessizce döner:
     * iki abone iki kat konuşur.
     */
    fun start(context: Context) {
        if (bound) return
        bound = true
        appContext = context.applicationContext
        ensureChannel(context.applicationContext)

        scope.launch {
            PolicyGate.pending.collect { pending ->
                val ids = pending.map { it.id }.toSet()
                // 1) Yeni kartlar: duyur.
                pending.forEach { request -> announce(request, pending.size) }
                // 2) Kuyruktan düşenler (cevap, zaman aşımı, iptal): sus.
                (repeats.keys + announced.keys).filter { it !in ids }.forEach { settle(it) }
            }
        }
    }

    /**
     * [37118] **Bilgi** duyurusu — kart yok, cevap beklenmiyor.
     *
     * `ALLOW_ALL` modunda geri alınamaz ya da sandbox dışı bir iş kart
     * çıkarmaz (kullanıcı bütün izinleri verdi) ama sessiz de geçmez: telefon
     * tek cümle söyler, titrer ve bir bildirim bırakır. Tekrar **yoktur** —
     * tekrar, cevap bekleyen bir soruya aittir; burada soru yok.
     *
     * Neden `PolicyGate`'ten çağrılıyor da `pending` akışından değil: bu iş
     * kuyruğa hiç girmiyor, dolayısıyla akışta görünmüyor. Kuyruğa sokup
     * sonra anında cevaplamak, aynı bilgiyi iki yoldan taşımak olurdu.
     */
    fun notice(context: Context, request: ApprovalRequest) {
        val ctx = context.applicationContext
        val settings = ApprovalSettings.voice(ctx)
        if (settings.vibrate) vibrate(ctx)
        postNotification(ctx, request, pending = 0)
        if (!settings.enabled) return
        val manager = manager(ctx) ?: return
        val line = ApprovalVoiceText.noticeLine(request, speakOperation = settings.speakOperation)
        manager.speak(line)
    }

    /** Kullanıcı "şimdi dinle" derse — ayarlar ekranındaki sınama düğmesi. */
    fun speakSample(context: Context) {
        val settings = ApprovalSettings.voice(context)
        val manager = manager(context) ?: return
        if (settings.vibrate) vibrate(context)
        manager.speak(
            if (settings.mode == VoiceMode.OFF) {
                "Sesli uyarı şu an kapalı."
            } else {
                "Onay bekliyorum. Dizin silinecek. Geri gelmez. Onaylamak için karta dokun."
            },
        )
    }

    // ------------------------------------------------------------- iç

    private fun announce(request: ApprovalRequest, pending: Int) {
        val ctx = appContext ?: return
        // Aynı kart için ikinci kez `announce` çağrılırsa (pending listesi her
        // değişiminde collect yeniden koşuyor) yeni döngü başlatmayız.
        if (repeats.containsKey(request.id)) return

        val settings = ApprovalSettings.voice(ctx)
        announced[request.id] = 1

        if (!settings.enabled) {
            // Ses kapalı ama kart görünmez olabilir: bildirim yine de düşsün,
            // çünkü "ses kapalı" "haberim olmasın" demek değil.
            postNotification(ctx, request, pending)
            return
        }

        speak(ctx, request, pending, repeat = false)
        if (settings.vibrate) vibrate(ctx)
        postNotification(ctx, request, pending)

        if (settings.mode != VoiceMode.REPEATING) return

        repeats[request.id] = scope.launch {
            var n = 1
            while (n < VoiceSettings.MAX_ANNOUNCEMENTS) {
                delay(settings.repeatMs)
                // Döngü boyunca ayar değişmiş olabilir: her turda yeniden oku.
                val now = ApprovalSettings.voice(ctx)
                if (now.mode != VoiceMode.REPEATING) break
                n += 1
                announced[request.id] = n
                speak(ctx, request, pending, repeat = true)
                if (now.vibrate) vibrate(ctx)
            }
        }
    }

    private fun settle(requestId: String) {
        repeats.remove(requestId)?.cancel()
        announced.remove(requestId)
        val ctx = appContext ?: return
        runCatching {
            NotificationManagerCompat.from(ctx).cancel(NOTIFICATION_TAG, requestId.hashCode())
        }
    }

    private fun speak(ctx: Context, request: ApprovalRequest, pending: Int, repeat: Boolean) {
        val settings = ApprovalSettings.voice(ctx)
        val manager = manager(ctx) ?: return
        val line = ApprovalVoiceText.line(
            request = request,
            pending = pending,
            repeat = repeat,
            speakOperation = settings.speakOperation,
        )
        // `speak` (QUEUE_FLUSH) bilinçli: tekrar duyurusu, hâlâ çalan önceki
        // duyurunun üstüne binmeli, arkasına kuyruğa girmemeli — kuyrukta
        // biriken 6 duyuru, 5 dakikalık pencerede hiç bitmeyen bir ses olur.
        manager.speak(line)
    }

    private fun manager(ctx: Context): TextToSpeechManager? {
        tts?.let { return it }
        val created = TextToSpeechManager()
        created.init(ctx)
        tts = created
        return created
    }

    private fun vibrate(ctx: Context) {
        val v = ContextCompat.getSystemService(ctx, Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        val pattern = ApprovalVoiceText.vibrationPattern()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        }.onFailure { AppLogger.warning(TAG, "vibrate failed: ${it.message}") }
    }

    private fun postNotification(ctx: Context, request: ApprovalRequest, pending: Int) {
        val nm = NotificationManagerCompat.from(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            return
        }
        val launch = Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            ctx,
            request.id.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = ApprovalVoiceText.notificationBody(request, pending)
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(ctx.getString(R.string.approval_voice_notif_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        runCatching {
            nm.notify(NOTIFICATION_TAG, request.id.hashCode(), notification)
        }.onFailure { AppLogger.info(TAG, "notify denied: ${it.message}") }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = ContextCompat.getSystemService(ctx, NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            ctx.getString(R.string.approval_voice_channel_name),
            // HIGH: onay isteği "şimdi bak" demek; DEFAULT kanalda bildirim
            // çoğu OEM'de sessizce listeye düşüyor ve şikâyet aynı kalıyor.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = ctx.getString(R.string.approval_voice_channel_desc)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
