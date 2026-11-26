package com.openminis.app.ui.team

/**
 * [37125 · §8.4] **Ekran okuyucu etiketleri** — saf, Android'siz.
 *
 * ## Neden ayrı ve neden test
 *
 * Matris hücresi ekranda tek bir karakter: `✓`. TalkBack onu "onay işareti"
 * diye okur — hangi üye, hangi iddia, hangi duruş: hiçbiri. Görme engelli bir
 * kullanıcı için tablo **tamamen okunamaz**; oysa tablonun tüm bilgisi tek
 * karakterlerde.
 *
 * Cümleyi burada kurmak iki şeyi garanti ediyor: (a) test edilebiliyor
 * (bu cihazda emülatör yok), (b) iki yerde iki farklı cümle üretilemiyor.
 *
 * ## Neden dil sabit değil
 *
 * Etiket **veriden** üretiliyor, çeviriden değil: üye adları ve iddia metni
 * kullanıcının kendi dili. Yalnız duruş kelimesi yerelleştiriliyor ve o da
 * çağıran taraftan geçiyor ([stanceWord]).
 */
object TeamA11y {

    /** Bir matris hücresinin ekran okuyucu etiketi. */
    fun cellLabel(
        member: String,
        claimId: String,
        stance: ClaimMatrixLayout.Stance?,
        supportWord: String,
        opposeWord: String,
        uncertainWord: String,
        emptyWord: String,
    ): String {
        val word = when (stance) {
            ClaimMatrixLayout.Stance.SUPPORT -> supportWord
            ClaimMatrixLayout.Stance.OPPOSE -> opposeWord
            ClaimMatrixLayout.Stance.UNCERTAIN -> uncertainWord
            null -> emptyWord
        }
        return "$member · $claimId · $word"
    }

    /** Sekme etiketi: seçili olan söylenmeli, yoksa TalkBack nerede olduğunu bilmez. */
    fun tabLabel(text: String, selected: Boolean, selectedWord: String): String =
        if (selected) "$text, $selectedWord" else text

    /** Üye kartındaki araç rozeti — "browser" tek başına cümle değil. */
    fun toolBadge(tool: String?, member: String, workingWord: String): String =
        if (tool.isNullOrBlank()) "$member · $workingWord" else "$member · $tool"

    /**
     * Boş durum + **eylem** ([37125 · §8.1]).
     *
     * Neden: boş durum "neden boş" diyordu ama "ne yapayım" demiyordu.
     * Kullanıcı altı odadan birine girip tek cümle görüp çıkıyordu. Etiket,
     * düğmenin ne yapacağını söyler; boş cümleyi tekrar etmez.
     */
    data class EmptyState(val text: String, val actionLabel: String?)

    fun emptyState(text: String, actionLabel: String?): EmptyState =
        EmptyState(text = text, actionLabel = actionLabel?.takeIf { it.isNotBlank() })
}
