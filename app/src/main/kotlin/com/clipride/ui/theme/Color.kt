package com.clipride.ui.theme

import androidx.compose.ui.graphics.Color

// Background
val DarkBackground = Color(0xFF000000)
val DarkSurface = Color(0xFF111111)
val DarkSurfaceVariant = Color(0xFF1A1A1A)

// Primary
val PrimaryBlue = Color(0xFF2196F3)
val PrimaryBlueDark = Color(0xFF1976D2)

// Status
val StatusConnected = Color(0xFF4CAF50)
val StatusConnecting = Color(0xFFFFC107)
val StatusDisconnected = Color(0xFF757575)
val StatusError = Color(0xFFF44336)
val RecordingRed = Color(0xFFF44336)

// Battery
val BatteryGood = Color(0xFF4CAF50)
val BatteryLow = Color(0xFFFFC107)
val BatteryCritical = Color(0xFFF44336)

// Text
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFAAAAAA)
val TextDim = Color(0xFF666666)

// Dividers & Borders
val DividerColor = Color(0xFF222222)
val FrameBorder = Color(0xFF333333)

// Backwards-compatible aliases
val ConnectedGreen = StatusConnected
val WarningOrange = StatusConnecting
val DisconnectedGray = StatusDisconnected
val CriticalRed = StatusError
