package com.openminis.app.screen

/**
 * [37099] Sanal ekran — **saf çekirdek**.
 *
 * ## Neden ayrı paket ve neden `android.*` yok
 *
 * Bu dosya yalnız durum makinesi: hangi bölme var, hangisi önde, hangi sırayla
 * çizilir, yakalama ne zaman gerekli, durum nasıl kaydedilir. Android'e dokunan
 * kısım [VirtualScreenHost] içinde. Ayrım tesadüf değil: bu cihazda JVM
 * başlamadığı için (`Failed to mark memory page as executable`) tek
 * doğrulayabildiğimiz şey saf fonksiyonlar. Ekranın kuralları burada olursa
 * test edilebilir; Compose içinde olsaydı hiçbir kapı görmezdi.
 *
 * ## Ne demek "sanal ekran"
 *
 * Ajanın **kendi** çalışma yüzeyi: tarayıcı, terminal ve notlar. Ekranda
 * görünmez, GPU ile çizilir (bkz. `browser/HeadlessRenderHost`), kullanıcının
 * gerçek ekranına dokunmaz.
 *
 * ## Neden B (gerçek ekran) ile karıştırılmamalı
 *
 * `docs/ozel/SANAL-EKRAN.md`: A = bu yüzey (kendi pencerelerimiz), B = gerçek
 * cihazda gezinme (erişilebilirlik). İkisinin **eylem izni ayrı** olmalı:
 * ajanın kendi tarayıcısında tıklaması, kullanıcının tarayıcısında tıklamasıyla
 * aynı riski taşımaz. [Action] ve [Target] bu ayrımı taşır.
 */
object VirtualScreen {

    /** Yüzeyde olabilecek bölmeler. Sıra çizim sırasıdır (önce gelen solda/üstte). */
    enum class Pane(val id: String) {
        BROWSER("browser"),
        TERMINAL("terminal"),
        NOTES("notes"),
        ;

        companion object {
            fun parse(raw: String?): Pane? = entries.firstOrNull { it.id == raw?.trim() }
        }
    }

    /** Eylemin nereye gittiği — izin seviyesini bu belirler. */
    enum class Target {
        /** Sanal ekran: yalnız bizim pencerelerimiz. Kullanıcı ekranı değişmez. */
        VIRTUAL,

        /** Gerçek cihaz ekranı: başka uygulamalar, kullanıcının gördüğü yer. */
        DEVICE,
    }

    /** Sanal ekranda yapılabilen eylemler. */
    enum class Action(val id: String) {
        OPEN("open"),
        NAVIGATE("navigate"),
        CLICK("click"),
        SCROLL("scroll"),
        TYPE("type"),
        READ("read"),
        CAPTURE("capture"),
        /**
         * [37124] Bölmeyi öne al. `VirtualScreenHost.focus()` `.34`'ten beri
         * vardı ama eylemi yoktu — yani ajan bölme açabiliyor, öne alamıyordu.
         * Derleme hatası olarak ortaya çıktı (ilk gerçek derleme turu).
         */
        FOCUS("focus"),
        /**
         * [37124] Bölmeyi gizle. `VirtualScreenHost.hide()` de vardı, eylemi
         * yoktu. Durum korunur: gizlemek silmek değil.
         */
        HIDE("hide"),
        ;

        companion object {
            fun parse(raw: String?): Action? = entries.firstOrNull { it.id == raw?.trim() }
        }
    }

    /**
     * Bir bölmenin durumu.
     *
     * [scrollY] ve [url] gibi alanlar **durum kaydı**nın kendisi: kapanıp
     * açıldığında ajan kaldığı yerden devam etmeli. Bu alanlar olmadan "ekran"
     * değil "tek kare" olur (bkz. SANAL-EKRAN.md §4).
     */
    data class PaneState(
        val pane: Pane,
        /** Görünür mü — kapalı bölme çizilmez ve durumu korunur. */
        val visible: Boolean = true,
        /** Tarayıcı: son adres. Terminal: çalışma dizini. Notlar: son not kimliği. */
        val location: String? = null,
        /** Kaydırma konumu (px). Yeniden açıldığında geri yüklenir. */
        val scrollY: Int = 0,
        /** Bölmeye ait serbest metin — notların taslağı, terminalin son komutu. */
        val draft: String = "",
    )

    /** Ekranın tamamı — kaydedilebilir, karşılaştırılabilir. */
    data class State(
        val panes: List<PaneState> = defaultPanes(),
        /** Önde olan bölme. null: hiçbiri (hepsi kapalı). */
        val focus: Pane? = Pane.BROWSER,
        /** Son yakalama zamanı (ms, monoton). 0 = hiç yakalanmadı. */
        val lastCaptureAt: Long = 0L,
        /**
         * [37099i · §5.3] **Bölme başına** son yakalama.
         *
         * Belge "aynı bölme 250 ms'de bir yakalanmaz" diyordu, kod yalnız
         * global bir damga tutuyordu. Fark gerçek: üç bölme açıkken herhangi
         * biri yakalanınca diğer ikisi de bütçesini harcamış sayılıyordu —
         * yani hızlı dönen bir sekme, iki durgun sekmeyi aç bırakıyordu.
         */
        val lastCaptureByPane: Map<Pane, Long> = emptyMap(),
    ) {
        fun pane(p: Pane): PaneState? = panes.firstOrNull { it.pane == p }
        fun visiblePanes(): List<PaneState> = panes.filter { it.visible }
    }

    fun defaultPanes(): List<PaneState> = Pane.entries.map { PaneState(it) }

    /**
     * Boş ekran — **hiçbir bölme yok**.
     *
     * `State()` ile karıştırılmamalı: varsayılan üç bölme açıktır, bu ise
     * kullanıcının "hepsini kapat" hâli. Ayrım önemli çünkü ilk açılışta
     * üç panelin de çizilmesi 3 yakalama demek; boş ekran hiç demek.
     */
    fun empty(): State = State(panes = defaultPanes().map { it.copy(visible = false) }, focus = null)

    // ── düzenleme ──────────────────────────────────────────────────────────

    fun show(s: State, p: Pane): State =
        s.copy(
            panes = s.panes.map { if (it.pane == p) it.copy(visible = true) else it },
            focus = p,
        )

    fun hide(s: State, p: Pane): State {
        val panes = s.panes.map { if (it.pane == p) it.copy(visible = false) else it }
        val focus = if (s.focus == p) panes.lastOrNull { it.visible }?.pane else s.focus
        return s.copy(panes = panes, focus = focus)
    }

    fun focus(s: State, p: Pane): State =
        if (s.pane(p)?.visible == true) s.copy(focus = p) else s

    /**
     * Bölmeyi yeniden sırala.
     *
     * `State.panes` sırası **çizim sırasıdır**; kullanıcı terminali sola
     * almak isterse bu tek çağrıdır. Bilinmeyen bölme adı yok sayılır (kayıtlı
     * durum eski bir sürümden gelmiş olabilir).
     */
    fun reorder(s: State, order: List<Pane>): State {
        val known = order.filter { p -> s.panes.any { it.pane == p } }.distinct()
        val rest = s.panes.map { it.pane }.filter { it !in known }
        val byPane = s.panes.associateBy { it.pane }
        return s.copy(panes = (known + rest).mapNotNull { byPane[it] })
    }

    fun update(s: State, p: Pane, f: (PaneState) -> PaneState): State =
        s.copy(panes = s.panes.map { if (it.pane == p) f(it) else it })

    // ── yakalama bütçesi ───────────────────────────────────────────────────

    /**
     * Yakalama gerekli mi?
     *
     * Kural: yalnız **görünür** bölmeler yakalanır ve aynı bölme çok sık
     * yakalanmaz. Sebep ölçülmüş: her yakalama bir `ImageReader` karesi ve
     * GPU işi; saniyede bir yakalamak pili yakar, hiç yakalamamak ajanı kör
     * bırakır.
     */
    /**
     * [37099i] Yakalanması gereken bölmeler — bütçe **bölme başına**.
     *
     * ## Durum: kural hazır, çağıran yok
     *
     * Bu fonksiyonun bugün **üretimde çağıranı yok**: sanal ekranın yakalama
     * hattı (§5.1 tek tuval) henüz yazılmadı. Kural, o hat geldiğinde doğru
     * davranması için burada ve testleri geçiyor. `scripts/ozel/olu-kod.py`
     * bunu listeler — "kapı yeşil" demek "bağlı" demek değil.
     *
     * Global damga korunuyor: `lastCaptureAt == 0` ise hiç yakalama yapılmadı
     * demektir ve ilk turda bütün görünür bölmeler yakalanır (soğuk açılış).
     * Sonrasında her bölme kendi 250 ms bütçesini taşır.
     */
    fun needsCapture(s: State, now: Long, minGapMs: Long = 250L): List<Pane> {
        if (s.visiblePanes().isEmpty()) return emptyList()
        return s.visiblePanes().map { it.pane }.filter { pane ->
            val last = s.lastCaptureByPane[pane] ?: 0L
            last == 0L || now - last >= minGapMs
        }
    }

    fun markCaptured(s: State, now: Long, pane: Pane? = null): State {
        val perPane = if (pane != null) s.lastCaptureByPane + (pane to now) else s.lastCaptureByPane
        return s.copy(lastCaptureAt = now, lastCaptureByPane = perPane)
    }


    // ── A / B eylem ayrımı ─────────────────────────────────────────────────

    /**
     * Bir eylemin risk sınıfı — **sanal ekranda ucuz, gerçek ekranda pahalı**.
     *
     * Bu fonksiyonun tek işi, kural tablosundaki tek bir boşluğu kapatmak:
     * `android-a11y-cli` ile gerçek ekranda tıklamak (B) ve kendi tarayıcımızda
     * tıklamak (A) **aynı komut metnine** düşüyordu. Arada fark:
     *
     *   - A'da hiçbir şey kullanıcının gördüğü ekranda değişmez, geri alınacak
     *     bir şey de yoktur (yalnız bizim sekme durumu).
     *   - B'de kullanıcının ekranı değişir; yanlış dokunuş başka bir
     *     uygulamada geri alınamaz bir işlem yapabilir (silme, satın alma,
     *     gönderme).
     *
     * Bu yüzden `LEVEL_VIRTUAL < LEVEL_DEVICE`: sanal ekranda `TYPE`/`CLICK`
     * sessiz kalabilir, gerçek ekranda karta düşer.
     */
    enum class Cost(val level: Int) { SILENT(0), ASK(1), CONFIRM(2) }

    val LEVEL_VIRTUAL = Cost.SILENT
    val LEVEL_DEVICE = Cost.ASK

    /** B'de her zaman onay; A'da yalnız yazma/gönderme eylemleri sorar. */
    fun cost(action: Action, target: Target): Cost = when (target) {
        Target.VIRTUAL -> when (action) {
            // Kendi yüzeyimizde okuma/gezinme/yakalama bedelsiz.
            Action.READ, Action.CAPTURE, Action.SCROLL, Action.NAVIGATE, Action.OPEN -> Cost.SILENT
            // Kendi sekme durumumuzu değiştirmek geri alınabilir: kart yok.
            Action.CLICK, Action.TYPE -> Cost.SILENT
            // [37124] Odak/gizle: bölme düzeni, veri değil. Sessiz.
            Action.FOCUS, Action.HIDE -> Cost.SILENT
        }
        Target.DEVICE -> when (action) {
            Action.READ, Action.CAPTURE -> Cost.SILENT      // okumak ekranı değiştirmez
            Action.SCROLL -> Cost.ASK                        // kaydırma görünümü kaybettirir
            Action.OPEN, Action.NAVIGATE -> Cost.ASK         // kullanıcının yerini değiştirir
            Action.CLICK -> Cost.CONFIRM                     // geri alınamaz işlem başlatabilir
            Action.TYPE -> Cost.CONFIRM                      // gönderme/silme olabilir
            // [37124] Gerçek ekranda odak/gizle yok — bu araç B'yi sürmez.
            // Yine de tablo tam olmalı: eksik dal, sessizce "geçti" demek olurdu.
            Action.FOCUS, Action.HIDE -> Cost.ASK
        }
    }

    // ── durum kaydı ────────────────────────────────────────────────────────

    /**
     * Durumu tek satıra yazar: `browser|1|https://x|120|draft~terminal|1|/root|0|~notes|0||0|~~browser~~browser,terminal,notes`
     *
     * [37099b] Son alan **çizim sırası**: kullanıcı bölmeleri yeniden
     * sıralayabildiği hâlde `decode` sonunda enum sırasına geri eziyordu
     * (ölçüm: bellekte `terminal,browser,notes` → diskten `browser,terminal,notes`).
     * Sıra kayıtta yoksa eski biçim kabul edilir ve enum sırası kullanılır —
     * göç istemez.
     *
     * Neden JSON değil: bu kayıt her ekran açılışında okunur ve biçim insan
     * tarafından `rg` ile okunabilmeli. JSON daha doğru olurdu ama iki satırlık
     * bir durum için 400 baytlık anahtar tekrarı üretirdi. Alanlar `|` ile,
     * bölmeler `~` ile ayrılır; metin alanlarındaki `|`/`~` kaçırılır.
     */
    fun encode(s: State): String = s.panes.joinToString("~") { p ->
        listOf(
            p.pane.id,
            if (p.visible) "1" else "0",
            esc(p.location.orEmpty()),
            p.scrollY.toString(),
            esc(p.draft),
        ).joinToString("|")
    } + "~~" + (s.focus?.id ?: "-") + "~~" + s.panes.joinToString(",") { it.pane.id }

    fun decode(raw: String): State {
        if (raw.isBlank()) return State()
        val parts = raw.split("~~")
        val paneText = parts.firstOrNull().orEmpty()
        val focus = Pane.parse(parts.getOrNull(1))
        val savedOrder = parts.getOrNull(2)
            ?.split(",")
            ?.mapNotNull { Pane.parse(it) }
            ?.takeIf { it.isNotEmpty() }
        val panes = paneText.split("~").mapNotNull { line ->
            val f = line.split("|")
            val pane = Pane.parse(f.getOrNull(0)) ?: return@mapNotNull null
            PaneState(
                pane = pane,
                visible = f.getOrNull(1) == "1",
                location = unesc(f.getOrNull(2)).ifEmpty { null },
                scrollY = f.getOrNull(3)?.toIntOrNull() ?: 0,
                draft = unesc(f.getOrNull(4)),
            )
        }
        if (panes.isEmpty()) return State()
        // Kayıtta olmayan yeni bölmeler (eski sürümden gelen durum) kapalı eklenir.
        val known = panes.map { it.pane }.toSet()
        val merged = panes + defaultPanes().filter { it.pane !in known }.map { it.copy(visible = false) }
        // [37099b] Kayıtlı çizim sırası varsa o; yoksa eski kayıt: enum sırası.
        val order = savedOrder?.plus(Pane.entries.filter { it !in savedOrder }) ?: Pane.entries
        return State(
            panes = merged.sortedBy { order.indexOf(it.pane) },
            focus = focus?.takeIf { p -> merged.any { it.pane == p && it.visible } },
        )
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\p").replace("~", "\\t").replace("\n", "\\n")

    private fun unesc(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'p' -> out.append('|')
                    't' -> out.append('~')
                    'n' -> out.append('\n')
                    '\\' -> out.append('\\')
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
