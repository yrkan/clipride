package com.clipride.karoo.datatypes.glance

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

@Composable
fun DataFieldContainer(
    modifier: GlanceModifier = GlanceModifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceColors.Frame)
            .padding(1.dp)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceColors.Background),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
fun ValueText(
    text: String,
    fontSize: TextUnit,
    color: Color = GlanceColors.TextPrimary,
    bold: Boolean = true,
) {
    Text(
        text = text,
        style = TextStyle(
            color = ColorProvider(color),
            fontSize = fontSize,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
        ),
    )
}

@Composable
fun LabelText(
    text: String,
    color: Color = GlanceColors.TextSecondary,
    fontSize: TextUnit = 12.sp,
) {
    Text(
        text = text,
        style = TextStyle(
            color = ColorProvider(color),
            fontSize = fontSize,
            textAlign = TextAlign.Center,
        ),
    )
}

@Composable
fun NoDataText(fontSize: TextUnit = 24.sp) {
    ValueText(
        text = "---",
        fontSize = fontSize,
        color = GlanceColors.TextDim,
    )
}

@Composable
fun StatusDot(
    isActive: Boolean,
    activeColor: Color = GlanceColors.RecordingActive,
    inactiveColor: Color = GlanceColors.RecordingIdle,
) {
    Text(
        text = if (isActive) "\u25CF" else "\u25CB",
        style = TextStyle(
            color = ColorProvider(if (isActive) activeColor else inactiveColor),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
        ),
    )
}

fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

fun formatSdRemaining(seconds: Int?): String {
    if (seconds == null) return "--"
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
}

fun formatSdShort(seconds: Int?): String {
    if (seconds == null) return "--"
    val hours = seconds / 3600
    val mins = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h${mins}m" else "${mins}m"
}
