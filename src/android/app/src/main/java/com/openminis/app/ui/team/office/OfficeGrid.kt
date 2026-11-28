package com.openminis.app.ui.team.office

import com.openminis.app.agent.team.CouncilLiveRegistry

/**
 * [F2 · .58] **Ofis ızgarası** — yerleşim ve durum matematiği, **saf**.
 *
 * ## Neden ayrı sınıf
 *
 * Izgara çizmek kolay, ızgaranın **taşmaması** zor. 5 üye 2 sütuna sığar, 7 üye
 * sığmaz; bunu Composable içinde `if (count > 6)` diye yazmak, her yeni kadro
 * boyutunda yeniden bakmayı gerektirir. Burada tek yerde ve testli.
 *
 * ## `memberState` neden burada, neden UI'da değil
 *
 * Aynı üyeye iki yerde iki farklı etiket vermek, bu depoda kınanan sınıf.
 * Üç yüzey de (Bakış satırı, Kat kutusu, adım modu) **bu** fonksiyondan geçer;
 * etiketi her yüzey kendi `when`'i ile üretirse birinde "sırada", diğerinde
 * "bekliyor" yazar ve kullanıcı hangisine inanacağını bilemez.
 *
 * ## "Düşünüyor" neden yok
 *
 * Veride yok. Hangi üyenin o an modelden yanıt beklediği bilinmiyor; tahmin
 * etmek ileride yanlış teşhis ettirir (İ4).
 */
object OfficeGrid {

    /** Bir üyenin kutuda görünen durumu — **tek** sıralı tanım. */
    enum class State {
        /** Gelmedi (model yok, çağrı düştü). */
        ABSENT,

        /** Şu an bir araç çalıştırıyor (araç adı `detail`te). */
        TOOL,

        /** Dalga sırası gelmedi; koşu üyeyi henüz çağırmadı. */
        QUEUED,

        /** Bu turda cevabını verdi (iddiası var). */
        ANSWERED,

        /** Hiçbiri değil: bekliyor / henüz tur başlamadı. */
        IDLE,
    }

    /**
     * Üye durumunu hesapla.
     *
     * @param waveIndex üyenin kadrodaki sırası (dalga hesabı için)
     * @param waveSize dalga boyutu (`0` = bilinmiyor → sırada sayılmaz)
     * @param roundActive koşu `POSITIONS` fazında mı
     */
    fun stateOf(
        member: CouncilLiveRegistry.MemberLive,
        waveIndex: Int,
        waveSize: Int,
        roundActive: Boolean,
    ): State = when {
        member.absent -> State.ABSENT
        member.tool != null -> State.TOOL
        // Sırada: dalgası henüz gelmedi VE ne araç çalıştırıyor ne cevap verdi.
        // `waveSize <= 0` iken bu karar verilemez — "bilmiyorum"u "sırada"
        // diye yazmak uydurma olurdu.
        roundActive && waveSize > 0 && waveIndex >= waveSize &&
            member.claim.isBlank() -> State.QUEUED
        member.claim.isNotBlank() -> State.ANSWERED
        else -> State.IDLE
    }

    /**
     * Kaç sütun? Genişlik ve üye sayısına göre.
     *
     * Kural: kutu en az [MIN_COLUMN_DP] geniş olmalı; sığdığı kadar sütun.
     * Üye sayısı sütun sayısından azsa **fazla sütun açılmaz** (tek üyeli
     * koşuda iki sütun, sağda boş yarım ekran demektir).
     */
    fun columns(availableDp: Int, memberCount: Int): Int {
        if (memberCount <= 0) return 0
        val fit = (availableDp / MIN_COLUMN_DP).coerceAtLeast(1)
        return fit.coerceAtMost(memberCount).coerceAtMost(MAX_COLUMNS)
    }

    /** Kutu genişliği (dp) — sütun sayısına bölünmüş, boşluk düşülmüş. */
    fun columnWidthDp(availableDp: Int, columns: Int, gapDp: Int = GAP_DP): Int {
        if (columns <= 0) return availableDp
        val totalGap = gapDp * (columns - 1)
        return ((availableDp - totalGap) / columns).coerceAtLeast(1)
    }

    /** Bir kutu en az bu kadar geniş olmalı; altında ad + durum sığmaz. */
    const val MIN_COLUMN_DP = 156

    /** Bu sütundan fazlası telefonda okunmaz (yatay tablette bile). */
    const val MAX_COLUMNS = 4

    const val GAP_DP = 8
}
