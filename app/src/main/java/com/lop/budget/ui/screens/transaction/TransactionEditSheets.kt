package com.lop.budget.ui.screens.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lop.budget.R
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.ui.common.TestTags
import com.lop.budget.ui.components.DefaultTagColor
import com.lop.budget.ui.components.LopFieldShape
import com.lop.budget.ui.components.PressScale
import com.lop.budget.ui.components.TagColorPicker
import com.lop.budget.ui.components.lopFieldColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagsBottomSheet(
    tags: List<TagEntity>,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onCreateTag: (String, Int) -> Unit,
    onDismiss: () -> Unit,
    /**
     * LOP-21, CA-08 : suppression du tag **du référentiel**, pas de la seule sélection. N'est
     * appelée qu'après confirmation ; la feuille porte la confirmation, le use case ne confirme pas.
     */
    onDeleteTag: (Long) -> Unit,
    /** CA-05 : message d'erreur de création, résolu par l'écran. `null` quand il n'y en a pas. */
    tagNameError: String? = null,
    /** CA-05 : purge de l'erreur à la frappe suivante. */
    onTagNameChanged: () -> Unit = {},
) {
    // CA-08 : l'identifiant, et non l'entité — `TagEntity` n'est pas `Parcelable`, et une fois le
    // tag supprimé la recherche ne rend plus rien, ce qui referme le dialogue de lui-même.
    var tagPendingDeleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    val tagPendingDelete = tags.firstOrNull { it.id == tagPendingDeleteId }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.tx_tags_sheet_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                stringResource(R.string.tx_tags_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            // Les cinq derniers tags enregistrés, identifiants les plus élevés d'abord : `id` est
            // auto-incrémenté, il ordonne donc les créations. `observeAll` trie par nom, ce tri-ci
            // est donc purement d'affichage et ne touche ni au DAO ni au use case.
            //
            // **Partition, jamais duplication** : les récents sont retirés de « Tous ». CA-01 de
            // LOP-3 exige exactement une entrée par tag existant — un raccourci qui réafficherait
            // les mêmes tags plus haut en produirait deux.
            val recentTags = remember(tags) { tags.sortedByDescending { it.id }.take(RECENT_TAGS) }
            val otherTags = remember(tags, recentTags) { tags - recentTags.toSet() }

            PressScale(
                modifier = Modifier.fillMaxWidth(),
                onClick = {}
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Sous le seuil, tout tient dans les « récents » : les deux intitulés
                    // n'apprendraient rien et la feuille reste la liste simple d'avant.
                    if (otherTags.isNotEmpty()) {
                        TagSectionLabel(stringResource(R.string.tx_tags_section_recent))
                    }
                    TagChipFlow(
                        tags = recentTags,
                        selectedTagIds = selectedTagIds,
                        onToggleTag = onToggleTag,
                        onRequestDelete = { tagPendingDeleteId = it },
                    )
                    if (otherTags.isNotEmpty()) {
                        TagSectionLabel(stringResource(R.string.tx_tags_section_all))
                        TagChipFlow(
                            tags = otherTags,
                            selectedTagIds = selectedTagIds,
                            onToggleTag = onToggleTag,
                            onRequestDelete = { tagPendingDeleteId = it },
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // Création d'un tag
            Text(
                stringResource(R.string.tx_tags_create_title),
                style = MaterialTheme.typography.titleMedium
            )
            // CA-14 : `rememberSaveable` et non `remember`. La feuille elle-même est sauvegardée
            // par `activeSheet` ; une saisie en cours doit avoir la même durée de vie, sinon
            // l'utilisateur retrouve sa feuille ouverte et son champ vidé.
            var newTagName by rememberSaveable { mutableStateOf("") }
            // La couleur suit la même règle de survie que le nom : la saisie en cours, c'est le
            // couple nom + couleur. Stockée en ARGB — `Color` n'est pas `Saveable`.
            var newTagColorArgb by rememberSaveable {
                mutableStateOf(DefaultTagColor.toArgb())
            }
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newTagName,
                    onValueChange = {
                        newTagName = it
                        onTagNameChanged()
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.tx_tags_name_label)) },
                    isError = tagNameError != null,
                    supportingText = if (tagNameError != null) {
                        { Text(tagNameError) }
                    } else {
                        null
                    },
                    singleLine = true,
                    // Même rendu « pilule » que la barre de recherche de catégorie, sans son
                    // comportement : ni loupe, ni `ImeAction.Search` — ce champ crée, il ne
                    // cherche pas. Filtrer la liste au fil de la frappe masquerait les tags
                    // sélectionnés, que CA-14 de LOP-3 exige de retrouver intacts.
                    shape = LopFieldShape,
                    colors = lopFieldColors(),
                )
                Spacer(Modifier.width(12.dp))
                // CA-05 : le bouton reste actionnable même sur un nom vide. Le refus appartient au
                // ViewModel, qui expose un message ; une interdiction silencieuse n'explique rien.
                IconButton(
                    onClick = {
                        onCreateTag(newTagName, newTagColorArgb)
                        if (newTagName.isNotBlank()) newTagName = ""
                    },
                ) {
                    // L'icône portait `contentDescription = null` : le bouton n'avait aucun nom
                    // pour un lecteur d'écran, et rien d'autre ne le désignait.
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.tx_tags_add_action),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // P-2 : la couleur fait partie du tag à la création. La modal imposait un violet en
            // dur, l'écran de gestion offrait la palette — deux tags créés côte à côte n'avaient
            // pas le même contrat selon l'écran emprunté. Même sélecteur des deux côtés.
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.tx_tags_color_label),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(12.dp))
            TagColorPicker(
                selected = Color(newTagColorArgb),
                onSelect = { newTagColorArgb = it.toArgb() },
            )
        }
    }

    // CA-08 : confirmation par `AlertDialog` et non par `ConfirmDeleteSheet`. On est déjà dans un
    // `ModalBottomSheet` ; imbriquer une seconde feuille M3 laisse la première en travers de la
    // confirmation. Le dialogue ouvre sa propre racine de composition : un test instrumenté qui
    // vise ces `testTag` doit reposer `testTagsAsResourceId` sur cette racine-là.
    if (tagPendingDelete != null) {
        AlertDialog(
            onDismissRequest = { tagPendingDeleteId = null },
            title = { Text(stringResource(R.string.tx_tags_delete_title)) },
            text = {
                Text(stringResource(R.string.tx_tags_delete_message, tagPendingDelete.name))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteTag(tagPendingDelete.id)
                        tagPendingDeleteId = null
                    },
                    modifier = Modifier.testTag(TestTags.TAG_DELETE_CONFIRM),
                ) {
                    Text(
                        stringResource(R.string.delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { tagPendingDeleteId = null },
                    modifier = Modifier.testTag(TestTags.TAG_DELETE_CANCEL),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Nombre de tags mis en avant comme « récents » dans la modal. */
private const val RECENT_TAGS = 5

@Composable
private fun TagSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Rangée de chips de tags, sélectionnables et supprimables.
 *
 * Chaque chip porte **la couleur du tag** : une pastille quand il n'est pas sélectionné, la coche
 * quand il l'est. Sans cette pastille, la couleur — qui fait partie du tag depuis P-2 — n'était
 * visible que dans l'écran de gestion, jamais là où l'on choisit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChipFlow(
    tags: List<TagEntity>,
    selectedTagIds: Set<Long>,
    onToggleTag: (Long) -> Unit,
    onRequestDelete: (Long) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEach { tag ->
            val selected = selectedTagIds.contains(tag.id)
            FilterChip(
                selected = selected,
                onClick = { onToggleTag(tag.id) },
                label = { Text(tag.name) },
                leadingIcon = {
                    if (selected) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                    } else {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(tag.colorArgb)),
                        )
                    }
                },
                // CA-08 : la corbeille, pas une croix. Une croix sur un chip se lit « retirer de
                // la sélection » — or l'action supprime le tag partout. Cible tactile de 18 dp,
                // celle que M3 prévoit dans un chip de 32 dp : le clic enfant est prioritaire,
                // il ne bascule donc pas la sélection.
                trailingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.tx_tags_delete_action),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .size(18.dp)
                            .clickable { onRequestDelete(tag.id) }
                            .testTag("${TestTags.TAG_CHIP_DELETE}_${tag.id}"),
                    )
                },
            )
        }
    }
}

@Composable
fun BalanceImpactDialog(
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("impact_alert_dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.impact_balance_title)) },
        text = { Text(stringResource(R.string.impact_balance_msg)) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(true) },
                modifier = Modifier.testTag("impact_alert_confirm")
            ) {
                Text(stringResource(R.string.impact_balance_account_now))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onConfirm(false) },
                modifier = Modifier.testTag("impact_alert_dismiss")
            ) {
                Text(stringResource(R.string.impact_balance_do_not_account))
            }
        }
    )
}
