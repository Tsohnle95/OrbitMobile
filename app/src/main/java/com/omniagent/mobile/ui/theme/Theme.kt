package com.omniagent.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class OmniColors(
    val bg: Color,
    val bgPanel: Color,
    val bgInset: Color,
    val bgElev: Color,
    val bgActivePill: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentHover: Color,
    val accentDim: Color,
    val accentTint: Color,
    val green: Color,
    val red: Color,
    val yellow: Color,
    val sky: Color,
    val border: Color,
    val borderStrong: Color,
    val borderSubtle: Color,
    val sendBackgroundTop: Color,
    val sendBackgroundBottom: Color,
    val sendContent: Color,
)

val PaperColors = OmniColors(
    bg = Color(0xFFF4EEE1),
    bgPanel = Color(0xFFFBF7EC),
    bgInset = Color(0xFFEEE5D4),
    bgElev = Color(0xFFFFFAF0),
    bgActivePill = Color(0xFFE3D7C4),
    text = Color(0xFF2B2119),
    textDim = Color(0xFF6B5F50),
    textFaint = Color(0xFF948571),
    accent = Color(0xFF617A68),
    accentHover = Color(0xFF4F6757),
    accentDim = Color(0x26617A68),
    accentTint = Color(0x17617A68),
    green = Color(0xFF587657),
    red = Color(0xFFAA624F),
    yellow = Color(0xFF9C742F),
    sky = Color(0xFF49708F),
    border = Color(0x132B2119),
    borderStrong = Color(0x272B2119),
    borderSubtle = Color(0x0D2B2119),
    sendBackgroundTop = Color(0xFF708976),
    sendBackgroundBottom = Color(0xFF566F5D),
    sendContent = Color(0xFFFFFAF0),
)

val DuskColors = OmniColors(
    bg = Color(0xFF171412),
    bgPanel = Color(0xFF262220),
    bgInset = Color(0xFF201D1B),
    bgElev = Color(0xFF2D2926),
    bgActivePill = Color(0xFF37322E),
    text = Color(0xFFE8E3DD),
    textDim = Color(0xFFA8A29E),
    textFaint = Color(0xFF8F8880),
    accent = Color(0xFF9EB4A1),
    accentHover = Color(0xFFB2C4B4),
    accentDim = Color(0x299EB4A1),
    accentTint = Color(0x179EB4A1),
    green = Color(0xFFA9CBAD),
    red = Color(0xFFE2988A),
    yellow = Color(0xFFE5C084),
    sky = Color(0xFF8FBCD9),
    border = Color(0x0DFFFFFF),
    borderStrong = Color(0x1AFFFFFF),
    borderSubtle = Color(0x0AFFFFFF),
    sendBackgroundTop = Color(0xFF9EB4A1),
    sendBackgroundBottom = Color(0xFF778F7C),
    sendContent = Color(0xFF172019),
)

val LocalOmniColors = staticCompositionLocalOf { PaperColors }

private val PaperScheme = lightColorScheme(
    primary = PaperColors.accent,
    onPrimary = PaperColors.sendContent,
    primaryContainer = PaperColors.accentDim,
    onPrimaryContainer = PaperColors.text,
    secondary = PaperColors.textDim,
    background = PaperColors.bgPanel,
    onBackground = PaperColors.text,
    surface = PaperColors.bgPanel,
    onSurface = PaperColors.text,
    surfaceVariant = PaperColors.bgInset,
    onSurfaceVariant = PaperColors.textDim,
    outline = PaperColors.borderStrong,
    error = PaperColors.red,
)

private val DuskScheme = darkColorScheme(
    primary = DuskColors.accent,
    onPrimary = DuskColors.sendContent,
    primaryContainer = DuskColors.accentDim,
    onPrimaryContainer = DuskColors.text,
    secondary = DuskColors.textDim,
    background = DuskColors.bgPanel,
    onBackground = DuskColors.text,
    surface = DuskColors.bgPanel,
    onSurface = DuskColors.text,
    surfaceVariant = DuskColors.bgInset,
    onSurfaceVariant = DuskColors.textDim,
    outline = DuskColors.borderStrong,
    error = DuskColors.red,
)

val OmniTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.W400, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.W500, fontSize = 19.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.W500, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.5.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = Color.Unspecified),
    labelLarge = TextStyle(fontWeight = FontWeight.W500, fontSize = 13.sp),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 12.sp),
)

val radiusSm = 9.dp
val radiusMd = 12.dp
val radiusLg = 16.dp
val radiusXl = 20.dp

@Composable
fun OmniAgentTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val omni = if (darkTheme) DuskColors else PaperColors
    val scheme = if (darkTheme) DuskScheme else PaperScheme
    androidx.compose.runtime.CompositionLocalProvider(LocalOmniColors provides omni) {
        MaterialTheme(
            colorScheme = scheme,
            typography = OmniTypography,
            shapes = MaterialTheme.shapes.copy(
                small = RoundedCornerShape(radiusSm),
                medium = RoundedCornerShape(radiusMd),
                large = RoundedCornerShape(radiusLg),
            ),
            content = content,
        )
    }
}
