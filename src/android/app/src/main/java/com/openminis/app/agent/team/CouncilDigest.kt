package com.openminis.app.agent.team

/**
 * [H-019] **Koşu özeti** — "bu konsey ne karara vardı?" sorusunun tek satırı.
 *
 * ## Neden
 *
 * Karşılaştırma odası matrisi çiziyordu: satır iddia, sütun üye. Doğru ama
 * okunmuyordu. Beş iddia × beş üye = 25 hücre; kullanıcı "eee, sonuç?" diye
 * bakıyordu. Konseyin asıl çıktısı **kaç iddianın kapandığı** ve **ne açık
 * kaldığı**; bu ikisi hiçbir yerde sayı olarak yazmıyordu.
 *
 * ## Neden saf ve neden `ClaimLine` alıyor
 *
 * Karar `CouncilLoop`/`StanceMath` tarafından çoktan verilmiş (her iddianın
 * `verdict` alanı dolu). Burada yeniden karar vermek, iki kaynaklı bir doğruluk
 * üretir: rapor "decided" derken özet "contested" derse hangisi doğru?
 * Bu sınıf yalnız **sayar**. Kararın kendisi tek yerde kalır.
 *
 * ## "Yakınsadı" neden yüzde değil, "açık kaldı mı"
 *
 * Yüzde eşiği (ör. %70) uydurma bir sabittir: 3 iddialı bir koşuda %67 ile
 * %100 arasında hiçbir anlam farkı yok. Konseyin işi **karara bağlanamayanı
 * göstermek**; özet de onu yapar: [open] boşsa koşu kapandı, değilse hangi
 * iddiaların açık kaldığı tek tek yazılır.
 */
object CouncilDigest {

    /** Tek iddianın sayılabilir hâli — `ClaimLine`'dan türetilir. */
    data class Stat(
        val id: String,
        val text: String,
        val support: Int,
        val oppose: Int,
        val uncertain: Int,
        /** `Verdict` wire değeri (`unanimous`, `decided`, `contested`, `unresolved`, `orphan`). */
        val verdict: String,
    ) {
        val voted: Int get() = support + oppose + uncertain
        val open: Boolean get() = verdict == "contested" || verdict == "unresolved" || verdict == "orphan"
    }

    data class Summary(
        val total: Int,
        val unanimous: Int,
        val decided: Int,
        val contested: Int,
        val unresolved: Int,
        val orphan: Int,
        val participants: Int,
        /** Açık kalan iddialar, en çok oy alandan aza — kullanıcı önce en tartışmalıya bakar. */
        val open: List<Stat>,
        /** Hiç oy almamış iddialar: sandalyenin uydurduğu ya da duruşları kaybettiği satırlar. */
        val unvoted: List<Stat>,
        val roundsUsed: Int,
        val roundsPlanned: Int,
    ) {
        /** Karara bağlanan iddia sayısı. */
        val settled: Int get() = unanimous + decided
        /** Açık kalan var mı — koşunun "bitti" sayılması için boş olmalı. */
        val hasOpen: Boolean get() = open.isNotEmpty()
        /** Planlanan turun kaçı kullanıldı; `0` = plan yok. */
        val roundsLabel: String
            get() = if (roundsPlanned > 0) "$roundsUsed/$roundsPlanned" else roundsUsed.toString()
    }

    fun of(
        claims: List<Stat>,
        participants: Int,
        roundsUsed: Int,
        roundsPlanned: Int,
    ): Summary {
        val open = claims.filter { it.open }
            .sortedWith(compareByDescending<Stat> { it.oppose + it.uncertain }.thenBy { it.id })
        return Summary(
            total = claims.size,
            unanimous = claims.count { it.verdict == "unanimous" },
            decided = claims.count { it.verdict == "decided" },
            contested = claims.count { it.verdict == "contested" },
            unresolved = claims.count { it.verdict == "unresolved" },
            orphan = claims.count { it.verdict == "orphan" },
            participants = participants,
            open = open,
            unvoted = claims.filter { it.voted == 0 },
            roundsUsed = roundsUsed,
            roundsPlanned = roundsPlanned,
        )
    }

    /**
     * `ClaimLine` listesinden özet. Duruşlar (`stances`) varsa ondan sayılır:
     * `support/oppose/uncertain` alanları bazı yollarda güncellenmeden
     * kalabiliyor, harita ise kim-kim sorusunun tek kaynağı.
     */
    fun ofLines(
        lines: List<CouncilLiveRegistry.ClaimLine>,
        participants: Int,
        roundsUsed: Int,
        roundsPlanned: Int,
    ): Summary = of(
        claims = lines.map { line ->
            val fromMap = line.stances.values
            Stat(
                id = line.id,
                text = line.text,
                support = if (fromMap.isEmpty()) line.support else fromMap.count { it == Stance.SUPPORT.wire },
                oppose = if (fromMap.isEmpty()) line.oppose else fromMap.count { it == Stance.OPPOSE.wire },
                uncertain = if (fromMap.isEmpty()) line.uncertain else fromMap.count { it == Stance.UNCERTAIN.wire },
                verdict = line.verdict,
            )
        },
        participants = participants,
        roundsUsed = roundsUsed,
        roundsPlanned = roundsPlanned,
    )
}
