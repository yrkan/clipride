package com.clipride.karoo.datatypes.glance

import androidx.compose.ui.graphics.Color

object GlanceColors {
    val Background = Color(0xFF000000)
    val Frame = Color(0xFF333333)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFFAAAAAA)
    val TextDim = Color(0xFF666666)
    val RecordingActive = Color(0xFFF44336)
    val RecordingIdle = Color(0xFF757575)
    val BatteryGood = Color(0xFF4CAF50)
    val BatteryLow = Color(0xFFFFC107)
    val BatteryCritical = Color(0xFFF44336)

    fun batteryColor(level: Int?): Color = when {
        level == null -> TextDim
        level < 15 -> BatteryCritical
        level < 30 -> BatteryLow
        else -> BatteryGood
    }
}
