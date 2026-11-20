package com.example.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.example.model.AccentPalette

data class PalettePreviewColors(
    val primary: Color,
    val secondary: Color,
    val container: Color,
)

// 1. Slate Teal (青灰)
val SlateTealLightColorScheme = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EEFFD),
    onPrimaryContainer = Color(0xFF001F24),
    secondary = Color(0xFF4A6267),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCDE7EC),
    onSecondaryContainer = Color(0xFF051F23),
    tertiary = Color(0xFF525E7D),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDAE2FF),
    onTertiaryContainer = Color(0xFF0E1B37),
    background = Color(0xFFF5FAFB),
    onBackground = Color(0xFF171D1E),
    surface = Color(0xFFF5FAFB),
    onSurface = Color(0xFF171D1E),
    surfaceVariant = Color(0xFFDBE4E6),
    onSurfaceVariant = Color(0xFF3F484A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F6),
    surfaceContainer = Color(0xFFE9EFF0),
    surfaceContainerHigh = Color(0xFFE3E9EA),
    surfaceContainerHighest = Color(0xFFDEE3E5),
    outline = Color(0xFF6F797A),
    outlineVariant = Color(0xFFBFC8CA),
)

val SlateTealDarkColorScheme = darkColorScheme(
    primary = Color(0xFF82D3E0),
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF9EEFFD),
    secondary = Color(0xFFB1CBD0),
    onSecondary = Color(0xFF1C3438),
    secondaryContainer = Color(0xFF334B4F),
    onSecondaryContainer = Color(0xFFCDE7EC),
    tertiary = Color(0xFFBAC6EA),
    onTertiary = Color(0xFF24304D),
    tertiaryContainer = Color(0xFF3B4664),
    onTertiaryContainer = Color(0xFFDAE2FF),
    background = Color(0xFF0E1415),
    onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF0E1415),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3F484A),
    onSurfaceVariant = Color(0xFFBFC8CA),
    surfaceContainerLowest = Color(0xFF090F10),
    surfaceContainerLow = Color(0xFF171D1E),
    surfaceContainer = Color(0xFF1B2122),
    surfaceContainerHigh = Color(0xFF252B2C),
    surfaceContainerHighest = Color(0xFF303637),
    outline = Color(0xFF899294),
    outlineVariant = Color(0xFF3F484A),
)

// 2. Ocean Blue (海蓝)
val OceanBlueLightColorScheme = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF535F70),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD7E3F7),
    onSecondaryContainer = Color(0xFF101C2B),
    tertiary = Color(0xFF6B5778),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2DAFF),
    onTertiaryContainer = Color(0xFF251431),
    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE0E2E8),
    outline = Color(0xFF73777F),
    outlineVariant = Color(0xFFC3C7CF),
)

val OceanBlueDarkColorScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    secondary = Color(0xFFBBC7DB),
    onSecondary = Color(0xFF253140),
    secondaryContainer = Color(0xFF3B4858),
    onSecondaryContainer = Color(0xFFD7E3F7),
    tertiary = Color(0xFFD6BEE4),
    onTertiary = Color(0xFF3B2948),
    tertiaryContainer = Color(0xFF523F5F),
    onTertiaryContainer = Color(0xFFF2DAFF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2F),
    surfaceContainerHighest = Color(0xFF32353A),
    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
)

// 3. Forest Green (森绿)
val ForestGreenLightColorScheme = lightColorScheme(
    primary = Color(0xFF2B6A45),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFAFF2C3),
    onPrimaryContainer = Color(0xFF002110),
    secondary = Color(0xFF4F6354),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD2E8D5),
    onSecondaryContainer = Color(0xFF0D1F13),
    tertiary = Color(0xFF3B6470),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBFE9F8),
    onTertiaryContainer = Color(0xFF001F27),
    background = Color(0xFFF6FBF4),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF6FBF4),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DC),
    onSurfaceVariant = Color(0xFF414942),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F5EE),
    surfaceContainer = Color(0xFFEAEFE8),
    surfaceContainerHigh = Color(0xFFE4EAE3),
    surfaceContainerHighest = Color(0xFFDFE4DD),
    outline = Color(0xFF717972),
    outlineVariant = Color(0xFFC0C9C0),
)

val ForestGreenDarkColorScheme = darkColorScheme(
    primary = Color(0xFF93D5A8),
    onPrimary = Color(0xFF00391E),
    primaryContainer = Color(0xFF0E512F),
    onPrimaryContainer = Color(0xFFAFF2C3),
    secondary = Color(0xFFB6CCBA),
    onSecondary = Color(0xFF223527),
    secondaryContainer = Color(0xFF384B3D),
    onSecondaryContainer = Color(0xFFD2E8D5),
    tertiary = Color(0xFFA3CDDB),
    onTertiary = Color(0xFF033540),
    tertiaryContainer = Color(0xFF224C58),
    onTertiaryContainer = Color(0xFFBFE9F8),
    background = Color(0xFF0F1511),
    onBackground = Color(0xFFDFE4DD),
    surface = Color(0xFF0F1511),
    onSurface = Color(0xFFDFE4DD),
    surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC0C9C0),
    surfaceContainerLowest = Color(0xFF0A100C),
    surfaceContainerLow = Color(0xFF171D19),
    surfaceContainer = Color(0xFF1B211D),
    surfaceContainerHigh = Color(0xFF262B27),
    surfaceContainerHighest = Color(0xFF303632),
    outline = Color(0xFF8A938B),
    outlineVariant = Color(0xFF414942),
)

// 4. Warm Amber (暖橙)
val WarmAmberLightColorScheme = lightColorScheme(
    primary = Color(0xFF9A4522),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCE),
    onPrimaryContainer = Color(0xFF380D00),
    secondary = Color(0xFF77574B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCE),
    onSecondaryContainer = Color(0xFF2C160C),
    tertiary = Color(0xFF695E2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2E2A8),
    onTertiaryContainer = Color(0xFF211B00),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231916),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231916),
    surfaceVariant = Color(0xFFF5DDD5),
    onSurfaceVariant = Color(0xFF53433E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF1EC),
    surfaceContainer = Color(0xFFFCEAE4),
    surfaceContainerHigh = Color(0xFFF6E4DE),
    surfaceContainerHighest = Color(0xFFF0DFD8),
    outline = Color(0xFF85736D),
    outlineVariant = Color(0xFFD8C2BA),
)

val WarmAmberDarkColorScheme = darkColorScheme(
    primary = Color(0xFFFFB59A),
    onPrimary = Color(0xFF5A1C00),
    primaryContainer = Color(0xFF7C2E0D),
    onPrimaryContainer = Color(0xFFFFDBCE),
    secondary = Color(0xFFE7BDB0),
    onSecondary = Color(0xFF442A20),
    secondaryContainer = Color(0xFF5D4035),
    onSecondaryContainer = Color(0xFFFFDBCE),
    tertiary = Color(0xFFD5C68E),
    onTertiary = Color(0xFF393005),
    tertiaryContainer = Color(0xFF50461A),
    onTertiaryContainer = Color(0xFFF2E2A8),
    background = Color(0xFF1A110E),
    onBackground = Color(0xFFF0DFD8),
    surface = Color(0xFF1A110E),
    onSurface = Color(0xFFF0DFD8),
    surfaceVariant = Color(0xFF53433E),
    onSurfaceVariant = Color(0xFFD8C2BA),
    surfaceContainerLowest = Color(0xFF140C09),
    surfaceContainerLow = Color(0xFF231916),
    surfaceContainer = Color(0xFF271D1A),
    surfaceContainerHigh = Color(0xFF322824),
    surfaceContainerHighest = Color(0xFF3D322E),
    outline = Color(0xFFA08D86),
    outlineVariant = Color(0xFF53433E),
)

fun colorSchemeForPalette(palette: AccentPalette, darkTheme: Boolean): ColorScheme {
    return when (palette) {
        AccentPalette.SLATE_TEAL -> if (darkTheme) SlateTealDarkColorScheme else SlateTealLightColorScheme
        AccentPalette.OCEAN_BLUE -> if (darkTheme) OceanBlueDarkColorScheme else OceanBlueLightColorScheme
        AccentPalette.FOREST_GREEN -> if (darkTheme) ForestGreenDarkColorScheme else ForestGreenLightColorScheme
        AccentPalette.WARM_AMBER -> if (darkTheme) WarmAmberDarkColorScheme else WarmAmberLightColorScheme
    }
}

fun previewColorsForPalette(palette: AccentPalette): PalettePreviewColors {
    return when (palette) {
        AccentPalette.SLATE_TEAL -> PalettePreviewColors(
            primary = Color(0xFF006874),
            secondary = Color(0xFF4A6267),
            container = Color(0xFF9EEFFD),
        )
        AccentPalette.OCEAN_BLUE -> PalettePreviewColors(
            primary = Color(0xFF0061A4),
            secondary = Color(0xFF535F70),
            container = Color(0xFFD1E4FF),
        )
        AccentPalette.FOREST_GREEN -> PalettePreviewColors(
            primary = Color(0xFF2B6A45),
            secondary = Color(0xFF4F6354),
            container = Color(0xFFAFF2C3),
        )
        AccentPalette.WARM_AMBER -> PalettePreviewColors(
            primary = Color(0xFF9A4522),
            secondary = Color(0xFF77574B),
            container = Color(0xFFFFDBCE),
        )
    }
}
