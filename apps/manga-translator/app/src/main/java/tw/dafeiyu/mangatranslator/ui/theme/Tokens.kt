package tw.dafeiyu.mangatranslator.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 瑤芯系列共用設計語彙：顏色、字級、間距、圓角。 */
@Immutable
data class YaoXinColors(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val primary: Color,
    val primaryDeep: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val text: Color,
    val textMuted: Color,
    val outline: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
)

val LightYaoXinColors = YaoXinColors(
    background = Color(0xFFFFF7FC),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFFFEAF6),
    primary = Color(0xFFB52C6A),
    primaryDeep = Color(0xFF9D1F59),
    onPrimary = Color(0xFFFFFFFF),
    primarySoft = Color(0xFFFFE1EF),
    text = Color(0xFF241C28),
    textMuted = Color(0xFF7A6672),
    outline = Color(0xFFF2D9E6),
    success = Color(0xFF6FD69A),
    warning = Color(0xFFFFD166),
    error = Color(0xFFFF6B81),
    info = Color(0xFF7AB6FF),
)

val DarkYaoXinColors = YaoXinColors(
    background = Color(0xFF2E1E33),
    surface = Color(0xFF3A2740),
    surfaceAlt = Color(0xFF452E4C),
    primary = Color(0xFFFF8FC0),
    primaryDeep = Color(0xFFE85A9B),
    onPrimary = Color(0xFF3A1024),
    primarySoft = Color(0xFF4E3556),
    text = Color(0xFFF6EAF0),
    textMuted = Color(0xFFC3A9B8),
    outline = Color(0xFF4E3556),
    success = Color(0xFF7FE0AC),
    warning = Color(0xFFFFD98A),
    error = Color(0xFFFF8F9E),
    info = Color(0xFF9CC7FF),
)

val LocalYaoXinColors = staticCompositionLocalOf { LightYaoXinColors }

object YxSpace {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

object YxRadius {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val float = 24.dp
}

val YxShapes = Shapes(
    extraSmall = RoundedCornerShape(YxRadius.xs),
    small = RoundedCornerShape(YxRadius.sm),
    medium = RoundedCornerShape(YxRadius.md),
    large = RoundedCornerShape(YxRadius.lg),
    extraLarge = RoundedCornerShape(YxRadius.xl),
)

private fun base(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

val YxTypography = Typography(
    displaySmall = base(28, 36, FontWeight.Bold),
    headlineMedium = base(22, 30, FontWeight.SemiBold),
    titleLarge = base(20, 28, FontWeight.SemiBold),
    titleMedium = base(16, 24, FontWeight.SemiBold),
    bodyLarge = base(15, 22),
    bodyMedium = base(14, 20),
    labelLarge = base(14, 20, FontWeight.Medium),
    labelMedium = base(13, 18, FontWeight.Medium),
    labelSmall = base(12, 16, FontWeight.Medium),
)
