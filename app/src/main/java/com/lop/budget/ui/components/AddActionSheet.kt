package com.lop.budget.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lop.budget.R
import com.lop.budget.domain.model.TransactionType
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.motion.MotionSpec
import com.lop.budget.ui.screens.settings.SettingsViewModel
import com.lop.budget.ui.theme.LopTheme
import com.lop.budget.ui.theme.ThemeMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect

@Composable
fun AddActionSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSelect: (TransactionType) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
    settingsVm: SettingsViewModel = hiltViewModel(),
) {
    val settings by settingsVm.uiState.collectAsState()
    val isDark = settings.themeMode == ThemeMode.DARK

    if (visible) {
        BackHandler(onBack = onDismiss)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut)),
        exit = fadeOut(tween(MotionSpec.FAST_MS, easing = MotionSpec.easeOut)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (isDark) Color.Black.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.20f))
                .then(if (hazeState != null) Modifier.hazeEffect(state = hazeState) else Modifier)
                .clickableNoRipple(onDismiss),
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    animationSpec = tween(MotionSpec.SLOW_MS, easing = MotionSpec.easeOut),
                ) { it } + fadeIn(tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut)),
                exit = slideOutVertically(
                    animationSpec = tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut),
                ) { it } + fadeOut(tween(MotionSpec.FAST_MS, easing = MotionSpec.easeOut)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { /* bloque le dismiss du scrim */ }
                        .testTag(TestTags.ADD_ACTION_SHEET),
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp)
                            .padding(top = 20.dp, bottom = 28.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AddActionTile(
                                label = stringResource(R.string.add_action_expense),
                                icon = Icons.AutoMirrored.Filled.CallReceived,
                                tint = LopTheme.extended.expense,
                                testTag = TestTags.ADD_ACTION_EXPENSE,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelect(TransactionType.EXPENSE) },
                            )
                            AddActionTile(
                                label = stringResource(R.string.add_action_income),
                                icon = Icons.AutoMirrored.Filled.CallMade,
                                tint = LopTheme.extended.income,
                                testTag = TestTags.ADD_ACTION_INCOME,
                                modifier = Modifier.weight(1f),
                                onClick = { onSelect(TransactionType.INCOME) },
                            )
                            // 2 slots vides : réserve la grille 4 colonnes pour de futures actions
                            Spacer(Modifier.weight(1f))
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddActionTile(
    label: String,
    icon: ImageVector,
    tint: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.pressScaleClickable(
            intent = HapticIntent.Selection,
            pressedScale = 0.96f,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier
                .size(64.dp)
                .testTag(testTag),
            shape = CircleShape,
            color = tint.copy(alpha = 0.14f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = tint,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
