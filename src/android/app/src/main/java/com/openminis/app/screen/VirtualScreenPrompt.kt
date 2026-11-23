package com.openminis.app.screen

import android.content.Context
import com.openminis.app.data.OzelFeatures

/**
 * [37099b] Sanal ekranın **sistem istemine** giren kısmı.
 *
 * ## Neden burada, `brief()`'in yanında
 *
 * `VirtualScreenHost.brief()` yazıldı ve hiçbir yerden çağrılmadı: ölçüm
 * (2026-11-23) — tüm depoda tek referans kendi KDoc'u. Yani "ajan sistem
 * isteminde nerede çalıştığını görür" iddiası yanlıştı; `OzelFeatures.kt`
 * yorumu okuyucuyu gösteriyordu ama okuyucunun kendisi ölüydü. Bu dosya o
 * boşluğu kapatır: tek giriş noktası, `ChatViewModel`'de `TermuxPrompt.section`
 * ile aynı yerde çağrılıyor.
 *
 * ## Neden hem sabit metin hem durum satırı
 *
 * Kullanım kuralları (A/B farkı, ayarlar, örnekler) turdan tura değişmez;
 * bölme durumu değişir. Sabit kısmı ayrı tutmak, önek önbelleğinin en az
 * bozulması demek. Durum satırı kısa: modelin karar vermesi için gereken
 * bilgi, ansiklopedi değil.
 *
 * ## Neden sınırları da yazıyoruz
 *
 * Bugün bölmeler tek tuvalde **çizilmiyor** (`SANAL-EKRAN.md` §4 P1) ve
 * `VirtualScreenHost.Op` hiçbir araçtan çağrılmıyor. İstem "tarayıcıda
 * gezinebilirsin" derse model yapamayacağı bir şeyi iddia eder — `.14`'te
 * `STATION` için kınanan sınıfın istem tarafındaki karşılığı. Bu yüzden metin
 * "düzen ve durum yönetilir, çizim henüz yok" der; modelin yalan söylemesini
 * engelleyen tek yol bu.
 */
object VirtualScreenPrompt {

    fun section(ctx: Context): String {
        if (!OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.VIRTUAL_SCREEN)) return ""
        val state = runCatching { VirtualScreenStore.load(ctx) }.getOrNull() ?: return ""
        return RULES + "\n" + stateLine(state)
    }

    /** Turdan tura değişmeyen kısım. */
    private val RULES = """

Virtual screen (INSIDE the app) — this is your own working surface, not the user's phone:
- Panes: browser, terminal, notes. The user sets which are open, their order and the focused pane in Settings › Minis X › Virtual screen; that layout is persisted (SharedPreferences `virtual_screen`). You read it here; you cannot change it without the user, and nothing you do here rearranges their screen.
- A/B difference that decides approval (this is the whole point of the feature):
  · A — virtual screen (this surface): open/navigate/click/type/scroll inside the app's own panes. The user's real screen does not change, and anything you do is limited to this app's state, so these actions need no approval card.
  · B — real device screen (android-a11y-cli, android-shizuku-cli): every tap moves the phone the user is holding. READ/CAPTURE are silent; SCROLL/OPEN/NAVIGATE ask; CLICK/TYPE always need confirmation, because a wrong tap can send, buy or delete something in another app that cannot be undone.
  · Rule of thumb: if the user says "the screen", "my phone", "that app" → B, and say out loud that you are touching their real screen. If it is your own browsing/notes/terminal work → A.
- Interaction you can rely on today: pane visibility and focus ordering come from the user's settings; a pane keeps its location (URL / cwd / note id), scroll offset and draft across restarts, so "where I left off" survives. There is no tool yet to draw the panes on one canvas and no tool to click inside them — do NOT claim you browsed, scrolled or clicked in the virtual screen. Use browser_use or shell_execute for real work and report honestly which surface you used.
- Settings, for when the user asks: Settings › Minis X › Virtual screen (master switch, pane switches, focused pane shown as a subtitle, "reset layout"), Settings › Minis X › Features for the app-wide switch. Turning the master switch off hides this section and the settings row entirely.
- Usage examples:
  · "Open the docs page and summarise it" → browser_use (A). Say it stayed inside the app.
  · "Go to Settings on my phone and turn on Wi-Fi" → accessibility (B). Warn first; expect an approval card per tap.
  · "Keep a note of what we found" → note tool, and mention the notes pane holds the draft if the user has it open.
  · "Why did my screen jump?" → the user's own action or a B-mode tool; the virtual screen never moves their display."""

    private fun stateLine(s: VirtualScreen.State): String {
        val visible = s.visiblePanes()
        if (visible.isEmpty()) {
            return "Virtual screen state: EMPTY — no pane is open, so nothing is being rendered for you. " +
                "Say so instead of pretending to look at a pane."
        }
        val panes = visible.joinToString(", ") { p ->
            val loc = p.location?.takeIf { it.isNotBlank() } ?: "—"
            "${p.pane.id}(loc=$loc, scroll=${p.scrollY})"
        }
        return "Virtual screen state: $panes · focus=${s.focus?.id ?: "none"} · order=" +
            s.panes.joinToString(",") { it.pane.id } + "."
    }
}
