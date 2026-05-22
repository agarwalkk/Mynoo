package com.krishanagarwal.mynoo.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Mynoo always uses a light (warm-cream) scheme — no dark mode in Phase 1 ──

private val MynooColorScheme = lightColorScheme(
    primary          = MynooOrange,
    onPrimary        = Color.White,
    primaryContainer = MynooYellow,
    onPrimaryContainer = TextDark,

    secondary        = MynooTeal,
    onSecondary      = Color.White,
    secondaryContainer = MynooMint,
    onSecondaryContainer = TextDark,

    background       = MynooBackground,
    onBackground     = TextDark,
    surface          = MynooCard,
    onSurface        = TextDark,
    surfaceVariant   = Color(0xFFF5EDD8),
    onSurfaceVariant = TextMid,

    outline          = MynooBorder,
    error            = MynooRed,
    onError          = Color.White,
)

// ── Composition local for extra tokens not in Material3 ──────────────────────
data class MynooExtras(
    val langEn: Color = LangEnglish,
    val langHi: Color = LangHindi,
    val langPa: Color = LangPunjabi,
    val textLight: Color = TextLight,
    val divider: Color = MynooDivider,
)

val LocalMynooExtras = staticCompositionLocalOf { MynooExtras() }

@Composable
fun MynooTheme(
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMynooExtras provides MynooExtras()) {
        MaterialTheme(
            colorScheme = MynooColorScheme,
            typography  = MynooTypography,
            content     = content,
        )
    }
}
