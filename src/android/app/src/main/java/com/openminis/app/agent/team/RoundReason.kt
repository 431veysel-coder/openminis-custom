package com.openminis.app.agent.team

/**
 * [F1 · .57] **"Neden bir tur daha?"** — döngünün devam kararının gerekçesi.
 *
 * ## Neden var
 *
 * `StanceMath` tur devam mı kararını veriyor (`converged`, `stableStreak`,
 * tur/jeton/süre tavanı). Ekranda yalnız `tur 2/3` yazıyordu: **sebebi yok**.
 * Tur 3'e neden girildiğini bilmeyen kullanıcı, koşuyu ya "bozuk" ya "bitmez"
 * sanıyor. Bu depoda tekrar eden sınıf: *sayı var, gerekçe yok*.
 *
 * ## Neden saf ve neden dil bilmez
 *
 * Girdi `Consensus` + `CouncilBudget`, çıktı makine anahtarı + sayı. Cümleyi
 * UI kurar. Çekirdeğe Türkçe cümle yazmak bu depoda bir kez ihlal edildi
 * (`.28`) ve düzeltildi; aynı hatayı ikinci kez yapmıyoruz.
 *
 * ## Neden karar VERMEZ
 *
 * Karar `StanceMath` ve `CouncilLoop`'ta. Burası yalnız **açıklar**: hangi
 * iddia hâlâ açık, hangi iddia karara bağlandı, eşik ne. İki yerde karar =
 * iki doğruluk = çelişen rapor (İ1).
 *
 * ## Neden "eşik %60" yazılıyor
 *
 * "2-2 kaldı" tek başına anlamsız: 4 kişilik konseyde 2-2 çıkmaz sokak, 8
 * kişilikte 5-3 karar. Eşiği yazmayan bir gerekçe, kullanıcıyı kendi kafasında
 * bölme yapmaya zorlar.
 */
object RoundReason {

    /** Hâlâ karara bağlanmamış tek iddia. */
    data class Open(
        val id: String,
        val support: Int,
        val oppose: Int,
        val uncertain: Int,
        /** `Verdict` wire adı (`contested`, `unresolved`, `orphan`). */
        val verdict: String,
    )

    /**
     * Bir sonraki tura geçme gerekçesi.
     *
     * @param nextRound gidilecek tur numarası (1 tabanlı)
     * @param open karara bağlanmayan iddialar — **boşsa** tur sebebi "hareket var"
     */
    data class Next(
        val nextRound: Int,
        val open: List<Open>,
        /** Önceki tura göre değişen (iddia, üye) hücre sayısı; `-1` = önceki tur yok. */
        val cellsChanged: Int,
        val stableStreak: Int,
        /** Karar için gereken oy oranı, yüzde olarak (`StanceMath.MAJORITY_SHARE`). */
        val majorityPercent: Int,
        val attendees: Int,
    ) {
        val hasOpen: Boolean get() = open.isNotEmpty()
    }

    /**
     * Sıradaki tur için gerekçe; **sıradaki tur yoksa `null`**.
     *
     * `null` döndüğü durumlar tam olarak döngünün durduğu durumlardır:
     * yakınsadı, yeterince kararlı, ya da tur tavanı doldu. Bunu burada
     * yeniden hesaplamak karar kopyalamak olurdu; bu yüzden çağıran
     * [CouncilLoop] durmadığını **bildiği** anda çağırır.
     */
    fun next(
        consensus: Consensus,
        budget: CouncilBudget,
        nextRound: Int,
        attendees: Int,
    ): Next? {
        if (nextRound > budget.maxRounds) return null
        val open = consensus.verdicts
            .filter { it.verdict != Verdict.DECIDED && it.verdict != Verdict.UNANIMOUS }
            .map {
                Open(
                    id = it.claim.id,
                    support = it.claim.support,
                    oppose = it.claim.oppose,
                    uncertain = it.claim.uncertain,
                    verdict = it.verdict.name.lowercase(),
                )
            }
            // En çok direnç gören üstte: kullanıcı önce onu okur.
            .sortedWith(compareByDescending<Open> { it.oppose + it.uncertain }.thenBy { it.id })
        return Next(
            nextRound = nextRound,
            open = open,
            cellsChanged = consensus.cellsChanged,
            stableStreak = consensus.stableStreak,
            majorityPercent = (StanceMath.MAJORITY_SHARE * 100).toInt(),
            attendees = if (attendees > 0) attendees else consensus.attendees,
        )
    }
}
