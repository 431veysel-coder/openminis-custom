package com.openminis.app.ui.team.office

import com.openminis.app.agent.team.CouncilLiveRegistry
import com.openminis.app.agent.team.CouncilLiveRegistry.MemberLive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [F2] Ofis ızgarası testleri.
 *
 * Sınanan iki şey: (1) kutu **taşmıyor**, (2) aynı üyeye **yanlış etiket**
 * verilmiyor. İkincisi daha önemli: "sırada" ile "bekliyor" karışırsa
 * kullanıcı çalışan bir koşuyu bozuk sanır.
 */
class OfficeGridTest {

    private fun member(
        name: String = "Ayşe",
        claim: String = "",
        tool: String? = null,
        absent: Boolean = false,
    ) = MemberLive(name = name, role = "analiz", claim = claim, tool = tool, absent = absent)

    // ── sütunlar ────────────────────────────────────────────────────────────

    @Test
    fun `two columns on a phone width, never fewer members than columns`() {
        // 360 dp telefon, 8 dp boşluk, 156 dp asgari kutu → 360/156 = 2 sütun
        assertEquals(2, OfficeGrid.columns(availableDp = 360, memberCount = 5))
        // Tek üyeli koşuda iki sütun açmak, sağ yarıyı boş bırakmak olurdu.
        assertEquals(1, OfficeGrid.columns(availableDp = 360, memberCount = 1))
    }

    @Test
    fun `tablet width never exceeds the readable cap`() {
        // 4 sütundan fazlası telefonda okunmaz; geniş ekranda da sınır 4.
        assertEquals(OfficeGrid.MAX_COLUMNS, OfficeGrid.columns(availableDp = 1500, memberCount = 12))
    }

    @Test
    fun `narrow width still gives one column instead of zero`() {
        // 156 dp'den dar bir alan (ör. bölünmüş ekran): 0 sütun = hiç kutu
        // çizilmemesi olurdu. Bir sütun, taşan ama görünen bir kutu.
        assertEquals(1, OfficeGrid.columns(availableDp = 120, memberCount = 3))
        assertEquals(0, OfficeGrid.columns(availableDp = 360, memberCount = 0))
    }

    @Test
    fun `column width accounts for the gaps`() {
        // 360 - 8 = 352 / 2 = 176
        assertEquals(176, OfficeGrid.columnWidthDp(availableDp = 360, columns = 2))
        // Tek sütunda boşluk yok.
        assertEquals(360, OfficeGrid.columnWidthDp(availableDp = 360, columns = 1))
    }

    // ── durum ───────────────────────────────────────────────────────────────

    @Test
    fun `absent wins over everything`() {
        // Model yok + araç adı kalmış olsa bile "gelmedi" yazılır: araç
        // rozeti ölü bir koşudan kalmış olabilir.
        val m = member(absent = true, tool = "rg", claim = "eski iddia")
        assertEquals(
            OfficeGrid.State.ABSENT,
            OfficeGrid.stateOf(m, waveIndex = 0, waveSize = 4, roundActive = true),
        )
    }

    @Test
    fun `tool beats answered because it is happening now`() {
        val m = member(tool = "rg", claim = "önceki turdan iddia")
        assertEquals(
            OfficeGrid.State.TOOL,
            OfficeGrid.stateOf(m, 0, 4, roundActive = true),
        )
    }

    @Test
    fun `queued only when the wave has not reached the member`() {
        val m = member()
        // dalga 4, üye sırası 5 → sırada
        assertEquals(OfficeGrid.State.QUEUED, OfficeGrid.stateOf(m, 5, 4, true))
        // dalga 4, üye sırası 2 → sırası geldi, henüz cevap yok → bekliyor
        assertEquals(OfficeGrid.State.IDLE, OfficeGrid.stateOf(m, 2, 4, true))
    }

    @Test
    fun `unknown wave size never claims a member is queued`() {
        // `waveSize = 0` = bilinmiyor. "Sırada" demek uydurma olurdu (İ4).
        assertEquals(OfficeGrid.State.IDLE, OfficeGrid.stateOf(member(), 9, 0, true))
    }

    @Test
    fun `queued is not shown when the round is over`() {
        // Tur bitmişse kimse "sırada" değildir; koşu bittiğinde de aynı.
        assertEquals(OfficeGrid.State.IDLE, OfficeGrid.stateOf(member(), 9, 4, roundActive = false))
    }

    @Test
    fun `answered wins when the member has a claim and no tool`() {
        assertEquals(OfficeGrid.State.ANSWERED, OfficeGrid.stateOf(member(claim = "iddia"), 0, 4, true))
    }
}
