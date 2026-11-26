package com.openminis.app.agent.team

/**
 * [37126] **Diskteki matris → ekranın satır modeli.**
 *
 * ## Neden ayrı
 *
 * Karşılaştırma odası `CouncilLiveRegistry.ClaimLine` çiziyor (UI modeli);
 * diskte ise `Claim` duruyor (çekirdek modeli). İkisini ekranın içinde
 * birleştirmeye çalıştım ve derleme düştü: `live?.claims ?: recorded?.claims`
 * iki farklı liste tipini birleştirmeye çalışıyor. Doğru yer burası —
 * dönüşüm tek yerde, saf ve testli.
 *
 * ## Neden `verdict` boş
 *
 * `ClaimLine.verdict` oda içinde **çizilmiyor**; yalnız gündemde kullanılıyor.
 * Kayıttaki başlık iddia başına karar taşımıyor (yalnız toplam sayaçlar:
 * unanimous/decided/contested/unresolved). Buraya bir karar **uydurmak**
 * yerine boş bırakıyorum ve bunu yazıyorum: tablo duruşları doğru gösterir,
 * karar sütununu göstermez — çünkü o bilgi kayıtta yok.
 */
object RecordedMatrix {

    /** Order preserved: the chair's claim order is meaningful (C1, C2, …). */
    fun claimLines(matrix: ClaimMatrix?): List<CouncilLiveRegistry.ClaimLine> =
        matrix?.claims.orEmpty().map { c ->
            CouncilLiveRegistry.ClaimLine(
                id = c.id,
                text = c.text,
                support = c.support,
                oppose = c.oppose,
                uncertain = c.uncertain,
                verdict = "",
                stances = c.stances.mapValues { it.value.wire },
            )
        }
}
