package com.openminis.app.screen

import android.content.Context
import com.openminis.app.logging.AppLogger

/**
 * [37099] Sanal ekranın **Android tarafı**: durumu sakla, sürümü çöz, bırak.
 *
 * ## Neden ayrı dosya
 *
 * [VirtualScreen] saf karar mantığı ve `android.*` import etmiyor; testi
 * cihazsız koşar. Bu dosya `Context` kullanıyor, o yüzden ayrı. Ayrım bu
 * depoda iki kez yararlı oldu (`PolicyGate`/`ApprovalInbox`,
 * `ChatHealthWiring`/`ChatHealth`): Android'e bağlı kısım ince kalır, çekirdek
 * test edilebilir kalır.
 *
 * ## Neden `SharedPreferences` ve neden toleranslı okuma
 *
 * Kayıtlı durum **eski bir sürümden** gelebilir: yeni bir bölme eklenmişse,
 * kayıtta yoktur; kaldırılmışsa, kayıtta vardır. [VirtualScreen.decode]
 * bilinmeyen bölmeyi atlar ve eksik bölmeyi kapalı ekler, yani biçim değişimi
 * göç istemez. Bozuk kayıt **atılmaz**: varsayılana döner ve bu loglanır —
 * sessizce silmek, kullanıcının ekran düzenini bir daha bulamaması demek.
 */
object VirtualScreenStore {

    private const val TAG = "VirtualScreen"
    private const val PREFS = "virtual_screen"
    private const val KEY_STATE = "state_v1"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Kayıtlı durum, yoksa varsayılan üç bölme. */
    fun load(ctx: Context): VirtualScreen.State {
        val raw = prefs(ctx).getString(KEY_STATE, null) ?: return VirtualScreen.State()
        val state = runCatching { VirtualScreen.decode(raw) }.getOrElse {
            AppLogger.info(TAG, "[load] bozuk kayıt atıldı: ${it.javaClass.simpleName}")
            return VirtualScreen.State()
        }
        // Boş ekran kaydı (`hiçbir bölme görünür değil`) geçerli bir durumdur,
        // varsayılana çevrilmemeli — kullanıcı hepsini kapatmış olabilir.
        return state
    }

    fun save(ctx: Context, state: VirtualScreen.State) {
        prefs(ctx).edit().putString(KEY_STATE, VirtualScreen.encode(state)).apply()
    }

    /** Ekranı sıfırla — "bir şey bozuldu, baştan" düğmesi. */
    fun reset(ctx: Context): VirtualScreen.State {
        val fresh = VirtualScreen.State()
        save(ctx, fresh)
        AppLogger.info(TAG, "[reset] varsayılan düzene dönüldü")
        return fresh
    }

    /** Hiçbir bölme görünür değil — ucuz bir "boş ekran" kontrolü. */
    fun isEmpty(ctx: Context): Boolean = load(ctx).visiblePanes().isEmpty()
}

/**
 * [37099] Ajanın sanal ekranı üzerinde **yaptığı işlemler** için tek giriş.
 *
 * ## Ne yapar, ne yapmaz
 *
 * Yapar: bölmeyi aç/kapat/öne al, durumunu (adres, kaydırma, taslak) yaz,
 * durumu diske kaydet, eylemin **hedefini** (sanal / gerçek) raporla.
 *
 * Yapmaz: çizim. Çizim `browser/HeadlessRenderHost` (tarayıcı) ve
 * `sandbox/PersistentShell` (terminal) tarafında. Bu sınıf onların **durumunu**
 * tutar — yani kapanıp açıldığında ajan kaldığı yerden devam eder
 * (`SANAL-EKRAN.md` §4, parça 2).
 *
 * ## Neden eylemler burada listeleniyor
 *
 * `SANAL-EKRAN.md` §4 parça 3: A'da (sanal) tıklama ile B'de (gerçek) tıklama
 * aynı riski taşımıyor. [perform] tek yerden geçtiği için, ileride kural
 * tablosuna bağlanacak tek bir nokta var: `VirtualScreen.cost(action, target)`.
 */
object VirtualScreenHost {

    private const val TAG = "VirtualScreen"

    /** Ajanın yaptığı tek bir ekran işlemi. */
    data class Op(
        val action: VirtualScreen.Action,
        val target: VirtualScreen.Target = VirtualScreen.Target.VIRTUAL,
        val pane: VirtualScreen.Pane? = null,
    ) {
        /** Kural tablosunun bakacağı sınıf. */
        val cost: VirtualScreen.Cost
            get() = VirtualScreen.cost(action, target)
    }

    fun state(ctx: Context): VirtualScreen.State = VirtualScreenStore.load(ctx)

    /** Bölmeyi aç ve öne al. */
    fun show(ctx: Context, pane: VirtualScreen.Pane): VirtualScreen.State =
        commit(ctx, VirtualScreen.show(VirtualScreenStore.load(ctx), pane))

    fun hide(ctx: Context, pane: VirtualScreen.Pane): VirtualScreen.State =
        commit(ctx, VirtualScreen.hide(VirtualScreenStore.load(ctx), pane))

    fun focus(ctx: Context, pane: VirtualScreen.Pane): VirtualScreen.State =
        commit(ctx, VirtualScreen.focus(VirtualScreenStore.load(ctx), pane))

    /**
     * Bölmenin durumunu yaz (adres, kaydırma, taslak).
     *
     * Her yazımda diske inmez: kayan bir kaydırma konumu için saniyede onlarca
     * yazma olurdu. Çağıran taraf [flush] ile karar verir (ekran kapanırken,
     * bölme değişirken).
     */
    fun update(
        ctx: Context,
        pane: VirtualScreen.Pane,
        f: (VirtualScreen.PaneState) -> VirtualScreen.PaneState,
    ): VirtualScreen.State = commit(ctx, VirtualScreen.update(VirtualScreenStore.load(ctx), pane, f))

    fun reorder(ctx: Context, order: List<VirtualScreen.Pane>): VirtualScreen.State =
        commit(ctx, VirtualScreen.reorder(VirtualScreenStore.load(ctx), order))

    fun flush(ctx: Context, state: VirtualScreen.State) = VirtualScreenStore.save(ctx, state)

    /**
     * Ajanın "şu an nerede çalışıyorum" sorusuna cevap.
     *
     * Neden gerekli: model `android-a11y-cli tap` çağırdığında **kullanıcının**
     * ekranına dokunur; `screen:virtual click` ise kendi tarayıcısına. İkisi
     * arasındaki farkı modelin bilmesi gerekiyor, yoksa "kendi odamda
     * çalışıyorum" sanıp kullanıcının ekranını değiştirir. İstemci bunu kısa
     * bir satır olarak sistem istemine ekler.
     */
    fun brief(ctx: Context): String {
        // [37099] Anahtarın gerçek okuyucusu burası: kapalıyken ajan sistem
        // isteminde sanal ekrandan hiç söz görmez. Açık bir anahtarın üretimde
        // okuyucusu olma kuralı (bkz. OzelFeatures.VIRTUAL_SCREEN).
        if (!com.openminis.app.data.OzelFeatures.isEnabled(ctx, com.openminis.app.data.OzelFeatures.Feature.VIRTUAL_SCREEN)) {
            return ""
        }
        val s = VirtualScreenStore.load(ctx)
        val visible = s.visiblePanes()
        if (visible.isEmpty()) {
            return "Virtual screen: EMPTY (no pane open). Nothing is being rendered for the agent."
        }
        val parts = visible.map { p ->
            val loc = p.location?.takeIf { it.isNotBlank() } ?: "—"
            "${p.pane.id}(loc=$loc, scroll=${p.scrollY})"
        }
        val focus = s.focus?.id ?: "none"
        return "Virtual screen: ${parts.joinToString(", ")} · focus=$focus. " +
            "This surface is INSIDE the app: clicking here changes nothing on the user's real screen. " +
            "For the real screen use the accessibility tool (that one asks for approval)."
    }

    private fun commit(ctx: Context, state: VirtualScreen.State): VirtualScreen.State {
        VirtualScreenStore.save(ctx, state)
        AppLogger.info(
            TAG,
            "[state] visible=${state.visiblePanes().joinToString(",") { it.pane.id }} focus=${state.focus?.id}",
        )
        return state
    }
}
