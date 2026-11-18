package com.openminis.app.policy

/**
 * [OZEL 37080] Komut → risk kararı. Saf fonksiyonlar, Android bağımlılığı yok.
 *
 * ## Neden "hedef" ayrı hesaplanıyor
 *
 * Kural tablosu `rm` der, ama `rm`'in riski sildiği şeye bağlı. Bu yüzden
 * kural eşleştikten sonra komutun **yolları** çıkarılıp hedefe göre seviye
 * düzeltiliyor. Tek bir "rm varsa yüksek" kuralı iki yönde de yanlış:
 * ajanın kendi `tmp` dizinini temizlemesini engeller (kullanıcıyı kart
 * yağmuruna tutar), `/var/minis/shared` silmesini serbest bırakır.
 *
 * ## Neden kapsam sessizce düşürülmüyor
 *
 * Hedefi bilinmeyen bir silme (ör. `rm -rf $DIR`) **en geniş** kapsamda
 * değerlendirilir. Değişkenden gelen yolu model çözemez; "bilmiyorum" cevabı
 * "güvenli" değil, "sor" olmalı.
 *
 * ## Neden parçalara bölünüyor
 *
 * `npm install && rm -rf dist` tek komut ama iki karar. Bölünmeden
 * sınıflandırılırsa ilk kural kazanır ve `rm` görünmez — tam da kaçınmak
 * istediğimiz şey. Bölme sonrası en ağır parça komutun kararı olur, gerekçeler
 * birleşir.
 */
class RiskClassifier(
    /** Bu oturumun çalışma dizini, ör. `/var/minis/minis-sessions/<id>`. */
    private val sessionDir: String,
) {

    /** Ajanın kendi çöpü sayılan alt dizinler — burada silme sessiz geçer. */
    private val scratchSubdirs = listOf("workspace", "tmp", ".cache", "offloads")

    /** Kullanıcı verisi: burada hiçbir silme sessiz geçmez. */
    private val userDataRoots = listOf(
        "/var/minis/shared",
        "/var/minis/attachments",
        "/var/minis/memory",
        "/var/minis/mounts",
        "/var/minis/skills",
    )

    private val deviceRoots = listOf("/storage", "/sdcard", "/data", "/system", "/etc", "/sdcard0")

    // ------------------------------------------------------------------ shell

    /** Tek bir kabuk komutunu, parçalarına ayırıp en ağır karara indirger. */
    fun classifyShell(command: String): RiskVerdict =
        classifyShellParts(command).maxByOrNull { rank(it) } ?: RiskVerdict.silent()

    /** Parça parça verdict — gerekçelerin hangi kuraldan geldiği kaybolmasın. */
    fun classifyShellParts(command: String): List<RiskVerdict> {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return emptyList()
        return splitSegments(trimmed).mapNotNull { segment -> classifySegment(segment) }
    }

    private fun classifySegment(segment: String): RiskVerdict? {
        // 1) Hiç sorulmadan reddedilenler önce — kart bile üretilmez.
        RiskRules.DENY.firstOrNull { it.pattern.containsMatchIn(segment) }?.let { deny ->
            return RiskVerdict(
                level = RiskLevel.HIGH,
                reach = deny.reach,
                recovery = deny.recovery,
                ruleId = deny.id,
                targetPrefix = "",
                reasons = listOf(RiskReason(RiskReason.Kind.EFFECT, deny.note)),
                segment = segment,
            )
        }

        // 2) Normal kurallar; tablo sıralı, ilk eşleşen kazanır (özel → genel).
        val rule = RiskRules.ALL.firstOrNull { it.pattern.containsMatchIn(segment) } ?: return null

        val targets = if (rule.pathScoped) extractPaths(segment) else emptyList()
        val reach = if (rule.pathScoped) targets.maxOfOrNull { reachOf(it) } ?: Reach.DEVICE
        else rule.reach

        val level = adjustLevel(rule, targets)

        val reasons = buildList {
            add(RiskReason(RiskReason.Kind.EFFECT, rule.note))
            targets.forEach { p ->
                add(RiskReason(RiskReason.Kind.TARGET, "$p (${reachLabel(reachOf(p))})"))
            }
            if (rule.pathScoped && targets.isEmpty()) {
                add(RiskReason(RiskReason.Kind.SCOPE, "hedef yol komut metninden çözülemedi; en geniş kapsam varsayıldı"))
            }
            if (rule.recovery == Recovery.PERMANENT) {
                add(RiskReason(RiskReason.Kind.RECOVERY, "geri dönüşü yok"))
            }
            if (rule.id.startsWith("credential")) {
                add(RiskReason(RiskReason.Kind.CREDENTIAL, "okunan değer model bağlamına girer"))
            }
            if (rule.reach == Reach.NETWORK || rule.reach == Reach.ACCOUNT) {
                add(RiskReason(RiskReason.Kind.EXTERNAL, "etki bu cihazın dışında"))
            }
        }

        return RiskVerdict(
            level = level,
            reach = reach,
            recovery = rule.recovery,
            ruleId = rule.id,
            targetPrefix = grantPrefixFor(targets.maxByOrNull { reachOf(it) } ?: ""),
            reasons = reasons.distinctBy { it.kind to it.text },
            segment = segment,
        )
    }

    /**
     * Seviye düzeltmesi — kuralın cezalandırıcı seviyesini hedefe göre esnetir.
     *
     * Buradaki tek serbestlik: ajanın kendi çöp dizininde silme **düşer**.
     * Yükseltme yok: hiçbir durumda kuralın seviyesi altına inilmez, yalnız
     * kendi çalışma alanı için LOW'a çekilir.
     */
    private fun adjustLevel(rule: RiskRules.Rule, targets: List<String>): RiskLevel {
        if (!rule.pathScoped) return rule.level
        if (targets.isEmpty()) return RiskLevel.HIGH // hedefi bilinmeyen silme
        val allScratch = targets.all { isScratch(it) }
        if (allScratch && rule.recovery == Recovery.PERMANENT) return RiskLevel.LOW
        val anyUserData = targets.any { p -> userDataRoots.any { p.startsWith(it) } }
        return when {
            anyUserData -> RiskLevel.HIGH
            targets.any { reachOf(it) >= Reach.DEVICE } -> RiskLevel.HIGH
            else -> rule.level
        }
    }

    /** Ajanın kendi çalışma alanındaki geçici dizin mi? */
    fun isScratch(path: String): Boolean {
        val p = normalize(path)
        if (!p.startsWith("$sessionDir/")) return false
        val rest = p.removePrefix("$sessionDir/")
        return scratchSubdirs.any { rest == it || rest.startsWith("$it/") }
    }

    fun reachOf(path: String): Reach {
        val p = normalize(path)
        if (p.isEmpty()) return Reach.DEVICE
        if (p.startsWith("$sessionDir/") || p == sessionDir) return Reach.SANDBOX
        if (deviceRoots.any { p == it || p.startsWith("$it/") }) return Reach.DEVICE
        if (userDataRoots.any { p == it || p.startsWith("$it/") }) return Reach.SESSION
        if (p.startsWith("/var/minis/")) return Reach.SESSION
        if (p.startsWith("/")) return Reach.DEVICE
        return Reach.SANDBOX // göreli yol: kabuk zaten oturum dizininde
    }

    // ------------------------------------------------------------------- araç

    /**
     * Araç düzeyi kapı: `file_write` / `file_edit` gibi kabuğa girmeyen yollar.
     *
     * `shell_execute` buraya girmez — o [classifyShell]'den geçer. Tarayıcı için
     * karar yok: tarayıcı zaten kullanıcının gördüğü bir pencerede çalışıyor.
     */
    fun classifyTool(tool: String, args: Map<String, String>): RiskVerdict? {
        val path = args["path"] ?: args["file_path"] ?: return null
        return when (tool) {
            "file_write", "file_edit" -> classifyWrite(path)
            else -> null
        }
    }

    fun classifyWrite(path: String): RiskVerdict {
        val reach = reachOf(path)
        val scratch = isScratch(path)
        val level = when {
            scratch -> RiskLevel.NONE
            reach == Reach.SANDBOX -> RiskLevel.LOW
            reach == Reach.SESSION -> RiskLevel.MEDIUM
            else -> RiskLevel.HIGH
        }
        val recovery = when {
            scratch -> Recovery.UNDOABLE
            reach <= Reach.SESSION -> Recovery.UNDOABLE
            else -> Recovery.RECOVERABLE
        }
        val reasons = buildList {
            add(RiskReason(RiskReason.Kind.TARGET, "$path (${reachLabel(reach)})"))
            add(RiskReason(RiskReason.Kind.EFFECT, "dosya içeriği değişecek"))
            if (recovery == Recovery.UNDOABLE) {
                add(RiskReason(RiskReason.Kind.RECOVERY, "journal eski içeriği geri getirebilir"))
            } else {
                add(RiskReason(RiskReason.Kind.RECOVERY, "journal bu konumu kapsamıyor"))
            }
        }
        return RiskVerdict(
            level = level,
            reach = reach,
            recovery = recovery,
            ruleId = "file:write",
            targetPrefix = grantPrefixFor(path),
            reasons = reasons,
            segment = path,
        )
    }

    // ------------------------------------------------------------------ yarf

    /**
     * Onayın bağlanacağı önek.
     *
     * Çok dar olursa her yeni dosya için yeniden sorulur (kullanıcı yorulur),
     * çok geniş olursa `/var/minis` onayı her şeyi kapsar (koruma biter).
     * Kural: bilinen bir kökün altında **ilk iki bileşen**, aksi hâlde tam yol.
     * Yani `/var/minis/shared/proje-a/src/x.kt` → `/var/minis/shared/proje-a`.
     */
    fun grantPrefixFor(path: String): String {
        val p = normalize(path)
        if (p.isEmpty()) return ""
        val parts = p.split('/').filter { it.isNotEmpty() }
        if (parts.size < 3) return p
        val rootDepth = when {
            parts.size >= 2 && parts[0] == "var" && parts[1] == "minis" -> 2
            parts[0] in listOf("storage", "sdcard", "data", "system", "etc") -> 1
            else -> 0
        }
        val keep = minOf(parts.size, rootDepth + 2)
        return "/" + parts.take(keep).joinToString("/")
    }

    // ----------------------------------------------------------- iç yardımcı

    private fun splitSegments(command: String): List<String> =
        command.split(Regex("""(?:&&|\|\||[;|\n])"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Komuttaki yol gibi görünen belirteçler. URL'ler ve seçenekler dışlanır. */
    private fun extractPaths(segment: String): List<String> {
        return segment.split(Regex("""\s+"""))
            .map { it.trim('\'', '"', '`') }
            .filter { tok ->
                tok.isNotEmpty() &&
                    !tok.startsWith("-") &&
                    !tok.contains("://") &&
                    (tok.startsWith("/") || tok.startsWith("~") || tok.startsWith("./") || tok.startsWith("../") ||
                        tok.contains("/") && !tok.contains("="))
            }
            .map { normalize(it) }
            .filter { it.isNotEmpty() && it != "/" && it != "~" }
            .distinct()
    }

    private fun normalize(path: String): String {
        var p = path.trim()
        if (p.startsWith("~")) p = "/root" + p.removePrefix("~")
        if (p.startsWith("/var/minis/workspace")) p = p // tarihsel kök, olduğu gibi
        if (!p.startsWith("/")) p = "$sessionDir/$p"
        // `.` ve `..` sadeleştirme — `rm -rf ./workspace/../shared` kaçamasın.
        val out = ArrayDeque<String>()
        p.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeLast()
                else -> out.addLast(part)
            }
        }
        return "/" + out.joinToString("/")
    }

    private fun rank(v: RiskVerdict): Int =
        v.level.ordinal * 100 + v.recovery.ordinal * 10 + v.reach.ordinal

    private fun reachLabel(r: Reach): String = when (r) {
        Reach.SANDBOX -> "yalnız çalışma dizini"
        Reach.SESSION -> "bu oturumun dosyaları"
        Reach.DEVICE -> "telefon geneli"
        Reach.NETWORK -> "ağ / dış dünya"
        Reach.ACCOUNT -> "hesap ve anahtarlar"
    }
}
