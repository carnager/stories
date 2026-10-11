package io.github.carnager.stories.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.carnager.stories.R
import io.github.carnager.stories.data.Settings

/** The quiet tones the screens name, beyond Material's roles. */
data class Tones(
    val secondary: Color,
    val muted: Color,
    val faint: Color,
    val hairline: Color,
    val raised: Color,
    val done: Color,
)

val LocalTones = staticCompositionLocalOf {
    Tones(Color(0xFFB3AA9C), Color(0xFF7D766B), Color(0xFF4F4A43), Color(0xFF221F1B), Color(0xFF1F1C18), Color(0xFF8DBF94))
}

// Evening: a warm near-black, cream text, and the amber of a reading lamp.
private val lamp = darkColorScheme(
    primary = Color(0xFFF0B474),
    onPrimary = Color(0xFF2A1A08),
    primaryContainer = Color(0xFF3D2A16),
    onPrimaryContainer = Color(0xFFFFDDB8),
    secondary = Color(0xFFB3AA9C),
    onSecondary = Color(0xFF12110F),
    secondaryContainer = Color(0xFF26231F),
    onSecondaryContainer = Color(0xFFF1EADF),
    background = Color(0xFF12110F),
    onBackground = Color(0xFFF1EADF),
    surface = Color(0xFF12110F),
    onSurface = Color(0xFFF1EADF),
    surfaceVariant = Color(0xFF26231F),
    onSurfaceVariant = Color(0xFFB3AA9C),
    surfaceContainerLowest = Color(0xFF0C0B0A),
    surfaceContainerLow = Color(0xFF171513),
    surfaceContainer = Color(0xFF1B1916),
    surfaceContainerHigh = Color(0xFF221F1B),
    surfaceContainerHighest = Color(0xFF2B2823),
    outline = Color(0xFF453F37),
    outlineVariant = Color(0xFF26231F),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF5B2A2A),
    onErrorContainer = Color(0xFFF3D6D2),
)
private val lampTones = Tones(Color(0xFFB3AA9C), Color(0xFF7D766B), Color(0xFF4F4A43), Color(0xFF221F1B), Color(0xFF1F1C18), Color(0xFF8DBF94))

// Daylight: paper, ink, and a terracotta accent.
private val paper = lightColorScheme(
    primary = Color(0xFFA9512A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF7DCCB),
    onPrimaryContainer = Color(0xFF3B1505),
    secondary = Color(0xFF615A50),
    secondaryContainer = Color(0xFFEAE3D7),
    onSecondaryContainer = Color(0xFF1E1B17),
    background = Color(0xFFF5F1EA),
    onBackground = Color(0xFF1E1B17),
    surface = Color(0xFFF5F1EA),
    onSurface = Color(0xFF1E1B17),
    surfaceVariant = Color(0xFFEAE3D7),
    onSurfaceVariant = Color(0xFF615A50),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0EBE2),
    surfaceContainer = Color(0xFFEBE5DB),
    surfaceContainerHigh = Color(0xFFE5DED3),
    surfaceContainerHighest = Color(0xFFDED6CA),
    outline = Color(0xFFC9C0B2),
    outlineVariant = Color(0xFFE3DCD0),
    errorContainer = Color(0xFFF6D9D6),
    onErrorContainer = Color(0xFF5B1A16),
)
private val paperTones = Tones(Color(0xFF615A50), Color(0xFF8C8478), Color(0xFFB8AFA2), Color(0xFFE5DED3), Color(0xFFEBE5DB), Color(0xFF3F7D4A))

@OptIn(ExperimentalTextApi::class)
private fun manrope(weight: Int) = Font(
    R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Manrope = FontFamily(manrope(400), manrope(500), manrope(600), manrope(700), manrope(800))

/** Books' own names are set in a serif, as on a spine; everything around them in Manrope. */
val BookFace = FontFamily.Serif

private fun style(size: Int, weight: Int, tracking: Double = 0.0, line: Int = 0, family: FontFamily = Manrope) = TextStyle(
    fontFamily = family,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    letterSpacing = tracking.em,
    lineHeight = if (line > 0) line.sp else (size * 1.3).sp,
)

private val type = Typography(
    displaySmall = style(32, 600, -0.01, 38, BookFace),
    headlineMedium = style(30, 800, -0.025, 36),
    headlineSmall = style(24, 600, -0.01, 30, BookFace),
    titleLarge = style(20, 600, -0.005, 26, BookFace),
    titleMedium = style(16, 700),
    titleSmall = style(15, 700),
    bodyLarge = style(16, 600),
    bodyMedium = style(15, 600),
    bodySmall = style(13, 500),
    labelLarge = style(15, 700),
    labelMedium = style(13, 600),
    labelSmall = style(11, 800, 0.08),
)

/** A book's name in a list or grid: the serif, at the size around it. */
val bookTitle = style(15, 600, 0.0, 19, BookFace)

@Composable
fun StoriesTheme(settings: Settings, content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val scheme = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> lamp
        else -> paper
    }
    CompositionLocalProvider(LocalTones provides if (dark) lampTones else paperTones) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
