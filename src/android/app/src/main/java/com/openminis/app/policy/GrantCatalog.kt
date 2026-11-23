package com.openminis.app.policy

/**
 * [37099b] **İzin kataloğu** — Ayarlar'dan toplu yetki vermenin tek kaynağı.
 *
 * ## Neden gerekli (ölçülen arıza)
 *
 * Kullanıcı, bir iş sırasında karta üst üste basmaktan şikâyet etti: her
 * `rm`, her `apt`, her `git push` için ayrı kart. Onay sistemi tasarım gereği
 * *işlem başına* soruyor; kullanıcının istediği ise **kategori başına bir kez,
 * Ayarlar'da, toplu**.
 *
 * ## Neden her kural toplu verilemiyor
 *
 * `ApprovalPolicy.offeredScopes` ürün kuralını zaten yazıyor: **geri dönüşü
 * olmayan iş, oturumdan uzun yaşayan onay alamaz**. Bu ekran o kuralı
 * gevşetmez, sadece *verilebilecek* izinleri tek yerde toplar. Yani:
 *
 * - `reach ≤ SANDBOX` ve `recovery ≠ PERMANENT` → toplu verilebilir.
 * - Geri kalanı → listede **görünür** ama anahtarı kapalı ve nedeni yazılı.
 *   Gizlemek "neden bunu soruyor ama bana göstermiyor" sorusunu doğurur.
 *
 * ## Neden kategori
 *
 * 43 kural tek tek anahtar olsaydı ekran yine bir karta dönüşürdü. Kategori
 * (`shell:`, `pkg:`, `net:` …) kullanıcının gerçek sorusuna karşılık geliyor:
 * "bu ajan dosya silsin mi", "paket kursun mu", "ağa çıksın mı".
 */
object GrantCatalog {

    /** Bir kuralın Ayarlar'daki satırı. */
    data class Item(
        val rule: RiskRules.Rule,
        /** Toplu izin verilebilir mi — `offeredScopes` ile aynı kural. */
        val grantable: Boolean,
        /** Verilemiyorsa nedeni; kartta aynen görünür. */
        val blockedWhy: String?,
    )

    /** Bir kategori = bir başlık + satırlar. */
    data class Category(
        val id: String,
        val title: String,
        val subtitle: String,
        val items: List<Item>,
    ) {
        val grantableItems: List<Item> get() = items.filter { it.grantable }
    }

    private data class Spec(val id: String, val title: String, val subtitle: String)

    private val SPECS = listOf(
        Spec("shell", "Files and shell", "delete, move, redirect, run scripts"),
        Spec("git", "Git", "force push, reset --hard, clean -f"),
        Spec("pkg", "Package install", "Alpine side: apk add / del"),
        Spec("termux", "Termux layer", "apt / pkg, sources.list, sshd"),
        Spec("net", "Network", "ssh, uploading files, raw sockets, cloud CLIs"),
        Spec("device", "Device (real screen)", "Shizuku, settings, app management"),
        Spec("sql", "Database", "destructive queries"),
        Spec("credential", "Credentials", "reading key files"),
    )

    /**
     * Tüm katalog. `deny:` kuralları **listelenmez**: onların kartı yok,
     * dolayısıyla verilecek izni de yok — göstermek "kapatılabilir" izlenimi
     * verirdi.
     */
    fun categories(): List<Category> = SPECS.mapNotNull { spec ->
        val items = RiskRules.ALL
            .filter { it.id.substringBefore(':') == spec.id }
            .map { rule -> item(rule) }
        if (items.isEmpty()) null else Category(spec.id, spec.title, spec.subtitle, items)
    }

    fun item(rule: RiskRules.Rule): Item {
        // `ApprovalPolicy` ile aynı kural — iki yerde iki farklı cevap olmaz.
        val grantable = rule.reach <= Reach.SANDBOX && rule.recovery != Recovery.PERMANENT
        val why = when {
            grantable -> null
            rule.recovery == Recovery.PERMANENT ->
                "geri alınamaz: yalnız bu sohbet için, kartla sorulur"
            else -> "sandbox dışına taşar (${rule.reach.name.lowercase()}): yalnız kartla"
        }
        return Item(rule, grantable, why)
    }

    /** Toplu verme için kayıtlar. Yalnız [grantable] kurallar kabul edilir. */
    fun grantsFor(
        items: List<Item>,
        scope: ApprovalScope = ApprovalScope.Global,
        nowMs: Long,
        note: String = "Ayarlar › İzinler",
    ): List<Grant> = items.filter { it.grantable }.map { grant(it.rule, scope, nowMs, note) }

    fun grant(
        rule: RiskRules.Rule,
        scope: ApprovalScope = ApprovalScope.Global,
        nowMs: Long,
        note: String = "Ayarlar › İzinler",
    ): Grant {
        // `ApprovalPolicy.ttlMs` bir verdict istiyor; sınıflandırıcıya komut
        // uydurmak yerine aynı alanlardan verdict kuruyoruz — kuralın kendi
        // level/reach/recovery değerleri elimizde.
        val verdict = RiskVerdict(
            level = rule.level,
            reach = rule.reach,
            recovery = rule.recovery,
            ruleId = rule.id,
            targetPrefix = "",
            reasons = listOf(RiskReason(RiskReason.Kind.TARGET, rule.note)),
            segment = "",
        )
        return ApprovalPolicy.grantFor(
            request = ApprovalRequest.of(verdict, Purpose(null, Purpose.Source.MISSING), listOf(scope)),
            scope = scope,
            decision = Decision.ALLOW,
            nowMs = nowMs,
            note = note,
        )
    }

    /**
     * Şu an geçerli olan toplu izinler — ekranın anahtar durumu.
     *
     * Yalnız `global` kapsam okunur: bu ekran toplu izin verir, oturum/proje
     * kapsamlı kayıtlar kartla verilir ve burada "açık" görünmemeli.
     */
    fun activeRuleIds(
        byScope: Map<String, List<Grant>>,
        nowMs: Long,
        live: (Grant, Long) -> Boolean = { g, now -> g.decision == Decision.ALLOW && g.live(now) },
    ): Set<String> = byScope[ApprovalScope.Global.key].orEmpty()
        .filter { live(it, nowMs) }
        .map { it.ruleId }
        .toSet()

    /** "Hepsini geri al" — global kapsamdaki tüm kayıtlar silinir. */
    fun revokeAll(byScope: Map<String, List<Grant>>): Map<String, List<Grant>> =
        byScope.toMutableMap().apply { remove(ApprovalScope.Global.key) }
}
