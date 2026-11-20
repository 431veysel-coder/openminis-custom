package com.openminis.app.policy

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37096] Risk korpusu — kararların tek kaynağı.
 *
 * Neden dize testleri değil: tek tek `assert` yazmak, 30 komut için 30 test
 * demek ve yeni bir kaçış yolu eklendiğinde nereye yazılacağı belirsiz kalıyor.
 * Korpus tek dosya (`test/resources/ozel/risk-corpus.txt`), aynı dosyayı
 * `scripts/ozel/risk-corpus.py` de okuyor. Bir satırı değiştirmek bir ürün
 * kararını değiştirmektir — ve o karar burada, tek yerde görünür.
 *
 * Karar eşlemesi `ApprovalPolicy`'nin kendisinden geliyor: test, sınıflandırıcı
 * ile politika arasındaki anlaşmayı da sınıyor (ör. `deny:` kimliğinin
 * gerçekten sorulmadan reddedildiğini).
 */
class RiskCorpusTest {

    /** Üretimdeki oturum diziniyle aynı biçim: `/var/minis/minis-sessions/<id>`. */
    private val session = "/var/minis/minis-sessions/abc123"

    @Test
    fun `korpus kararlari beklenenle ayni`() {
        val corpus = javaClass.classLoader
            ?.getResourceAsStream("ozel/risk-corpus.txt")
            ?.bufferedReader()
            ?.readText()
            ?: error("korpus bulunamadı: test/resources/ozel/risk-corpus.txt")

        val classifier = RiskClassifier(session)
        // Boş defter: hiçbir izin verilmemiş kullanıcı. Testin sorduğu soru bu.
        val book = GrantBook(ApprovalScope.Session(session), null)

        val failures = mutableListOf<String>()
        var checked = 0

        corpus.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val parts = line.split("|||").map { it.trim() }
            if (parts.size < 2) {
                failures += "bozuk satır: $line"
                return@forEach
            }
            val command = parts[0]
            val expected = parts[1]
            val verdict = classifier.classifyShell(command)
            val actual = when (ApprovalPolicy.decide(verdict, book, 0L)) {
                is GateDecision.Deny -> "DENY"
                is GateDecision.Ask -> "ASK"
                is GateDecision.Silent -> "SILENT"
            }
            checked++
            if (actual != expected) {
                failures += "«$command» → $actual, beklenen $expected " +
                    "(kural=${verdict.ruleId}, seviye=${verdict.level}, " +
                    "reach=${verdict.reach}, geri=${verdict.recovery})"
            }
        }

        assertTrue("korpus okunamadı ya da boş", checked >= 20)
        assertTrue(
            "risk kararları saptı (${failures.size}/$checked):\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
