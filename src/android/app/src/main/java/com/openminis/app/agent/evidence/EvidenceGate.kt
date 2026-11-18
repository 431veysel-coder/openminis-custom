package com.openminis.app.agent.evidence

/**
 * [OZEL 37087] Kanıtlı "bitti" — erken "tamam" demenin panzehiri.
 *
 * ## Neden gerekli
 *
 * Küçük modellerin en pahalı hatası yalan söylemek değil, **yanlış sanmak**:
 * dosyayı yazdığını hatırlıyor, testi çalıştırdığını sanıyor, "tamamlandı"
 * diyor. Kullanıcı ekrana bakıyor, iş yapılmamış. `verify` aracı var ama
 * opsiyonel; kullanılmayan araç korumaz.
 *
 * ## Kural
 *
 * "Bitti" beyanı, son [WINDOW_TURNS] tur içinde üretilmiş bir **kanıt** ile
 * gelmek zorunda. Kanıt aracın kendi çıktısıdır, modelin cümlesi değil:
 * komut çıktısı, test sonucu, var olan dosya, ekran görüntüsü.
 *
 * ## Neden sert reddetmiyor
 *
 * "Kanıt yok" cevabı işi bitirmez, modeli döngüye sokar. Bu yüzden ret,
 * **ne sayılacağını** söylüyor: "şu komutu çalıştır, çıktısını göster".
 * Reddin işlevi cezalandırmak değil, bir sonraki adımı tarif etmek.
 */
enum class ProofKind {
    /** Komut başarıyla koştu ve çıktısı beklenen şeyi içeriyor. */
    COMMAND_OK,
    TEST_PASS,
    BUILD_OK,
    FILE_EXISTS,
    SCREENSHOT,
    /** `verify` aracı PASS verdi. */
    VERIFY_TOOL,
    /** Dış araç (MCP) sonucu. */
    EXTERNAL,
}

data class Proof(
    val kind: ProofKind,
    val turnIndex: Int,
    val atMs: Long,
    val detail: String,
    /** Ajanın iddiasıyla ilgili mi? İlgisiz kanıt kanıt değildir. */
    val subject: String = "",
)

sealed class ClaimCheck {
    data class Accepted(val proof: Proof) : ClaimCheck()
    data class Rejected(val reason: String, val howToProve: String) : ClaimCheck()
}

object EvidenceGate {

    /** Kanıt bu kadar turdan eskiyse sayılmaz — eski kanıt yeni işi kanıtlamaz. */
    const val WINDOW_TURNS = 3

    /**
     * @param claim ajanın "bitti" dediği şey ("testler geçti", "3 dosya düzeltildi")
     * @param currentTurn şu anki tur indeksi
     */
    fun check(
        claim: String,
        proofs: List<Proof>,
        currentTurn: Int,
        requireSubjectMatch: Boolean = true,
    ): ClaimCheck {
        val fresh = proofs.filter { currentTurn - it.turnIndex <= WINDOW_TURNS }
        if (fresh.isEmpty()) {
            return ClaimCheck.Rejected(
                reason = "son $WINDOW_TURNS turda üretilmiş kanıt yok",
                howToProve = howToProve(claim),
            )
        }

        val relevant = if (requireSubjectMatch && claim.isNotBlank()) {
            fresh.filter { subjectMatches(claim, it) }
        } else {
            fresh
        }
        if (relevant.isEmpty()) {
            return ClaimCheck.Rejected(
                reason = "kanıt var ama iddia edilen işle ilgili değil",
                howToProve = howToProve(claim),
            )
        }

        // En güçlü kanıtı seç: verify/test/build, komut çıktısından üstün.
        val best = relevant.maxByOrNull { strength(it.kind) }!!
        return ClaimCheck.Accepted(best)
    }

    private fun strength(k: ProofKind): Int = when (k) {
        ProofKind.VERIFY_TOOL -> 6
        ProofKind.TEST_PASS -> 5
        ProofKind.BUILD_OK -> 4
        ProofKind.SCREENSHOT -> 3
        ProofKind.FILE_EXISTS -> 2
        ProofKind.EXTERNAL -> 2
        ProofKind.COMMAND_OK -> 1
    }

    private fun subjectMatches(claim: String, p: Proof): Boolean {
        if (p.subject.isBlank()) return true // konu belirtilmemiş kanıt herkese açık
        return norm(claim).contains(norm(p.subject)) || norm(p.subject).contains(norm(claim).take(24))
    }

    /** İddiaya göre "nasıl kanıtlanır" — reddin ikinci yarısı. */
    fun howToProve(claim: String): String {
        val c = norm(claim)
        return when {
            c.contains("test") -> "testi çalıştırıp çıktısını göster (ör. `python3 -m pytest -q`)"
            c.contains("derle") || c.contains("build") -> "derlemeyi çalıştırıp son satırını göster"
            c.contains("dosya") || c.contains("yaz") -> "`file_exists` ya da `file_read` ile dosyanın içeriğini göster"
            c.contains("ekran") || c.contains("gorunum") -> "`browser_use` ile ekran görüntüsü al"
            c.contains("duzel") || c.contains("fix") -> "değişikliği gösteren komutu çalıştır (diff, grep, test)"
            else -> "`verify` aracını çalıştır ve sonucu göster"
        }
    }

    private fun norm(s: String): String = s.lowercase()
        .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
        .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
}
