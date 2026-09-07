package com.lop.budget.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Menu d'ajout (CA-01, CA-02) : carte **flottante** posée au-dessus de l'écran courant.
 *
 * Ordre de dessin du flou — c'est le point qui était faux : `hazeEffect` peint le rendu
 * flouté *par-dessus* ce que les modificateurs précédents ont dessiné. Un `background()`
 * chaîné **avant** lui est donc entièrement recouvert et la teinte du scrim n'a aucun effet
 * prévisible. Le scrim doit venir **après** le flou, et la carte porte son propre flou pour
 * lire comme du verre plutôt que comme une surface opaque.
 */
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

    // Scrim : teinte appliquée par haze lui-même, pas par un background() séparé.
    val scrimColor = if (isDark) Color.Black.copy(alpha = 0.55f) else Color.Black.copy(alpha = 0.30f)
    val hazeBackground = MaterialTheme.colorScheme.background
    // La carte est opaque : rien du contenu sous-jacent ne doit transparaître.
    val sheetColor = MaterialTheme.colorScheme.surface
    val sheetBorder =
        if (isDark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.07f)

    val density = LocalDensity.current
    val contentProgress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) MotionSpec.sheetEnterSpring() else MotionSpec.sheetExitSpring(),
        label = "sheetContentCascade",
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut)),
        exit = fadeOut(tween(MotionSpec.FAST_MS, easing = MotionSpec.easeOut)),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // `hazeEffect(state)` seul retombe sur HazeDefaults.blurRadius (20 dp), trop
                // faible : le texte du fond restait lisible. Rayon et teinte sont donc
                // explicites, et la teinte passe par haze (un background() chaîné avant
                // l'effet est recouvert par le rendu flouté, chaîné après il l'aplatit).
                .then(
                    if (hazeState != null) {
                        Modifier.hazeEffect(state = hazeState) {
                            blurRadius = 40.dp
                            noiseFactor = 0f
                            backgroundColor = hazeBackground
                            tints = listOf(HazeTint(scrimColor))
                        }
                    } else {
                        Modifier.background(scrimColor)
                    }
                )
                .clickableNoRipple(onDismiss),
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInVertically(
                    animationSpec = MotionSpec.sheetEnterSpring(),
                ) { fullHeight -> (fullHeight * 0.4f).toInt() } +
                        scaleIn(
                            initialScale = 0.92f,
                            animationSpec = MotionSpec.sheetEnterSpring(),
                        ) +
                        fadeIn(
                            animationSpec = tween(MotionSpec.MEDIUM_MS, easing = MotionSpec.easeOut),
                        ),
                exit = slideOutVertically(
                    animationSpec = MotionSpec.sheetExitSpring(),
                ) { fullHeight -> (fullHeight * 0.4f).toInt() } +
                        scaleOut(
                            targetScale = 0.94f,
                            animationSpec = MotionSpec.sheetExitSpring(),
                        ) +
                        fadeOut(
                            animationSpec = tween(MotionSpec.FAST_MS, easing = MotionSpec.easeOut),
                        ),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                val sheetShape = RoundedCornerShape(32.dp)

                // Marges sur les quatre côtés : la carte flotte au lieu d'être collée au bord.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(elevation = 24.dp, shape = sheetShape, clip = false)
                            .clip(sheetShape)
                            // Opaque : la carte de choix ne laisse rien transparaître.
                            // Un hazeEffect ici ne floutait rien de toute façon — le
                            // `shadow` au-dessus ouvre une nouvelle couche graphique et
                            // le contenu réapparaissait net.
                            .background(sheetColor)
                            .border(1.dp, sheetBorder, sheetShape)
                            .clickableNoRipple { /* bloque le dismiss du scrim */ }
                            .testTag(TestTags.ADD_ACTION_SHEET),
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .padding(top = 22.dp, bottom = 20.dp),
                        ) {
                            val headerAlpha = contentProgress.coerceIn(0f, 1f)
                            val headerTranslationY = with(density) { (1f - headerAlpha) * 10.dp.toPx() }

                            Column(
                                modifier = Modifier.graphicsLayer {
                                    alpha = headerAlpha
                                    translationY = headerTranslationY
                                },
                            ) {
                                Text(
                                    text = stringResource(R.string.add_action_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.add_action_subtitle),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Spacer(Modifier.height(18.dp))

                            // Deux tuiles de largeur égale avec animation en cascade.
                            val expenseProgress = ((contentProgress - 0.12f) / 0.88f).coerceIn(0f, 1f)
                            val incomeProgress = ((contentProgress - 0.22f) / 0.78f).coerceIn(0f, 1f)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AddActionTile(
                                    label = stringResource(R.string.add_action_expense),
                                    icon = Icons.AutoMirrored.Filled.CallReceived,
                                    tint = LopTheme.extended.expense,
                                    testTag = TestTags.ADD_ACTION_EXPENSE,
                                    modifier = Modifier
                                        .weight(1f)
                                        .graphicsLayer {
                                            alpha = expenseProgress
                                            scaleX = 0.94f + (0.06f * expenseProgress)
                                            scaleY = 0.94f + (0.06f * expenseProgress)
                                            translationY = with(density) { (1f - expenseProgress) * 16.dp.toPx() }
                                        },
                                    onClick = { onSelect(TransactionType.EXPENSE) },
                                )
                                AddActionTile(
                                    label = stringResource(R.string.add_action_income),
                                    icon = Icons.AutoMirrored.Filled.CallMade,
                                    tint = LopTheme.extended.income,
                                    testTag = TestTags.ADD_ACTION_INCOME,
                                    modifier = Modifier
                                        .weight(1f)
                                        .graphicsLayer {
                                            alpha = incomeProgress
                                            scaleX = 0.94f + (0.06f * incomeProgress)
                                            scaleY = 0.94f + (0.06f * incomeProgress)
                                            translationY = with(density) { (1f - incomeProgress) * 16.dp.toPx() }
                                        },
                                    onClick = { onSelect(TransactionType.INCOME) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tuile d'action : la zone cliquable **et** le testTag portent sur toute la carte, pas
 * seulement sur la pastille d'icône — la cible de tap couvre désormais la tuile entière.
 */
@Composable
private fun AddActionTile(
    label: String,
    icon: ImageVector,
    tint: Color,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tileShape = RoundedCornerShape(24.dp)

    Column(
        modifier = modifier
            .clip(tileShape)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.22f), tileShape)
            .pressScaleClickable(
                intent = HapticIntent.Selection,
                pressedScale = 0.96f,
                onClick = onClick,
            )
            .padding(vertical = 18.dp)
            .testTag(testTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
