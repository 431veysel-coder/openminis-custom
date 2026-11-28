package com.openminis.app.ui.team

/**
 * [37131 · Ofis] **Odaların sözleşmesi** — her odanın tek bir sorusu var.
 *
 * ## Neden bu dosya var
 *
 * Altı oda yalnız **sekme etiketi**ydi. Amacı, girdisi ve *ne söylemediği*
 * yazılı olmadığı için iki hata tekrar etti:
 *
 * 1. **Boş oda** (`.22`): "İletişim" odası açıldı, gösterilecek verisi yoktu,
 *    kullanıcı "çalışmıyor" dedi. Veri `Position.questions` içinde duruyordu ama
 *    hiçbir yere yazılmıyordu.
 * 2. **Yanlış beklenti** (`.46`): "Karşılaştırma" odası karar göstermiyor,
 *    yalnız duruş. `verdict` boş kalınca kullanıcı tablodan karar bekledi.
 *
 * İkisinin de kökü aynı: oda hakkında **yazılı bir iddia yok**.
 *
 * ## `claimsNot` neden var
 *
 * Bir odanın ne yapmadığını yazmak, ne yaptığını yazmaktan daha çok iş
 * çıkarıyor. Bu yüzden ayrı alan: `verdict` boşluğu bir hata değil, sözleşme.
 */
object RoomContract {

    enum class Id(val wire: String) {
        FLOOR("floor"),
        TASKS("tasks"),
        RESULT("result"),
        COMPARE("compare"),
        RELATIONS("relations"),
    }

    /**
     * One room.
     *
     * @param question dize anahtarı — **dil bilmez**. Çeviri `strings.xml`'de;
     *   bu depoda saf çekirdeğin dil bilmemesi kuralı (`.28`'de ihlal edilmişti).
     * @param feeds bu odayı besleyen `CouncilEvent` sınıf adları. Boş = koşu
     *   kaydından okunur (diskten).
     * @param claimsNot bu odanın **söylemediği** şey — yanlış beklentiyi önler.
     */
    data class Room(
        val id: Id,
        val question: String,
        val feeds: List<String>,
        val claimsNot: String,
        /** Kayıttan (diskten) da çizilebiliyor mu? */
        val fromRecord: Boolean,
    )

    val ROOMS: List<Room> = listOf(
        Room(
            id = Id.FLOOR,
            question = "council_q_floor",
            feeds = listOf(
                "CouncilEvent.Started",
                "CouncilEvent.RoundStarted",
                "CouncilEvent.Positioned",
                // [F1] Tur gerekçesi **Kat**'ın sorusuna aittir: "şu an ne
                // oluyor, neden bitmiyor". Tüketicisi `RoundReasonLine`
                // (Kat odasının başı + ofis şeridi) — aynı composable, iki
                // yüzey, tek cümle.
                "CouncilEvent.RoundAdvanced",
            ),
            claimsNot = "council_not_floor",
            fromRecord = false,
        ),
        Room(
            id = Id.TASKS,
            question = "council_q_tasks",
            feeds = listOf(),               // panodan, olaydan değil
            claimsNot = "council_not_tasks",
            fromRecord = true,
        ),
        Room(
            id = Id.RESULT,
            question = "council_q_result",
            feeds = listOf(
                "CouncilEvent.Synthesized",
                "CouncilEvent.Audited",
                "CouncilEvent.Finished",
            ),
            claimsNot = "council_not_result",
            fromRecord = true,
        ),
        Room(
            id = Id.COMPARE,
            question = "council_q_compare",
            feeds = listOf(
                "CouncilEvent.Canonicalized",
                "CouncilEvent.ConsensusReached",
                "CouncilEvent.StableRound",
            ),
            claimsNot = "council_not_compare",
            fromRecord = true,
        ),
        Room(
            id = Id.RELATIONS,
            question = "council_q_relations",
            feeds = listOf("CouncilEvent.Positioned"),
            claimsNot = "council_not_relations",
            fromRecord = false,
        ),
    )

    /** Every event class the loop can emit. Kept here so a new one cannot be forgotten. */
    val EVENT_TYPES: List<String> = listOf(
        "CouncilEvent.Started",
        "CouncilEvent.RoundStarted",
        "CouncilEvent.Positioned",
        "CouncilEvent.Canonicalized",
        "CouncilEvent.ConsensusReached",
        "CouncilEvent.StableRound",
        "CouncilEvent.RoundAdvanced",
        "CouncilEvent.Synthesized",
        "CouncilEvent.Audited",
        "CouncilEvent.Finished",
    )

    /**
     * Events no room consumes. Empty for a healthy build; a non-empty list means
     * data is produced and thrown away — the `.22` bug in one line.
     */
    fun orphans(): List<String> {
        val consumed = ROOMS.flatMap { it.feeds }.toSet()
        return EVENT_TYPES.filter { it !in consumed }
    }

    /** Shortest line that makes the contract reviewable in a PR. */
    fun summary(): String = ROOMS.joinToString("\n") { r ->
        "  ${r.id.wire}: feeds=${r.feeds.size} record=${r.fromRecord} not=${r.claimsNot}"
    }
}
