// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import eu.schicht8.meavox.R

// Bundled, not downloadable: F-Droid builds can't rely on Google Play services.
// Both faces are SIL Open Font License 1.1 (docs/fonts).

/** Display face: screen and song titles, the countdown digit. */
val DmSerifDisplay = FontFamily(
    Font(R.font.dm_serif_display_regular, FontWeight.Normal),
)

/** Text face: everything else, lyrics included. */
val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

private fun serif(size: Int, line: Int) = TextStyle(
    fontFamily = DmSerifDisplay,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = line.sp,
)

private fun sans(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Manrope,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

/**
 * Material 3 roles mapped to the design system's styles:
 * display → countdown digit; headline/titleLarge → screen and top-bar titles (serif);
 * titleMedium → list titles; body → text; bodySmall → captions with tabular figures;
 * label → buttons, chips, tabs.
 */
internal val MeaVoxTypography = Typography(
    displayLarge = serif(56, 64),
    displayMedium = serif(45, 52),
    displaySmall = serif(36, 44),
    headlineLarge = serif(32, 40),
    headlineMedium = serif(28, 34),
    headlineSmall = serif(24, 32),
    // TopAppBar titles.
    titleLarge = serif(22, 28),
    titleMedium = sans(18, 24, FontWeight.SemiBold),
    titleSmall = sans(14, 20, FontWeight.SemiBold),
    bodyLarge = sans(16, 24, FontWeight.Normal),
    bodyMedium = sans(14, 20, FontWeight.Normal),
    // Times, Take details, pipeline status: figures line up while they count.
    bodySmall = sans(12, 16, FontWeight.Medium).copy(fontFeatureSettings = "tnum"),
    labelLarge = sans(14, 20, FontWeight.SemiBold, tracking = 0.02),
    labelMedium = sans(12, 16, FontWeight.SemiBold, tracking = 0.02),
    labelSmall = sans(11, 16, FontWeight.Medium, tracking = 0.02),
)

/** Text styles Material 3 has no role for. Read them with `MeaVoxTheme.type`. */
@Immutable
data class MeaVoxExtraTypography(
    /**
     * Karaoke lines. The current line draws this SemiBold, scaled 1.15× and in primary;
     * the others Normal in onSurfaceVariant (further ones in `MeaVoxTheme.colors.inkFaint`).
     */
    val lyric: TextStyle,
    /** Page view of the lyrics: whole verses. */
    val lyricPage: TextStyle,
    /** "● Recording · 0:21": tabular so the time doesn't jiggle. */
    val recordingTime: TextStyle,
)

internal val MeaVoxExtraTypographyDefault = MeaVoxExtraTypography(
    lyric = sans(20, 28, FontWeight.Normal),
    lyricPage = sans(18, 28, FontWeight.Normal),
    recordingTime = sans(16, 22, FontWeight.Bold).copy(fontFeatureSettings = "tnum"),
)
