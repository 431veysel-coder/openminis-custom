package com.openminis.app.screen

/**
 * [37118] `screen` aracının **saf** isteği — çözümleme ve doğrulama.
 *
 * ## Neden ayrı dosya
 *
 * `ScreenTool` `Context` kullanıyor (durum diske yazılıyor), yani JVM testi
 * yok. Ama aracın en kolay bozulan yeri burası değil: **hangi eylemin hangi
 * argümanı istediği** ve **hangi hatanın kullanıcıya değil modele söyleneceği**.
 * Bu kuralları saf bir nesneye almak, onları testsiz bırakmamak demek.
 *
 * ## Neden hata metinleri modele yazılıyor
 *
 * Araç sonucu modele gider. "eksik argüman" gibi bir cevap modelin aynı çağrıyı
 * aynen tekrar denemesine yol açar; "click needs x and y" ise ne yapacağını
 * söyler. Bu depoda `Deny` gerekçesinin modele aynen dönmesi kuralı aynı
 * sebepten var (`GateDecision`).
 */
sealed class ScreenRequest {

    /** Bölmesiz çalışan eylemler: durum okuma ve toplu yakalama. */
    data class Stateless(val action: VirtualScreen.Action) : ScreenRequest()

    data class PaneAction(
        val action: VirtualScreen.Action,
        val pane: VirtualScreen.Pane,
        val location: String? = null,
        val scrollY: Int? = null,
        val text: String? = null,
        val x: Int? = null,
        val y: Int? = null,
    ) : ScreenRequest()

    /** Modele dönen hata; `hint` ne yapılacağını söyler. */
    data class Invalid(val message: String) : ScreenRequest()

    companion object {

        /** Bölme isteyen eylemler — tek liste, iki yerde tekrar etmesin. */
        val PANE_ACTIONS: Set<VirtualScreen.Action> = setOf(
            VirtualScreen.Action.OPEN,
            VirtualScreen.Action.NAVIGATE,
            VirtualScreen.Action.FOCUS,
            VirtualScreen.Action.HIDE,
            VirtualScreen.Action.SCROLL,
            VirtualScreen.Action.TYPE,
            VirtualScreen.Action.CLICK,
        )

        /**
         * Ham argümanlardan istek kurar.
         *
         * `raw` haritası `JSONObject`'ten düzleştirilmiş olsun diye `Map<String,
         * Any?>`: saf kalsın (org.json bağımlılığı testte de var ama Android
         * stub'ı JVM'de `JSONObject`'ı gerçekten çalıştırır; yine de gereksiz
         * bağımlılık).
         */
        fun parse(raw: Map<String, Any?>): ScreenRequest {
            val actionRaw = raw["action"]?.toString()?.trim().orEmpty()
            val action = VirtualScreen.Action.parse(actionRaw)
                ?: return Invalid(
                    if (actionRaw.isEmpty()) "action is required." else "Unknown action '$actionRaw'.",
                )

            val paneRaw = raw["pane"]?.toString()?.trim().orEmpty()
            if (!PANE_ACTIONS.contains(action)) {
                // state/capture: bölme verilirse yalnız o bölme yakalanır.
                if (paneRaw.isEmpty()) return Stateless(action)
                val pane = VirtualScreen.Pane.parse(paneRaw)
                    ?: return Invalid("Unknown pane '$paneRaw'.")
                return PaneAction(action, pane)
            }

            if (paneRaw.isEmpty()) return Invalid("action=${action.id} needs a pane.")
            val pane = VirtualScreen.Pane.parse(paneRaw)
                ?: return Invalid("Unknown pane '$paneRaw'.")

            val location = raw["location"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            val text = raw["text"]?.toString()?.takeIf { it.isNotEmpty() }
            val scrollY = intOf(raw["scroll_y"])
            val x = intOf(raw["x"])
            val y = intOf(raw["y"])

            return when (action) {
                VirtualScreen.Action.NAVIGATE ->
                    if (location == null) Invalid("action=navigate needs a location.") else PaneAction(action, pane, location = location)
                VirtualScreen.Action.SCROLL ->
                    if (scrollY == null) Invalid("action=scroll needs scroll_y.") else PaneAction(action, pane, scrollY = scrollY)
                VirtualScreen.Action.TYPE ->
                    if (text == null) Invalid("action=type needs text.") else PaneAction(action, pane, text = text)
                VirtualScreen.Action.CLICK ->
                    if (x == null || y == null) Invalid("action=click needs x and y.") else PaneAction(action, pane, x = x, y = y)
                else -> PaneAction(action, pane, location = location)
            }
        }

        /**
         * Sınır denetimi: kaydırma negatife inmez, koordinat negatife inmez.
         *
         * Neden: `scroll_y = -5` ile `0` arasındaki fark kullanıcıya görünmez
         * ama kayda yazılır; negatif değer bir sonraki açılışta `dy` hesabını
         * bozar. Kırpmak, reddetmekten daha iyi: reddetmek modelin aynı şeyi
         * farklı sayıyla denemesine yol açar.
         */
        fun clamp(value: Int?): Int? = value?.coerceAtLeast(0)

        /** Boşluk kırp; boşsa `null` (boş adres kayda yazılmaz). */
        fun cleanText(value: String?): String? =
            value?.trim()?.takeIf { it.isNotEmpty() }

        private fun intOf(v: Any?): Int? = when (v) {
            null -> null
            is Int -> v
            is Number -> v.toInt()
            else -> v.toString().trim().toIntOrNull()
        }
    }
}
