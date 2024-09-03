package org.xtimms.shirizu.sections.stats.categories

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.xtimms.shirizu.utils.material.HarmonizedColorPalette
import java.math.BigDecimal

@Composable
fun TagAmount(
    modifier: Modifier = Modifier,
    value: String,
    amount: BigDecimal = BigDecimal.ZERO,
    isSpecial: Boolean = false,
    palette: HarmonizedColorPalette? = null,
) {
    val context = LocalContext.current

    Surface(
        shape = CircleShape,
        color = palette?.main ?: MaterialTheme.colorScheme.surface,
        contentColor = palette?.onSurface ?: MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontStyle = if (isSpecial) FontStyle.Italic else FontStyle.Normal,
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = amount.toString(),
                softWrap = false,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.W900),
            )
        }
    }
}