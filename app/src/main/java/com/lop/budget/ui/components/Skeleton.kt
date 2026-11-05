package com.lop.budget.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lop.budget.R
import com.lop.budget.ui.motion.MotionSpec
import kotlinx.coroutines.flow.first

/**
 * Vrai tant qu'un écran doit montrer son squelette plutôt que son contenu (LOP-226, CA-06, P-9) :
 * il charge, ou il chargeait à son ouverture et sa transition d'entrée n'est pas finie. Le contenu
 * lourd n'est ainsi jamais construit pendant le glissement, qui reste fluide.
 *
 * Un écran ouvert sans rien à charger (formulaire de création) n'a jamais de squelette.
 */
@Composable
fun rememberShowSkeleton(loading: Boolean): Boolean {
    val loadingAtOpen = rememberSaveable { loading }
    return loading || (loadingAtOpen && !rememberScreenEntered())
}

/**
 * Vrai une fois la transition d'entrée de l'écran terminée. Navigation tient l'écran qui entre à
 * STARTED et ne le passe RESUMED qu'à la fin de son animation. Hors navigation (écran testé seul),
 * l'hôte est déjà RESUMED : vrai tout de suite. Retenu : un retour sur l'écran ne rejoue rien.
 */
@Composable
private fun rememberScreenEntered(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var entered by rememberSaveable { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    if (!entered) {
        LaunchedEffect(lifecycle) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            entered = true
        }
    }
    return entered
}

/**
 * Squelette d'un contenu qui charge (LOP-226, CA-06, P-9) : des cartes aux formes des lignes de
 * l'app, sans donnée ni scintillement, presque gratuites à dessiner. À l'arrivée du contenu, il se
 * dissout dessus. Annoncé « Chargement… » par TalkBack.
 *
 * [tag] : repère de test de l'état de chargement de l'écran, posé une seule fois.
 */
fun LazyListScope.skeleton(tag: String? = null, cards: Int = 2) {
    item(key = "lop.skeleton", contentType = "lop.skeleton") {
        val label = stringResource(R.string.loading)
        Column(
            modifier = Modifier
                .animateItem(fadeInSpec = null, placementSpec = null, fadeOutSpec = MotionSpec.mediumTween())
                .fillMaxWidth()
                .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                .semantics { contentDescription = label },
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(cards) {
                FloatingCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        repeat(3) { SkeletonRow() }
                    }
                }
            }
        }
    }
}

/** Une ligne type : pastille, libellé et détail, montant à droite. */
@Composable
private fun SkeletonRow() {
    val shade = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(shade, CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(12.dp).background(shade, RoundedCornerShape(6.dp)))
            Box(Modifier.fillMaxWidth(0.35f).height(10.dp).background(shade, RoundedCornerShape(5.dp)))
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(56.dp).height(12.dp).background(shade, RoundedCornerShape(6.dp)))
    }
}
