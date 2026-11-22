package com.openminis.app.data

/**
 * [37099] Çeviri motoru **zinciri** — sıralı, biri olmazsa sonrakine düşer.
 *
 * ## Neden zincir, neden tek seçim değil
 *
 * Kullanıcının sorusu: *"kullanıcı hangisini öne alırsa ilk olarak o yanıt
 * vermezse ikinciyi gibi çeşitli seçenek olmalı"*. Tek seçim bunu
 * cevaplamıyor: çevrimdışı paket bir terimi bilmiyorsa ya da model kotası
 * bittiyse kullanıcı ayar ekranına dönüp elle değiştirmek zorunda kalıyordu.
 *
 * Zincir üç şeyi birden veriyor:
 *   1. **Sıra** — hangisi önce denenir ([TranslationChain.plan]).
 *   2. **Düşme** — biri başarısız/eksikse sonraki denenir ([TranslationChain.next]).
 *   3. **Kayıt** — hangi motorun cevap verdiği sonuçta yazılır, arayüz söyler.
 *
 * ## Neden "çevrimdışı motor" dürüstçe sınırlı
 *
 * Bu uygulamada **çevrimdışı nöral çeviri yok**: gerçek bir NMT modeli 30–100 MB
 * + SentencePiece tokenizer demek (bkz. `docs/ozel/HARITA.md` §11 ve
 * `TranslationPacks`). Olan şey **paket**tir: sözlük + "bunu çevirme" listesi +
 * birkaç örnek. Bu yüzden `OFFLINE_PACK` motoru:
 *
 *   - yalnız **sözlük terimlerini** değiştirir, cümle çevirmez;
 *   - sonucu **`degraded = true`** ile döndürür, arayüz "sözlük tabanlı (tam
 *     çeviri değil)" der;
 *   - tek başına yeterliyse (metnin tamamı sözlükte varsa) bunu da söyler.
 *
 * Yani "çevrimdışı" bir vaat değil, **kapsamı yazılı** bir yedek. Bunu tam
 * çeviri gibi sunmak, `.14`'te `STATION` için kınanan sınıfın aynısı olurdu.
 */
object TranslationChain {

    /** Zincire girebilen motorlar. */
    enum class Engine(val id: String) {
        /** Sözlük paketi — çevrimdışı, cümle çevirmez, hızlı, bedava. */
        OFFLINE_PACK("offline_pack"),

        /** Model + paket sözlüğü: tutarlı terim, en yüksek kalite, en pahalı. */
        MODEL_PACK("model_with_pack"),

        /** Model, paket yok: ucuz, hızlı, terimler tutarsız. */
        MODEL_ONLY("model_only"),
        ;

        companion object {
            fun parse(raw: String?): Engine? = entries.firstOrNull { it.id == raw?.trim() }
        }
    }

    /** Varsayılan sıra: bedava olan önce, pahalı olan sonra. */
    val DEFAULT: List<Engine> = listOf(Engine.OFFLINE_PACK, Engine.MODEL_PACK, Engine.MODEL_ONLY)

    /**
     * Kayıtlı sırayı çözer.
     *
     * Toleranslı: bilinmeyen ad atlanır, tekrar eden ad tekilleşir, boş/bozuk
     * kayıt varsayılana döner. Neden: sıra bir kullanıcı ayarıdır ve bir sürüm
     * yükseltmesinden sonra içinde olmayan bir motor adı kalabilir; o durumda
     * çevirinin **tamamen susması** kabul edilemez.
     */
    fun parse(raw: String?): List<Engine> {
        if (raw.isNullOrBlank()) return DEFAULT
        val parsed = raw.split(",", " ").mapNotNull { Engine.parse(it) }.distinct()
        return parsed.ifEmpty { DEFAULT }
    }

    fun encode(plan: List<Engine>): String = plan.distinct().joinToString(",") { it.id }

    /**
     * [engine] başarısız olduğunda sıradaki motor.
     *
     * `null` = zincir bitti. Sıra kullanıcının kararıdır; burada yeniden
     * sıralama yapılmaz.
     */
    fun next(plan: List<Engine>, engine: Engine): Engine? {
        val i = plan.indexOf(engine)
        if (i < 0) return plan.firstOrNull()
        return plan.getOrNull(i + 1)
    }

    /** Bir motorun bu isteği karşılayıp karşılamadığı — düşme kararının ölçütü. */
    data class Outcome(
        val engine: Engine,
        /** Boş değilse kullanılabilir cevap var. */
        val text: String = "",
        /** Tam çeviri mi (yalnız model motorları true döndürebilir). */
        val complete: Boolean = false,
        /** Sözlük tabanlıysa true — arayüz bunu söylemek zorunda. */
        val degraded: Boolean = false,
        /** Bu motorda neden durduk (log ve kullanıcı bilgisi). */
        val reason: String = "",
    ) {
        /** Zincirde sonraki motora geçmeyi hak ediyor mu? */
        val fallThrough: Boolean
            get() = text.isBlank() || (degraded && !complete)
    }

    /**
     * Zinciri yürütür.
     *
     * [run] bir motoru dener. Kural: **boş metin** ya da **degraded ve tam
     * değil** ise sonraki denenir; ama degraded sonuç **saklanır** — hiçbir
     * model cevap vermezse kullanıcıya hiçbir şey yerine sözlük sonucu
     * gösterilir. "Hiç yok" her zaman "kısmi var"dan kötüdür.
     */
    suspend fun run(
        plan: List<Engine>,
        run: suspend (Engine) -> Outcome,
    ): Outcome? {
        var fallback: Outcome? = null
        var current: Engine? = plan.firstOrNull()
        while (current != null) {
            val outcome = run(current)
            if (!outcome.fallThrough) return outcome
            if (outcome.text.isNotBlank()) fallback = outcome
            current = next(plan, current)
        }
        return fallback
    }
}
