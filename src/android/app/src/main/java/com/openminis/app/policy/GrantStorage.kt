package com.openminis.app.policy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [OZEL 37080] Onay defterinin diskteki karşılığı.
 *
 * ## Neden tek dosya, kapsam anahtarı içinde
 *
 * Üç kapsam var (`session:` / `project:` / `global`). Her biri için ayrı dosya
 * açmak, proje silindiğinde o projeye ait izinleri de silmek demekti. Tek
 * dosyada anahtar bazlı tutmak aynı şeyi yapıyor ama silme işini tek yerde
 * (`forgetScope`) topluyor — "proje gitti ama izni kaldı" hatasının tek kapısı.
 *
 * ## Neden JSON, neden oda değil
 *
 * İçerik küçük (onlarca kayıt), okuma sıklığı düşük (araç çağrısı başına bir
 * kez), ve Room göçü gerektirmiyor. Bir sürüm numarası tutuluyor: biçim
 * değişirse eski dosya okunmaz, **sessizce sıfırlanır** — yanlış yorumlanmış
 * bir izin, sıfırlanmış izinden çok daha tehlikeli.
 *
 * ## Neden bozuk dosya silinmiyor
 *
 * `ozel-approvals.bad.json` olarak yana alınıyor. Kullanıcı "neden yine sordu"
 * diye sorduğunda bakılacak bir şey kalsın; sessizce silmek hatayı gizler.
 */
object GrantStorage {

    private const val FILE = "ozel-approvals.json"
    private const val BAD = "ozel-approvals.bad.json"
    private const val VERSION = 1

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    fun load(ctx: Context): Map<String, List<Grant>> {
        val f = file(ctx)
        if (!f.exists()) return emptyMap()
        return try {
            val root = JSONObject(f.readText())
            if (root.optInt("version", 0) != VERSION) return emptyMap()
            val scopes = root.optJSONObject("scopes") ?: return emptyMap()
            buildMap {
                scopes.keys().forEach { key ->
                    val scope = ApprovalScope.parse(key) ?: return@forEach
                    val arr = scopes.optJSONArray(key) ?: JSONArray()
                    put(key, (0 until arr.length()).mapNotNull { i -> readGrant(arr.optJSONObject(i), scope) })
                }
            }
        } catch (t: Throwable) {
            // Bad file: set it aside, do not delete. See the class comment.
            runCatching { f.renameTo(File(f.parentFile, BAD)) }
            emptyMap()
        }
    }

    fun save(ctx: Context, byScope: Map<String, List<Grant>>) {
        val root = JSONObject()
        root.put("version", VERSION)
        val scopes = JSONObject()
        byScope.forEach { (key, grants) ->
            val arr = JSONArray()
            grants.forEach { arr.put(writeGrant(it)) }
            scopes.put(key, arr)
        }
        root.put("scopes", scopes)
        val f = file(ctx)
        // Write-then-rename: a crash mid-write must not leave a half file that
        // parses as "no grants" (which would silently re-ask everything) or as
        // garbage (which would lose every granted approval).
        val tmp = File(f.parentFile, "$FILE.tmp")
        runCatching {
            tmp.writeText(root.toString())
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        }
    }

    /** Proje silindiğinde çağrılır: o kapsamdaki tüm kayıtlar gider. */
    fun forgetScope(ctx: Context, scope: ApprovalScope) {
        val all = load(ctx).toMutableMap()
        if (all.remove(scope.key) != null) save(ctx, all)
    }

    private fun writeGrant(g: Grant): JSONObject = JSONObject().apply {
        put("rule", g.ruleId)
        put("target", g.targetPrefix)
        put("decision", g.decision.name)
        put("at", g.grantedAtMs)
        g.expiresAtMs?.let { put("expires", it) }
        g.maxUses?.let { put("maxUses", it) }
        put("uses", g.uses)
        if (g.note.isNotEmpty()) put("note", g.note)
    }

    private fun readGrant(o: JSONObject?, scope: ApprovalScope): Grant? {
        if (o == null) return null
        val rule = o.optString("rule")
        if (rule.isEmpty()) return null
        val decision = runCatching { Decision.valueOf(o.optString("decision")) }.getOrNull() ?: return null
        return Grant(
            ruleId = rule,
            targetPrefix = o.optString("target", ""),
            scope = scope,
            decision = decision,
            grantedAtMs = o.optLong("at", 0L),
            expiresAtMs = if (o.has("expires")) o.optLong("expires") else null,
            maxUses = if (o.has("maxUses")) o.optInt("maxUses") else null,
            uses = o.optInt("uses", 0),
            note = o.optString("note", ""),
        )
    }
}
