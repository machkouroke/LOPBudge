package com.lop.budget.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow

/** Montant pas encore lu pour les critères affichés : jamais un zéro (LOP-228 CA-05, LOP-231 CA-04). */
const val SUMMARY_PLACEHOLDER = "—"

/**
 * Un montant de synthèse sous son libellé — Dépenses, Revenus, Solde. [amount] à `null` affiche
 * [SUMMARY_PLACEHOLDER]. Partagé par la vue unifiée (LOP-228) et la carte « Ce mois » (LOP-231).
 */
@Composable
fun SummaryFigure(@StringRes label: Int, amount: String?, color: Color, tag: String, modifier: Modifier = Modifier) {
    Column(modifier.testTag(tag).semantics(mergeDescendants = true) {}) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            amount ?: SUMMARY_PLACEHOLDER,
            style = MaterialTheme.typography.titleMedium,
            color = if (amount == null) MaterialTheme.colorScheme.onSurfaceVariant else color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
