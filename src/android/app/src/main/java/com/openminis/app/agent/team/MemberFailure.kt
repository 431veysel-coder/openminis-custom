package com.openminis.app.agent.team

/**
 * [37099e · §1.1] **Üye neden düştü?** — arıza sınıflandırması.
 *
 * ## Neden gerekli (ölçülen arıza)
 *
 * Konsey hiç pozisyon üretemediğinde sonuç artık boş dönmüyor: üye üye
 * `absentWhy` listeleniyor. Ama gerekçe **tek satır serbest metin**ti; kota
 * hatası, bozuk JSON, boş cevap, model yokluğu — hepsi aynı cümleye
 * sıkışıyordu. Kullanıcı "konsey çalışmadı" diyor ve ne yapacağını bilmiyor:
 * kotaysa bekleyecek, model yoksa ekleyecek, JSON'sa modeli değiştirecek.
 *
 * ## Neden enum + gerekçe, yalnız metin değil
 *
 * Ekran ve istem aynı sınıfı farklı biçimde kullanıyor: ekran kısa etiket,
 * istem tam cümle. Tek kaynak olmazsa ikisi kaçınılmaz olarak ayrışır — bu
 * depoda aynı hata sınıfı dört PRoot kurucusunda görüldü.
 *
 * ## Neden `UNKNOWN` var
 *
 * Tanımadığımız bir hatayı tanıdık gibi göstermek, yanlış bir düzeltme
 * önerisinden kötüdür. `UNKNOWN` metni olduğu gibi taşır ve "bu sınıfı
 * bilmiyoruz" der.
 */
enum class MemberFailure(val label: String) {
    /** Sağlayıcı yok / anahtarı geçersiz / uygulama tam açılmamış. */
    NO_MODEL("model yok"),

    /** HTTP 401/403 — anahtar reddedildi. */
    AUTH("kimlik reddedildi"),

    /** HTTP 429 / kota — beklemek gerekir. */
    QUOTA("kota"),

    /** Zaman aşımı (kısmi metin korunmuş olabilir). */
    TIMEOUT("zaman aşımı"),

    /** Serbest akış yuvası yok — konsey cihazın sınırında. */
    NO_SLOT("akış yuvası yok"),

    /** Cevap geldi ama beklenen JSON çıkmadı. */
    BAD_JSON("JSON bozuk"),

    /** Cevap boş. */
    EMPTY("boş cevap"),

    /** Oturum açılamadı. */
    SESSION("oturum açılamadı"),

    /** Sınıflandırılamadı — metin olduğu gibi taşınır. */
    UNKNOWN("bilinmeyen"),
    ;

    /**
     * Bu sınıf için kullanıcıya **ne yapmalı** — tek satır.
     *
     * Neden burada: "kota" ile "model yok" arasındaki farkı ekranın bilmesi
     * gerekiyor, ve bu bilgi hatanın kendisinde değil, sınıfında.
     */
    val advice: String
        get() = when (this) {
            NO_MODEL -> "Ekibi düzenle: bu üyeye Providers'ta kurulu bir model seç."
            AUTH -> "Sağlayıcı anahtarını Ayarlar › Providers'ta yenile."
            QUOTA -> "Kotayı bekle ya da bu üye için başka bir sağlayıcı seç."
            TIMEOUT -> "Üye süresi doldu; turu küçült ya da daha hızlı bir model seç."
            NO_SLOT -> "Aynı anda açık üye sayısı cihazın sınırında; üye sayısını azalt."
            BAD_JSON -> "Model yapılandırılmış cevap veremedi; farklı bir model dene."
            EMPTY -> "Model boş döndü; sağlayıcıda bir sorun olabilir."
            SESSION -> "Üye oturumu açılamadı; koşuyu yeniden başlat."
            UNKNOWN -> "Gerekçeyi oku; sınıflandırılamayan bir hata."
        }

    companion object {
        /**
         * Serbest metin gerekçeyi sınıfa çevirir.
         *
         * Eşleşme **sıralı**: `NO_SLOT` metni "waited … for a free stream slot"
         * içerir ve "timed out" kelimesini de geçirebilir; önce daha özgül
         * kalıp denenir. Sıra tersine dönerse yuva hatası "zaman aşımı" diye
         * görünür ve kullanıcı süreyi uzatmaya çalışır — yanlış düzeltme.
         */
        fun classify(reason: String?): MemberFailure {
            val r = reason.orEmpty().lowercase()
            if (r.isBlank()) return EMPTY
            return when {
                r.contains("free stream slot") || r.contains("queued") -> NO_SLOT
                r.contains("kota") || r.contains("quota") || r.contains("429") ||
                    r.contains("rate limit") || r.contains("insufficient") -> QUOTA
                r.contains("401") || r.contains("403") || r.contains("unauthorized") ||
                    r.contains("invalid api key") || r.contains("authentication") -> AUTH
                r.contains("no usable model") || r.contains("pinned model is missing") -> NO_MODEL
                r.contains("not fully started") -> NO_MODEL
                r.contains("session") -> SESSION
                r.contains("timed out") || r.contains("timeout") -> TIMEOUT
                r.contains("json") -> BAD_JSON
                r.contains("no report") || r.contains("boş cevap") || r.contains("empty") -> EMPTY
                else -> UNKNOWN
            }
        }

        /** Ekranda tek satır: `kota · ham gerekçe`. */
        fun line(reason: String?): String {
            val f = classify(reason)
            val raw = reason.orEmpty().trim()
            return if (raw.isEmpty()) f.label else "${f.label}: $raw"
        }
    }
}
