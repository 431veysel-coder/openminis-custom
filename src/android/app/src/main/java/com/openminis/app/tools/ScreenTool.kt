package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.OzelFeatures
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.screen.ScreenRequest
import com.openminis.app.screen.VirtualScreen
import com.openminis.app.screen.VirtualScreenHost
import org.json.JSONObject

/**
 * [37118] `screen` — ajanın **kendi** ekranındaki bölmeleri sürmesi.
 *
 * ## Neden var
 *
 * `VirtualScreenHost.Op` `.34`'te yazıldı ve **hiçbir yerden çağrılmadı**
 * (ölçüm: 0). Yani "sanal ekran" bir durum kaydıydı, bir yüzey değil; ajan
 * bölme açamıyor, kaydıramıyor, yakalayamıyordu. Bu dosya o API'nin tek
 * çağıranı.
 *
 * ## A / B ayrımı neden burada da yazılı
 *
 * `VirtualScreen.cost(action, target)` tablosu zaten var ve tek gerçek:
 * **A (VIRTUAL)** yalnız bizim bölmelerimiz → `SILENT`; **B (DEVICE)**
 * kullanıcının telefonu → `click`/`type` `CONFIRM`.
 *
 * Bu araç **B'yi kendisi yapmaz**: gerçek ekran `android-a11y-cli` işidir ve
 * orada ayrı bir kapı var. İkisini tek araçta birleştirmek A/B ayrımını tek
 * yazım hatasıyla kaybettirirdi; ayrıca `a11y_cli` kendi onay defterine
 * (`OffloadPermissionManager`) yazıyor, buradaki defter (grant book) başka.
 *
 * ## Neden `capture` bir kanıt eylemi
 *
 * "Ekranı gördüm" demek için kare gerekir. Kare üretilemezse araç
 * `success=false` döner ve modele "anlatma" der. Uydurulmuş bir "gördüm"
 * cevabı hiç cevap vermemekten kötüdür (`.29` dersi: kural doğru, kanıt yok).
 *
 * ## Neden `click` dürüst
 *
 * Bölmenin içindeki DOM/PTY'ye ulaşan bir tıklama hattı **yok** (§5.1 tek
 * tuval `.38`'de). Araç bunu saklamaz: "click recorded" der, "tıklandı"
 * demez. Saklamak, modelin bir sonraki turda yanlış varsayım kurmasına ve
 * kullanıcıya olmayan bir işi raporlamasına yol açardı.
 */
object ScreenTool {

    const val NAME = "screen"

    /** Kare üreticisi — `browser/HeadlessRenderHost` bağlar; testte sahte. */
    fun interface FrameSource {
        /** Bölmenin o anki karesi. `null` = üretilemedi (çizim yok / bölme kapalı). */
        fun frame(pane: VirtualScreen.Pane): ByteArray?
    }

    /** Kayıtlı kare kaynağı; `MinisApp` açar. */
    @Volatile
    var frameSource: FrameSource? = null

    fun enabled(ctx: Context): Boolean =
        OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.VIRTUAL_SCREEN)

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Drive your OWN surface inside the app: browser, terminal and notes panes. " +
            "This is not the user's screen — it is a workspace the app owns, so opens and scrolls " +
            "here change nothing the user sees. " +
            "Actions: " +
            "state (what is visible, which pane has focus, where each pane is), " +
            "open (show a pane and focus it; pass `location` — a URL for browser, a working " +
            "directory for terminal, a note id for notes), " +
            "focus, hide (state is kept), scroll (`scroll_y` in px), " +
            "type (write text into the pane's draft; nothing is submitted), " +
            "click (records a click at `x`,`y`; the pane's own page does not receive it yet — " +
            "use browser_use for a real interaction), " +
            "capture (returns captured=true only when a frame was actually produced). " +
            "Never describe a pane you have not captured.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary of what this tool call does, shown to the user " +
                    "(e.g. 'Open docs pane and read it'). Use the same language as the user.",
            ),
            "action" to AgentToolParam(
                "string",
                "What to do on the virtual screen",
                enumValues = listOf(
                    "state", "open", "focus", "hide", "scroll", "type", "click", "capture",
                ),
            ),
            "pane" to AgentToolParam(
                "string",
                "Which pane. Optional for state/capture (omit = every visible pane); " +
                    "required for every other action.",
                enumValues = VirtualScreen.Pane.entries.map { it.id },
            ),
            "location" to AgentToolParam(
                "string",
                "open/navigate only. Browser: URL. Terminal: working directory. Notes: note id.",
            ),
            "scroll_y" to AgentToolParam("integer", "scroll only. Target scroll offset in px."),
            "text" to AgentToolParam("string", "type only. Text written into the pane's draft."),
            "x" to AgentToolParam("integer", "click only. X inside the pane."),
            "y" to AgentToolParam("integer", "click only. Y inside the pane."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf(
            "tool_title", "action", "pane", "location", "scroll_y", "text", "x", "y",
        ),
    )

    data class Result(val output: String, val success: Boolean)

    fun execute(argsJson: String, context: Context, nowMs: Long = System.currentTimeMillis()): Result {
        if (!enabled(context)) {
            return Result("The virtual screen is switched off (Settings › Minis X › Features).", false)
        }
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return Result("Arguments were not valid JSON: ${it.message}", false)
        }
        val flat = linkedMapOf<String, Any?>()
        args.keys().forEach { k -> flat[k] = args.opt(k) }

        return when (val request = ScreenRequest.parse(flat)) {
            is ScreenRequest.Invalid -> Result(request.message, false)
            is ScreenRequest.Stateless -> runStateless(request.action, context, nowMs)
            is ScreenRequest.PaneAction -> runPane(request, context, nowMs)
        }
    }

    // ---------------------------------------------------------------- iç

    private fun runStateless(
        action: VirtualScreen.Action,
        context: Context,
        nowMs: Long,
    ): Result {
        val state = VirtualScreenHost.state(context)
        if (action != VirtualScreen.Action.CAPTURE) {
            return Result(describe(state, nowMs), true)
        }
        val targets = state.visiblePanes().map { it.pane }
        if (targets.isEmpty()) {
            return Result("No pane is visible, so there is nothing to capture.", false)
        }
        return capture(targets, context, nowMs)
    }

    private fun runPane(
        request: ScreenRequest.PaneAction,
        context: Context,
        nowMs: Long,
    ): Result {
        val pane = request.pane
        val state = VirtualScreenHost.state(context)
        return when (request.action) {
            VirtualScreen.Action.CAPTURE -> capture(listOf(pane), context, nowMs)

            VirtualScreen.Action.OPEN -> {
                VirtualScreenHost.show(context, pane)
                val loc = ScreenRequest.cleanText(request.location)
                if (loc != null) {
                    VirtualScreenHost.update(context, pane) { it.copy(location = loc) }
                }
                Result(
                    "$pane is open and focused" + if (loc == null) "." else ", location=$loc.",
                    true,
                )
            }

            VirtualScreen.Action.NAVIGATE -> {
                val loc = ScreenRequest.cleanText(request.location)
                    ?: return Result("action=navigate needs a location.", false)
                VirtualScreenHost.update(context, pane) { it.copy(location = loc) }
                Result("$pane now points at $loc.", true)
            }

            VirtualScreen.Action.FOCUS -> {
                if (state.pane(pane)?.visible != true) {
                    return Result("$pane is hidden; open it first.", false)
                }
                VirtualScreenHost.focus(context, pane)
                Result("$pane has focus.", true)
            }

            VirtualScreen.Action.HIDE -> {
                val after = VirtualScreenHost.hide(context, pane)
                Result(
                    "$pane is hidden; its state is kept." +
                        if (after.focus == null) " No pane has focus now." else "",
                    true,
                )
            }

            VirtualScreen.Action.SCROLL -> {
                val y = ScreenRequest.clamp(request.scrollY)
                    ?: return Result("action=scroll needs scroll_y.", false)
                VirtualScreenHost.update(context, pane) { it.copy(scrollY = y) }
                Result("$pane scrolled to y=$y.", true)
            }

            VirtualScreen.Action.TYPE -> {
                val text = request.text ?: return Result("action=type needs text.", false)
                VirtualScreenHost.update(context, pane) { it.copy(draft = text) }
                Result("$pane draft set (${text.length} chars). Nothing was submitted.", true)
            }

            VirtualScreen.Action.CLICK -> {
                val x = ScreenRequest.clamp(request.x)
                    ?: return Result("action=click needs x and y.", false)
                val y = ScreenRequest.clamp(request.y)
                    ?: return Result("action=click needs x and y.", false)
                Result(
                    "$pane: click recorded at ($x,$y). The pane's own page/terminal does not " +
                        "receive it yet — use browser_use for a real interaction.",
                    true,
                )
            }

            VirtualScreen.Action.READ -> Result(describe(state, nowMs), true)
        }
    }

    private fun capture(
        targets: List<VirtualScreen.Pane>,
        context: Context,
        nowMs: Long,
    ): Result {
        val source = frameSource
            ?: return Result(
                "No frame source is attached, so I cannot prove the pane is drawn. " +
                    "Do not describe what is on the virtual screen.",
                false,
            )
        val lines = targets.map { p ->
            val frame = runCatching { source.frame(p) }.getOrNull()
            if (frame == null) "$p: capture=false (no frame produced)"
            else "$p: capture=true (${frame.size} bytes)"
        }
        val ok = lines.any { it.contains("capture=true") }
        // [37118] Kare gerçekten üretildiyse bütçe damgası düşer; aksi hâlde
        // `needsCapture` aynı bölmeyi sonsuza kadar "yakalanmadı" sanar.
        if (ok) VirtualScreenHost.markCaptured(context, targets, nowMs)
        return Result(lines.joinToString("\n"), ok)
    }

    private fun describe(state: VirtualScreen.State, nowMs: Long): String {
        if (state.visiblePanes().isEmpty()) {
            return "No pane is visible. The virtual screen is empty."
        }
        val due = runCatching { VirtualScreen.needsCapture(state, nowMs) }.getOrDefault(emptyList())
        val sb = StringBuilder(
            "Virtual screen (your own surface inside the app, not the user's phone):\n",
        )
        state.visiblePanes().forEach { p ->
            val mark = if (state.focus == p.pane) "*" else " "
            sb.append("$mark ${p.pane.id}")
            p.location?.let { sb.append(" @ $it") }
            if (p.scrollY != 0) sb.append(" scroll=${p.scrollY}")
            if (p.draft.isNotEmpty()) sb.append(" draft=${p.draft.length}ch")
            sb.append(if (p.pane in due) " · not captured yet" else " · freshly captured")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }
}
