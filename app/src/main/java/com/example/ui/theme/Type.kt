package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

const val TabularNumericFeature = "tnum"

@OptIn(ExperimentalTextApi::class)
private fun googleSansFlexFonts(weight: FontWeight): List<Font> {
    val variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
        FontVariation.Setting("ROND", 100f),
    )
    return listOf(
        // Prefer built-in Pixel / Android 16-17 system Google Sans Flex variable font
        Font(
            familyName = DeviceFontFamilyName("google-sans-flex"),
            weight = weight,
            variationSettings = variationSettings,
        ),
        // Zero-permission offline bundled variable Google Sans Flex fallback
        // (CJK glyphs automatically fall back to system native sans-serif)
        Font(
            resId = R.font.google_sans_flex,
            weight = weight,
            variationSettings = variationSettings,
        ),
    )
}

val GoogleSansFlexFamily: FontFamily = FontFamily(
    googleSansFlexFonts(FontWeight.Normal) +
        googleSansFlexFonts(FontWeight.Medium) +
        googleSansFlexFonts(FontWeight.SemiBold) +
        googleSansFlexFonts(FontWeight.Bold) +
        googleSansFlexFonts(FontWeight.ExtraBold),
)

val JetBrainsMonoFamily: FontFamily = FontFamily(
    Font(R.font.jetbrains_mono, FontWeight.Normal),
    Font(R.font.jetbrains_mono, FontWeight.Medium),
)

val ExpressiveTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = TabularNumericFeature,
    ),
    labelMedium = TextStyle(
        fontFamily = GoogleSansFlexFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TabularNumericFeature,
    ),
    labelSmall = TextStyle(
        fontFamily = JetBrainsMonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = TabularNumericFeature,
    ),
)
