package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NetworkPing
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.browser.SpeedTestLiveState
import com.example.browser.SpeedTestPhase
import com.example.browser.SpeedTestRecord
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SpeedTestSheet(
    liveState: SpeedTestLiveState,
    history: List<SpeedTestRecord>,
    onStartTest: () -> Unit,
    onStopTest: () -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val isRunning = liveState.phase == SpeedTestPhase.PING ||
        liveState.phase == SpeedTestPhase.DOWNLOAD ||
        liveState.phase == SpeedTestPhase.UPLOAD

    val gaugeMaxMbps = 250f
    val targetFraction = (liveState.currentMbps.toFloat() / gaugeMaxMbps).coerceIn(0f, 1f)
    val animatedSweep by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 320),
        label = "speedometer_sweep"
    )

    val ratingLabel = when {
        liveState.phase != SpeedTestPhase.COMPLETE -> liveState.phase.label
        liveState.downloadMbps >= 100.0 -> "Ultra Fast • 4K Ready"
        liveState.downloadMbps >= 40.0 -> "Fast • Smooth HD"
        liveState.downloadMbps >= 15.0 -> "Good • Everyday Web"
        else -> "Moderate Connection"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Speed Test Meter",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Real-time ping, jitter, download & upload throughput",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp).testTag("close_speed_test_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close speed test")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Animated Speedometer Gauge Card
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier.size(230.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val primaryColor = MaterialTheme.colorScheme.primary
                            val secondaryColor = MaterialTheme.colorScheme.secondary
                            val trackColor = MaterialTheme.colorScheme.surfaceVariant
                            val needleColor = MaterialTheme.colorScheme.onSurface

                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val strokePx = 18.dp.toPx()
                                val inset = strokePx / 2f + 8.dp.toPx()
                                val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                                val topLeft = Offset(inset, inset)
                                val startAngle = 140f
                                val maxSweep = 260f

                                // Background arc
                                drawArc(
                                    color = trackColor,
                                    startAngle = startAngle,
                                    sweepAngle = maxSweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                )

                                // Active speed arc
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        colors = listOf(primaryColor, ShieldEmerald, secondaryColor, primaryColor)
                                    ),
                                    startAngle = startAngle,
                                    sweepAngle = maxSweep * animatedSweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                                )

                                // Precision needle indicator
                                val angleDeg = startAngle + maxSweep * animatedSweep
                                val rad = Math.toRadians(angleDeg.toDouble())
                                val cx = size.width / 2f
                                val cy = size.height / 2f
                                val radius = (arcSize.width / 2f) - strokePx * 0.9f
                                val tip = Offset(
                                    cx + (cos(rad) * radius).toFloat(),
                                    cy + (sin(rad) * radius).toFloat()
                                )
                                drawLine(
                                    color = needleColor.copy(alpha = 0.85f),
                                    start = Offset(cx, cy),
                                    end = tip,
                                    strokeWidth = 3.5.dp.toPx(),
                                    cap = StrokeCap.Round
                                )
                                drawCircle(
                                    color = primaryColor,
                                    radius = 7.dp.toPx(),
                                    center = Offset(cx, cy)
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(top = 86.dp)
                            ) {
                                Text(
                                    text = "%.1f".format(liveState.currentMbps),
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Mbps",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = ratingLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                            )
                        }

                        if (liveState.errorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = liveState.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        if (isRunning) {
                            Spacer(modifier = Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { liveState.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(5.dp)
                            )
                        }

                        // Live Throughput Sparkline
                        if (liveState.samples.size >= 2) {
                            Spacer(modifier = Modifier.height(12.dp))
                            val lineColor = MaterialTheme.colorScheme.primary
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                            ) {
                                val maxVal = (liveState.samples.maxOrNull() ?: 1f).coerceAtLeast(5f)
                                val path = Path()
                                val stepX = size.width / (liveState.samples.size - 1).coerceAtLeast(1)
                                liveState.samples.forEachIndexed { i, v ->
                                    val x = i * stepX
                                    val y = size.height - ((v / maxVal).coerceIn(0f, 1f) * size.height)
                                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                                }
                                drawPath(
                                    path = path,
                                    color = lineColor,
                                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { if (isRunning) onStopTest() else onStartTest() },
                            colors = if (isRunning) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("speed_test_start_stop_button")
                        ) {
                            Icon(
                                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRunning) "Stop Test" else "Start Speed Test")
                        }
                    }
                }
            }

            // Live Metrics Cards (Ping, Download, Upload)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricStatCard(
                        icon = Icons.Default.NetworkPing,
                        label = "Ping / Jitter",
                        value = "${liveState.pingMs} ms",
                        subValue = "±${liveState.jitterMs} ms",
                        accent = WarningAmber,
                        modifier = Modifier.weight(1f)
                    )
                    MetricStatCard(
                        icon = Icons.Default.ArrowDownward,
                        label = "Download",
                        value = "%.1f".format(liveState.downloadMbps),
                        subValue = "Mbps",
                        accent = ShieldEmerald,
                        modifier = Modifier.weight(1f)
                    )
                    MetricStatCard(
                        icon = Icons.Default.ArrowUpward,
                        label = "Upload",
                        value = "%.1f".format(liveState.uploadMbps),
                        subValue = "Mbps",
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Recent Speed Test History
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Recent Tests (${history.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (history.isNotEmpty()) {
                        IconButton(
                            onClick = onClearHistory,
                            modifier = Modifier.size(40.dp).testTag("clear_speed_history_button")
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear speed test history")
                        }
                    }
                }
            }

            if (history.isEmpty()) {
                item {
                    Text(
                        text = "Tap Start Speed Test to measure your real connection performance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(history, key = { it.id }) { rec ->
                    SpeedHistoryRow(rec)
                }
            }
        }
    }
}

@Composable
private fun MetricStatCard(
    icon: ImageVector,
    label: String,
    value: String,
    subValue: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subValue,
                style = MaterialTheme.typography.labelSmall,
                color = accent
            )
        }
    }
}

@Composable
private fun SpeedHistoryRow(record: SpeedTestRecord) {
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(
                        text = "${record.connectionType} • ${fmt.format(Date(record.testedAtMillis))}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Ping ${record.pingMs}ms (±${record.jitterMs}ms)",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "↓ ${"%.1f".format(record.downloadMbps)} Mbps",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = ShieldEmerald
                )
                Text(
                    text = "↑ ${"%.1f".format(record.uploadMbps)} Mbps",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
