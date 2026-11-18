package com.openminis.app.policy

/**
 * [OZEL 37080] Onay kartının verisi ve metni.
 *
 * ## Kart neyi cevaplamak zorunda
 *
 * Kullanıcı: *"risk olan bölüm ne için riskli, geri döndürülebilir mi, amaç ve
 * yetki belirlenip onay isteyecek"*. Bu beş satır kartın sözleşmesi:
 *
 * 1. **NE** — çalışacak şey, kısaltılmış (komut saklanmaz, özet saklanır)
 * 2. **NEDEN RİSKLİ** — kuralın tek cümlesi + hedefin etiketi
 * 3. **GERİ DÖNÜŞ** — "journal geri getirir" / "geri gelmez"
 * 4. **AMAÇ** — ajanın beyanı; beyan yoksa bunu **söyler**
 * 5. **YETKİ** — hangi alana dokunuyor (sandbox / oturum / telefon / ağ / hesap)
 *
 * Beş satırdan biri eksikse kart yanlış tasarlanmıştır. Özellikle 4. satır:
 * uydurulmuş bir amaç cümlesi yazmak, "amaç belirtilmedi" yazmaktan daha
 * zararlı — onay kartının tek işi kullanıcıyı yanlış rahatlatmamak.
 */
data class ApprovalRequest(
    /** Araç çağrısı + parça indeksi; aynı çağrı iki kez kart üretmesin. */
    val id: String,
    val verdict: RiskVerdict,
    val purpose: Purpose,
    val offeredScopes: List<ApprovalScope>,
    /** Kartın başlığı — "Dizin silinecek" gibi kısa bir ad. */
    val title: String,
) {

    data class Line(val label: String, val value: String, val strong: Boolean = false)

    fun lines(): List<Line> = buildList {
        add(Line("NE", shortOperation(), strong = true))
        add(Line("NEDEN RİSKLİ", reasonsLine()))
        add(Line("GERİ DÖNÜŞ", recoveryLine(), strong = verdict.recovery == Recovery.PERMANENT))
        add(Line("AMAÇ", purpose.render, strong = purpose.weak))
        add(Line("YETKİ", reachLine()))
    }

    /** Kapsam seçeneklerinin metni — kartın düğmeleri. */
    fun scopeOptions(): List<String> = offeredScopes.map { scope ->
        when (scope) {
            is ApprovalScope.Session -> "Yalnız bu sohbet"
            is ApprovalScope.Project -> "Bu projede hep"
            ApprovalScope.Global -> "Tüm Minis'te hep"
        }
    }

    /**
     * Kalıcılık kısıtı varsa açıkça yazılır.
     *
     * Sessiz kısıt, kullanıcının "neden projede hatırlamadı" sorusunu doğurur;
     * kart bunu önceden cevaplar.
     */
    fun persistenceNote(): String? {
        if (offeredScopes.none { it is ApprovalScope.Session } && offeredScopes.isNotEmpty()) {
            return null
        }
        return when {
            verdict.recovery == Recovery.PERMANENT &&
                offeredScopes.size == 1 -> "Geri alınamadığı için yalnız bu sohbette hatırlanır."
            verdict.reach >= Reach.DEVICE && offeredScopes.none { it == ApprovalScope.Global } ->
                "Telefon genelini etkilediği için tüm Minis'te hatırlanmaz."
            else -> null
        }
    }

    private fun shortOperation(): String {
        val seg = verdict.segment.trim().replace(Regex("""\s+"""), " ")
        if (seg.isEmpty()) return title
        return if (seg.length <= 90) seg else seg.take(87) + "…"
    }

    private fun reasonsLine(): String {
        val effect = verdict.reasons.firstOrNull { it.kind == RiskReason.Kind.EFFECT }?.text
        val target = verdict.reasons.filter { it.kind == RiskReason.Kind.TARGET }.joinToString(", ") { it.text }
        return listOfNotNull(effect, target.ifEmpty { null }).joinToString(" — ").ifEmpty { title }
    }

    private fun recoveryLine(): String = when (verdict.recovery) {
        Recovery.UNDOABLE -> "geri alınabilir (journal eski hâli tutuyor)"
        Recovery.RECOVERABLE -> "kendiliğinden gelmez; elle kurtarılabilir (reflog, yedek, yeniden kurulum)"
        Recovery.PERMANENT -> "GERİ GELMEZ"
    }

    private fun reachLine(): String = when (verdict.reach) {
        Reach.SANDBOX -> "yalnız ajanın kendi çalışma dizini"
        Reach.SESSION -> "bu oturumun dosyaları (offloads, ekler)"
        Reach.DEVICE -> "telefon geneli — sandbox dışı"
        Reach.NETWORK -> "ağ — bu cihazın dışında etki"
        Reach.ACCOUNT -> "hesap ve anahtarlar"
    }

    companion object {
        fun of(verdict: RiskVerdict, purpose: Purpose, scopes: List<ApprovalScope>): ApprovalRequest =
            ApprovalRequest(
                id = verdict.ruleId + "#" + verdict.segment.hashCode().toString(16),
                verdict = verdict,
                purpose = purpose,
                offeredScopes = scopes,
                title = defaultTitle(verdict.ruleId),
            )

        private fun defaultTitle(ruleId: String): String = when (ruleId) {
            "shell:rm-recursive" -> "Dizin ağacı silinecek"
            "shell:rm" -> "Dosya silinecek"
            "shell:find-delete" -> "Kalıba uyan dosyalar silinecek"
            "git:push-force" -> "Uzak dal geçmişi ezilecek"
            "git:reset-hard" -> "Commit edilmemiş değişiklikler atılacak"
            "git:clean-f" -> "İzlenmeyen dosyalar silinecek"
            "net:upload" -> "Dosya dışarıya gönderilecek"
            "net:ssh" -> "Başka makinede komut çalışacak"
            "net:cloud-cli" -> "Bulut hesabında işlem yapılacak"
            "device:settings" -> "Telefon ayarı değişecek"
            "device:pm-bad" -> "Uygulama verisi silinecek"
            "device:sudo" -> "Root yetkisiyle komut çalışacak"
            "credential:read" -> "Anahtar dosyası okunacak"
            "file:write" -> "Dosya değiştirilecek"
            else -> "Onay gerekiyor"
        }
    }
}
