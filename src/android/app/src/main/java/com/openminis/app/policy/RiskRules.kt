package com.openminis.app.policy

/**
 * [OZEL 37080] Kural tablosu — **veri**, mantık değil.
 *
 * ## Neden ayrı dosya
 *
 * Bir güvenlik kuralı koda gömülürse üç şey bozulur: (1) kullanıcı neyin
 * engellendiğini okuyamaz, (2) yeni bir risk sınıfı eklendiğinde testler
 * kuralın kendisini değil kodun tamamını kırar, (3) `rg` ile "bu komut
 * engelleniyor mu" sorusu cevaplanamaz. Tablo olunca üçü de çözülüyor.
 *
 * ## Tabloya girmeyen şey
 *
 * Her komut buraya yazılmaz. Yalnız **etkisi geri alınamayan** ya da **yetkisi
 * sandbox dışına taşan** kalıplar. `ls`, `cat`, `rg`, `git status` burada
 * yok — bunlar için onay sormak kullanıcıyı "yine mi" noktasına getirir ve
 * gerçek uyarıyı görünmez yapar. Sessiz geçiş varsayılan, onay istisna.
 *
 * ## `note` alanı neden zorunlu
 *
 * Her kural tek cümleyle "bu neden riskli" sorusunu cevaplamak zorunda; bu
 * cümle onay kartındaki gerekçe satırı oluyor. Cümle yazılamıyorsa kural
 * yanlış tasarlanmış demektir — yani bu alan bir tasarım baskısı aracı.
 */
object RiskRules {

    /** Tetikleyici eşleşme tipleri. Path kuralları argümandan, diğerleri metinden. */
    enum class Match { REGEX, DESTRUCTIVE_PATH }

    data class Rule(
        val id: String,
        /** Komut parçasında aranan kalıp. */
        val pattern: Regex,
        val level: RiskLevel,
        val reach: Reach,
        val recovery: Recovery,
        val note: String,
        /**
         * Onay verilirse hangi hedef öneki için verilebilir. `true` ise
         * sınıflandırıcı komuttaki yolu çıkarır (`/var/minis/x` gibi);
         * `false` ise hedef komutun kendisidir (ör. `git push`).
         */
        val pathScoped: Boolean = false,
        val match: Match = Match.REGEX,
    )

    // --- Kalıcı silme -------------------------------------------------------
    private val RM = Regex("""(^|[\s;&|])rm\s""")
    private val RM_RECURSIVE = Regex("""(^|[\s;&|])rm\s[^;&|]*(-\w*r|--recursive)""")
    private val FIND_DELETE = Regex("""find\s[^;&|]*-delete""")
    private val DD = Regex("""(^|[\s;&|])dd\s""")
    private val MKFS = Regex("""(^|[\s;&|])mkfs(\.\w+)?\s""")

    // --- Git: uzak ve yerel -------------------------------------------------
    private val GIT_PUSH_FORCE = Regex("""git\s+push[^;&|]*(--force(?!-with-lease)|\s-f(\s|$))""")
    private val GIT_RESET_HARD = Regex("""git\s+reset\s+--hard""")
    private val GIT_CLEAN_F = Regex("""git\s+clean\s+[^;&|]*-[a-z]*f""")
    private val GIT_BRANCH_D = Regex("""git\s+branch\s+[^;&|]*-D""")

    // --- Yetki yükseltme / cihaz ------------------------------------------
    private val SUDO = Regex("""(^|[\s;&|])(sudo|su|doas)\s""")
    private val DEVICE_SETTINGS = Regex("""(^|[\s;&|])settings\s+(put|delete)\s""")
    private val DEVICE_PM_BAD = Regex("""\bpm\s+(uninstall|clear|disable-user|disable)\b""")
    private val DEVICE_AM = Regex("""\bam\s+(force-stop|kill)\b""")
    private val SHIZUKU_EXEC = Regex("""android-shizuku-cli\s+exec\b""")
    private val DEVICE_OTHER_CMDS = Regex("""(^|[\s;&|])(reboot|svc\s+(wifi|data|power)|input\s+keyevent\s+(26|POWER))\b""")

    // --- Ağ: dışarı veri ----------------------------------------------------
    private val NET_UPLOAD = Regex("""(curl\b[^;&|]*(-F\s|-T\s|--upload-file|--data-binary\s+@|--data\s+@)|wget\b[^;&|]*--post)""")
    private val NET_RAW = Regex("""(^|[\s;&|])(nc|ncat|netcat|socat)\s""")
    private val SSH_SCP = Regex("""(^|[\s;&|])(ssh|scp|rsync)\s""")
    private val CLOUD_CLI = Regex("""(^|[\s;&|])(gh|aws|gcloud|firebase|vercel|heroku|flyctl)\s""")

    // --- Kimlik bilgisi -----------------------------------------------------
    private val SECRET_READ = Regex(
        """(cat|less|head|tail|bat|rg|grep|strings)\b[^;&|]*(\.env\b|\.ssh/|id_rsa|id_ed25519|\.keystore|\.jks|\.p12|secrets?\.|credentials)""",
    )

    // --- Kurulum / paket ----------------------------------------------------
    private val PKG_INSTALL = Regex("""(^|[\s;&|])(apk|apt|apt-get|pip|pip3|npm|pnpm|yarn|cargo|gem|go)\s+(add|install|get|i)\b""")
    private val PKG_UNINSTALL = Regex("""(^|[\s;&|])(apk|apt|apt-get|pip|npm)\s+(del|remove|uninstall|rm)\b""")

    val ALL: List<Rule> = listOf(
        // Kalıcı silme — en üstte, çünkü en sık yapılan hata.
        Rule(
            id = "shell:rm-recursive",
            pattern = RM_RECURSIVE,
            level = RiskLevel.HIGH,
            reach = Reach.SANDBOX, // hedefe göre yükseltilir
            recovery = Recovery.PERMANENT,
            note = "dizin ağacını geri dönüşü olmadan siler",
            pathScoped = true,
            match = Match.DESTRUCTIVE_PATH,
        ),
        Rule(
            id = "shell:rm",
            pattern = RM,
            level = RiskLevel.MEDIUM,
            reach = Reach.SANDBOX,
            recovery = Recovery.PERMANENT,
            note = "dosyayı geri dönüşü olmadan siler",
            pathScoped = true,
            match = Match.DESTRUCTIVE_PATH,
        ),
        Rule(
            id = "shell:find-delete",
            pattern = FIND_DELETE,
            level = RiskLevel.HIGH,
            reach = Reach.SANDBOX,
            recovery = Recovery.PERMANENT,
            note = "kalıba uyan her dosyayı tek komutta siler; kalıp genişse sonuç öngörülemez",
            pathScoped = true,
            match = Match.DESTRUCTIVE_PATH,
        ),
        Rule("shell:dd", DD, RiskLevel.HIGH, Reach.DEVICE, Recovery.PERMANENT, "diske ham yazma; yanlış hedef cihazı kullanılamaz hâle getirir"),
        Rule("shell:mkfs", MKFS, RiskLevel.HIGH, Reach.DEVICE, Recovery.PERMANENT, "bölümü biçimlendirir; içindeki her şey gider"),

        // Git
        Rule("git:push-force", GIT_PUSH_FORCE, RiskLevel.HIGH, Reach.NETWORK, Recovery.PERMANENT, "uzak geçmişi ezer; başkasının commit'i sunucudan silinir ve geri getirilemez"),
        Rule("git:reset-hard", GIT_RESET_HARD, RiskLevel.MEDIUM, Reach.SANDBOX, Recovery.RECOVERABLE, "commit edilmemiş değişiklikleri atar; reflog'da iz kalır ama iş kaybolur"),
        Rule("git:clean-f", GIT_CLEAN_F, RiskLevel.MEDIUM, Reach.SANDBOX, Recovery.PERMANENT, "izlenmeyen dosyaları siler; git bunları hiç kaydetmediği için geri gelmez"),
        Rule("git:branch-D", GIT_BRANCH_D, RiskLevel.MEDIUM, Reach.SANDBOX, Recovery.RECOVERABLE, "merge edilmemiş dalı zorla siler; reflog'da kısa süre durur"),

        // Yetki
        Rule("device:sudo", SUDO, RiskLevel.HIGH, Reach.DEVICE, Recovery.PERMANENT, "kabuğu root yetkisiyle çalıştırır; sandbox sınırı kalkar"),
        Rule("device:settings", DEVICE_SETTINGS, RiskLevel.HIGH, Reach.DEVICE, Recovery.RECOVERABLE, "telefon geneli ayarı değiştirir; hangi uygulamaların ne yapacağını etkiler"),
        Rule("device:pm-bad", DEVICE_PM_BAD, RiskLevel.HIGH, Reach.DEVICE, Recovery.PERMANENT, "uygulamayı kaldırır ya da verisini siler; uygulamanın içindeki her şey gider"),
        Rule("device:am", DEVICE_AM, RiskLevel.LOW, Reach.DEVICE, Recovery.UNDOABLE, "çalışan uygulamayı durdurur; yeniden açınca döner"),
        Rule("device:power", DEVICE_OTHER_CMDS, RiskLevel.HIGH, Reach.DEVICE, Recovery.UNDOABLE, "cihazın kendisini (ağ, güç, kilit) kontrol eder"),
        Rule("device:shizuku-exec", SHIZUKU_EXEC, RiskLevel.MEDIUM, Reach.DEVICE, Recovery.RECOVERABLE, "Shizuku üzerinden kabuk komutu çalıştırır; etkisi sandbox'la sınırlı değil"),

        // Ağ
        Rule("net:upload", NET_UPLOAD, RiskLevel.HIGH, Reach.NETWORK, Recovery.PERMANENT, "yerel dosyayı dışarıya gönderir; gönderildikten sonra geri alınamaz"),
        Rule("net:raw", NET_RAW, RiskLevel.HIGH, Reach.NETWORK, Recovery.PERMANENT, "ham soket açar; ne gönderildiği denetlenemez"),
        Rule("net:ssh", SSH_SCP, RiskLevel.MEDIUM, Reach.NETWORK, Recovery.RECOVERABLE, "başka bir makinede komut çalıştırır ya da dosya taşır"),
        Rule("net:cloud-cli", CLOUD_CLI, RiskLevel.HIGH, Reach.ACCOUNT, Recovery.PERMANENT, "bulut hesabında işlem yapar; ücret doğurabilir veya canlı ortamı değiştirir"),

        // Kimlik
        Rule("credential:read", SECRET_READ, RiskLevel.HIGH, Reach.ACCOUNT, Recovery.UNDOABLE, "anahtar dosyasını okur; okunan değer model bağlamına girer ve oradan çıkmaz"),

        // Paket
        Rule("pkg:install", PKG_INSTALL, RiskLevel.LOW, Reach.SANDBOX, Recovery.UNDOABLE, "ortama yeni paket kurar; geri alınabilir, ama kaynağına güvenmek gerekir"),
        Rule("pkg:uninstall", PKG_UNINSTALL, RiskLevel.MEDIUM, Reach.SANDBOX, Recovery.RECOVERABLE, "kurulu paketi kaldırır; ona bağlı araçlar bozulabilir"),
    )

    /** Kural kimliğinden kuralı bulur (onay defteri çözümlemesi için). */
    fun byId(id: String): Rule? = ALL.firstOrNull { it.id == id }

    /**
     * Hiç sorulmadan reddedilen kalıplar.
     *
     * Buraya giren şeyin onay kartı **yok**, çünkü doğru cevap "evet" olamaz.
     * Kart göstermek kullanıcıya "belki" imkânı tanımak olur; bu kalıplarda
     * belki yok. Ajan da reddin gerekçesini alır ve etrafından dolaşabilir.
     */
    val DENY: List<Rule> = listOf(
        Rule("deny:root", Regex("""rm\s+[^;&|]*(-\w*r\w*\s+)?/(\s|$)"""), RiskLevel.HIGH, Reach.DEVICE, Recovery.PERMANENT, "kök dizini silmek sistemi anında çöker; kurtarma yolu yok"),
        Rule("deny:storage", Regex("""rm\s+[^;&|]*(-\w*r\w*\s+)?/storage/?(\s|$)"""), RiskLevel.HIGH, Reach.DEVICE, Recovery.PERMANENT, "tüm depolama alanını silmek fotoğraf, belge ve indirmeleri birlikte götürür"),
        Rule("deny:fork-bomb", Regex("""\w+\(\)\s*\{[^}]*\|\s*\w+[^}]*&"""), RiskLevel.HIGH, Reach.DEVICE, Recovery.UNDOABLE, "kendini çoğaltan süreç; cihazı kullanılamaz hâle getirir"),
        Rule("deny:pm-all", Regex("""pm\s+uninstall\s+(com\.openminis\.app|com\.veysel\.minis)\b"""), RiskLevel.HIGH, Reach.DEVICE, Recovery.PERMANENT, "uygulamanın kendisini kaldırmak; ajanı ve tüm verisini yok eder"),
    )
}
