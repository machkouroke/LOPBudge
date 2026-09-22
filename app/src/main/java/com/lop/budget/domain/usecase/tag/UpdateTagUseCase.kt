package com.lop.budget.domain.usecase.tag

import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.repository.TagRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renommage d'un tag (LOP-21, CA-04, CA-05, I-3).
 *
 * **Unique point d'entrée** du renommage, et depuis l'écran de gestion seulement : la modal tags
 * reste une action rapide, pas un écran d'administration (P-3). Le nom et la couleur changent
 * ensemble (P-2).
 *
 * L'écriture porte sur **la même ligne** — même identifiant, donc mêmes liens transaction et
 * série : c'est un `upsert` sur le tag existant, jamais une création (I-3).
 *
 * Deux refus, tous deux silencieux : rien n'est écrit et le tag reste inchangé.
 *  - Nom vide ou composé uniquement d'espaces (CA-05).
 *  - Nom déjà porté par un **autre** tag à la normalisation près (CA-05, I-2 de LOP-3) : les deux
 *    tags restent inchangés, noms, couleurs et liens. Ni CA-04 ni CA-05 n'exigent de message —
 *    contrairement à la création rapide depuis la modal, où CA-05 de LOP-3 en impose un.
 *
 * Le second refus corrige un écart : `TagsManageViewModel.updateTag` écrivait via `upsert` sans
 * comparer aux autres tags, si bien que renommer « Pro » en « santé » alors que « Santé » existait
 * produisait deux tags de même nom normalisé — une violation de I-2 par un chemin que la création
 * fermait déjà.
 *
 * La comparaison exclut le tag lui-même : recasser « Santé » en « SANTÉ » est un renommage
 * légitime du même tag, pas une collision avec lui-même.
 */
@Singleton
class UpdateTagUseCase @Inject constructor(
    private val tagRepo: TagRepository,
) {
    suspend operator fun invoke(tag: TagEntity, newName: String, newColor: Int) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return

        val normalized = trimmed.normalizedTagName()
        val takenByAnother = tagRepo.observeAll().first().any {
            it.id != tag.id && it.name.normalizedTagName() == normalized
        }
        if (takenByAnother) return

        tagRepo.upsert(tag.copy(name = trimmed, colorArgb = newColor))
    }
}
