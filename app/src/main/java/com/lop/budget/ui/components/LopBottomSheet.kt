package com.lop.budget.ui.components

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.lop.budget.ui.motion.MotionSpec
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LopBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.75f,
    content: @Composable ColumnScope.() -> Unit,
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val maxHeight = configuration.screenHeightDp.dp * maxHeightFraction
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val dismissPx = with(density) { 140.dp.toPx() }
    val lightIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(screenHeightPx) }
    val scrimAlpha = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }

    fun animateClose() {
        if (closing) return
        closing = true
        scope.launch {
            launch {
                scrimAlpha.animateTo(
                    0f,
                    tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut),
                )
            }
            offsetY.animateTo(
                screenHeightPx,
                tween(MotionSpec.SLOW_MS, easing = MotionSpec.easeOut),
            )
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        launch {
            offsetY.animateTo(
                targetValue = 0f,
                animationSpec = tween(MotionSpec.SLOW_MS, easing = MotionSpec.easeOut),
            )
        }
        launch {
            scrimAlpha.animateTo(
                targetValue = 0.55f,
                animationSpec = tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut),
            )
        }
    }

    BackHandler(onBack = ::animateClose)

    Dialog(
        onDismissRequest = ::animateClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val dialogView = LocalView.current
        val window = (dialogView.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.apply {
                setLayout(MATCH_PARENT, MATCH_PARENT)
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                setDimAmount(0f)
                addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                statusBarColor = android.graphics.Color.TRANSPARENT
                navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isNavigationBarContrastEnforced = false
                    isStatusBarContrastEnforced = false
                }
                WindowCompat.setDecorFitsSystemWindows(this, false)
                WindowCompat.getInsetsController(this, decorView).apply {
                    isAppearanceLightStatusBars = lightIcons
                    isAppearanceLightNavigationBars = lightIcons
                }
            }
        }

        Box(Modifier.fillMaxSize().imePadding()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha.value))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = ::animateClose,
                    ),
            )

            Column(
                modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .offset { IntOffset(0, offsetY.value.roundToInt()) }
                    .heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    // Empêche le clic header / zones vides d’atteindre le scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    val next = (offsetY.value + dragAmount).coerceAtLeast(0f)
                                    scope.launch { offsetY.snapTo(next) }
                                },
                                onDragEnd = {
                                    if (offsetY.value > dismissPx) animateClose()
                                    else {
                                        scope.launch {
                                            offsetY.animateTo(
                                                0f,
                                                tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut),
                                            )
                                        }
                                    }
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    BottomSheetDefaults.DragHandle()
                }
                content()
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}