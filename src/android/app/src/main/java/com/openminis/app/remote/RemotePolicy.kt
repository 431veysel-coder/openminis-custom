package com.openminis.app.remote

/**
 * [OZEL 37090] Uzaktan konsol — **jeton ve yetki modeli**. Düşük öncelik.
 *
 * ## Neden bu dosya kod, geri kalanı karar
 *
 * Altyapı hazır: `NetworkHub`, Tailscale, `DebugServer`. Aynı ajana tarayıcıdan
 * bağlanmak teknik olarak bir günlük iş. Ama bu özellik, uygulamanın bugüne
 * kadarki tek güvenlik varsayımını kırıyor: **kullanıcı cihazın başında.**
 * Telefon masada, kullanıcı başka şehirdeyse, yanlış bir `settings put` geri
 * alınamaz.
 *
 * Bu yüzden önce model yazıldı, uygulama yazılmadı. Model şu üç şeyi
 * zorunlu kılmıyor, **imkânsız** kılıyor:
 *
 * 1. Uzun ömürlü jeton (tavan 30 dakika).
 * 2. Varsayılan yazma (varsayılan salt-okuma; yazma ayrıca açılır).
 * 3. Cihaz geneline dokunma (uzaktan oturumda `Reach.DEVICE` ve üstü yasak).
 *
 * ## Neden jeton, şifre değil
 *
 * DebugServer zaten jeton kullanıyor; aynı deseni tekrarlamak yeni bir
 * güvenlik yüzeyi öğrenmekten iyi. Jeton tek kullanımlık üretilir, ekranda
 * QR olarak gösterilir, süresi dolunca ölür.
 */
data class RemoteGrant(
    val token: String,
    val sessionId: String,
    val expiresAtMs: Long,
    val readOnly: Boolean,
    val issuedAtMs: Long,
    /** Tailnet dışına açılmayacaksa `true`. */
    val tailnetOnly: Boolean = true,
)

sealed class RemoteCheck {
    data class Ok(val grant: RemoteGrant) : RemoteCheck()
    data class Refused(val reason: String) : RemoteCheck()
}

object RemotePolicy {

    /** Tavan. Uzun yaşayan uzaktan jeton, uzaktan kalıcı erişim demek. */
    const val MAX_TTL_MS: Long = 30L * 60 * 1000

    /** Uzaktan oturumda yasak kapsamlar. */
    val FORBIDDEN_REACH = setOf("DEVICE", "NETWORK", "ACCOUNT")

    fun issue(
        token: String,
        sessionId: String,
        nowMs: Long,
        ttlMs: Long = 10L * 60 * 1000,
        allowWrite: Boolean = false,
    ): RemoteGrant {
        require(ttlMs in 1..MAX_TTL_MS) { "uzaktan jeton ömrü en fazla ${MAX_TTL_MS / 60000} dakika" }
        return RemoteGrant(
            token = token,
            sessionId = sessionId,
            expiresAtMs = nowMs + ttlMs,
            readOnly = !allowWrite,
            issuedAtMs = nowMs,
        )
    }

    /**
     * İsteği değerlendirir.
     *
     * [reach] bu isteğin etki alanı (bkz. `policy.Reach` adı). Metin olarak
     * taşınıyor ki bu paket `policy` paketine bağımlı olmasın — iki özellik
     * birbirini kilitlemesin.
     */
    fun check(
        grant: RemoteGrant?,
        token: String,
        sessionId: String,
        nowMs: Long,
        wantsWrite: Boolean,
        reach: String = "SANDBOX",
    ): RemoteCheck {
        if (grant == null) return RemoteCheck.Refused("uzaktan erişim kapalı")
        if (grant.token != token) return RemoteCheck.Refused("jeton geçersiz")
        if (nowMs >= grant.expiresAtMs) return RemoteCheck.Refused("jetonun süresi doldu")
        if (grant.sessionId != sessionId) return RemoteCheck.Refused("jeton bu sohbete ait değil")
        if (wantsWrite && grant.readOnly) return RemoteCheck.Refused("bu jeton salt-okuma")
        if (reach in FORBIDDEN_REACH) return RemoteCheck.Refused("uzaktan oturum cihaz geneline dokunamaz")
        return RemoteCheck.Ok(grant)
    }

    /** Ekranda görünen uyarı — kullanıcı ne açtığını okumadan kapatmasın. */
    fun consentText(grant: RemoteGrant): String = buildString {
        append("Uzaktan erişim açık · ${(grant.expiresAtMs - grant.issuedAtMs) / 60000} dakika · ")
        append(if (grant.readOnly) "salt-okuma" else "yazma açık")
        if (grant.tailnetOnly) append(" · yalnız Tailscale ağından")
    }
}
