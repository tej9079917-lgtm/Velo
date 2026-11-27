package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.browser.ReaderArticle
import com.example.system.ReaderTtsController

private enum class ReaderPaperTheme(val label: String) {
    DARK("Midnight"),
    SEPIA("Sepia"),
    LIGHT("Paper")
}

@Composable
fun ReaderModeSheet(
    article: ReaderArticle,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val ttsController = remember(context) { ReaderTtsController(context) }
    val ttsState by ttsController.state.collectAsStateWithLifecycle()

    DisposableEffect(ttsController) {
        onDispose { ttsController.shutdown() }
    }

    BackHandler {
        ttsController.stop()
        onDismiss()
    }

    var paperTheme by remember { mutableStateOf(ReaderPaperTheme.DARK) }
    var fontSizeSp by remember { mutableFloatStateOf(17f) }
    var useSerif by remember { mutableStateOf(true) }

    val bgColor = when (paperTheme) {
        ReaderPaperTheme.DARK -> Color(0xFF0E131D)
        ReaderPaperTheme.SEPIA -> Color(0xFFF6EFE0)
        ReaderPaperTheme.LIGHT -> Color(0xFFFAFAFA)
    }
    val textColor = when (paperTheme) {
        ReaderPaperTheme.DARK -> Color(0xFFE6ECF5)
        ReaderPaperTheme.SEPIA -> Color(0xFF2F261A)
        ReaderPaperTheme.LIGHT -> Color(0xFF181C24)
    }
    val subtleColor = textColor.copy(alpha = 0.65f)
    val accentColor = MaterialTheme.colorScheme.primary

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Controls Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    text = "READER MODE • ${article.readingMinutes} MIN READ",
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor
                )
                Text(
                    text = article.host.ifBlank { "Web Article" },
                    style = MaterialTheme.typography.bodySmall,
                    color = subtleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { fontSizeSp = (fontSizeSp - 2f).coerceAtLeast(13f) },
                    modifier = Modifier.size(44.dp).testTag("reader_font_decrease")
                ) {
                    Icon(Icons.Default.TextDecrease, contentDescription = "Decrease font size", tint = textColor)
                }
                IconButton(
                    onClick = { fontSizeSp = (fontSizeSp + 2f).coerceAtMost(26f) },
                    modifier = Modifier.size(44.dp).testTag("reader_font_increase")
                ) {
                    Icon(Icons.Default.TextIncrease, contentDescription = "Increase font size", tint = textColor)
                }
                IconButton(
                    onClick = {
                        ttsController.stop()
                        onDismiss()
                    },
                    modifier = Modifier.size(48.dp).testTag("close_reader_mode_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close reader mode", tint = textColor)
                }
            }
        }

        // Theme & Typography Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ReaderPaperTheme.entries.forEach { mode ->
                FilterChip(
                    selected = paperTheme == mode,
                    onClick = { paperTheme = mode },
                    label = { Text(mode.label) },
                    modifier = Modifier.testTag("reader_theme_${mode.name.lowercase()}")
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            FilterChip(
                selected = useSerif,
                onClick = { useSerif = !useSerif },
                label = { Text(if (useSerif) "Serif" else "Sans") },
                modifier = Modifier.testTag("reader_font_family_toggle")
            )
        }

        HorizontalDivider(color = textColor.copy(alpha = 0.12f))

        // Readable Article Body
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 680.dp),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = article.title,
                    fontSize = (fontSizeSp + 7f).sp,
                    lineHeight = (fontSizeSp + 14f).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = if (useSerif) FontFamily.Serif else FontFamily.SansSerif,
                    color = textColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Tap any paragraph to listen from that line",
                    style = MaterialTheme.typography.labelSmall,
                    color = subtleColor
                )
            }

            itemsIndexed(article.paragraphs) { index, paragraph ->
                val isCurrentlySpoken = ttsState.isSpeaking && ttsState.currentParagraphIndex == index
                Surface(
                    color = if (isCurrentlySpoken) accentColor.copy(alpha = 0.16f) else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = ttsState.isReady) {
                            ttsController.speakParagraphs(article.paragraphs, startIndex = index)
                        }
                ) {
                    Text(
                        text = paragraph,
                        fontSize = fontSizeSp.sp,
                        lineHeight = (fontSizeSp * 1.58f).sp,
                        fontFamily = if (useSerif) FontFamily.Serif else FontFamily.SansSerif,
                        color = textColor,
                        modifier = Modifier.padding(if (isCurrentlySpoken) 10.dp else 2.dp)
                    )
                }
            }
        }

        // Bottom On-Device Text-to-Speech Dock
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = if (ttsState.isSpeaking) {
                                "Reading paragraph ${ttsState.currentParagraphIndex + 1} of ${article.paragraphs.size}"
                            } else if (ttsState.isReady) {
                                "On-Device Audio Reader"
                            } else {
                                "Initializing voice engine..."
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        val speeds = listOf(0.8f, 1.0f, 1.25f, 1.5f, 1.8f)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            speeds.forEach { spd ->
                                val selected = kotlin.math.abs(ttsState.speechRate - spd) < 0.05f
                                Text(
                                    text = "${spd}x",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clickable { ttsController.setSpeechRate(spd) }
                                        .padding(vertical = 2.dp, horizontal = 4.dp)
                                )
                            }
                        }
                    }
                }

                FilledTonalButton(
                    onClick = {
                        if (ttsState.isSpeaking) {
                            ttsController.stop()
                        } else {
                            ttsController.speakParagraphs(article.paragraphs, startIndex = ttsState.currentParagraphIndex)
                        }
                    },
                    enabled = ttsState.isReady && article.paragraphs.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp).testTag("reader_tts_play_button")
                ) {
                    Icon(
                        imageVector = if (ttsState.isSpeaking) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (ttsState.isSpeaking) "Stop audio reading" else "Listen aloud",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (ttsState.isSpeaking) "Stop" else "Listen")
                }
            }
        }
    }
}
