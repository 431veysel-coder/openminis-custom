package com.openminis.app.ozel

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.replay.ReplayRecording
import com.openminis.app.replay.ReplayEntry
import com.openminis.app.replay.ReplayOutcome
import com.openminis.app.replay.Replayer
import com.openminis.app.replay.ToolCallKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [37089] Replay kayıt/oynatma köprüsü.
 *
 * ## Ne yapıyor, ne yapmıyor
 *
 * **Yapıyor:** son sohbetin araç çağrılarını ve sonuçlarını dondurulmuş bir
 * dosyaya yazar; sonra o kaydı yeniden koştururken araç çağrılarını kayıttan
 * besler.
 *
 * **Yapmıyor:** modeli çağırmıyor. Yeniden koşu, aynı sohbetin **araç
 * katmanını** tekrarlıyor — hangi çağrılar aynı, hangileri ıskalandı. Model
 * karşılaştırması bunun üstüne kurulacak ayrı bir iş; şimdi ölçtüğü şey
 * "kayıt sadık mı" ve "aynı niyet aynı çağrıları üretiyor mu".
 *
 * Bu sınırı yazmak zorunlu: buraya "model karşılaştırması" yazıp yapmamak,
 * özelliğin en pahalı yalanı olurdu.
 *
 * ## Neden son sohbet
 *
 * Ayarlar ekranı hangi sohbetin açık olduğunu bilmiyor ve bilmemeli. "Son
 * sohbeti kaydet" tek anlaşılır seçenek; kayıt zaten bir kopya, canlı sohbete
 * bağ değil.
 */
object ReplayStore {

    private const val DIR = "replays"
    private const val INDEX = "index.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _recordings = MutableStateFlow<List<ReplayRecording>>(emptyList())
    val recordings: StateFlow<List<ReplayRecording>> = _recordings.asStateFlow()

    private val _outcomes = MutableStateFlow<Map<String, ReplayOutcome>>(emptyMap())
    val outcomes: StateFlow<Map<String, ReplayOutcome>> = _outcomes.asStateFlow()

    /** Kaydın neden yapılmadığı — sessiz başarısızlık yerine tek cümle. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, DIR).apply { mkdirs() }

    // ------------------------------------------------------------- okuma

    fun load(ctx: Context) {
        val f = File(dir(ctx), INDEX)
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            _recordings.value = (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(::readRecording)
            }
        }
    }

    private fun readRecording(o: JSONObject): ReplayRecording? {
        val id = o.optString("id").takeIf { it.isNotEmpty() } ?: return null
        val entries = o.optJSONArray("entries") ?: JSONArray()
        return ReplayRecording(
            id = id,
            sessionId = o.optString("session"),
            createdAtMs = o.optLong("at"),
            modelId = o.optString("model").takeIf { it.isNotEmpty() },
            prompt = o.optString("prompt"),
            entries = (0 until entries.length()).mapNotNull { i ->
                val e = entries.optJSONObject(i) ?: return@mapNotNull null
                ReplayEntry(
                    key = ToolCallKey(e.optString("tool"), e.optString("args")),
                    result = e.optString("result"),
                    turnIndex = e.optInt("turn"),
                    ok = e.optBoolean("ok", true),
                )
            },
            originalTurns = o.optInt("turns"),
        )
    }

    private fun writeRecording(r: ReplayRecording): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("session", r.sessionId)
        put("at", r.createdAtMs)
        put("model", r.modelId.orEmpty())
        put("prompt", r.prompt)
        put("turns", r.originalTurns)
        val arr = JSONArray()
        r.entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("tool", e.key.tool)
                    put("args", e.key.argsHash)
                    put("result", e.result)
                    put("turn", e.turnIndex)
                    put("ok", e.ok)
                },
            )
        }
        put("entries", arr)
    }

    // ------------------------------------------------------------- yazma

    /** Son sohbetin araç çağrılarını dondurur. */
    fun recordCurrentChat(ctx: Context) {
        scope.launch {
            runCatching {
                val app = ctx.applicationContext as? MinisApp ?: return@launch
                val repo = app.chatRepositoryOrNull ?: return@launch
                // One-shot read: the flow is infinite, so take its first
                // emission rather than collecting it forever in a job.
                // `first` is an EXTENSION on Flow, not a top-level function: the
                // call has to read as `flow.first()`. Written the other way it
                // does not resolve, and every use of `sessions` below then fails
                // as "unresolved reference" — eight errors from one mistake.
                val sessions = repo.observeSessions().first()
                val session = sessions.firstOrNull() ?: return@launch
                val messages = repo.loadMessages(session.id)
                if (messages.isEmpty()) return@launch

                // Reuse the existing step extractor instead of writing a second
                // parser for partsJson: it already pairs toolUse with its result
                // by id and is what the Live Tasks screen shows. A second parser
                // would drift the moment the persisted shape changed.
                val steps = com.openminis.app.ui.settings.AgentStepExtractor.extract(messages)
                if (steps.isEmpty()) {
                    // No tool calls: nothing to replay. Saying so is better than
                    // saving an empty recording that later reads as "0 misses".
                    _lastError.value = "Bu sohbette araç çağrısı yok — yeniden oynatılacak bir şey yok."
                    return@launch
                }
                var turn = 0
                val entries = steps.map { step ->
                    turn++
                    ReplayEntry(
                        key = ToolCallKey(step.name, step.input.take(200)),
                        result = (step.output ?: "").take(2_000),
                        turnIndex = turn,
                        ok = step.success != false,
                    )
                }

                val rec = ReplayRecording(
                    id = "r" + System.currentTimeMillis().toString(36),
                    sessionId = session.id,
                    createdAtMs = System.currentTimeMillis(),
                    modelId = session.modelId,
                    prompt = session.title ?: "(başlıksız)",
                    entries = entries,
                    originalTurns = turn,
                )
                _lastError.value = null
                save(ctx, rec)
            }
        }
    }

    private fun save(ctx: Context, rec: ReplayRecording) {
        val all = (_recordings.value + rec).takeLast(20)
        _recordings.value = all
        runCatching {
            val arr = JSONArray()
            all.forEach { arr.put(writeRecording(it)) }
            File(dir(ctx), INDEX).writeText(arr.toString())
        }
    }

    /**
     * Kaydı yeniden oynatır.
     *
     * Araç katmanı yeniden üretiliyor: her kayıt girdisi sırayla "çağrılıyor"
     * ve kayıttan dönüyor. Böylece "bu kayıt kendi kendine tutarlı mı" ve
     * "kaç çağrı ıskalandı" ölçülebiliyor. Ağa çıkılmıyor.
     */
    fun runReplay(ctx: Context, id: String) {
        scope.launch {
            val rec = _recordings.value.firstOrNull { it.id == id } ?: return@launch
            val replayer = Replayer(rec)
            rec.entries.forEach { e ->
                replayer.step(e.key.tool, mapOf("args" to e.key.argsHash))
            }
            val out = replayer.outcome(
                modelId = rec.modelId,
                turns = rec.originalTurns,
                completed = true,
                finalText = replayer.selfCheck().joinToString("\n").ifEmpty { "kayıt tutarlı" },
            )
            _outcomes.value = _outcomes.value + (id to out)
        }
    }

    fun remove(ctx: Context, id: String) {
        val all = _recordings.value.filterNot { it.id == id }
        _recordings.value = all
        runCatching {
            val arr = JSONArray()
            all.forEach { arr.put(writeRecording(it)) }
            File(dir(ctx), INDEX).writeText(arr.toString())
        }
    }
}
