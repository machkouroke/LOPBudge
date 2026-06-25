package com.lop.budget.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lop.budget.ui.theme.LopDesign

/**
 * Carte "flottante" : rendu premium unifié (glass-like).
 *
 * Les écrans ne doivent pas choisir une couleur arbitraire : utiliser cette carte.
 */
@Composable
fun FloatingCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface.copy(alpha = LopDesign.tokens.cardAlpha),
    cornerRadius: Dp = LopDesign.tokens.radiusCard,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    val t = LopDesign.tokens
    val shape = RoundedCornerShape(cornerRadius)

    Surface(
        modifier = modifier,
        color = Color.Transparent,
        shape = shape,
        tonalElevation = 0.dp,
        shadowElevation = t.cardShadow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = t.cardBorderAlpha)),
    ) {
        // Gradient interne subtil + alpha contrôlé pour éviter les "teintes bizarres".
        Box(
            modifier = Modifier
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            color,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = (t.cardAlpha * 0.72f).coerceIn(0f, 1f)),
                        ),
                    ),
                )
                .padding(contentPadding),
        ) {
            content()
        }
    }
}

/** Pastille ronde colorée contenant une icône de catégorie/compte. */
@Composable
fun CircleIcon(
    icon: ImageVector,
    tint: Color,
    background: Color,
    size: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Petit chip capsule (catégorie, tag, filtre). */
@Composable
fun PillTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.18f), shape = CircleShape, modifier = modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
