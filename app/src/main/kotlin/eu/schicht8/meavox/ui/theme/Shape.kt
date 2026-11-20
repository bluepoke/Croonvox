// SPDX-License-Identifier: GPL-3.0-or-later
package eu.schicht8.meavox.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Calm, medium corners instead of pills: 6 dp for chips and text fields,
 * 12 dp for buttons, cards, sheets and dialogs. Circles only for round controls.
 */
internal val MeaVoxShapes = Shapes(
    // Text fields, menus, chips.
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    // Cards.
    medium = RoundedCornerShape(12.dp),
    // Extended FAB ("Import file", "Sing").
    large = RoundedCornerShape(12.dp),
    // Dialogs, bottom-sheet top edge.
    extraLarge = RoundedCornerShape(12.dp),
)

/**
 * Material 3 buttons are pill-shaped by default and don't read [Shapes].
 * Pass this to Button, OutlinedButton, TextButton and FilledTonalButton,
 * e.g. `Button(onClick, shape = MeaVoxButtonShape)`, Record and Stop included.
 */
val MeaVoxButtonShape = RoundedCornerShape(12.dp)
