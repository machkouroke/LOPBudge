package com.lop.budget.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lop.budget.data.local.entity.CategoryEntity
import com.lop.budget.util.IconMapper

val ScreenPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 120.dp)

/** Carte "flottante" : surface arrondie, légèrement surélevée, padding interne. */
@Composable
fun FloatingCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    cornerRadius: Dp = 28.dp,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        color = color,
        shape = RoundedCornerShape(cornerRadius),
        tonalElevation = 2.dp,
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
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

/** Bouton segment capsule, utilisé pour les sélecteurs Dépense/Revenu. */
@Composable
fun SegmentedButton(
    label: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.pressScaleClickable(intent = HapticIntent.Selection, onClick = onClick),
        shape = CircleShape,
        color = if (selected) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

/** Sélecteur horizontal de catégories avec icônes rondes. */
@Composable
fun CategoryPicker(
    categories: List<CategoryEntity>,
    selectedId: Long?,
    iconSize: Dp = 52.dp,
    onSelect: (Long) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(categories, key = { it.id }) { cat ->
            val selected = selectedId == cat.id
            val c = Color(cat.colorArgb)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.pressScaleClickable(intent = HapticIntent.Selection) { onSelect(cat.id) },
            ) {
                CircleIcon(
                    icon = IconMapper.get(cat.icon),
                    tint = c,
                    background = if (selected) c.copy(alpha = 0.32f) else c.copy(alpha = 0.14f),
                    size = iconSize,
                )
                Spacer(Modifier.height(4.dp))
                Text(cat.name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
