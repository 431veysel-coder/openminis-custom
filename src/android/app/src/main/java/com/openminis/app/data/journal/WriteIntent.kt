package com.openminis.app.data.journal

/**
 * [OZEL 37088] Yazma niyeti (claim) + çakışma sicili.
 *
 * ## Ne var, ne yok
 *
 * `FileLock` **zaten var** ve iyi: yol başına kilit, okuyup-yazma arasında
 * parmak izi karşılaştırması, çakışma mesajı. Bu dosya onu tekrarlamıyor.
 *
 * Eksik olan şey zaman ölçeği. `withLock` tek bir yazma anını koruyor; ama bir
 * ajanın işi çok adımlı: oku → düzenle → derle → düzelt → tekrar yaz. Bu iki
 * dakika boyunca başka bir ajan (üye, alt ajan, istasyon paneli) aynı dosyaya
 * girebilir. Kilit her adımda doğru çalışır, sonuç yine bozuk olur: iki ajan
 * birbirinin düzeltmesini eziyor, ikisi de "başarılı" raporluyor.
 *
 * ## Çözüm: niyet, kilit değil
 *
 * [claim] bir kilidi tutmuyor — **beyan** ediyor: "şu dosyayla şu işi
 * yapıyorum". Kilit gibi bloklamıyor, çünkü çok adımlı işte kilidi tutmak
 * diğer ajanı dakikalarca durdurur ve zaman aşımına yol açar. Bunun yerine:
 *
 * 1. Aynı dosyaya niyet eden ikinci ajan **uyarılır**: "X şu an bu dosyada".
 * 2. Devam ederse yazma anında `FileLock` parmak izi kontrolü yakalar.
 * 3. Kullanıcı sicilde görür: hangi ajan, hangi dosya, ne zaman.
 *
 * ## Neden süreç belleği, neden disk değil
 *
 * Niyetler uygulama ömrü kadar yaşıyor. Diske yazılsaydı, uygulama öldüğünde
 * kalan niyet "sonsuza kadar meşgul" gibi görünürdü — bir kez çökme, kalıcı
 * kilit. Ölü süreç niyetini temizleyemez; bu yüzden yaşam süresi doğru ölçek.
 */
object WriteIntent {

    data class Claim(
        val sessionId: String,
        val path: String,
        val purpose: String,
        val startedAtMs: Long,
        val expiresAtMs: Long,
    ) {
        fun live(nowMs: Long): Boolean = nowMs < expiresAtMs
    }

    /** Aynı dosyaya bakan iki niyet. Kullanıcıya gösterilen sicil satırı. */
    data class Conflict(
        val path: String,
        val first: Claim,
        val second: Claim,
        val atMs: Long,
    ) {
        fun text(): String = buildString {
            append("${short(path)}: ")
            append("${first.sessionId.take(8)} (${first.purpose.take(40)}) ile ")
            append("${second.sessionId.take(8)} (${second.purpose.take(40)}) çakıştı")
        }

        private fun short(p: String): String =
            if (p.contains("/var/minis/")) p.substringAfter("/var/minis/") else p
    }

    /** Niyet ömrü: tek bir dosya üzerinde çok adımlı iş için cömert, sonsuz değil. */
    const val TTL_MS: Long = 10 * 60 * 1000

    private const val MAX_CONFLICTS = 200

    private val claims = LinkedHashMap<String, MutableList<Claim>>()
    private val conflicts = ArrayDeque<Conflict>()

    /**
     * Niyet beyan eder ve **çakışma varsa döner**. Bloklamaz: ikinci ajan da
     * devam eder, ama uyarıyı görür. Kararı ajan verir (dosyayı bırak, bekle,
     * ya da kullanıcıya sor) — kilit gibi zorla bekletmek döngü üretir.
     */
    fun claim(
        sessionId: String,
        path: String,
        purpose: String,
        nowMs: Long,
        ttlMs: Long = TTL_MS,
    ): List<Conflict> {
        val key = FileLock.normalise(path)
        val list = claims.getOrPut(key) { mutableListOf() }
        list.removeAll { !it.live(nowMs) }

        val mine = Claim(sessionId, key, purpose, nowMs, nowMs + ttlMs)
        val clashes = list
            .filter { it.sessionId != sessionId }
            .map { other -> Conflict(key, other, mine, nowMs) }

        list += mine
        clashes.forEach(::record)
        return clashes
    }

    /** İş bitti — niyet kalkar. Çağrılmazsa TTL temizler. */
    fun release(sessionId: String, path: String) {
        val key = FileLock.normalise(path)
        claims[key]?.removeAll { it.sessionId == sessionId }
        if (claims[key]?.isEmpty() == true) claims.remove(key)
    }

    fun releaseAll(sessionId: String) {
        claims.values.forEach { it.removeAll { c -> c.sessionId == sessionId } }
        claims.entries.removeAll { it.value.isEmpty() }
    }

    /** Ajanın göreceği metin: "bu dosyada başkası var, dikkat". */
    fun warningFor(conflicts: List<Conflict>, nowMs: Long): String = when {
        conflicts.isEmpty() -> ""
        conflicts.size == 1 -> {
            val c = conflicts.single()
            "Uyarı: ${c.first.path.let { if (it.contains("/var/minis/")) it.substringAfter("/var/minis/") else it }} " +
                "dosyasında başka bir ajan çalışıyor (${c.first.purpose.take(60)}). " +
                "Yazmadan önce içeriği yeniden okuyun; parmak izi değişmişse yazmayın."
        }
        else -> "Uyarı: ${conflicts.size} dosyada başka ajanlar çalışıyor. Yazmadan önce hepsini yeniden okuyun."
    }

    fun conflicts(): List<Conflict> = conflicts.toList()

    fun clearConflicts() = conflicts.clear()

    fun activeClaims(nowMs: Long): List<Claim> =
        claims.values.flatten().filter { it.live(nowMs) }

    private fun record(c: Conflict) {
        conflicts.addLast(c)
        while (conflicts.size > MAX_CONFLICTS) conflicts.removeFirst()
    }
}
