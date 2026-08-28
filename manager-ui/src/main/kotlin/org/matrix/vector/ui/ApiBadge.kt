package org.matrix.vector.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * `API 101` / `Xposed 93`, with the scale name small and quiet and the number carrying the colour.
 *
 * The scale name is context that rarely changes and repeats down every row; the number is the fact
 * being checked, so it is the only part given weight and colour. A caller with no value to show
 * passes `"?"` for [value] and `incompatible = true`, which keeps the badge the same shape while the
 * missing number reads as missing rather than as a different kind of thing.
 *
 * Model-agnostic on purpose: Vector maps a module's declared API into ([label], [value]); LSPatch
 * passes its own text. Both get one badge instead of two that drift apart.
 */
@Composable
fun ApiBadge(label: String, value: String, incompatible: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    // Single line, never wrapping: the badge sits in a fixed-width column, and a wrapping badge
    // (e.g. "LSPosed\n102") would break the shared name/description start that the fixed column
    // exists to keep. The scale name and number stay on one line however narrow the column is.
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.width(IntrinsicSize.Max),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = colors.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            softWrap = false,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (incompatible) colors.error else colors.primary,
            maxLines = 1,
            softWrap = false,
        )
    }
}
