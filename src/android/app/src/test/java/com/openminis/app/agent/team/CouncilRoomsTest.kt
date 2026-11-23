package com.openminis.app.agent.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37099c] Konsey odalarının veri hattı — cihazsız koşar.
 *
 * Neden test: üç oda (karşılaştırma, iletişim, bağımlılık) **var olmayan veri**
 * göstermek üzere yazılırsa boş bir ekran olur ve kullanıcı "çalışmıyor" der.
 * Kayıt defteri `android.*` import etmiyor; bu yüzden hangi alanın gerçekten
 * dolduğunu burada kanıtlayabiliyoruz.
 */
class CouncilRoomsTest {

    private fun startRun(id: String = "r1") {
        CouncilLiveRegistry.start(
            runId = id,
            teamId = "t1",
            teamName = "Konsey",
            task = "görev",
            memberNames = listOf("Ayşe" to "analist", "Mehmet" to "mühendis"),
            roundsPlanned = 3,
        )
    }

    @Test
    fun `sorular iletisim hattina yazilir`() {
        startRun("comms")
        CouncilLiveRegistry.memberAsks(
            runId = "comms",
            round = 1,
            from = "Ayşe",
            questions = listOf(Question(to = "Mehmet", q = "Hangi Android sürümü?")),
            tasks = emptyList(),
        )
        val run = CouncilLiveRegistry.runs.value["comms"]
        assertEquals(1, run?.questions?.size)
        assertEquals("Ayşe → Mehmet", "${run!!.questions[0].from} → ${run.questions[0].to}")
        assertEquals(1, run.questions[0].round)
        CouncilLiveRegistry.forget("comms")
    }

    @Test
    fun `ayni soru iki kez eklenmez`() {
        startRun("dup")
        repeat(3) {
            CouncilLiveRegistry.memberAsks(
                runId = "dup", round = 1, from = "Ayşe",
                questions = listOf(Question("Mehmet", "Aynı soru?")), tasks = emptyList(),
            )
        }
        // Üyeler bir turda sordukları soruyu sonraki turda yine sorabiliyor;
        // ekranda dört aynı satır bir hata gibi okunur.
        assertEquals(1, CouncilLiveRegistry.runs.value["dup"]?.questions?.size)
        CouncilLiveRegistry.forget("dup")
    }

    @Test
    fun `bos soru ve bos istek yazilmaz`() {
        startRun("empty")
        CouncilLiveRegistry.memberAsks(
            runId = "empty", round = 1, from = "Ayşe",
            questions = listOf(Question("  ", "x"), Question("Mehmet", " ")),
            tasks = listOf(TaskRequest(to = "Mehmet", title = "  ")),
        )
        val run = CouncilLiveRegistry.runs.value["empty"]
        assertTrue(run!!.questions.isEmpty())
        assertTrue(run.needs.isEmpty())
        CouncilLiveRegistry.forget("empty")
    }

    @Test
    fun `devir istegi bagimlilik hattina yazilir`() {
        startRun("needs")
        CouncilLiveRegistry.memberAsks(
            runId = "needs", round = 2, from = "Ayşe",
            questions = emptyList(),
            tasks = listOf(TaskRequest(to = "Mehmet", title = "Testleri koş", why = "kanıt yok")),
        )
        val run = CouncilLiveRegistry.runs.value["needs"]!!
        assertEquals(1, run.needs.size)
        assertEquals("Ayşe", run.needs[0].from)
        assertEquals("Testleri koş", run.needs[0].title)
        assertEquals(2, run.needs[0].round)
        CouncilLiveRegistry.forget("needs")
    }

    @Test
    fun `karsilastirma odasi icin duruslar kaydedilir`() {
        startRun("cmp")
        val claim = Claim(
            id = "C1",
            text = "Bu cihaz destekler",
            stances = mapOf("Ayşe" to Stance.SUPPORT, "Mehmet" to Stance.OPPOSE),
        )
        val verdict = ClaimVerdict(claim, Verdict.CONTESTED)
        CouncilLiveRegistry.claims("cmp", Consensus(verdicts = listOf(verdict), cellsChanged = 1), tokens = 120)
        val line = CouncilLiveRegistry.runs.value["cmp"]!!.claims.single()
        // Sayılar "kaç kişi", duruşlar "kim" sorusunu cevaplar; oda ikincisini çizer.
        assertEquals(1, line.support)
        assertEquals(1, line.oppose)
        assertEquals("support", line.stances["Ayşe"])
        assertEquals("oppose", line.stances["Mehmet"])
        CouncilLiveRegistry.forget("cmp")
    }

    @Test
    fun `yeni alanlar varsayilan bos`() {
        startRun("fresh")
        val run = CouncilLiveRegistry.runs.value["fresh"]!!
        // Eski bir çalıştırma kaydı bu alanları bilmez; boş olmalı, null değil.
        assertTrue(run.questions.isEmpty())
        assertTrue(run.needs.isEmpty())
        CouncilLiveRegistry.forget("fresh")
    }

    @Test
    fun `sorunun cevabi hedefin sonraki turundan eslenir`() {
        startRun("ans")
        CouncilLiveRegistry.memberAsks(
            runId = "ans", round = 1, from = "Ayşe",
            questions = listOf(Question("Mehmet", "Hangi sürüm?")), tasks = emptyList(),
        )
        // Tur 2: Mehmet pozisyon verir — cevap bu metinden gelir.
        CouncilLiveRegistry.matchAnswers("ans", round = 2, member = "Mehmet", claim = "Android 16, SDK 36")
        val q = CouncilLiveRegistry.runs.value["ans"]!!.questions.single()
        assertEquals("Android 16, SDK 36", q.answer)
        assertEquals(2, q.answeredRound)
        CouncilLiveRegistry.forget("ans")
    }

    @Test
    fun `ayni turda verilen pozisyon cevap sayilmaz`() {
        startRun("same")
        CouncilLiveRegistry.memberAsks(
            runId = "same", round = 2, from = "Ayşe",
            questions = listOf(Question("Mehmet", "Soru")), tasks = emptyList(),
        )
        // q.round < round koşulu: aynı turda sorulan soruya aynı turda cevap olmaz.
        CouncilLiveRegistry.matchAnswers("same", round = 2, member = "Mehmet", claim = "erken cevap")
        assertTrue(CouncilLiveRegistry.runs.value["same"]!!.questions.single().answer.isEmpty())
        CouncilLiveRegistry.forget("same")
    }

    @Test
    fun `cevap bir kez yazilir`() {
        startRun("once")
        CouncilLiveRegistry.memberAsks(
            runId = "once", round = 1, from = "Ayşe",
            questions = listOf(Question("Mehmet", "Soru")), tasks = emptyList(),
        )
        CouncilLiveRegistry.matchAnswers("once", 2, "Mehmet", "ilk cevap")
        CouncilLiveRegistry.matchAnswers("once", 3, "Mehmet", "ikinci cevap")
        // Kullanıcının okuduğu satır sonradan değişirse hat "kayan" olur.
        assertEquals("ilk cevap", CouncilLiveRegistry.runs.value["once"]!!.questions.single().answer)
        CouncilLiveRegistry.forget("once")
    }

    @Test
    fun `yanlis uyeye cevap yazilmaz`() {
        startRun("wrong")
        CouncilLiveRegistry.memberAsks(
            runId = "wrong", round = 1, from = "Ayşe",
            questions = listOf(Question("Mehmet", "Soru")), tasks = emptyList(),
        )
        CouncilLiveRegistry.matchAnswers("wrong", 2, "Ayşe", "kendi kendine cevap")
        assertTrue(CouncilLiveRegistry.runs.value["wrong"]!!.questions.single().answer.isEmpty())
        CouncilLiveRegistry.forget("wrong")
    }

    @Test
    fun `kanitsiz gorev kapanmaz kanitla kapanir`() {
        var board = TaskBoard()
        val (b1, task, why1) = board.add(TeamTask(id = "t1", title = "Testleri koş"))
        assertTrue("başlık boş değilse eklenir", why1.isEmpty())
        board = b1
        // Kanıtsız DONE reddedilir — kural TeamTask.canBeDone'da.
        val (b2, _, why2) = board.moveTo(task!!.id, TaskState.DONE)
        assertTrue("kanıtsız kapanmamalı", why2.isNotEmpty())
        assertEquals(1, b2.tasks.size)
        // Kanıt eklenince açılır.
        val (b3, why3) = b2.addEvidence(
            task.id,
            TaskEvidence(kind = EvidenceKind.OUTPUT, value = "./gradlew test — 0 failed", by = "user"),
        )
        assertTrue(why3.isEmpty())
        val (b4, done, why4) = b3.moveTo(task.id, TaskState.DONE)
        assertTrue("kanıtla kapanmalı: $why4", why4.isEmpty())
        assertEquals(TaskState.DONE, done?.state)
        assertEquals(TaskState.DONE, b4.find(task.id)?.state)
    }

    @Test
    fun `bos kanit reddedilir`() {
        val (board, task, _) = TaskBoard().add(TeamTask(id = "t2", title = "x"))
        val (after, why) = board.addEvidence(task!!.id, TaskEvidence(EvidenceKind.PATH, "   "))
        assertTrue("boş kanıt bir yalan satırıdır", why.isNotEmpty())
        assertEquals(0, after.find(task.id)?.evidence?.size)
    }

    @Test
    fun `on kontrol notu kayda yazilir`() {
        startRun("pf")
        CouncilLiveRegistry.preflight("pf", "Konsey ön kontrolü: 1/2 üye hazır")
        assertEquals(
            "Konsey ön kontrolü: 1/2 üye hazır",
            CouncilLiveRegistry.runs.value["pf"]?.preflightNote,
        )
        CouncilLiveRegistry.forget("pf")
    }
}
