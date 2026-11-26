package com.openminis.app.ui.team

import com.openminis.app.agent.team.MemberToolBelt

/**
 * [37127 · Roller] **Rolün ne verdiğini kullanıcıya söylemek.**
 *
 * ## Bulgu
 *
 * Rol sistemi zaten iyiydi: dört ön ayar (`researcher`, `engineer`, `reviewer`,
 * `critic`), Türkçe eş adları (`araştırmacı`, `yazılımcı`, …) ve "boş = rol
 * karar verir" kuralı. Ama **kullanıcı bunu göremiyordu**: editörde yalnız
 * "Rol" alanı ve genel bir ipucu vardı; hangi rolün hangi araçları açtığı
 * hiçbir yerde yazmıyordu.
 *
 * İki türlü zarar: (a) kullanıcı `araştırmacı` yazıp `shell_execute` bekliyor ve
 * aracın neden yok olduğunu anlamıyor, (b) **tanınmayan** bir rol yazdığında
 * sessizce **genel araç setini** alıyor — `MemberToolBelt`'in kendi KDoc'u bu
 * tuzağı "silently get the generalist belt" diye adlandırıyor.
 *
 * ## Neden saf ve neden kendi tablosu yok
 *
 * Cümle değil **anahtar** döndürüyor (dil bilmez). Araç listesini
 * `MemberToolBelt.rolePreset`'ten alıyor: ikinci bir doğruluk kaynağı
 * yaratmıyor. Eş ad tablosunu burada tekrar yazmak, "iki liste er geç
 * ayrışır" hatasının tam kendisi olurdu.
 */
object RoleHint {

    /** Editörün rol alanının altında ne söyleyeceği. */
    enum class Kind {
        /** Rol boş: üye tam üye kemerini alır. */
        NO_ROLE,

        /** Açık araç seçimi var: rol araç listesini belirlemiyor. */
        EXPLICIT_TOOLS,

        /** Bilinen bir ön ayar eşleşti. */
        PRESET,

        /**
         * Metin boş değil ama hiçbir ön ayarla eşleşmedi. Üye yine çalışır —
         * **genel** araç setiyle — ve kullanıcıya bunun söylenmesi şart;
         * alternatif, beklemediği izinleri sessizce vermek.
         */
        UNKNOWN,
    }

    data class Resolved(val kind: Kind, val tools: List<String>)

    fun of(role: String, declaredTools: List<String> = emptyList()): Resolved {
        if (declaredTools.isNotEmpty()) {
            return Resolved(Kind.EXPLICIT_TOOLS, declaredTools.map { it.trim() }
                .filter { it.isNotEmpty() }.sorted())
        }
        val trimmed = role.trim()
        if (trimmed.isEmpty()) return Resolved(Kind.NO_ROLE, emptyList())
        val preset = MemberToolBelt.rolePreset(trimmed)
        return if (preset == null) {
            Resolved(Kind.UNKNOWN, emptyList())
        } else {
            Resolved(Kind.PRESET, preset.sorted())
        }
    }

    /**
     * Araç listesini tek satıra sığdır. Uzun kemer **sessizce kesilmez**:
     * "browser_use, file_read +7" kullanıcıya devamı olduğunu söyler, kesilmiş
     * bir listeyi tam sanmasını engeller.
     */
    fun toolSummary(tools: List<String>, keep: Int = 4): String {
        if (tools.isEmpty()) return ""
        if (tools.size <= keep) return tools.joinToString(", ")
        return tools.take(keep).joinToString(", ") + " +${tools.size - keep}"
    }
}
