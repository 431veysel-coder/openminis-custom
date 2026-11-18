package com.openminis.app.agent.evidence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [OZEL 37087] Kanıt kapısı testleri. */
class EvidenceGateTest {

    private fun proof(kind: ProofKind, turn: Int, subject: String = "", detail: String = "ok") =
        Proof(kind, turn, 0L, detail, subject)

    @Test
    fun `kanitsiz bitis reddedilir`() {
        val r = EvidenceGate.check("testler geçti", emptyList(), currentTurn = 5)
        assertTrue(r is ClaimCheck.Rejected)
        assertTrue((r as ClaimCheck.Rejected).howToProve.contains("testi çalıştır"))
    }

    @Test
    fun `eski kanit yeni isi kanitlamaz`() {
        // KARAR: 10 tur önceki test çıktısı, bu turdaki değişikliğin kanıtı değil.
        val r = EvidenceGate.check(
            "testler geçti",
            listOf(proof(ProofKind.TEST_PASS, turn = 1, subject = "testler")),
            currentTurn = 10,
        )
        assertTrue(r is ClaimCheck.Rejected)
    }

    @Test
    fun `penceredeki kanit kabul edilir`() {
        val r = EvidenceGate.check(
            "testler geçti",
            listOf(proof(ProofKind.TEST_PASS, turn = 4, subject = "testler")),
            currentTurn = 5,
        )
        assertTrue(r is ClaimCheck.Accepted)
        assertEquals(ProofKind.TEST_PASS, (r as ClaimCheck.Accepted).proof.kind)
    }

    @Test
    fun `ilgisiz kanit kabul edilmez`() {
        val r = EvidenceGate.check(
            "veritabanı göçü tamamlandı",
            listOf(proof(ProofKind.FILE_EXISTS, turn = 4, subject = "testler")),
            currentTurn = 5,
        )
        assertTrue(r is ClaimCheck.Rejected)
        assertTrue((r as ClaimCheck.Rejected).reason.contains("ilgili değil"))
    }

    @Test
    fun `konusuz kanit her iddiaya sayilir`() {
        val r = EvidenceGate.check(
            "dosya yazıldı",
            listOf(proof(ProofKind.FILE_EXISTS, turn = 4)),
            currentTurn = 5,
        )
        assertTrue(r is ClaimCheck.Accepted)
    }

    @Test
    fun `en guclu kanit secilir`() {
        // verify > test > komut çıktısı.
        val r = EvidenceGate.check(
            "testler geçti",
            listOf(
                proof(ProofKind.COMMAND_OK, turn = 5, subject = "testler"),
                proof(ProofKind.TEST_PASS, turn = 5, subject = "testler"),
                proof(ProofKind.VERIFY_TOOL, turn = 4, subject = "testler"),
            ),
            currentTurn = 5,
        )
        assertEquals(ProofKind.VERIFY_TOOL, (r as ClaimCheck.Accepted).proof.kind)
    }

    @Test
    fun `reddin ikinci yarisi sonraki adimi tarif eder`() {
        // KARAR: cezalandırmak değil, yön göstermek. "Kanıt yok" tek başına
        // modeli döngüye sokar.
        assertTrue(EvidenceGate.howToProve("derleme başarılı").contains("derlemeyi çalıştırıp"))
        assertTrue(EvidenceGate.howToProve("bir şeyler yaptım").contains("verify"))
    }
}
