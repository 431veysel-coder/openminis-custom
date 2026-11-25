package com.openminis.app.agent.team

/**
 * [37123] **Katılım satırı** — "kaç üye cevap verdi, kim düştü".
 *
 * ## Neden var (ölçülen boşluk)
 *
 * `CouncilLoop` tur içinde `answered` sayısını biliyor (`StanceMath.evaluate`
 * için), ama bu sayı **rapora** hiç yazılmıyordu. Kullanıcı 5 üyeli bir ekipte
 * 2 cevap aldığında "konsey çalıştı" görüyor, 3 üyenin düştüğünü göremiyordu.
 * Bu, `.22`'deki `no_positions` sessizliğinin daha küçük bir kardeşi: koşu
 * boş dönmüyorsa sessiz kalıyor.
 *
 * ## Neden saf ve ayrı
 *
 * Aynı cümle iki yerde (rapor sonu + ekran başlığı) gerekiyor; iki yerde iki
 * farklı metin üretmek "ekran başka, rapor başka" derdine yol açar. Bu depoda
 * bu sınıf hata bir kez yaşandı (`.31` dil karışması).
 *
 * ## Neden dil karışmıyor
 *
 * Rapor metni **modele/kullanıcıya** Türkçe gidiyor; araç sonucu İngilizce.
 * Bu nesne Türkçe cümle üretiyor çünkü yalnız rapora yazılıyor — ve bunu
 * KDoc'a yazmak, altı ay sonra "neden burada Türkçe var" sorusunu önlüyor.
 */
object AttendanceLine {

    /**
     * @param total koşuya katılan üye sayısı (planlanan)
     * @param answered pozisyon üretebilen üye sayısı
     * @param absent fallen members: (name, why)
     */
    fun line(total: Int, answered: Int, absent: List<Pair<String, String>>): String? {
        if (total <= 0) return null
        if (answered == total && absent.isEmpty()) {
            // Hepsi katıldıysa satır yazılmaz: her koşuda "5/5 katıldı" yazmak,
            // gerçek bir eksilme olduğunda satırın fark edilmemesine yol açar.
            return null
        }
        val head = "$answered/$total üye cevap verdi"
        if (absent.isEmpty()) return head
        val why = absent.joinToString("; ") { (name, reason) ->
            // Gerekçe boşsa uydurma: adı yaz, nedeni "belirtilmedi" yap.
            if (reason.isBlank()) name else "$name — $reason"
        }
        return "$head · düşen: $why"
    }

    /**
     * Kuyruk satırı — "2 çalışıyor, 3 sırada".
     *
     * `AgentJobRegistry.queueSummaryOf` **modele** giden İngilizce cümleyi
     * üretiyor. Bu ayrı fonksiyon, çünkü aynı sayılar kullanıcıya Türkçe
     * gösterilecek; tek fonksiyonu iki dile bölmek yerine iki çağıranı olan iki
     * fonksiyon, her birinin hedef kitlesi tek.
     */
    fun queue(running: Int, pending: Int, queued: Int): String? {
        if (running + pending + queued <= 0) return null
        val parts = mutableListOf<String>()
        if (running > 0) parts += "$running çalışıyor"
        if (pending > 0) parts += "$pending başlıyor"
        if (queued > 0) parts += "$queued sırada"
        return parts.joinToString(", ")
    }

    /**
     * Ham durum sayılarından **kullanıcıya** gösterilecek üçlü.
     *
     * `AgentJobState`'te `QUEUED` yok: sınırı aşan işler `PENDING` kalıyor.
     * "Sırada" sayısını her çağrı yerinde `pending - limit` diye yazmak, iki
     * yerde iki farklı sonuç üretir (ve birinde `coerceAtLeast` unutulur →
     * negatif sayı). Burada tek kez hesaplanıyor.
     */
    data class QueueCounts(val running: Int, val starting: Int, val waiting: Int)

    fun split(running: Int, pending: Int, limit: Int): QueueCounts {
        val r = running.coerceAtLeast(0)
        val p = pending.coerceAtLeast(0)
        val free = (limit.coerceAtLeast(0) - r).coerceAtLeast(0)
        val starting = p.coerceAtMost(free)
        return QueueCounts(r, starting, p - starting)
    }

    /**
     * Eşzamanlı sınır — kullanıcı "neden biri beklemede" sorusunu cevaplasın.
     *
     * @param limit bu konuşmada aynı anda koşabilecek alt ajan sayısı
     */
    fun queueDetail(queued: Int, limit: Int): String? {
        if (queued <= 0 || limit <= 0) return null
        return "$queued iş sırada (aynı anda $limit tanesi koşabilir)"
    }
}
