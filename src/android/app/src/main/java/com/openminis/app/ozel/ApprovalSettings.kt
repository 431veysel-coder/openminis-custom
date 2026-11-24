package com.openminis.app.ozel

import android.content.Context
import com.openminis.app.policy.ApprovalMode

/**
 * [37116] Onay ayarlarının diskteki hâli — mod + ses.
 *
 * ## Neden tek dosya, tek `SharedPreferences`
 *
 * `OzelFeatures` gibi ayrı bir dosya (`ozel_approval_prefs`), config
 * registry'ye değil: o şema iOS ile aynalanıyor ve buradaki anahtarlar orada
 * yok. Ayrı dosya, üst kaynak senkronunun bunları ezmesini imkânsız kılar.
 *
 * ## Neden okuma *her seferinde*
 *
 * Mod ayarlar ekranından değişiyor ve bir sonraki araç çağrısında geçerli
 * olmalı. Önbelleğe alınırsa "değiştirdim ama hâlâ soruyor" hatası doğar —
 * bu depoda aynı sınıf hata (`VirtualScreenHost` sıralaması bellekte doğru,
 * diskte eski) bir kez yaşandı.
 */
object ApprovalSettings {

    private const val PREFS = "ozel_approval_prefs"

    private const val KEY_MODE = "mode"
    private const val KEY_VOICE_MODE = "voice_mode"
    private const val KEY_REPEAT = "voice_repeat_seconds"
    private const val KEY_VIBRATE = "voice_vibrate"
    private const val KEY_SPEAK_OP = "voice_speak_operation"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(ctx: Context): ApprovalMode =
        ApprovalMode.parse(prefs(ctx).getString(KEY_MODE, null))

    fun setMode(ctx: Context, mode: ApprovalMode) {
        prefs(ctx).edit().putString(KEY_MODE, mode.key).apply()
    }

    /**
     * Ses ayarı.
     *
     * Varsayılan `REPEATING`: kullanıcının şikâyeti "duymuyorum" olduğu için
     * sessiz varsayılan, şikâyeti çözmeyen bir varsayılan olurdu. Tek sefer
     * konuşup susmak da aynı sorun: telefon cebimdeyken ilk duyuru kaçar.
     */
    fun voice(ctx: Context): VoiceSettings {
        val p = prefs(ctx)
        return VoiceSettings(
            mode = VoiceMode.parse(p.getString(KEY_VOICE_MODE, null)),
            repeatSeconds = p.getInt(KEY_REPEAT, VoiceSettings.DEFAULT_REPEAT_SECONDS),
            vibrate = p.getBoolean(KEY_VIBRATE, true),
            speakOperation = p.getBoolean(KEY_SPEAK_OP, true),
        )
    }

    fun setVoice(ctx: Context, settings: VoiceSettings) {
        prefs(ctx).edit()
            .putString(KEY_VOICE_MODE, settings.mode.key)
            .putInt(KEY_REPEAT, settings.repeatSeconds.coerceIn(
                VoiceSettings.MIN_REPEAT_SECONDS,
                VoiceSettings.MAX_REPEAT_SECONDS,
            ))
            .putBoolean(KEY_VIBRATE, settings.vibrate)
            .putBoolean(KEY_SPEAK_OP, settings.speakOperation)
            .apply()
    }
}
