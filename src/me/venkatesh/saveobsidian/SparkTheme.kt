package me.venkatesh.saveobsidian

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The Sparkbox palette (slate surfaces, emerald accent) as Material 3 colours, from the same values the View screens use. */
private fun scheme(ui: Ui): ColorScheme {
    val c = { v: Int -> Color(v) }
    val base = if (ui.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c(ui.accent), onPrimary = c(ui.onAccent),
        primaryContainer = c(ui.container), onPrimaryContainer = c(ui.onContainer),
        background = c(ui.bg), onBackground = c(ui.ink),
        surface = c(ui.bg), onSurface = c(ui.ink), onSurfaceVariant = c(ui.sub),
        surfaceContainerLowest = c(ui.bg), surfaceContainerLow = c(ui.surface), surfaceContainer = c(ui.surface),
        surfaceContainerHigh = c(ui.raised), surfaceContainerHighest = c(ui.raised), surfaceVariant = c(ui.raised),
        outline = c(ui.line), outlineVariant = c(ui.line),
        error = c(ui.err), errorContainer = c(ui.errBg),
    )
}

/** Same type scale as Ui.type: compact body, restrained headings. */
private val type = Typography(
    headlineSmall = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

/** Status colours Material 3 has no slot for. */
class Status(val warn: Color, val warnBg: Color)
val LocalStatus = staticCompositionLocalOf { Status(Color.Unspecified, Color.Unspecified) }

@Composable
fun SparkTheme(ui: Ui, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalStatus provides Status(Color(ui.warn), Color(ui.warnBg))) {
        MaterialTheme(colorScheme = scheme(ui), typography = type, content = content)
    }
}
