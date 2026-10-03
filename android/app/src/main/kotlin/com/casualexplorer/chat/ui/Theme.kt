package com.casualexplorer.chat.ui

// The palette and message styles are adapted from Crush
// (github.com/charmbracelet/crush, internal/ui/styles), Copyright 2025-2026
// Charmbracelet, Inc., used under FSL-1.1-MIT.

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/**
 * Crush's default dark theme, Charmtone Pantera, by role. Every colour in the
 * UI comes from here, as in the terminal app's palette.
 */
object Palette {
    // Charmtone colours.
    val Charple = Color(0xFF6B50FF)
    val Dolly = Color(0xFFFF60FF)
    val Bok = Color(0xFF68FFD6)
    val Blush = Color(0xFFFF84FF)
    val Sash = Color(0xFFECEBF0)
    val Squid = Color(0xFF858392)
    val Smoke = Color(0xFFBFBCC8)
    val Oyster = Color(0xFF605F6B)
    val Butter = Color(0xFFFFFAF1)
    val Pepper = Color(0xFF201F26)
    val BBQ = Color(0xFF2D2C36)
    val Char = Color(0xFF3A3943)
    val Iron = Color(0xFF4D4C57)
    val Coral = Color(0xFFFF577D)
    val Sriracha = Color(0xFFEB4268)
    val Zest = Color(0xFFE8FE96)
    val Mustard = Color(0xFFF5EF34)
    val Tang = Color(0xFFFF985A)
    val Malibu = Color(0xFF00A4FF)
    val Sardine = Color(0xFF4FBEFE)
    val Julep = Color(0xFF00FFB2)
    val Guac = Color(0xFF12C78F)
    val Zinc = Color(0xFF10B1AE)
    val Cheeky = Color(0xFFFF79D0)
    val Bengal = Color(0xFFFF6E63)
    val Pony = Color(0xFFFF4FBF)
    val Guppy = Color(0xFF7272FF)
    val Salmon = Color(0xFFFF7F90)
    val Cumin = Color(0xFFBF976F)
    val Salt = Color(0xFFF7F6FB)

    // Roles.
    val Primary = Charple
    val Secondary = Dolly
    val Accent = Bok
    val FgBase = Sash
    val FgMoreSubtle = Squid
    val FgSubtle = Smoke
    val FgMostSubtle = Oyster
    val OnPrimary = Butter
    val BgBase = Pepper
    val BgLeastVisible = BBQ
    val BgLessVisible = Char
    val BgMostVisible = Iron
    val Separator = Char
    val Destructive = Coral
    val Error = Sriracha
    val WarningSubtle = Zest
    val Warning = Mustard
    val Attention = Tang
    val Info = Malibu
    val Success = Julep
    val SuccessMoreSubtle = Bok
    val SuccessMostSubtle = Guac
}

const val MODEL_ICON = "◇"

/** The terminal app is all monospace; so is this. */
val Mono = FontFamily.Monospace

private val base = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp, color = Palette.FgBase)

@Composable
fun ChatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Primary,
            onPrimary = Palette.OnPrimary,
            secondary = Palette.Secondary,
            tertiary = Palette.Accent,
            background = Palette.BgBase,
            onBackground = Palette.FgBase,
            surface = Palette.BgBase,
            onSurface = Palette.FgBase,
            surfaceVariant = Palette.BgLeastVisible,
            onSurfaceVariant = Palette.FgSubtle,
            surfaceContainer = Palette.BgLeastVisible,
            surfaceContainerLow = Palette.BgLeastVisible,
            surfaceContainerHigh = Palette.BgLessVisible,
            surfaceContainerHighest = Palette.BgLessVisible,
            outline = Palette.BgMostVisible,
            outlineVariant = Palette.Separator,
            error = Palette.Error,
            inverseSurface = Palette.BgMostVisible,
            inverseOnSurface = Palette.FgBase,
        ),
        typography = Typography(
            bodyLarge = base.copy(fontSize = 15.sp),
            bodyMedium = base,
            bodySmall = base.copy(fontSize = 12.sp, lineHeight = 16.sp),
            labelLarge = base,
            labelMedium = base.copy(fontSize = 12.sp),
            labelSmall = base.copy(fontSize = 11.sp),
            titleLarge = base.copy(fontSize = 18.sp, lineHeight = 24.sp),
            titleMedium = base.copy(fontSize = 15.sp),
            titleSmall = base,
        ),
        content = content,
    )
}
