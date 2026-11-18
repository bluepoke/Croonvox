// SPDX-License-Identifier: GPL-3.0-or-later
package app.croonvox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalCroonvoxColors = staticCompositionLocalOf { CroonvoxExtraColorsDark }
private val LocalCroonvoxType = staticCompositionLocalOf { CroonvoxExtraTypographyDefault }

/**
 * Black and gold, always dark, the same on every device: no dynamic colour (Material You)
 * and no light theme, so the brand colours from the launcher icon hold everywhere.
 * The window background in res/values/themes.xml matches, so nothing flashes before Compose draws.
 */
@Composable
fun CroonvoxTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalCroonvoxColors provides CroonvoxExtraColorsDark,
        LocalCroonvoxType provides CroonvoxExtraTypographyDefault,
    ) {
        MaterialTheme(
            colorScheme = CroonvoxColorScheme,
            typography = CroonvoxTypography,
            shapes = CroonvoxShapes,
            content = content,
        )
    }
}

/** Croonvox additions to [MaterialTheme]: `CroonvoxTheme.colors.record`, `CroonvoxTheme.type.lyric`. */
object CroonvoxTheme {
    val colors: CroonvoxExtraColors
        @Composable @ReadOnlyComposable
        get() = LocalCroonvoxColors.current

    val type: CroonvoxExtraTypography
        @Composable @ReadOnlyComposable
        get() = LocalCroonvoxType.current
}
