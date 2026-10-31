package com.openminis.app.ui.chat

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.chat.ThinkingEnhancer.SummaryDetail
import com.openminis.app.ui.chat.ThinkingEnhancer.SummarizationEngine
import com.openminis.app.ui.chat.ThinkingEnhancer.TranslationEngine
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingEnhancementsSettingsScreen(
    context: Context = LocalContext.current,
    onBack: () -> Unit
) {
    val prefs = context.getSharedPreferences(ThinkingEnhancer.PREFS_NAME, Context.MODE_PRIVATE)

    var translateEngine by remember {
        mutableStateOf(
            TranslationEngine.values().firstOrNull { it.id == prefs.getString(ThinkingEnhancer.KEY_TRANSLATE_ENGINE, "") } ?: ThinkingEnhancer.DEFAULT_TRANSLATE_ENGINE
        )
    }
    var targetLang by remember {
        mutableStateOf(prefs.getString(ThinkingEnhancer.KEY_TRANSLATE_TARGET_LANG, ThinkingEnhancer.DEFAULT_TARGET_LANG))
    }
    var protectCode by remember {
        mutableStateOf(prefs.getBoolean(ThinkingEnhancer.KEY_TRANSLATE_PROTECT_CODE, ThinkingEnhancer.DEFAULT_PROTECT_CODE))
    }
    var autoTranslate by remember {
        mutableStateOf(prefs.getBoolean(ThinkingEnhancer.KEY_TRANSLATE_AUTO, ThinkingEnhancer.DEFAULT_TRANSLATE_AUTO))
    }
    var summarizeEngine by remember {
        mutableStateOf(
            SummarizationEngine.values().firstOrNull { it.id == prefs.getString(ThinkingEnhancer.KEY_SUMMARIZE_ENGINE, "") } ?: ThinkingEnhancer.DEFAULT_SUMMARIZE_ENGINE
        )
    }
    var summaryDetail by remember {
        mutableStateOf(
            SummaryDetail.values().firstOrNull { it.id == prefs.getString(ThinkingEnhancer.KEY_SUMMARIZE_DETAIL, "") } ?: ThinkingEnhancer.DEFAULT_SUMMARY_DETAIL
        )
    }
    var autoSummarize by remember {
        mutableStateOf(prefs.getBoolean(ThinkingEnhancer.KEY_SUMMARIZE_AUTO, ThinkingEnhancer.DEFAULT_SUMMARIZE_AUTO))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_thinking_enhancements)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // Translation Section
            Text(
                text = "🌐 Çeviri Ayarları",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
            )

            // Engine Selection
            TranslationEngine.values().forEach { engine ->
                SettingsItem(
                    icon = Language,
                    iconColor = MaterialTheme.colorScheme.primary,
                    title = engine.displayName,
                    subtitle = when (engine) {
                        TranslationEngine.ML_KIT -> "Çevrimdışı, hızlı, gizli"
                        TranslationEngine.LLM -> "Bağlamlı, token maliyetli"
                        TranslationEngine.HYBRID -> "ML Kit + LLM yedek"
                    },
                    onClick = { translateEngine = engine },
                    showDivider = false,
                    badge = (translateEngine == engine),
                )
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // Target Language
            SettingsItem(
                icon = Language,
                iconColor = MaterialTheme.colorScheme.primary,
                title = "Hedef Dil",
                subtitle = targetLang,
                onClick = { /* Could open language picker */ },
                showDivider = false,
            )

            // Protect Code
            SettingsItem(
                icon = Psychology,
                iconColor = MaterialTheme.colorScheme.primary,
                title = "Kod Bloklarını Koru",
                subtitle = "Çeviri sırasında kod bloklarını değiştirme",
                onClick = { protectCode = !protectCode },
                showDivider = false,
                badge = protectCode,
            )

            // Auto Translate
            SettingsItem(
                icon = Refresh,
                iconColor = MaterialTheme.colorScheme.primary,
                title = "Otomatik Çevir (Streaming bitince)",
                subtitle = "Düşünme tamamlandığında otomatik çevir",
                onClick = { autoTranslate = !autoTranslate },
                showDivider = false,
                badge = autoTranslate,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Summarization Section
            Text(
                text = "📝 Özetleme Ayarları",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
            )

            SummarizationEngine.values().forEach { engine ->
                SettingsItem(
                    icon = Description,
                    iconColor = MaterialTheme.colorScheme.primary,
                    title = engine.displayName,
                    subtitle = when (engine) {
                        SummarizationEngine.HEURISTIC -> "Hızlı, basit"
                        SummarizationEngine.LLM -> "Anlamsal, bağlamlı"
                        SummarizationEngine.HYBRID -> "Akıllı seçim"
                    },
                    onClick = { summarizeEngine = engine },
                    showDivider = false,
                    badge = (summarizeEngine == engine),
                )
            }

            Divider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // Detail Level
            Text(
                text = "Detay Seviyesi: ${summaryDetail.displayName}",
                fontSize = 14.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            SummaryDetail.values().forEach { detail ->
                SettingsItem(
                    icon = Description,
                    iconColor = MaterialTheme.colorScheme.primary,
                    title = detail.displayName,
                    subtitle = "${detail.maxPoints} maddeye kadar",
                    onClick = { summaryDetail = detail },
                    showDivider = false,
                    badge = (summaryDetail == detail),
                )
            }

            // Auto Summarize
            SettingsItem(
                icon = Refresh,
                iconColor = MaterialTheme.colorScheme.primary,
                title = "Otomatik Özetle",
                subtitle = "Düşünme tamamlandığında otomatik özet oluştur",
                onClick = { autoSummarize = !autoSummarize },
                showDivider = false,
                badge = autoSummarize,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Save Button
            Button(
                onClick = {
                    ThinkingEnhancer.savePreferences(
                        context = context,
                        translateEngine = translateEngine,
                        targetLanguage = targetLang,
                        protectCode = protectCode,
                        autoTranslate = autoTranslate,
                        summarizeEngine = summarizeEngine,
                        summaryDetail = summaryDetail,
                        autoSummarize = autoSummarize
                    )
                    Toast.makeText(context, "Kaydedildi", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text("Kaydet ve Uygula")
            }
        }
    }
}

// Helper composable matching SettingsScreen.SettingsItem style
@Composable
private fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    showDivider: Boolean = true,
    badge: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Colored circle icon (matching iOS settings style)
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = iconColor,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }

            Spacer(Modifier.width(14.dp))

            // Title + subtitle - use weight on the Column directly
            Column(
                modifier = Modifier
                    .weight(1f, fill = true),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }

            // Badge (check mark when selected)
            if (badge) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (showDivider) {
            Divider(
                modifier = Modifier.padding(start = 58.dp, end = 16.dp)
            )
        }
    }
}