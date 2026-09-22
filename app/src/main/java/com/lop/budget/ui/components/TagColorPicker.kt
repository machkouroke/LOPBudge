package com.lop.budget.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lop.budget.ui.common.TestTags

/**
 * Palette des couleurs de tag (LOP-21, P-2).
 *
 * **Une seule liste** pour les deux endroits où un tag se crée : l'écran de gestion et la modal
 * tags du formulaire de transaction. La modal imposait auparavant un violet en dur, si bien que la
 * couleur — qui fait pourtant partie du tag — dépendait de l'écran par lequel on était passé.
 */
val TagColors: List<Color> = listOf(
    Color(0xFFE53935), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFF5E35B1),
    Color(0xFF3949AB), Color(0xFF1E88E5), Color(0xFF039BE5), Color(0xFF00ACC1),
    Color(0xFF00897B), Color(0xFF00838F), Color(0xFF43A047), Color(0xFF2E7D32),
    Color(0xFF7CB342), Color(0xFFC0CA33), Color(0xFFFDD835), Color(0xFFFFB300),
    Color(0xFFFB8C00), Color(0xFFF4511E), Color(0xFF6D4C41), Color(0xFF546E7A),
)

/** Couleur proposée par défaut à la création, dans les deux écrans. */
val DefaultTagColor: Color = TagColors.first()

/**
 * Sélecteur de couleur d'un tag, partagé par l'écran de gestion et la modal tags.
 *
 * Chaque pastille porte `TestTags.TAG_COLOR_PICKER` suffixé de sa valeur ARGB : la couleur choisie
 * est donc désignable par sa valeur métier, et non par une position dans la liste.
 */
@Composable
fun TagColorPicker(
    selected: Color,
    onSelect: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(TagColors) { color ->
            val isSelected = selected == color
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onSelect(color) }
                    .border(
                        if (isSelected) 3.dp else 0.dp,
                        MaterialTheme.colorScheme.onSurface,
                        CircleShape,
                    )
                    .testTag("${TestTags.TAG_COLOR_PICKER}_${color.toArgb()}"),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
