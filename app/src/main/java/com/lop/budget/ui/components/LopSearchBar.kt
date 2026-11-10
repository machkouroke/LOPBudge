package com.lop.budget.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.motion.MotionSpec

/** Forme « pilule » des champs de saisie modernes de l'app. */
val LopFieldShape = RoundedCornerShape(32.dp)

/**
 * Accueil ↔ Recherche ouverte par la barre : la barre de l'accueil et le champ de Recherche sont
 * une même pilule qui voyage d'un écran à l'autre (DESIGN.md § 13.3). [screen] est la destination
 * de navigation qui porte la pilule.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
class SearchBarTransition(val shared: SharedTransitionScope, val screen: AnimatedVisibilityScope)

/** Pose la pilule partagée ; sans [transition], rien ne voyage. À chaîner avant le fond. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.searchBarBounds(transition: SearchBarTransition?): Modifier =
    if (transition == null) this else with(transition.shared) {
        sharedBounds(
            rememberSharedContentState("search_bar"),
            transition.screen,
            // Courbes appariées des onglets : la pilule de départ tient puis part, celle d'arrivée se
            // voit dès son départ. Le fond reste plein en vol, les deux libellés se croisent à peine.
            enter = fadeIn(tween(MotionSpec.TAB_IN_MS, MotionSpec.SCREEN_MS - MotionSpec.TAB_IN_MS, MotionSpec.enterDecelerate)),
            exit = fadeOut(tween(MotionSpec.TAB_OUT_MS, easing = MotionSpec.exitAccelerate)),
            boundsTransform = { _, _ -> MotionSpec.screenTween() },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(LopFieldShape),
        )
    }

/**
 * Couleurs des champs de saisie « pilule » : bordure invisible au repos, fond tonal léger.
 *
 * Extraites de [LopSearchBar] pour que le champ de création de tag de la modal ait exactement le
 * même rendu sans hériter du comportement de recherche — icône loupe, bouton d'effacement et
 * `ImeAction.Search` n'ont pas de sens sur un champ qui crée.
 */
@Composable
fun lopFieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Color.Transparent,
    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
)

/**
 * Composant de recherche moderne et hautement arrondi, utilisé partout dans l'app.
 */
@Composable
fun LopSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Rechercher...",
) {
    val focusManager = LocalFocusManager.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.SEARCH_BAR),
        placeholder = { Text(placeholder) },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.testTag("search.bar.clear")
                ) {
                    Icon(Icons.Default.Close, null)
                }
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = { focusManager.clearFocus() },
            onDone = { focusManager.clearFocus() }
        ),
        shape = LopFieldShape,
        colors = lopFieldColors(),
    )
}
