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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.ui.motion.MotionSpec
import kotlinx.coroutines.delay

/**
 * Vrai quand l'écran doit montrer son squelette (LOP-226, CA-06, P-12) : seulement si l'attente
 * dure. Un chargement plus court que [MotionSpec.SKELETON_DELAY_MS] n'affiche rien, le contenu
 * arrive directement ; l'écran n'affiche alors ni squelette ni contenu partiel. Une fois affiché,
 * le squelette reste au moins [MotionSpec.SKELETON_MIN_MS] : il ne clignote jamais.
 */
@Composable
fun rememberShowSkeleton(loading: Boolean): Boolean {
    // Instant d'apparition, sur l'horloge des images ; null tant qu'il n'est pas affiché.
    var shownAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(loading) {
        val since = shownAt
        if (loading && since == null) {
            delay(MotionSpec.SKELETON_DELAY_MS)
            shownAt = withFrameMillis { it }
        } else if (!loading && since != null) {
            delay((MotionSpec.SKELETON_MIN_MS - (withFrameMillis { it } - since)).coerceAtLeast(0))
            shownAt = null
        }
    }
    return shownAt != null
}

/**
 * Squelette d'un contenu qui charge (LOP-226, CA-06, P-9, P-12) : des cartes aux formes des lignes de
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
