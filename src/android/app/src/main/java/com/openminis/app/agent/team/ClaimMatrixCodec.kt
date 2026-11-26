package com.openminis.app.agent.team

import org.json.JSONArray
import org.json.JSONObject

/**
 * [37126 · Konsey] **Duruş matrisini diske yazmak.**
 *
 * ## Neden
 *
 * `TeamRunStore.Header` koşu bittiğinde özeti (kaç tur, kaç token, hangi iddia
 * kaç oy aldı) yazıyordu — ama **kim hangi iddiaya ne dedi** yazılmıyordu.
 * `Claim.stances` yalnız bellekte, `CouncilLiveRegistry` içinde yaşıyordu.
 *
 * Sonuç: uygulama kapandığında karşılaştırma odası `live == null` oluyor ve
 * ekran "No comparison yet — the chair folds positions into claims first"
 * diyordu. Yani **koşu bitti diye tablo yok oluyordu**. Kullanıcı için en
 * değerli an tam olarak sonrası: "kim neye karşı çıktı" sorusunun cevabı.
 *
 * ## Neden JSON ve neden ayrı dosya
 *
 * Kayıt biçimi `TeamRunStore`'un geri kalanıyla aynı (org.json, tek dosya).
 * Kodek ayrı ve saf: (a) test edilebilir, (b) ileride biçim değişirse tek
 * yerde değişir, (c) ekran kodu JSON ayrıntısı görmez.
 *
 * ## Neden bilinmeyen duruş **atılıyor**, `UNCERTAIN` yapılmıyor
 *
 * `Stance.parse` bilinmeyen metni `UNCERTAIN`'e düşürür — modelin serbest
 * metnini okurken doğru (en kötü ihtimalle "kararsız" der). Diskte saklanmış
 * veriyi okurken **yanlış**: bozulmuş bir hücreyi "kararsız oy verdi" diye
 * göstermek, olmayan bir oy uydurmaktır. Bu yüzden burada eşleşmeyen değer
 * sessizce düşer; üye o iddiada oy vermemiş sayılır.
 */
object ClaimMatrixCodec {

    /** Encode for storage. Empty matrix still encodes (an empty table is a fact). */
    fun encode(matrix: ClaimMatrix): String {
        val claims = JSONArray()
        matrix.claims.forEach { c ->
            claims.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("text", c.text)
                    put(
                        "stances",
                        JSONObject().apply {
                            c.stances.forEach { (member, stance) -> put(member, stance.wire) }
                        },
                    )
                    if (c.evidence.isNotEmpty()) put("evidence", JSONArray(c.evidence))
                    if (!c.falsifiable) put("falsifiable", false)
                    if (c.needsTask) put("needsTask", true)
                },
            )
        }
        return JSONObject().apply {
            put("v", 1)
            put("claims", claims)
            if (matrix.dropped.isNotEmpty()) put("dropped", JSONArray(matrix.dropped))
        }.toString()
    }

    /**
     * Decode what [encode] wrote.
     *
     * Returns `null` for: an absent field (a run recorded before this version),
     * a blank string, or anything that does not parse. The caller must render
     * "this run has no matrix" rather than an empty table — the two mean
     * different things and conflating them would hide data loss.
     */
    fun decode(raw: String?): ClaimMatrix? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val arr = o.optJSONArray("claims") ?: return null
            val claims = ArrayList<Claim>(arr.length())
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val id = c.optString("id").takeIf { it.isNotBlank() } ?: continue
                val stances = LinkedHashMap<String, Stance>()
                c.optJSONObject("stances")?.let { s ->
                    s.keys().forEach { member ->
                        val wire = s.optString(member)
                        val stance = Stance.entries.firstOrNull {
                            it.wire.equals(wire.trim(), ignoreCase = true)
                        }
                        // Bilinmeyen değer ATILIR — bkz. sınıf KDoc'u.
                        if (stance != null && member.isNotBlank()) stances[member] = stance
                    }
                }
                claims += Claim(
                    id = id,
                    text = c.optString("text"),
                    stances = stances,
                    evidence = c.optJSONArray("evidence")?.let { e ->
                        (0 until e.length()).map { e.optString(it) }.filter { it.isNotBlank() }
                    } ?: emptyList(),
                    falsifiable = c.optBoolean("falsifiable", true),
                    needsTask = c.optBoolean("needsTask", false),
                )
            }
            ClaimMatrix(
                claims = claims,
                dropped = o.optJSONArray("dropped")?.let { d ->
                    (0 until d.length()).map { d.optString(it) }.filter { it.isNotBlank() }
                } ?: emptyList(),
            )
        }.getOrNull()
    }
}
