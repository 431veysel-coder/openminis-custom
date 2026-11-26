package com.openminis.app.data.team

/**
 * [37129 · A1] **Bu sohbet hangi konseyi kullanır?**
 *
 * ## Neden bu sınıf var
 *
 * İki kolon var ve ikisi de farklı zamanlarda eklendi:
 * `folders.default_team_id` (`.15`) ve `sessions.team_id` (`.16`). İki kolonu
 * okuyan kodun kuralı **tek yerde** olmalı; iki yerde yazılırsa er geç ayrışır
 * (bu depoda tam olarak bu sınıf dört kez tekrar etti).
 *
 * ## Kural
 *
 * ```
 * sohbetin takımı  →  projenin varsayılanı  →  yok
 * ```
 *
 * ## Neden "boş dize" de yok sayılıyor
 *
 * Bu depoda `folder_id` için aynı sorun yaşandı: bazı kayıtlarda `NULL`, bazı
 * kayıtlarda `''` yazılmış. Birini dışlayan bir sorgu sessiz veri kaybı
 * üretiyor. Aynı hata iki kez yapılmayacak.
 *
 * ## Neden saf
 *
 * Veritabanı bilmiyor: iki `String?` alıp bir karar veriyor. Test için Room
 * ya da `Context` gerekmiyor.
 */
object SessionCouncil {

    /** The column value that should be stored for [picked]. Blank = clear. */
    fun columnValue(picked: String?): String? = picked?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Which team id this session should run, or null when it has none.
     *
     * @param sessionTeamId   `sessions.team_id` — an explicit per-chat choice
     * @param projectTeamId   `folders.default_team_id` — the project default
     */
    fun resolve(sessionTeamId: String?, projectTeamId: String?): String? =
        columnValue(sessionTeamId) ?: columnValue(projectTeamId)

    /**
     * True when the resolved team came from the project rather than from the
     * chat itself. The UI must say which — a member list that silently changes
     * because the project default changed would look like the app lost the
     * chat's own setting.
     */
    fun isInherited(sessionTeamId: String?, projectTeamId: String?): Boolean =
        columnValue(sessionTeamId) == null && columnValue(projectTeamId) != null

    /**
     * The three states the chat's council button can be in.
     *
     * Kept here rather than in the composable so the mapping is testable: a
     * button that shows "running" when nothing runs, or hides "stop" while a
     * run is live, is a UI bug the compiler cannot catch.
     */
    enum class Presence {
        /** No team resolved — the button offers to set one up. */
        NONE,

        /** A team resolves but nothing is running — the button offers Run/Edit. */
        IDLE,

        /** A run for this chat's team is live — the button opens it and offers Stop. */
        RUNNING,
    }

    fun presence(resolvedTeamId: String?, activeRunTeamId: String?): Presence = when {
        columnValue(resolvedTeamId) == null -> Presence.NONE
        columnValue(resolvedTeamId) == columnValue(activeRunTeamId) -> Presence.RUNNING
        else -> Presence.IDLE
    }
}
