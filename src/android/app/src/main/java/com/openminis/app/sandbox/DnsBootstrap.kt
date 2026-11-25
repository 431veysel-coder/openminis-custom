package com.openminis.app.sandbox

import java.io.File
import java.io.IOException

/**
 * [37120] **Konuk kökünün DNS'i** — her oturumda tekrar bozulan şeyin kalıcı onarımı.
 *
 * ## Ölçülen arıza
 *
 * Sandbox'ta `getent`/python `getaddrinfo` `github.com`'u çözemedi, `curl`
 * çözebildi; push/indirme her yeni oturumda kırıldı. Çözüm `/etc/hosts`'a elle
 * IP yazmaktı — **oturumluk**: bir sonraki açılışta yine boş.
 *
 * ## Neden Android'in sistem DNS'i değil, konuk kökünün kendi dosyası
 *
 * Uygulama sistem DNS'ini değiştirme yetkisine sahip değil (ve olmamalı: aynı
 * değişiklik cihazdaki bütün uygulamaları etkiler). Konuk kökünün
 * `etc/resolv.conf`'u **bizim** dosyamız; oraya yazmak ne cihazı ne kullanıcının
 * VPN'ini bozar.
 *
 * ## Üç mod, çünkü "tek doğru resolver" yok
 *
 * - [Mode.AUTO] (varsayılan): dosyada **hiç** `nameserver` yoksa yaz, varsa
 *   **dokunma**. Kullanıcı bir resolver seçtiyse (ya da vekil koyduysa) onu
 *   ezmek, teşhis edilmesi zor bir "neden bu ağda çalışmıyor" üretir.
 * - [Mode.DIRECT]: her zaman kendi bloğunu yazar (1.1.1.1 + 8.8.8.8). Fake-IP
 *   resolver'ın adı çözemediği ağlarda gereken şey bu.
 * - [Mode.FAKEIP]: bloğu **kaldırır**. Bazı VPN'ler 198.18/15 fake-IP aralığını
 *   kullanır ve sabit resolver o aralığı atlarsa bağlantı kopar. Bu mod "ben
 *   karışmıyorum" demenin adı.
 *
 * ## Neden işaretli blok
 *
 * Kullanıcının (ya da bir aracın) yazdığı satırları silmemek için yalnız
 * `# >>> minis-dns` ile `# <<< minis-dns` arasına yazılır. Blok **tektir**:
 * ikinci çalıştırma eski bloğu değiştirir, yenisini eklemez. Bu olmadan her
 * açılışta dosya büyür ve bir süre sonra `resolv.conf` okunamaz hâle gelir.
 */
object DnsBootstrap {

    const val BEGIN = "# >>> minis-dns"
    const val END = "# <<< minis-dns"

    /** Konuk kökünde `resolv.conf` yolu. */
    fun resolvPath(rootfsDir: File): File = File(rootfsDir, "etc/resolv.conf")

    enum class Mode(val key: String) {
        AUTO("auto"),
        DIRECT("direct"),
        FAKEIP("fakeip"),
        ;

        companion object {
            /**
             * Bilinmeyen değer [AUTO]'ya düşer: bozuk bir tercih yüzünden
             * kullanıcının kendi resolver'ını ezmek, hiçbir şey yapmamaktan
             * daha kötü.
             */
            fun parse(raw: String?): Mode =
                entries.firstOrNull { it.key == raw?.trim() } ?: AUTO
        }
    }

    /** Yeni içerik + değişti mi. */
    data class Plan(val content: String, val changed: Boolean, val reason: String)

    /** Blok gövdesi. */
    private const val BLOCK_BODY = """nameserver 1.1.1.1
nameserver 8.8.8.8
"""

    /**
     * Dosyanın yeni hâlini hesaplar — **saf**, IO yok.
     *
     * @param existing dosyanın şu anki içeriği (`""` = dosya yok)
     */
    fun plan(existing: String, mode: Mode): Plan {
        val withoutBlock = stripBlock(existing)
        return when (mode) {
            Mode.FAKEIP -> {
                val trimmed = withoutBlock.trimEnd()
                val content = if (trimmed.isEmpty()) "" else trimmed + "\n"
                Plan(content, changed = content != existing, reason = "block removed (fake-ip mode)")
            }

            Mode.AUTO -> when {
                // GERÇEK HATA (bu turda yakalandı): kontrol `withoutBlock`
                // üzerinde yapılıyordu, yani **kendi bloğumuz** çıkarıldıktan
                // sonra "nameserver yok" görünüyordu ve her çalıştırmada blok
                // yeniden yazılıyordu (`changed=true`, içerik aynı). Bu, her
                // açılışta disk yazımı demek; sessiz ama gerçek bir israf.
                existing.contains(BEGIN) ->
                    Plan(existing, changed = false, reason = "our block is already in place")
                hasNameserver(withoutBlock) ->
                    Plan(existing, changed = false, reason = "a nameserver already exists; left alone")
                else -> replaced(withoutBlock, "no nameserver found")
            }

            Mode.DIRECT -> replaced(withoutBlock, "direct resolver pinned")
        }
    }

    private fun replaced(withoutBlock: String, reason: String): Plan {
        val head = withoutBlock.trimEnd()
        val content = buildString {
            if (head.isNotEmpty()) {
                append(head)
                append('\n')
            }
            append('\n')
            append(BEGIN).append('\n')
            append("# [37120] Minis X — konuk kökü resolver. Bu blok tekrar yazılabilir;\n")
            append("# kendi nameserver satırlarını bu bloğun DIŞINA yaz.\n")
            append(BLOCK_BODY)
            append(END).append('\n')
        }
        return Plan(content, changed = content != withoutBlock, reason = reason)
    }

    /** İçerikte gerçek bir `nameserver` satırı var mı? (Yorum satırları sayılmaz.) */
    fun hasNameserver(content: String): Boolean =
        content.lineSequence().any { line ->
            val t = line.trim()
            !t.startsWith("#") && t.startsWith("nameserver") && t.length > "nameserver".length
        }

    /**
     * İşaretli bloğu (ve varsa yarım kalmış başını) çıkarır.
     *
     * Yarım blok: BEGIN var, END yok. Bu, yazma sırasında süreç öldüyse olur.
     * Kalan her şeyi silmek yerine BEGIN'den dosya sonuna kadar atılır —
     * sonrasında zaten yalnız bizim satırlarımız vardı.
     */
    fun stripBlock(content: String): String {
        if (!content.contains(BEGIN)) return content
        val out = StringBuilder()
        var inBlock = false
        content.lineSequence().forEach { line ->
            if (line.trim() == BEGIN) {
                inBlock = true
                return@forEach
            }
            if (inBlock) {
                if (line.trim() == END) inBlock = false
                return@forEach
            }
            if (out.isNotEmpty()) out.append('\n')
            out.append(line)
        }
        return out.toString()
    }

    /**
     * `resolv.conf`'u uygular. Var olan kurulumlarda da çağrılabilir (idempotent).
     *
     * @return yazıldıysa `true`; içerik zaten doğruysa ya da dosya sistemi izin
     *   vermediyse `false` — ikisi **aynı** cevap değil, o yüzden [Plan.reason]
     *   çağıranın günlüğüne yazılır.
     */
    fun ensure(rootfsDir: File, mode: Mode = Mode.AUTO): Plan {
        val file = resolvPath(rootfsDir)
        val existing = if (file.isFile) {
            runCatching { file.readText() }.getOrElse { "" }
        } else {
            ""
        }
        val plan = plan(existing, mode)
        if (!plan.changed) return plan
        if (plan.content.isEmpty()) {
            // FAKEIP + boş dosya: yazacak bir şey yok, dosya da yaratılmaz.
            return plan.copy(changed = false, reason = "nothing to write")
        }
        return try {
            file.parentFile?.mkdirs()
            file.writeText(plan.content)
            plan
        } catch (e: IOException) {
            plan.copy(changed = false, reason = "write failed: ${e.message}")
        }
    }
}
