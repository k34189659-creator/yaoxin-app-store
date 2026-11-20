package tw.dafeiyu.mangatranslator.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** 全系列統一主題入口；深淺色共用同一組 token，只切換色票。 */
@Composable
fun YaoXinTheme(dark: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkYaoXinColors else LightYaoXinColors
    val scheme = if (dark) darkColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.primarySoft,
        onPrimaryContainer = c.text,
        secondary = if (dark) androidx.compose.ui.graphics.Color(0xFFD4C1EF) else androidx.compose.ui.graphics.Color(0xFF8662B6),
        onSecondary = if (dark) androidx.compose.ui.graphics.Color(0xFF352444) else androidx.compose.ui.graphics.Color.White,
        secondaryContainer = c.surfaceAlt,
        onSecondaryContainer = c.text,
        tertiary = c.info,
        tertiaryContainer = c.primarySoft,
        onTertiaryContainer = c.text,
        background = c.background,
        onBackground = c.text,
        surface = c.surface,
        onSurface = c.text,
        surfaceVariant = c.surfaceAlt,
        surfaceContainer = c.surfaceAlt,
        surfaceContainerLow = c.surface,
        surfaceContainerHigh = c.surfaceAlt,
        onSurfaceVariant = c.textMuted,
        outline = c.outline,
        outlineVariant = c.outline,
        error = c.error,
        onError = if (dark) androidx.compose.ui.graphics.Color(0xFF341B27) else androidx.compose.ui.graphics.Color.White,
    ) else lightColorScheme(
        primary = c.primary,
        onPrimary = c.onPrimary,
        primaryContainer = c.primarySoft,
        onPrimaryContainer = c.text,
        secondary = if (dark) androidx.compose.ui.graphics.Color(0xFFD4C1EF) else androidx.compose.ui.graphics.Color(0xFF8662B6),
        onSecondary = if (dark) androidx.compose.ui.graphics.Color(0xFF352444) else androidx.compose.ui.graphics.Color.White,
        secondaryContainer = c.surfaceAlt,
        onSecondaryContainer = c.text,
        tertiary = c.info,
        tertiaryContainer = c.primarySoft,
        onTertiaryContainer = c.text,
        background = c.background,
        onBackground = c.text,
        surface = c.surface,
        onSurface = c.text,
        surfaceVariant = c.surfaceAlt,
        surfaceContainer = c.surfaceAlt,
        surfaceContainerLow = c.surface,
        surfaceContainerHigh = c.surfaceAlt,
        onSurfaceVariant = c.textMuted,
        outline = c.outline,
        outlineVariant = c.outline,
        error = c.error,
        onError = if (dark) androidx.compose.ui.graphics.Color(0xFF341B27) else androidx.compose.ui.graphics.Color.White,
    )
    CompositionLocalProvider(LocalYaoXinColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = YxTypography, shapes = YxShapes, content = content)
    }
}
