package com.casualexplorer.chat.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.casualexplorer.chat.data.ThemeMode

/** Whether [mode] means a dark theme here. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/**
 * Material 3: Material You colours from the wallpaper on Android 12 and
 * later (unless [dynamicColor] is off), else the baseline scheme; light or
 * dark by [darkTheme]. Text uses the default type scale; only code is
 * monospace.
 */
@Composable
fun ChatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalCodeColors provides if (darkTheme) DarkCodeColors else LightCodeColors) {
        MaterialTheme(colorScheme = colorScheme, typography = ChatTypography, shapes = ChatShapes, content = content)
    }
}

/** The font of code blocks and inline code. */
val CodeFont: FontFamily = FontFamily.Monospace

/** Syntax colours for code blocks, which the colour scheme has no roles for. */
@Immutable
data class CodeColors(
    val keyword: Color,
    val type: Color,
    val function: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val operator: Color,
)

// GitHub's light and dark syntax colours, which read well on neutral surfaces.
private val LightCodeColors = CodeColors(
    keyword = Color(0xFFCF222E),
    type = Color(0xFF953800),
    function = Color(0xFF8250DF),
    string = Color(0xFF0A3069),
    number = Color(0xFF0550AE),
    comment = Color(0xFF6E7781),
    operator = Color(0xFFCF222E),
)

private val DarkCodeColors = CodeColors(
    keyword = Color(0xFFFF7B72),
    type = Color(0xFFFFA657),
    function = Color(0xFFD2A8FF),
    string = Color(0xFFA5D6FF),
    number = Color(0xFF79C0FF),
    comment = Color(0xFF8B949E),
    operator = Color(0xFFFF7B72),
)

val LocalCodeColors = staticCompositionLocalOf { LightCodeColors }
