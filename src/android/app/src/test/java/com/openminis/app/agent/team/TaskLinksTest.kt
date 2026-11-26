package com.openminis.app.agent.team

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37127] Görev ↔ iddia bağları.
 *
 * Neden test: karşılaştırma odasında "C3" yazıyordu ama ters yön yoktu —
 * hangi tartışmanın işe dönüştüğü görünmüyordu. Bağ yanlış hesaplanırsa bir
 * iddia yanlış göreve, bir görev yanlış iddiaya bağlanır ve kullanıcı
 * olmayan bir nedensellik okur.
 */
class TaskLinksTest {

    private fun claim(id: String, text: String = "t") =
        CouncilLiveRegistry.ClaimLine(id, text, 0, 0, 0, "")

    private fun task(id: String, claimRef: String = "", run: String = "run_1", by: String = "chair") =
        TeamTask(id = id, title = id, fromClaim = claimRef, runId = run, createdBy = by)

    @Test
    fun iddia_goreve_baglanir() {
        val links = TaskLinks.claimLinks(
            listOf(claim("C1"), claim("C2"), claim("C3")),
            listOf(task("t1", "C3"), task("t2", "C3"), task("t3", "C1")),
        )
        assertEquals(2, links.size)                      // C2 gorev uretmedi -> satir YOK
        assertEquals("C1", links[0].claimId)             // sira korunur
        assertEquals(1, links[0].count)
        assertEquals("C3", links[1].claimId)
        assertEquals(2, links[1].count)
    }

    @Test
    fun buyuk_kucuk_harf_ayni_iddia() {
        val links = TaskLinks.claimLinks(listOf(claim("C3")), listOf(task("t1", " c3 ")))
        assertEquals(1, links.size)
        assertEquals(1, links[0].count)
    }

    @Test
    fun gorevsiz_iddia_listelenmez_ama_sayilir() {
        val links = TaskLinks.claimLinks(listOf(claim("C1")), emptyList())
        assertEquals(0, links.size)
        assertEquals(0, TaskLinks.taskCountFor("C1", emptyList()))
    }

    @Test
    fun kaynaksiz_gorev_ayri_tutulur() {
        val orphans = TaskLinks.orphans(
            listOf(
                task("t1", "C1"),
                TeamTask(id = "t2", title = "elle eklenen"),          // kullanici
                TeamTask(id = "t3", title = "kaynaksiz", createdBy = "chair"),
            ),
        )
        assertEquals(2, orphans.size)
        assertTrue(orphans.none { it.id == "t1" })
    }
}
