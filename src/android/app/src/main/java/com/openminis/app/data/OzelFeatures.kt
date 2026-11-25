package com.openminis.app.data

import android.content.Context

/**
 * [MINIS-OZEL] Per-feature switches for everything this fork added.
 *
 * ## Why a flag registry rather than a switch on each screen
 *
 * Each fork feature already had its own switch somewhere, or none at all. That
 * is fine while every feature is new and wanted, and wrong the moment one of
 * them gets in the way: a user who does not want a Live Tasks row in Settings
 * had no way to remove it. "We added it" is not a reason for it to be
 * permanent in someone else's app.
 *
 * ## What a flag may and may not do
 *
 * A flag here hides an ENTRY POINT or stops a BACKGROUND behaviour. It never
 * deletes user data and never makes saved state unreadable:
 *
 *  - turning Live Tasks off hides the row; a task already running keeps
 *    running, and turning it back on shows it again;
 *  - turning Toolchains off hides the screen; installed packages stay on disk;
 *  - turning a preset off removes it from Add Provider; an instance the user
 *    already created keeps working, because it is an ordinary OpenAI provider
 *    by then (same rule as [com.openminis.app.util.CopilotFeatureFlag]);
 *  - turning the developer server off closes the socket; nothing else depends
 *    on it.
 *
 * ## Why SharedPreferences and not the config registry
 *
 * The config registry is a schema mirrored with iOS, where none of these keys
 * exist. A separate file means an upstream sync of that schema cannot collide
 * with them — the same reasoning [TranslationPrefs] documents.
 *
 * ## Defaults
 *
 * Everything that is user-visible and harmless defaults ON, so an existing
 * install looks unchanged after updating. The developer server defaults OFF:
 * it is a local HTTP surface that can read provider keys and drive the UI, and
 * a feature that is off unless asked for cannot be reached by an app on the
 * same device that the user never consented to.
 */
object OzelFeatures {

    private const val PREFS = "ozel_features_prefs"

    /** One toggle as the settings screen renders it. */
    enum class Feature(
        val key: String,
        val defaultEnabled: Boolean,
    ) {
        /** Settings › Live Tasks: what is running right now. */
        LIVE_TASKS("live_tasks", true),

        /** Settings › Toolchains: install build tools into the sandbox. */
        TOOLCHAINS("toolchains", true),

        /** Thinking-block translation (mirrors, but does not replace, TranslationPrefs.enabled). */
        TRANSLATION("translation", true),

        /** The Quick Settings tile that opens a new chat. */
        QS_TILE("qs_tile", true),

        /** Settings › Background gates: permission checklist for long tasks. */
        BACKGROUND_GATES("background_gates", true),

        /**
         * The on-device developer server (127.0.0.1:5321). Off by default.
         * Only meaningful in builds that ship it (DEV_TOOLS).
         */
        DEV_SERVER("dev_server", false),

        /**
         * [MINIS-OZEL 11.1] "Test connection" on a provider's detail screen.
         * One GET against the vendor's models endpoint, with the reason a
         * failure happened instead of an empty model list.
         */
        PROVIDER_TEST("provider_test", true),

        /**
         * [MINIS-OZEL 11.1] The notes module: the agent's scratchpad and the
         * user's vault, behind one Settings entry. Off hides the entry and
         * unregisters the agent tool; stored notes stay on disk (same rule as
         * every other flag here).
         */
        NOTES("notes", true),

        /**
         * [MINIS-OZEL 11.1] The vault inside Notes — entries only the user can
         * write. Its own flag because it is the one part of Notes that holds
         * secrets: someone may want the agent's notepad and not a keystore.
         */
        VAULT("vault", true),

        /**
         * [MINIS-OZEL 11.1] Whether an agent may READ a vault value.
         *
         * Off by default, and deliberately separate from [VAULT]: the vault
         * existing and the agent being allowed to see inside it are different
         * decisions. Turning this on means the secret is sent to the model
         * provider as part of the conversation — the settings row says so.
         */
        VAULT_AGENT_READ("vault_agent_read", false),

        // [37121] `STATION` **silindi**, kapatılmadı. Gerekçe: `ui/station/`
        // hiç yazılmadı, anahtarın üretimde 0 çağrısı vardı ve iki sürüm
        // boyunca "kapalı" durması kullanıcıya "kapatılmış bir özellik var"
        // izlenimi verdi. Kapalı bir anahtar var olmayan bir özelliği hâlâ
        // reklam eder; silmek dürüst olan. Paneller yazılırsa anahtar da
        // panellerle birlikte geri gelir (plan: `.45+`).

        // [37091] `EDIT_MODE` removed, not disabled. Its row promised "a pen in
        // the middle of every screen: tap it, mark what should change, and the
        // note lands in Notes" — no overlay, no pen, no capture existed, and no
        // code read the flag (0 call sites). A disabled switch still advertises
        // the feature; deleting it is the honest version. Comes back with the
        // feature, not before.

        /**
         * [MINIS-OZEL 11.3.1] The action journal: every file an agent changed,
         * with the recipe to undo it, plus the `journal` tool that lets the
         * agent see its own history.
         *
         * ON by default. It is the safety net under every other feature — an
         * agent that can be undone is one the user can let run — so a default
         * of off would only protect the installs that already asked for it.
         * Turning it off stops new entries being written; the ones on disk
         * stay readable until they age out.
         */
        JOURNAL("journal", true),

        /**
         * [MINIS-OZEL 11.3.1] Acceptance criteria: the `verify` tool, which
         * lets a run record what "done" means and check it before claiming
         * completion.
         *
         * ON by default. The failure it prevents ("it said it was done and it
         * was not") is the most common complaint about an agent, and the tool
         * costs one prompt slot. Turning it off removes the tool from the
         * schema, so the model is not even offered it.
         */
        ACCEPTANCE_CRITERIA("acceptance_criteria", true),

        /**
         * [MINIS-OZEL 11.5 · 37060] Council mode: the third agent kind and the
         * per-member belt that comes with it.
         *
         * ON by default because it changes no existing behaviour: a team whose
         * `mode` is `handoff` runs exactly as before, and TOP/HELPER belts are
         * untouched. OFF hides the council entry points and — the load-bearing
         * part — makes `TeamRunner` refuse a `council` run, so a saved team
         * cannot start an argument the user has switched off. Stored teams,
         * runs and memory are never deleted by this flag (rule 2 of
         * ANA-BAGLAM).
         */
        COUNCIL("council", true),

        // ── [37080–37090] Özellik paketi ─────────────────────────────────
        // Her anahtar kapalıyken o dilimin **davranışı** kalkar; veri silinmez
        // (ANA-BAĞLAM kural 2). Varsayılanlar tek tek gerekçeli: güvenliği
        // artıran fiiller açık, davranışı değiştiren ya da ölçüm bekleyenler
        // kapalı.

        /**
         * [37080] Tehlike sınıflandırması + onay kartı.
         *
         * ON: bu bir fren, hız değil. Kapalı bırakmak, Shizuku/erişilebilirlik
         * açık bir cihazda ajanın `settings put` çalıştırmasını serbest bırakır.
         * Yalnız MEDIUM ve üstü sorar; `ls`, `cat`, `rg` sessiz geçer.
         */
        APPROVAL_GATE("approval_gate", true),

        /**
         * [37081] Sohbet/gün/ay harcama tavanı.
         *
         * ON, ama tavan tanımlanmadıkça hiçbir şey engellemez — bu yüzden
         * açık olması bedava. Tavanlar ayarlardan girilir.
         */
        BUDGET_CAP("budget_cap", true),

        /**
         * [37082] Kendiliğinden yedek.
         *
         * [37121] AÇIK — satır görünsün. Ayrım önemli: **anahtar açık** demek
         * "yedek al" demek değil; `AutoBackupPolicy` varsayılanı kapalı
         * (`enabled=false`), yani gerçek yedek yine kullanıcı istemeden
         * başlamaz. `.36`'daki `gorunurluk` kapısı bu ayrımı yazamadığı için
         * anahtarı görünmez sayıyordu; burada ayrım KDoc'a yazıldı.
         */
        AUTO_BACKUP("auto_backup", true),

        /**
         * [37083] Pahalı işten önce plan kartı.
         *
         * ON: eşiğin altındaki işte hiç görünmez, üstündeki işte tek kart.
         */
        PLAN_PREVIEW("plan_preview", true),

        /**
         * [37084] Tur sonunda "ne değişti" özeti.
         *
         * ON: yalnız dosya değiştiyse çıkar, hiçbir şeyi durdurmaz.
         */
        TURN_DIFF("turn_diff", true),

        /**
         * [37085] Araç şeması daraltma (niyet → 6-10 aday).
         *
         * OFF: ilk turda doğru araç oranını düşürebilir. Ölçüm olmadan açmak,
         * sessiz bir kalite kaybı riski. Açmadan önce "araç seçimi" sayacına
         * bakılmalı.
         */
        TOOL_ROUTER("tool_router", false),

        /**
         * [37086] Hata/düzeltme → ders önerisi.
         *
         * ON: kendiliğinden hafızaya yazmaz, yalnız öneri kartı çıkarır.
         */
        LESSON_BOOK("lesson_book", true),

        /**
         * [37087] "Bitti" beyanı için kanıt.
         *
         * [37121] AÇIK — ve sınırı KDoc'a yazıldı: kanıtsız "bitti" beyanı
         * turu **durdurmaz**, ikinci turdan sonra hatırlatıcı ekler. Geri
         * dönüşü olan bir uyarı; kapalı tutmanın tek etkisi "bitti dedi ama
         * bitmemişti" sınıfını geri getirmek olurdu.
         */
        EVIDENCE_GATE("evidence_gate", true),

        /**
         * [37088] Çok adımlı iş boyunca yazma niyeti + çakışma sicili.
         *
         * ON: yalnız parallel ajan varken anlamlı; tek ajanda maliyeti sıfır.
         */
        WRITE_INTENT("write_intent", true),

        /**
         * [37089] Kayıtlı yeniden oynatma (model karşılaştırması).
         *
         * [37121] AÇIK — `ReplayStore` bağlı (`integration_audit`: BAĞLI) ve
         * Ayarlar › Geçmiş koşuyu yeniden oynat satırı var. Kapalı tutmak,
         * bağlı bir yüzeyi kullanıcıdan saklamak demekti.
         */
        REPLAY("replay", true),

        // [37121] `REMOTE_CONSOLE` **silindi**. `remote/RemotePolicy.kt` jeton
        // modeli olarak duruyor (ürün kararı: sunucu yok, yarım güvenlik kodu
        // kullanıcıya açılmaz) ama anahtarın kendisi bir vaat taşıyordu ve
        // hiçbir yüzeyi yoktu. Karar: **kod kalsın, vaat kalksın.**

        /**
         * [37095] Sohbet üst çubuğundaki sağlık göstergesi (TTFB, tekrar
         * deneme, hata kodu).
         *
         * [37117] ÖLÇÜMLE KAPATILDI — `.16`'da "bağlandı" yazıyordu, bağlı
         * değil: `ChatHealthWiring` bu dosyanın dışında **hiçbir yerden**
         * çağrılmıyor (`requestStarted`/`firstChunk`/`finished` — 0 çağrı,
         * yalnız kendi tanımı), yani hiçbir sohbet sağlık kaydı üretmiyordu
         * ve Canlı Görevler'de çizilecek çip yoktu. Anahtar açık olduğu için
         * kullanıcı "açık ama hiçbir şey yok" durumunu görüyordu.
         *
         * Aynı sınıf dördüncü kez: `.14` STATION, `.15` sağlık iddiası,
         * `.34` sanal ekran yakalama, bu. Ders `gorunurluk.py` kapısına
         * dönüştü: AÇIK+GÖRÜNMEZ anahtar artık kırmızı.
         *
         * Yeniden AÇILMA ŞARTI: `ChatViewModel` tur döngüsü
         * `requestStarted`/`firstChunk`/`finished` çağırmalı ve
         * `LiveTasksScreen` çipi çizmeli. İkisi de yoksa anahtar kapalı kalır.
         */
        CHAT_HEALTH("chat_health", false),

        /**
         * [37099] Sanal ekran — ajanın uygulama **içinde** kendi yüzeyi
         * (tarayıcı + terminal + notlar).
         *
         * AÇIK: çekirdek (bölme/odak/sıralama), durum kaydı ve A/B eylem izni
         * ayrımı hazır; arayüzü var (Ayarlar › Minis X › Sanal ekran).
         *
         * Okuyucusu [com.openminis.app.screen.VirtualScreenHost.brief]: ajan
         * sistem isteminde "kendi yüzeyinde mi, gerçek ekranda mı çalışıyor"
         * satırını görür. Kapalıyken bu satır hiç gönderilmez.
         *
         * Sınır dürüstçe: bölmeler henüz **tek tuvalde çizilmiyor**
         * (`SANAL-EKRAN.md` §4 P1). Anahtarın açık olması "çizim var" demek
         * değil; ayar ekranı da bunu footer'da söylüyor. Bir sonraki adım
         * tarayıcı + terminal yüzeyini aynı yüzeye bağlamak.
         */
        VIRTUAL_SCREEN("virtual_screen", true),
        ;

        companion object {
            fun parse(raw: String?): Feature? = entries.firstOrNull { it.key == raw?.trim() }
        }
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context, feature: Feature): Boolean =
        prefs(ctx).getBoolean(feature.key, feature.defaultEnabled)

    fun setEnabled(ctx: Context, feature: Feature, value: Boolean) {
        prefs(ctx).edit().putBoolean(feature.key, value).apply()
    }

    /** Every feature and its current value, in display order. */
    fun snapshot(ctx: Context): List<Pair<Feature, Boolean>> =
        Feature.entries.map { it to isEnabled(ctx, it) }

    /** Puts every switch back to its shipped default. */
    fun resetToDefaults(ctx: Context) {
        val editor = prefs(ctx).edit()
        Feature.entries.forEach { editor.putBoolean(it.key, it.defaultEnabled) }
        editor.apply()
    }

    /**
     * Whether the developer server should run. It also needs the build to have
     * been compiled with DEV_TOOLS; this only says the user wants it.
     */
    fun devServerEnabled(ctx: Context): Boolean = isEnabled(ctx, Feature.DEV_SERVER)
}
