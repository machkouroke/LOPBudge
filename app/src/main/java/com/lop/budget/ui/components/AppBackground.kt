package com.lop.budget.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.unit.dp
import com.lop.budget.ui.theme.LopDesign

/**
 * Fond d'écran unifié "Budge-like" (dark + light).
 *
 * Objectif: donner un look premium (gradient + légère vignette) sans dépendre des couleurs M3.
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val bg = LopDesign.background
    val tokens = LopDesign.tokens

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(bg.top, bg.bottom),
                    tileMode = TileMode.Clamp,
                ),
                shape = RectangleShape,
            ),
    ) {
        // Vignette douce (simule un focus au centre, comme Budge)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, bg.vignette.copy(alpha = tokens.bgVignetteAlpha)),
                        radius = 900f,
                    ),
                ),
        )

        content()
    }
}
