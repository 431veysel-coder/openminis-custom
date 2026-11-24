package com.openminis.app.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [37099b] Toplu izin kataloğu — Ayarlar › İzinler ekranının çekirdeği.
 *
 * Neden test: bu ekran **güvenlik sınırını genişletir**. Kural listesini yanlış
 * süzmek (ör. `device:sudo`'yu "verilebilir" saymak) tek dokunuşla sandbox
 * sınırını kaldırır. Testler ürün kuralını çiviliyor:
 *
 *   geri alınamaz ya da sandbox dışına taşan bir iş **toplu** verilemez.
 */
class GrantCatalogTest {

    private val now = 1_000_000L

    @Test
    fun `her kategori dolu ve kimlikleri kural onekine uyar`() {
        val cats = GrantCatalog.categories()
        assertTrue("katalog boş olmamalı", cats.isNotEmpty())
        cats.forEach { c ->
            assertTrue("${c.id} boş kategori", c.items.isNotEmpty())
            c.items.forEach { assertTrue("${c.id} ≠ ${it.rule.id}", it.rule.id.startsWith(c.id + ":")) }
        }
    }

    @Test
    fun `deny kurallari listede yok`() {
        val ids = GrantCatalog.categories().flatMap { c -> c.items.map { it.rule.id } }
        assertTrue(
            "deny: kurallarının kartı da izni de yok; listede görünmemeli",
            ids.none { it.startsWith("deny:") },
        )
    }

    @Test
    fun `device sudo toplu verilemez`() {
        val item = RiskRules.byId("device:sudo")?.let { GrantCatalog.item(it) }
        assertNotNull(item)
        assertFalse("sudo sandbox dışına taşar", item!!.grantable)
        assertNotNull(item.blockedWhy)
    }

    @Test
    fun `sandbox icinde kalan geri alinabilir isler verilebilir`() {
        val rule = RiskRules.byId("termux:pkg")
        assertNotNull(rule)
        val item = GrantCatalog.item(rule!!)
        assertTrue("apt/pip kurulumu geri alınabilir ve sandbox içinde", item.grantable)
        assertEquals(null, item.blockedWhy)
    }

    @Test
    fun `geri alinamaz is verilemez`() {
        // pm uninstall: PERMANENT → yalnız kartla, asla toplu.
        val item = RiskRules.byId("device:pm-bad")?.let { GrantCatalog.item(it) }
        assertNotNull(item)
        assertFalse(item!!.grantable)
    }

    @Test
    fun `grantsFor yalniz verilebilir kurallari yazar`() {
        val items = GrantCatalog.categories().flatMap { it.items }
        val grants = GrantCatalog.grantsFor(items, nowMs = now)
        assertTrue(grants.isNotEmpty())
        assertTrue(
            "verilemeyen kural kayda girmemeli",
            grants.all { g -> items.first { it.rule.id == g.ruleId }.grantable },
        )
        assertTrue(grants.all { it.scope == ApprovalScope.Global && it.decision == Decision.ALLOW })
        // Ömür sonlu: kalıcı izin yok (ApprovalPolicy.ttlMs global için 90/180 gün).
        assertTrue("global izin ömürsüz olmamalı", grants.all { it.expiresAtMs != null })
    }

    @Test
    fun `kayit gercekten izin veriyor`() {
        // Uçtan uca: katalogdan üretilen kayıt, defterde ALLOW olarak bulunmalı.
        val item = GrantCatalog.item(RiskRules.byId("termux:pkg")!!)
        val grant = GrantCatalog.grant(item.rule, nowMs = now)
        val book = GrantBook(ApprovalScope.Session("s"), null)
        book.replaceScope(ApprovalScope.Global, listOf(grant))
        val hit = book.lookup("termux:pkg", "", now)
        assertNotNull("katalog kaydı defterde bulunmalı", hit)
        assertEquals(Decision.ALLOW, hit!!.decision)
    }

    @Test
    fun `revokeAll global kapsami siler digerlerine dokunmaz`() {
        val sessionGrant = GrantCatalog.grant(
            RiskRules.byId("termux:pkg")!!, scope = ApprovalScope.Session("s"), nowMs = now,
        )
        val globalGrant = GrantCatalog.grant(RiskRules.byId("termux:pkg")!!, nowMs = now)
        val map = mapOf(
            ApprovalScope.Session("s").key to listOf(sessionGrant),
            ApprovalScope.Global.key to listOf(globalGrant),
        )
        val after = GrantCatalog.revokeAll(map)
        assertFalse(after.containsKey(ApprovalScope.Global.key))
        assertTrue(after.containsKey(ApprovalScope.Session("s").key))
    }

    @Test
    fun `activeRuleIds yalniz global ve gecerli kayitlari sayar`() {
        val live = GrantCatalog.grant(RiskRules.byId("termux:pkg")!!, nowMs = now)
        val expired = live.copy(expiresAtMs = now - 1)
        val sessionGrant = GrantCatalog.grant(
            RiskRules.byId("pkg:install")!!, scope = ApprovalScope.Session("s"), nowMs = now,
        )
        val map = mapOf(
            ApprovalScope.Global.key to listOf(live, expired),
            ApprovalScope.Session("s").key to listOf(sessionGrant),
        )
        val ids = GrantCatalog.activeRuleIds(map, now)
        assertEquals(setOf("termux:pkg"), ids)
    }

    @Test
    fun `kullanim sayaci yalniz global ve gecerli kayitlari toplar`() {
        val used = GrantCatalog.grant(RiskRules.byId("termux:pkg")!!, nowMs = now).copy(uses = 7)
        val expired = GrantCatalog.grant(RiskRules.byId("termux:pkg")!!, nowMs = now)
            .copy(expiresAtMs = now - 1, uses = 99)
        val session = GrantCatalog.grant(
            RiskRules.byId("pkg:install")!!, scope = ApprovalScope.Session("s"), nowMs = now,
        ).copy(uses = 3)
        val map = mapOf(
            ApprovalScope.Global.key to listOf(used, expired),
            ApprovalScope.Session("s").key to listOf(session),
        )
        val counts = GrantCatalog.usageCounts(map, now)
        // Süresi geçmiş kaydın 99 kullanımı sayılmaz: kullanıcıya "99 kez" demek yanlış olurdu.
        assertEquals(mapOf("termux:pkg" to 7), counts)
    }

    @Test
    fun `deny listesi okunur ama verilemez`() {
        val rows = GrantCatalog.denyRows()
        assertTrue("deny listesi boş olmamalı", rows.isNotEmpty())
        assertTrue(rows.all { it.id.startsWith("deny:") })
        assertTrue("her satırın nedeni olmalı", rows.all { it.note.isNotBlank() })
        // Listede görünmeleri onları verilebilir yapmaz.
        val ids = GrantCatalog.categories().flatMap { c -> c.items.map { it.rule.id } }
        assertTrue(ids.none { it.startsWith("deny:") })
    }

    @Test
    fun `ret listesi kurallari sayar ve son gerekceyi tasir`() {
        val deny1 = Grant(
            ruleId = "shell:rm", targetPrefix = "", scope = ApprovalScope.Global,
            decision = Decision.DENY, grantedAtMs = now - 5000, expiresAtMs = null,
            note = "bu projede değil",
        )
        val deny2 = deny1.copy(grantedAtMs = now - 1000, note = "hiçbir zaman")
        val deny3 = Grant(
            ruleId = "pkg:install", targetPrefix = "", scope = ApprovalScope.Session("s"),
            decision = Decision.DENY, grantedAtMs = now, expiresAtMs = null, note = "",
        )
        val map = mapOf(ApprovalScope.Global.key to listOf(deny1, deny2), "session:s" to listOf(deny3))
        val rows = GrantCatalog.denialRows(map, now)
        // En çok reddedilen başta.
        assertEquals("shell:rm", rows.first().ruleId)
        assertEquals(2, rows.first().count)
        // Son gerekçe geçerli olandır, ilk değil.
        assertEquals("hiçbir zaman", rows.first().lastNote)
        assertEquals("oturum", rows.first { it.ruleId == "pkg:install" }.scope)
    }

    @Test
    fun `onaylanan kayit ret listesine girmez`() {
        val allow = GrantCatalog.grant(RiskRules.byId("termux:pkg")!!, nowMs = now)
        val rows = GrantCatalog.denialRows(mapOf(ApprovalScope.Global.key to listOf(allow)), now)
        assertTrue("ALLOW kaydı red listesinde görünmemeli", rows.isEmpty())
    }

    @Test
    fun `kapsam onerisi riskle sinirlanir`() {
        // Sandbox + geri alınabilir: üç kapsam da önerilebilir.
        val safe = RiskRules.byId("termux:pkg")!!
        val safeScopes = GrantCatalog.offeredScopes(safe)
        assertEquals(3, safeScopes.size)

        // Geri alınamaz bir kural cihaz geneli izin almaz.
        val permanent = RiskRules.ALL.firstOrNull { it.recovery == Recovery.PERMANENT }
        if (permanent != null) {
            val scopes = GrantCatalog.offeredScopes(permanent)
            assertTrue(
                "geri alınamaz kural cihaz geneli izin almamalı",
                scopes.none { it is ApprovalScope.Global },
            )
            assertEquals(1, scopes.size)
        }

        // Cihaza taşan kural proje izni de almaz.
        val device = RiskRules.ALL.firstOrNull { it.reach == Reach.DEVICE }
        if (device != null) {
            val scopes = GrantCatalog.offeredScopes(device)
            assertTrue(
                "cihaz erişimli kural proje izni almamalı",
                scopes.none { it is ApprovalScope.Project },
            )
        }
    }

    @Test
    fun `uzun kosu onizlemesi ayarlardan susturulabilir`() {
        // [37099k · §1.3] `plan:long-run` hiçbir listede yoktu: PolicyGate onu
        // üretiyordu ama onay defteri kuralı tanımıyordu, dolayısıyla her ekip
        // koşusunda kart çıkıyor ve "bir daha sorma" mümkün olmuyordu.
        val rule = RiskRules.byId("plan:long-run")
        assertNotNull("kural kimliğiyle bulunabilmeli", rule)

        val ids = GrantCatalog.categories().flatMap { c -> c.items.map { it.rule.id } }
        assertTrue("İzinler ekranında görünmeli", ids.contains("plan:long-run"))

        val item = GrantCatalog.categories()
            .flatMap { it.items }
            .first { it.rule.id == "plan:long-run" }
        assertTrue("sandbox + geri alınabilir: verilebilir olmalı", item.grantable)

        // Metin taramasına girmemeli: rastgele bir komut bu kurala düşerse
        // sınıflandırma bozulur.
        assertFalse(rule!!.pattern.containsMatchIn("apt install jq"))
        assertFalse(rule.pattern.containsMatchIn("team_run"))
    }
}
