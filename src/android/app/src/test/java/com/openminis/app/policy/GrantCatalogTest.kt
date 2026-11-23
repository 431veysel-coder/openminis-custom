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
}
