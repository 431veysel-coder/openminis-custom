package com.openminis.app.ui.team

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * [37099k · §8.2] Rapor metni için **asgari** biçimlendirme.
 *
 * ## Neden (ölçülen sorun)
 *
 * Konsey raporu sonuç odasında tek bir `Text` olarak çiziliyordu. Model
 * `## Başlık`, `- madde`, `1. adım` yazıyor; ekran bunu **olduğu gibi**
 * gösteriyordu. Yirmi satırlık bir raporda başlıkları gözle ayırmak
 * kullanıcıya kalıyordu — yani rapor okunmuyordu.
 *
 * ## Neden tam Markdown değil
 *
 * Markdown kitaplığı eklemek APK'ya ~200 KB ve yeni bir bağımlılık demek.
 * Raporlarda gerçekten görünen üç şey var: başlık, liste maddesi, atıf
 * (`[E1]`, `[C3]`). Üçünü destekleyen 30 satır, 300 satırlık bir ayrıştırıcıdan
 * daha dürüst: desteklemediğim sözdizimi **gizlenmiyor**, olduğu gibi kalıyor.
 *
 * ## Neden satır satır
 *
 * Blok ayrıştırıcı (paragraf birleştirme) raporlarda işe yaramıyor: konsey
 * çıktısı zaten satır sonlarıyla anlam taşıyor. Satır satır çizmek, kaynağı
 * bozmadan gösteriyor.
 */
object ReportText {

    /** Bir satırın ne olduğu. */
    enum class Kind { HEADING, BULLET, NUMBERED, QUOTE, BODY, RULE }

    data class Line(val kind: Kind, val text: String, val level: Int = 0)

    /**
     * Metni satırlara ayırır.
     *
     * `level`: başlık seviyesi (`#` sayısı). Diğer türlerde 0.
     */
    fun lines(text: String): List<Line> = text.lineSequence().map { raw ->
        val line = raw.trimEnd()
        val trimmed = line.trimStart()
        when {
            trimmed.isBlank() -> Line(Kind.BODY, "")
            trimmed.all { it == '-' || it == '*' || it == '_' } && trimmed.length >= 3 ->
                Line(Kind.RULE, "")
            trimmed.startsWith("###") -> Line(Kind.HEADING, trimmed.removePrefix("###").trim(), 3)
            trimmed.startsWith("##") -> Line(Kind.HEADING, trimmed.removePrefix("##").trim(), 2)
            trimmed.startsWith("#") -> Line(Kind.HEADING, trimmed.removePrefix("#").trim(), 1)
            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") ->
                Line(Kind.BULLET, trimmed.drop(2).trim())
            trimmed.startsWith("> ") -> Line(Kind.QUOTE, trimmed.drop(2).trim())
            NUMBERED.matches(trimmed) -> Line(Kind.NUMBERED, trimmed.substringAfter(' ').trim())
            else -> Line(Kind.BODY, trimmed)
        }
    }.toList()

    private val NUMBERED = Regex("""^\d{1,2}[.)]\s+.*""")
}

/**
 * Raporu çizer. Boş metin **hiçbir şey** çizmez (çağıran boş durumu yazar).
 */
@Composable
fun ReportBody(text: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        ReportText.lines(text).forEach { line ->
            when (line.kind) {
                ReportText.Kind.HEADING -> Text(
                    line.text,
                    style = if (line.level <= 1) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
                ReportText.Kind.BULLET -> Text(
                    "• ${line.text}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
                ReportText.Kind.NUMBERED -> Text(
                    line.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
                ReportText.Kind.QUOTE -> Text(
                    line.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
                ReportText.Kind.RULE -> Text(
                    "─────",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                ReportText.Kind.BODY -> if (line.text.isNotBlank()) {
                    Text(
                        linkifyCitations(line.text),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * Atıfları (`[E1]`, `[C3]`) görünür biçimde işaretler.
 *
 * Neden: raporda `[E3]` geçiyor ama ekranda etiketin kendisi düz metindi ve
 * kullanıcı bunun **kanıt numarası** olduğunu bilmiyordu. Köşeli parantez
 * korunuyor (metin bozulmuyor), yalnız biçim değişiyor.
 */
fun linkifyCitations(text: String): androidx.compose.ui.text.AnnotatedString {
    val pattern = Regex("""\[([EC]\d{1,3})\]""")
    val builder = androidx.compose.ui.text.AnnotatedString.Builder(text)
    pattern.findAll(text).forEach { m ->
        builder.addStyle(
            androidx.compose.ui.text.SpanStyle(
                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            ),
            m.range.first,
            m.range.last + 1,
        )
    }
    return builder.toAnnotatedString()
}
