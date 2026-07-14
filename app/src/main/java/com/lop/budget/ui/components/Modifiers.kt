package com.lop.budget.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.lop.budget.ui.motion.MotionSpec

/** Clic sans effet de vague (ripple), pratique pour les icônes de barre. */
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onClick,
    )
}

/**
 * Types d'haptique "métier" (intention), mappés vers HapticFeedbackType.
 */
enum class HapticIntent {
    /** Tap léger (changement d'onglet, toggle simple). */
    Tap,
    /** Sélection dans une liste / picker. */
    Selection,
    /** Action principale / validation. */
    Confirm,
    /** Changement de période (swipe accueil). */
    PeriodChanged,
    /** Erreur ou action destructive (à utiliser avec parcimonie). */
    Destructive,
}

@Composable
private fun rememberHapticMapper(): (HapticIntent) -> HapticFeedbackType {
    return { intent ->
        when (intent) {
            HapticIntent.Tap -> HapticFeedbackType.TextHandleMove
            HapticIntent.Selection -> HapticFeedbackType.TextHandleMove
            HapticIntent.Confirm -> HapticFeedbackType.LongPress
            HapticIntent.PeriodChanged -> HapticFeedbackType.LongPress
            HapticIntent.Destructive -> HapticFeedbackType.LongPress
        }
    }
}

/**
 * Clickable sans ripple + haptique (optionnel).
 */
fun Modifier.clickableHaptic(
    intent: HapticIntent? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val haptic = LocalHapticFeedback.current
    val map = rememberHapticMapper()
    clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = {
            if (intent != null) haptic.performHapticFeedback(map(intent))
            onClick()
        },
    )
}

/**
 * Micro-animation d'appui (press scale) pour rendre les taps plus "vivants".
 *
 * PERF FIX #5 : remplacement de Modifier.composed{} par un @Composable wrapper.
 * `composed {}` crée une nouvelle instance de MutableInteractionSource à chaque recomposition
 * si la référence n'est pas stable, ce qui multiplie les allocations avec des listes longues.
 * On expose désormais un @Composable Modifier qui mémorise explicitement ses dépendances.
 */
@Composable
fun Modifier.pressScaleClickable(
    intent: HapticIntent? = null,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed = interactionSource.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val map = rememberHapticMapper()

    val scale = animateFloatAsState(
        targetValue = if (isPressed.value) pressedScale else 1f,
        animationSpec = MotionSpec.floatSpring(),
        label = "pressScale",
    )

    return this
        .scale(scale.value)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                if (intent != null) haptic.performHapticFeedback(map(intent))
                onClick()
            },
        )
}

/**
 * Wrapper pratique quand on veut scaler un bloc entier (Surface/Card) au press.
 */
@Composable
fun PressScale(
    modifier: Modifier = Modifier,
    intent: HapticIntent? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.pressScaleClickable(intent = intent, onClick = onClick)) {
        content()
    }
}
