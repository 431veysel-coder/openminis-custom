package com.openminis.app.policy

/**
 * [OZEL 37080] Bekleyen onayların kuyruğu ve **toplu onay sınırı**.
 *
 * ## Kullanıcının şartı
 *
 * *"olabildiğince de toplu onay istemeye dikkat edelim"*. İki farklı okuma var
 * ve ikisi de doğru:
 *
 * 1. **Az sor.** Ardışık onaylar tek tek kart olarak açılırsa kullanıcı 20
 *    kartı okumadan geçer; okunmayan onay onay değildir.
 * 2. **Ama birleştirme de yanlış.** Farklı üç işi tek "hepsine izin ver"
 *    düğmesine koymak, kullanıcının üç ayrı kararı tek tıkla vermesi demek.
 *
 * Çözüm: **aynı soru birleşir, farklı sorular birleşmez.** Grup anahtarı
 * `kural + geri dönüş + kapsam`; hedef de aynıysa tek kart olur ("bu dizinde
 * 3 dosya silinecek"). Hedefler farklıysa ayrı kart kalır — çünkü biri
 * `/var/minis/shared/proje` diğeri `/var/minis/shared/arsiv` olabilir ve
 * kullanıcının bunlara farklı cevap verme hakkı vardır.
 *
 * ## Sıralama
 *
 * En tehlikeli en üstte. Kullanıcı kartı yarıda bıraksa bile önce en ağır
 * kararı görsün; sıralama tersine olsa, ilk üç zararsız kartı geçtikten sonra
 * dikkat tükenir ve asıl soru okunmadan cevaplanır.
 */
class ApprovalQueue {

    private val pending = LinkedHashMap<String, ApprovalRequest>()

    fun add(request: ApprovalRequest) {
        pending[request.id] = request
    }

    fun addAll(requests: List<ApprovalRequest>) = requests.forEach(::add)

    fun remove(id: String): ApprovalRequest? = pending.remove(id)

    fun clear() = pending.clear()

    fun size(): Int = pending.size

    fun isEmpty(): Boolean = pending.isEmpty()

    fun all(): List<ApprovalRequest> = pending.values.toList()

    /**
     * Bir kart. [items] her zaman tek tek gösterilir; [bulkAllowed] yalnız
     * "aynı kural + aynı hedef" durumunda ve 3'ten fazla olmamak kaydıyla açılır.
     */
    data class Card(
        val key: String,
        val headline: String,
        val items: List<ApprovalRequest>,
        val bulkAllowed: Boolean,
    ) {
        val severity: Int
            get() = items.maxOf { it.verdict.level.ordinal * 10 + it.verdict.recovery.ordinal }
    }

    fun cards(): List<Card> = pending.values
        .groupBy { groupKey(it) }
        .map { (key, items) ->
            val first = items.first()
            Card(
                key = key,
                headline = headline(items),
                items = items,
                bulkAllowed = items.size in 2..3 && items.map { it.verdict.targetPrefix }.distinct().size == 1,
            )
        }
        .sortedByDescending { it.severity }

    private fun groupKey(r: ApprovalRequest): String =
        listOf(r.verdict.ruleId, r.verdict.recovery.name, r.verdict.reach.name, r.verdict.targetPrefix)
            .joinToString("|")

    private fun headline(items: List<ApprovalRequest>): String {
        val one = items.first()
        if (items.size == 1) return one.title
        val target = one.verdict.targetPrefix.ifEmpty { "aynı hedef" }
        return "${one.title} — $target altında ${items.size} işlem"
    }
}
