package com.openminis.app.policy

/**
 * [OZEL 37080] Onay kapsamı ve defteri — "bir daha sorma"nın nereye yazıldığı.
 *
 * ## Üç kapsam, üç ayrı dosya
 *
 * Kullanıcı: *"hep proje onayı farklı, hafızası farklı, işlemi farklı"*.
 * Bu yüzden onay **tek bir havuzda** tutulmuyor. Her kapsamın kendi anahtarı
 * ve kendi dosyası var:
 *
 * - `project:<folderId>` → `minis-spaces/<folderId>/approvals.json`
 * - `session:<sessionId>` → `minis-sessions/<sessionId>/approvals.json`
 * - `global` → `minis-global/approvals.json`
 *
 * Ayrı dosya olmasının sebebi sadece düzen değil: proje silindiğinde onayları
 * da gider. Tek havuzda tutulsa, silinmiş bir projenin `rm` izni yeni bir
 * projede sessizce geçerli olurdu.
 *
 * ## Neden kapsam yukarı taşınmıyor
 *
 * Bir oturumda verilen izin **projeye terfi etmez**. Terfi ettirilirse
 * kullanıcı bir kez "evet" der ve o projedeki bütün sohbetlerde aynı izin
 * sessizce yürür; kullanıcı hangi kapıyı açtığını unutur. Terfi varsa
 * kullanıcı kartta ayrı bir seçenek olarak görür ve ayrıca onaylar.
 */
sealed class ApprovalScope {

    abstract val key: String
    abstract val label: String

    /** Sohbetle birlikte ölür. Kalıcı olmayan her şey burada başlar. */
    data class Session(val sessionId: String) : ApprovalScope() {
        override val key: String get() = "session:$sessionId"
        override val label: String get() = "bu sohbet"
    }

    /** Proje klasörüne bağlı; proje silinince dosya da silinir. */
    data class Project(val folderId: String) : ApprovalScope() {
        override val key: String get() = "project:$folderId"
        override val label: String get() = "bu proje"
    }

    /** Cihaz geneli. Yalnız geri dönülebilir ve sandbox içi işler için. */
    data object Global : ApprovalScope() {
        override val key: String get() = "global"
        override val label: String get() = "tüm Minis"
    }

    companion object {
        fun parse(key: String): ApprovalScope? = when {
            key == "global" -> Global
            key.startsWith("project:") -> Project(key.removePrefix("project:"))
            key.startsWith("session:") -> Session(key.removePrefix("session:"))
            else -> null
        }
    }
}

/** Karar. `ASK` hiçbir zaman saklanmaz — saklanacak bir şey yok. */
enum class Decision { ALLOW, DENY }

/**
 * Tek bir onay kaydı.
 *
 * [ruleId] + [targetPrefix] ikilisi kaydın kapsamı: "bu projede, şu dizin
 * altında `rm` serbest". Komut metni saklanmaz — her seferinde değişir.
 */
data class Grant(
    val ruleId: String,
    /** Boş = komutun tamamı için (ör. `git:push-force`). */
    val targetPrefix: String,
    val scope: ApprovalScope,
    val decision: Decision,
    val grantedAtMs: Long,
    val expiresAtMs: Long?,
    val maxUses: Int? = null,
    val uses: Int = 0,
    /** Kullanıcı reddettiğinde ajanın görmesi için kısa gerekçe. */
    val note: String = "",
) {

    fun expired(nowMs: Long): Boolean = expiresAtMs != null && nowMs >= expiresAtMs

    fun exhausted(): Boolean = maxUses != null && uses >= maxUses

    fun live(nowMs: Long): Boolean = !expired(nowMs) && !exhausted()

    /** Bu kayıt verilen hedefi kapsıyor mu? Önek eşleşmesi **dizin sınırında**. */
    fun covers(rule: String, target: String): Boolean {
        if (ruleId != rule) return false
        if (targetPrefix.isEmpty()) return true
        val t = target.trimEnd('/')
        val p = targetPrefix.trimEnd('/')
        return t == p || t.startsWith("$p/")
    }
}

/**
 * Kapsam başına defter. Saf: disk işi çağıranın (IO bağdaştırıcısının) işi.
 *
 * Eşleşme sırası **dar → geniş**: önce oturum, sonra proje, sonra global.
 * Sebep: kullanıcı projede izin verip sonra aynı sohbette reddettiyse,
 * reddin kazanması gerekir. Geniş kapsam dar kapsamı ezerse az önce verilen
 * "hayır" sessizce yok sayılmış olur.
 */
class GrantBook(
    val session: ApprovalScope.Session,
    val project: ApprovalScope.Project?,
) {

    private val byScope = LinkedHashMap<String, MutableList<Grant>>()

    fun all(): List<Grant> = byScope.values.flatten()

    fun inScope(scope: ApprovalScope): List<Grant> = byScope[scope.key].orEmpty()

    fun replaceScope(scope: ApprovalScope, grants: List<Grant>) {
        byScope[scope.key] = grants.toMutableList()
    }

    /** Mevcut kayıt kararı verirse döner; yoksa `null` (onay istenir). */
    fun lookup(ruleId: String, target: String, nowMs: Long): Grant? {
        val order = listOfNotNull(session, project, ApprovalScope.Global)
        for (scope in order) {
            val hits = inScope(scope).filter { it.live(nowMs) && it.covers(ruleId, target) }
            if (hits.isEmpty()) continue
            // Aynı kapsamda ret, izni yener: kullanıcı "hayır" dediyse daha
            // geniş bir "evet" onu sessizce geçersiz kılmamalı.
            return hits.firstOrNull { it.decision == Decision.DENY }
                ?: hits.maxByOrNull { it.targetPrefix.length }
        }
        return null
    }

    /** Kullanım sayacını artırır; sayaçlı kayıt dolunca listeden düşer. */
    fun consume(grant: Grant, nowMs: Long) {
        val list = byScope[grant.scope.key] ?: return
        val idx = list.indexOfFirst {
            it.ruleId == grant.ruleId && it.targetPrefix == grant.targetPrefix && it.decision == grant.decision
        }
        if (idx < 0) return
        val updated = list[idx].copy(uses = list[idx].uses + 1)
        if (updated.exhausted() || updated.expired(nowMs)) list.removeAt(idx) else list[idx] = updated
    }

    /**
     * Yeni kayıt ekler. Aynı (rule, hedef) varsa **yenisi eskisini ezer**:
     * kullanıcı fikrini değiştirdiğinde iki zıt kaydın birlikte yaşaması,
     * hangisinin kazanacağını tesadüfe bırakır.
     */
    fun put(grant: Grant) {
        val list = byScope.getOrPut(grant.scope.key) { mutableListOf() }
        list.removeAll { it.ruleId == grant.ruleId && it.targetPrefix == grant.targetPrefix }
        list.add(grant)
    }

    fun clearScope(scope: ApprovalScope) {
        byScope.remove(scope.key)
    }

    /** Ayarlar ekranı için: bu kapsamda neye izin verilmiş. */
    fun summary(nowMs: Long): List<String> = all()
        .filter { it.live(nowMs) }
        .sortedBy { it.ruleId }
        .map { g ->
            val d = if (g.decision == Decision.ALLOW) "izin" else "ret"
            val t = g.targetPrefix.ifEmpty { "komut" }
            val u = g.uses.let { if (it > 0) " · $it kez" else "" }
            "$d · ${g.ruleId} · $t$u"
        }
}
