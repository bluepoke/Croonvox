// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalMeaVoxColors = staticCompositionLocalOf { MeaVoxExtraColorsDark }
private val LocalMeaVoxType = staticCompositionLocalOf { MeaVoxExtraTypographyDefault }

/**
 * Black and gold, always dark, the same on every device: no dynamic colour (Material You)
 * and no light theme, so the brand colours from the launcher icon hold everywhere.
 * The window background in res/values/themes.xml matches, so nothing flashes before Compose draws.
 */
@Composable
fun MeaVoxTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMeaVoxColors provides MeaVoxExtraColorsDark,
        LocalMeaVoxType provides MeaVoxExtraTypographyDefault,
    ) {
        MaterialTheme(
            colorScheme = MeaVoxColorScheme,
            typography = MeaVoxTypography,
            shapes = MeaVoxShapes,
            content = content,
        )
    }
}

/** MeaVox additions to [MaterialTheme]: `MeaVoxTheme.colors.record`, `MeaVoxTheme.type.lyric`. */
object MeaVoxTheme {
    val colors: MeaVoxExtraColors
        @Composable @ReadOnlyComposable
        get() = LocalMeaVoxColors.current

    val type: MeaVoxExtraTypography
        @Composable @ReadOnlyComposable
        get() = LocalMeaVoxType.current
}
