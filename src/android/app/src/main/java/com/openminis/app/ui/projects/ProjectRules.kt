package com.openminis.app.ui.projects

/**
 * [37126 · Projeler] **Proje işlemlerinin kuralları** — saf, Android'siz.
 *
 * ## Neden
 *
 * Depo katmanı projeler için tam bir CRUD sunuyordu (`createFolder`,
 * `renameFolder`, `toggleFolderPin`, `dissolveFolder`) ama **Projeler ekranı
 * hiçbirini çağırmıyordu**: ekran salt-okunur bir listeydi. Ölçüm: dört
 * fonksiyonun da çağıranı yoktu. Kullanıcı proje oluşturmak için "Sohbetler"
 * ekranına gidip grup açmak zorundaydı — ayrı bir ekranda, ayrı bir adla.
 *
 * ## Neden kural ayrı ve testli
 *
 * İki karar hata yapmaya açık: (a) ad doğrulama, (b) **çözme (dissolve)**
 * cümlesinin şekli. İkincisi veri kaybettiren tek işlem: `dissolveFolder`
 * klasörü siler, içindeki sohbetleri **grupsuz bırakır**. Cümle yanlış
 * kurulursa kullanıcı ya "sohbetler de gidiyor" sanıp vazgeçer ya da tersini
 * sanıp sohbetlerinin yerini kaybeder.
 *
 * ## Neden cümle değil **şekil** döndürüyor
 *
 * Bu depoda bir kez aynı hatayı yaptım: saf çekirdek Türkçe cümle kurdu,
 * İngilizce arayüzde Türkçe göründü. Çekirdek artık dil bilmiyor — hangi
 * kalıbın kullanılacağını söylüyor ([DissolveShape]), cümleyi dize kaynağı
 * kuruyor. Böylece kural test edilebilir, çeviri ayrı kalır.
 */
object ProjectRules {

    const val NAME_MAX = 60

    /** `true` if [name] can be saved. Blank and over-limit names cannot. */
    fun isValidName(name: String): Boolean {
        val t = name.trim()
        return t.isNotEmpty() && t.length <= NAME_MAX
    }

    /** Normalised form to store; `null` when invalid (never store a bad name). */
    fun normalise(name: String): String? = name.trim().takeIf { isValidName(it) }

    /** Which dissolve sentence to render. Count decides, grammar follows. */
    enum class DissolveShape {
        /** No sessions inside — nothing is left unfiled. */
        EMPTY,

        /** Exactly one session — singular wording. */
        ONE,

        /** Two or more — plural wording. */
        MANY,
    }

    fun dissolveShape(sessions: Int): DissolveShape = when {
        sessions <= 0 -> DissolveShape.EMPTY
        sessions == 1 -> DissolveShape.ONE
        else -> DissolveShape.MANY
    }

    /** Liste satırında gösterilecek ad; boş ad yer tutucu alır. */
    fun isNameless(name: String): Boolean = name.trim().isEmpty()
}
