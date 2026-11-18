package com.openminis.app.agent.router

/**
 * [OZEL 37085] Araç kapısı — 40+ aracın hepsini birden sunmamak.
 *
 * ## Sorun ölçülebilir
 *
 * Bugün her turda bütün araç şemaları gönderiliyor. İki maliyeti var:
 * şema başına ~200-600 token (küçük modellerde bağlamın üçte biri) ve
 * **seçim gürültüsü** — modelin önünde 40 seçenek varken yanlış aracı
 * seçme olasılığı artıyor. İkisi de her turda ödeniyor.
 *
 * ## Çözüm: niyetten aday, LLM'siz
 *
 * İki aşama. Birinci aşama saf sözlük: niyet cümlesindeki kelimeler → araç
 * grupları. İkinci aşama: yalnız adayların şeması gönderilir; model geri
 * kalanı gerekiyorsa `tool_search` ile açar. Birinci aşamada model
 * kullanılmıyor çünkü bir LLM çağrısıyla araç seçmek, tasarruf ettiği
 * token'dan fazlasını harcar.
 *
 * ## Neden kaçırma yerine fazla getirme
 *
 * Eşik tek yönlü: şüphe varsa araç **listeye girer**. 10 araç yerine 12 araç
 * göndermek zararsız; eksik araç göndermek modelin "yapamıyorum" demesine yol
 * açar. Bu yüzden skor eşiği gevşek ([MIN_SCORE] = 1) ve her zaman açık bir
 * çekirdek liste var.
 */
data class ToolHint(
    val name: String,
    /** Türkçe ve İngilizce anahtar kelimeler; küçük harf, aksansız. */
    val keywords: List<String>,
)

object ToolRouter {

    /** Her durumda sunulan çekirdek: yanlış seçim yaptığında geri dönebilsin. */
    val ALWAYS: List<String> = listOf("user_ask", "verify", "journal", "file_read")

    /** Aday listesi boş kalırsa düşülen taban. */
    val FALLBACK: List<String> = ALWAYS + listOf("shell_execute", "file_write", "file_edit", "subagent_task")

    private const val MIN_SCORE = 1

    val HINTS: List<ToolHint> = listOf(
        ToolHint("shell_execute", listOf("komut", "calistir", "terminal", "kabuk", "shell", "cli", "script", "kur", "install", "derle", "build", "test", "git")),
        ToolHint("browser_use", listOf("tarayici", "browser", "site", "web", "sayfa", "url", "tikla", "click", "form", "ara", "search", "indir", "download")),
        ToolHint("file_read", listOf("oku", "read", "dosya", "file", "icerik", "cat", "bak")),
        ToolHint("file_write", listOf("yaz", "write", "olustur", "create", "dosya", "kaydet", "save")),
        ToolHint("file_edit", listOf("duzenle", "edit", "degistir", "replace", "yamala", "patch")),
        ToolHint("read_image", listOf("resim", "image", "fotograf", "photo", "gorsel", "screenshot", "ekran goruntusu", "grafik", "chart")),
        ToolHint("subagent_task", listOf("alt ajan", "subagent", "delege", "delegate", "paralel", "arka plan", "background", "uzun surer")),
        ToolHint("team_run", listOf("ekip", "team", "konsey", "council", "tartis", "birden cok model", "kurul")),
        ToolHint("memory_write", listOf("hatirla", "remember", "hafiza", "memory", "not al", "kaydet")),
        ToolHint("memory_get", listOf("hatirla", "recall", "gecmis", "hafiza", "memory", "ne biliyorsun")),
        ToolHint("note", listOf("not", "note", "defter", "kasa", "vault")),
        ToolHint("verify", listOf("dogrula", "verify", "kontrol", "test", "kanit", "check")),
        ToolHint("journal", listOf("geri al", "undo", "journal", "ne degisti", "degisiklik")),
        ToolHint("user_ask", listOf("sor", "ask", "onay", "karar", "secim", "belirsiz")),
        ToolHint("android-speech", listOf("ses", "konus", "dikte", "transkript", "speech")),
        ToolHint("android-speak", listOf("oku", "sesli", "tts", "speak")),
        ToolHint("android-location", listOf("konum", "location", "gps", "harita", "map", "adres")),
        ToolHint("android-calendar", listOf("takvim", "calendar", "etkinlik", "randevu")),
        ToolHint("android-alarm", listOf("alarm", "hatirlatici", "reminder", "zamanla", "timer")),
        ToolHint("android-clipboard", listOf("pano", "clipboard", "kopyala", "yapistir")),
        ToolHint("android-notification", listOf("bildirim", "notification", "uyar")),
        ToolHint("android-contacts", listOf("kisi", "contact", "telefon numarasi", "rehber")),
        ToolHint("android-photos", listOf("galeri", "fotograf", "photos", "album")),
        ToolHint("android-shizuku-cli", listOf("shizuku", "yetki", "izin ver", "pm ", "settings put", "cihaz ayari")),
        ToolHint("android-a11y-cli", listOf("ekran", "ui", "dokun", "tap", "uygulama ac", "arayuz")),
        ToolHint("minis-scheduled", listOf("zamanla", "schedule", "her gun", "otomatik", "cron")),
        ToolHint("minis-config", listOf("ayar", "settings", "config", "saglayici", "provider")),
        ToolHint("minis-model-use", listOf("model", "baska model", "llm", "karsilastir")),
        ToolHint("minis-sessions-cli", listOf("gecmis sohbet", "sessions", "eski konusma")),
        ToolHint("minis-open", listOf("ac", "open", "goster", "preview", "onizle")),
        ToolHint("pdf", listOf("pdf", "belge", "document", "word", "excel", "tablo")),
    )

    /**
     * Niyet cümlesinden aday araçlar.
     *
     * [limit] aşılırsa skora göre kesilir ama [ALWAYS] her zaman kalır —
     * kesme yüzünden çekirdeğin düşmesi, modelin elini kolunu bağlar.
     */
    fun candidates(intent: String, limit: Int = 10): List<String> {
        val text = fold(intent)
        if (text.isBlank()) return FALLBACK

        val scored = HINTS.mapNotNull { hint ->
            val score = hint.keywords.count { kw -> text.contains(fold(kw)) }
            if (score >= MIN_SCORE) hint.name to score else null
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) return FALLBACK

        val picked = LinkedHashSet<String>()
        scored.forEach { (name, _) -> if (picked.size < limit) picked += name }
        ALWAYS.forEach { picked += it }
        return picked.toList()
    }

    /** Modelin gerektiğinde geri kalanı açması için: tüm adlar, çekirdek hariç. */
    fun closedCatalog(): List<String> = HINTS.map { it.name }.filter { it !in ALWAYS }

    /** Türkçe aksanları sadeleştirir; "şifre" ile "sifre" eşleşsin. */
    private fun fold(s: String): String {
        val map = mapOf(
            'ç' to 'c', 'ğ' to 'g', 'ı' to 'i', 'ö' to 'o', 'ş' to 's', 'ü' to 'u',
            'Ç' to 'c', 'Ğ' to 'g', 'İ' to 'i', 'Ö' to 'o', 'Ş' to 's', 'Ü' to 'u',
        )
        val sb = StringBuilder(s.length)
        s.forEach { sb.append(map[it] ?: it) }
        return sb.toString().lowercase()
    }
}
