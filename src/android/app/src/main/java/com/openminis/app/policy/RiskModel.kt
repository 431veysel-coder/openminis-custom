package com.openminis.app.policy

/**
 * [OZEL 37080] Onay kapısının veri modeli.
 *
 * ## Neden dört ayrı eksen
 *
 * "Riskli mi" tek bir sayıyla cevaplanamaz. Aynı komut iki durumda tamamen
 * farklı şeylerdir:
 *
 * - `rm -rf /var/minis/minis-sessions/<bu oturum>/tmp` — ajanın kendi çöpü.
 * - `rm -rf /var/minis/shared` — kullanıcının aylardır biriktirdiği dosyalar.
 *
 * İkisi de "rm -rf". Ayrımı yapan **[Reach]** (nereye uzanıyor) ve
 * **[Recovery]** (geri dönebilir mi). Bir onay kartı bu ikisini yazmazsa
 * kullanıcı ya her şeye "evet" der ya her şeye "hayır" — ikisi de bilgi eksikliği
 * yüzünden verilmiş yanlış karar.
 *
 * ## Seviye ≠ karar
 *
 * [RiskLevel] yalnızca sıralama ve öncelik için. Karar [ApprovalPolicy]'de
 * veriliyor; çünkü karar, seviyeden çok **kapsam** ve **geri dönüş**e bağlı.
 * `pip install` seviye olarak düşük ama geri dönüşü yok sayılmaz — yine de
 * her seferinde sormak işi öldürür. Bu yüzden seviye tek başına yeterli değil.
 */

/** Sıralanabilir ciddiyet. [NONE] sessiz geçiş, [HIGH] onay ister. */
enum class RiskLevel { NONE, LOW, MEDIUM, HIGH }

/**
 * Etki alanı — "yetki" sorusunun cevabı: bu işlem nereye dokunabiliyor?
 *
 * Sandbox → oturum → cihaz → ağ → hesap sırası, sandbox'tan dışarı açılan
 * halkalar. Her halka bir üsttekinin yetkisini miras almaz; `DEVICE`
 * erişimi olan bir komut `SANDBOX` sayılamaz.
 */
enum class Reach {
    /** Yalnız ajanın kendi çalışma dizini. En dar. */
    SANDBOX,

    /** Uygulamanın bu oturuma ait dosyaları (offloads, attachments). */
    SESSION,

    /** Telefonun geri kalanı: Shizuku, erişilebilirlik, /storage. */
    DEVICE,

    /** Dış dünya: indirme, yükleme, push, ssh. */
    NETWORK,

    /** Kimlik bilgileri, anahtarlar, para. En geniş. */
    ACCOUNT,
}

/**
 * Geri dönüş sınıfı — kullanıcının gerçekten bilmesi gereken tek şey.
 *
 * [UNDOABLE]: journal ters kaydı ya da dosya içeriği elimizde.
 * [RECOVERABLE]: geri gelir ama bedava değil (reflog, yedek, yeniden kurulum).
 * [PERMANENT]: gelmez. Bu sınıf **asla** oturumdan uzun yaşayan onay almaz.
 */
enum class Recovery { UNDOABLE, RECOVERABLE, PERMANENT }

/** Tek bir gerekçe satırı. Kartta aynen görünür, bu yüzden cümle olmalı. */
data class RiskReason(val kind: Kind, val text: String) {

    enum class Kind {
        /** Neyi hedefliyor ("/var/minis/shared dizini"). */
        TARGET,

        /** Ne yapıyor ("geri dönüşü olmayan silme"). */
        EFFECT,

        /** Ne kadar uzağa ("telefon geneli ayar"). */
        SCOPE,

        /** Geri dönüş durumu ("journal bu dosyayı geri getiremez"). */
        RECOVERY,

        /** Dışarıya veri çıkıyor ("dosya karşı tarafa yükleniyor"). */
        EXTERNAL,

        /** Anahtar/kimlik okunuyor. */
        CREDENTIAL,
    }
}

/**
 * Sınıflandırıcının çıktısı. Değişmez; onay kartı ve günlük bu nesneden üretilir.
 *
 * [ruleId] onay defterinin anahtarı: kullanıcı "bir daha sorma" dediğinde
 * saklanan şey komut metni değil, bu kimlik + [targetPrefix]. Sebep: komut
 * metni her seferinde farklı (dosya adı değişir) → hiç eşleşmez; ruleId tek
 * başına eşleşirse `rm` onayı `rm /` için de geçerli olur → felaket.
 */
data class RiskVerdict(
    val level: RiskLevel,
    val reach: Reach,
    val recovery: Recovery,
    val ruleId: String,
    val targetPrefix: String,
    val reasons: List<RiskReason>,
    /** Eşleşen ham parça (kayıt için; kartta kullanılmaz). */
    val segment: String,
) {

    fun reasonsText(): String = reasons.joinToString(" · ") { it.text }

    /** İki parçalı bir komutta daha ağır olanı seçer. */
    fun worstWith(other: RiskVerdict): RiskVerdict {
        if (other.level > level) return other.copy(reasons = reasons + other.reasons)
        if (other.level < level) return copy(reasons = reasons + other.reasons)
        // Aynı seviyede: geri dönüşü olmayan ve daha geniş olan kazanır.
        val useOther = other.recovery > recovery || (other.recovery == recovery && other.reach > reach)
        return (if (useOther) other else this).copy(
            reasons = (reasons + other.reasons).distinctBy { it.kind to it.text },
        )
    }

    companion object {
        fun silent(): RiskVerdict = RiskVerdict(
            level = RiskLevel.NONE,
            reach = Reach.SANDBOX,
            recovery = Recovery.UNDOABLE,
            ruleId = "none",
            targetPrefix = "",
            reasons = emptyList(),
            segment = "",
        )
    }
}

/**
 * Ajanın beyan ettiği amaç. **Boş olabilir** — ve boş olması bir bilgidir.
 *
 * Onay kartında amaç satırı boşsa kart şunu yazar: "amaç belirtilmedi".
 * Bu, kullanıcıya "model ne yaptığını bilmiyor" sinyalini verir; uydurulmuş
 * bir amaç cümlesinden çok daha dürüst. Uydurma cümle üretmek cazip görünür
 * ama onay kartının tek işlevi kullanıcıyı yanlış rahatlatmamak.
 */
data class Purpose(val text: String?, val source: Source) {

    enum class Source {
        /** Ajan `intent` alanını doldurdu. */
        AGENT_STATED,

        /** Yalnız araç başlığı var ("Revizyon geçmişini temizle"). */
        TOOL_TITLE_ONLY,

        /** Hiçbiri yok. */
        MISSING,
    }

    val render: String
        get() = when (source) {
            Source.AGENT_STATED -> text?.trim().orEmpty()
            Source.TOOL_TITLE_ONLY -> "${text?.trim().orEmpty()} (ayrıntı verilmedi)"
            Source.MISSING -> "amaç belirtilmedi"
        }

    val weak: Boolean get() = source != Source.AGENT_STATED
}
