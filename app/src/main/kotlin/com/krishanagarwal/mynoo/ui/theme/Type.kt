package com.krishanagarwal.mynoo.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val MynooTypography = Typography(
    displayLarge  = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold,   color = TextDark),
    displayMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold,   color = TextDark),
    displaySmall  = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold,   color = TextDark),
    headlineLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextDark),
    headlineMedium= TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextDark),
    headlineSmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextDark),
    titleLarge    = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold,   color = TextDark),
    titleMedium   = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextDark),
    titleSmall    = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextMid),
    bodyLarge     = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, color = TextDark),
    bodyMedium    = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, color = TextDark),
    bodySmall     = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = TextMid),
    labelLarge    = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextDark),
    labelMedium   = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextMid),
    labelSmall    = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, color = TextLight),
)
