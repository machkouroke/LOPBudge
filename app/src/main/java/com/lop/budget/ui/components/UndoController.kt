package com.lop.budget.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.lop.budget.ui.motion.MotionSpec
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.delay

@Stable
data class UndoEvent(
    val message: String,
    val actionLabel: String = "Annuler",
    val onUndo: () -> Unit,
    val token: Long = System.nanoTime(),
)

@Stable
class UndoController {
    var current by mutableStateOf<UndoEvent?>(null)
        private set

    fun show(message: String, actionLabel: String = "Annuler", onUndo: () -> Unit) {
        current = UndoEvent(message = message, actionLabel = actionLabel, onUndo = onUndo)
    }

    fun dismiss() {
        current = null
    }

    fun triggerUndo() {
        current?.onUndo?.invoke()
        current = null
    }
}

@Composable
fun rememberUndoController(): UndoController = remember { UndoController() }

val LocalUndoController = staticCompositionLocalOf<UndoController> {
    error("UndoController non fourni. Enveloppez l'UI avec un CompositionLocalProvider(LocalUndoController provides ...).")
}

@Composable
fun UndoBanner(
    controller: UndoController,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    durationMs: Long = 4000L,
) {
    val event = controller.current

    LaunchedEffect(event?.token) {
        if (event != null) {
            delay(durationMs)
            if (controller.current?.token == event.token) controller.dismiss()
        }
    }

    AnimatedVisibility(
        visible = event != null,
        enter = slideInVertically(animationSpec = tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut)) { it / 2 } +
            fadeIn(tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut)),
        exit = slideOutVertically(animationSpec = tween(MotionSpec.FAST_MS, easing = MotionSpec.easeOut)) { it / 2 } +
            fadeOut(tween(MotionSpec.FAST_MS, easing = MotionSpec.easeOut)),
        modifier = modifier,
    ) {
        val shape = RoundedCornerShape(22.dp)
        Surface(
            shape = shape,
            color = androidx.compose.ui.graphics.Color.Transparent,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
            shadowElevation = 14.dp,
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(shape)
                .then(
                    if (hazeState != null) Modifier.hazeEffect(state = hazeState, style = HazeMaterials.regular())
                    else Modifier,
                )
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f),
                        ),
                    ),
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = event?.message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickableHaptic(intent = HapticIntent.Confirm) { controller.triggerUndo() },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Undo, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = event?.actionLabel ?: "Annuler",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
