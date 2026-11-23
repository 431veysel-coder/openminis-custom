package com.openminis.app.agent.team

import com.openminis.app.data.team.TeamMember

/**
 * [37099e · §1.2] **Koşu başlamadan** üye doğrulaması.
 *
 * ## Neden (ölçülen arıza)
 *
 * Model seçilmemiş ya da sağlayıcı anahtarı geçersiz bir üyeyle konsey
 * başlıyordu; 0. turda düşüyor, geriye `no_positions` ve boş bir rapor
 * kalıyordu. Token ve para harcanmış oluyordu çünkü diğer üyeler koşmuştu.
 *
 * ## Neden saf (Android'siz)
 *
 * Karar "hangi üye koşabilir" sorusudur; `Context` buna gerek yok. Çağıran
 * taraf model listesini ölçer, bu sınıf karar verir — `CouncilPolicy` ile aynı
 * ayrım, aynı sebep: kural test edilebilir kalsın.
 *
 * ## Neden engelleme değil, uyarı + karar
 *
 * Üç davranış var ve hiçbiri diğerinin yerine geçmez:
 *  - **hepsi hazır** → koş,
 *  - **bazıları hazır** → kullanıcıya sor: "3 üyeden 2'si koşabilir, devam?"
 *    (sessizce 2'ye düşürmek "5 üye kurdum, 2 cevap geldi" şaşkınlığı üretir),
 *  - **hiçbiri hazır** → başlatma, nedeni üye üye yaz.
 */
object CouncilPreflight {

    /** Bir üyenin koşabilirliği. */
    data class Check(
        val member: String,
        val ready: Boolean,
        val failure: MemberFailure,
        /** Ekranda aynen görünecek gerekçe. */
        val note: String,
    )

    data class Verdict(
        val checks: List<Check>,
    ) {
        val ready: List<Check> get() = checks.filter { it.ready }
        val blocked: List<Check> get() = checks.filter { !it.ready }

        /** Hiç üye koşamıyorsa false — koşu başlamamalı. */
        val canStart: Boolean get() = ready.isNotEmpty()

        /** Geriye kalan üyelerle başlamak kullanıcı onayı ister mi. */
        val needsConfirmation: Boolean get() = ready.isNotEmpty() && blocked.isNotEmpty()

        /** Tek satır özet: "2/3 üye hazır". */
        fun summary(): String = "${ready.size}/${checks.size} üye hazır"

        /**
         * Kullanıcıya gösterilecek tam metin.
         *
         * Neden üye üye: "3 üye hazır değil" demek, hangisinin düzeltileceğini
         * söylemez. Her satır sınıf + öneri + ham gerekçe taşır.
         */
        fun report(): String = buildString {
            appendLine("Konsey ön kontrolü: ${summary()}")
            if (canStart) {
                appendLine("Koşabilir: ${ready.joinToString(", ") { it.member }}")
            }
            blocked.forEach { c ->
                appendLine("- ${c.member} → ${c.failure.label}: ${c.note}")
                appendLine("  öneri: ${c.failure.advice}")
            }
            if (!canStart) {
                appendLine()
                appendLine("Hiçbir üye koşamadığı için konsey BAŞLATILMADI. Token harcanmadı.")
            }
        }
    }

    /**
     * Üyeleri doğrular.
     *
     * @param members ekip tanımındaki üyeler
     * @param resolve üye → model var mı (çağıran taraf sağlayıcı deposuna bakar)
     */
    fun check(
        members: List<TeamMember>,
        resolve: (TeamMember) -> Boolean,
    ): Verdict {
        if (members.isEmpty()) {
            return Verdict(
                listOf(Check("<ekip>", ready = false, failure = MemberFailure.NO_MODEL, note = "Ekipte üye yok.")),
            )
        }
        val checks = members.map { m ->
            val ok = runCatching { resolve(m) }.getOrDefault(false)
            if (ok) {
                Check(m.name, ready = true, failure = MemberFailure.UNKNOWN, note = "")
            } else {
                Check(
                    member = m.name,
                    ready = false,
                    failure = MemberFailure.NO_MODEL,
                    note = "Providers'ta bu üyeye atanmış kullanılabilir model yok " +
                        "(model kimliği: ${m.modelId ?: "atanmamış"}).",
                )
            }
        }
        return Verdict(checks)
    }
}
