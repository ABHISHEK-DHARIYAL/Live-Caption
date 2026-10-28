package com.clearcap.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.sin

/** Flat bordered card, as in VoiceFlow / OpenWhispr. */
@Composable
fun Panel(modifier: Modifier = Modifier, fill: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = fill ?: t.surface,
        border = BorderStroke(1.dp, t.border),
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun Banner(text: String, danger: Boolean) {
    val t = LocalTokens.current
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = if (danger) t.dangerSoft else t.warnSoft) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Warning, null, tint = if (danger) t.danger else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Seven rounded bars that follow the microphone level (VoiceFlow's AudioVisualizer). */
@Composable
fun AudioBars(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val lv by animateFloatAsState(if (active) level else 0f, label = "level")
    Canvas(modifier.height(28.dp).width(60.dp)) {
        val n = 7
        val gap = 4.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val shape = 0.35f + 0.65f * abs(sin(i * 1.3f + 0.7f))
            val h = (size.height * (0.14f + 0.86f * lv * shape)).coerceAtLeast(4.dp.toPx())
            drawRoundRect(
                color = if (active) t.accent else t.muted.copy(alpha = 0.4f),
                topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2, w / 2),
            )
        }
    }
}

/** Big round record button: a microphone when idle, a red square while recording. */
@Composable
fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    val ring = if (recording) t.danger else t.accent
    Box(
        Modifier.size(92.dp).background(ring.copy(alpha = 0.14f), CircleShape).padding(8.dp)
            .background(if (enabled) ring else t.muted.copy(alpha = 0.4f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            if (recording) {
                drawRoundRect(Color.White, Offset(size.width * 0.15f, size.height * 0.15f), Size(size.width * 0.7f, size.height * 0.7f), CornerRadius(6.dp.toPx()))
            } else {
                val w = size.width
                val h = size.height
                drawRoundRect(Color.White, Offset(w * 0.34f, 0f), Size(w * 0.32f, h * 0.6f), CornerRadius(w * 0.16f))
                drawArc(Color.White, 0f, 180f, false, Offset(w * 0.2f, h * 0.18f), Size(w * 0.6f, h * 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
                drawLine(Color.White, Offset(w * 0.5f, h * 0.73f), Offset(w * 0.5f, h * 0.95f), 2.5.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White, Offset(w * 0.33f, h * 0.95f), Offset(w * 0.67f, h * 0.95f), 2.5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(modifier.fillMaxWidth().background(t.elevated, RoundedCornerShape(10.dp)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).background(if (on) t.surface else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onSurface else t.muted)
            }
        }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = t.surface, border = BorderStroke(1.dp, t.border)) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.labelMedium, color = t.muted)
        }
    }
}

/** Five dots, as VoiceFlow's speed / accuracy ratings. */
@Composable
fun Dots(label: String, filled: Int, size: Dp = 7.dp) {
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.muted, modifier = Modifier.width(64.dp))
        for (i in 1..5) Box(Modifier.size(size).background(if (i <= filled) t.accent else t.border, CircleShape))
    }
}

fun fmtDate(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

fun fmtDur(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d h %02d min".format(s / 3600, (s % 3600) / 60) else "%d min %02d s".format(s / 60, s % 60)
}
