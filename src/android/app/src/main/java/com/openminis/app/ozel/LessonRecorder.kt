package com.openminis.app.ozel

import android.content.Context
import com.openminis.app.agent.lesson.CorrectionSignal
import com.openminis.app.agent.lesson.LessonDetector
import com.openminis.app.agent.lesson.LessonProposal
import com.openminis.app.agent.lesson.LoopSignal
import com.openminis.app.agent.lesson.ToolErrorSignal
import com.openminis.app.data.OzelFeatures
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * [37086] Ders defterinin **toplayıcısı**.
 *
 * `LessonDetector` saf: sinyalleri alır, öneri üretir. Bu nesne sinyalleri
 * oturum başına toplar ve arayüzün okuyacağı tek bir akışa indirger.
 *
 * ## Neden tur başına en fazla bir öneri
 *
 * Üç ders birden göstermek, kullanıcının hepsini okumadan geçmesine ve
 * özelliğin kapatılmasına yol açar. Bu yüzden `propose()` en yüksek güvenli
 * olanı seçer; diğerleri bir sonraki turda sıraya girer (`backlog`).
 *
 * ## Neden kendiliğinden hafızaya yazmıyor
 *
 * Bir ajanın kendi hatasını "kural" olarak kaydetmesi, hatayı kalıcılaştırmanın
 * en hızlı yolu. Onay kartı çıkar; kullanıcı onaylarsa normal `memory_write`
 * yolundan girer.
 */
object LessonRecorder {

    private data class Bucket(
        val errors: MutableList<ToolErrorSignal> = mutableListOf(),
        val corrections: MutableList<CorrectionSignal> = mutableListOf(),
        val loops: MutableList<LoopSignal> = mutableListOf(),
        val shown: MutableSet<String> = mutableSetOf(),
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** Arayüzün gösterdiği öneri. Oturum başına tek. */
    private val _proposal = MutableStateFlow<Map<String, LessonProposal>>(emptyMap())
    val proposal: StateFlow<Map<String, LessonProposal>> = _proposal.asStateFlow()

    private fun bucket(sessionId: String): Bucket = buckets.getOrPut(sessionId) { Bucket() }

    /** Araç başarısız olduğunda çağrılır. */
    fun noteToolError(ctx: Context, sessionId: String, tool: String, message: String, turnIndex: Int) {
        if (!OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.LESSON_BOOK)) return
        runCatching {
            val b = bucket(sessionId)
            b.errors += ToolErrorSignal(tool, message, turnIndex, command = "")
            // Bound the buffer: a long run can fail hundreds of times and only
            // the recent window matters (LessonDetector looks at 3 turns).
            if (b.errors.size > 40) b.errors.removeAt(0)
            refresh(sessionId)
        }
    }

    /** Kullanıcı ajanı düzelttiğinde çağrılır. */
    fun noteCorrection(ctx: Context, sessionId: String, text: String, turnIndex: Int) {
        if (!OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.LESSON_BOOK)) return
        runCatching {
            val b = bucket(sessionId)
            b.corrections += CorrectionSignal(text, turnIndex)
            if (b.corrections.size > 20) b.corrections.removeAt(0)
            refresh(sessionId)
        }
    }

    fun noteLoop(ctx: Context, sessionId: String, turnIndex: Int, repeatedCalls: Int) {
        if (!OzelFeatures.isEnabled(ctx, OzelFeatures.Feature.LESSON_BOOK)) return
        runCatching {
            val b = bucket(sessionId)
            b.loops += LoopSignal(turnIndex, repeatedCalls)
            if (b.loops.size > 10) b.loops.removeAt(0)
            refresh(sessionId)
        }
    }

    private fun refresh(sessionId: String) {
        val b = bucket(sessionId)
        val found = LessonDetector.detect(
            errors = b.errors.toList(),
            corrections = b.corrections.toList(),
            loops = b.loops.toList(),
            knownKeys = b.shown.toSet(),
        )
        // One card at a time, highest confidence first. The rest wait: showing
        // three at once makes the user dismiss all three.
        val best = found.maxByOrNull {
            when (it.confidence) {
                LessonProposal.Confidence.HIGH -> 3
                LessonProposal.Confidence.MEDIUM -> 2
                LessonProposal.Confidence.LOW -> 1
            }
        }
        _proposal.value = if (best == null) _proposal.value - sessionId
        else _proposal.value + (sessionId to best)
    }

    /** Kullanıcı kartı kapattı/onayladı: aynı ders tekrar önerilmez. */
    fun dismiss(sessionId: String) {
        bucket(sessionId).apply {
            _proposal.value[sessionId]?.let { shown += it.key }
        }
        _proposal.value = _proposal.value - sessionId
    }

    /** Oturum kapanışı. */
    fun forget(sessionId: String) {
        buckets.remove(sessionId)
        _proposal.value = _proposal.value - sessionId
    }
}
