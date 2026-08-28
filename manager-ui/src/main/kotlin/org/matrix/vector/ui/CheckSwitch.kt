package org.matrix.vector.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A Material 3 switch that shows a **check when on and a cross when off** inside the thumb.
 *
 * The ordinary Material 3 `Switch` is a slider whose thumb colour alone says which state it is in;
 * a row where the thumb is the only clue is hard to read at a glance, and a module list with a
 * switch per row makes "read the colour" the whole interaction. Putting the mark in the thumb keeps
 * the switch's Material 3 skeleton (the track, the shape, the motion) while the state is legible in
 * the mark itself.
 *
 * It is a thin wrapper: all of the switch's own parameters are forwarded unchanged, and only
 * [thumbContent] is added. The mark colour follows the switch's `iconColor`, so the check and the
 * cross pick up the same themed colour the switch would have used for its thumb.
 */
@Composable
fun CheckSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        thumbContent = {
            Icon(
                imageVector = if (checked) Icons.Rounded.Check else Icons.Rounded.Close,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(SwitchDefaults.IconSize),
            )
        },
    )
}
