package com.lop.budget.domain.usecase.tag

import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flux du référentiel de tags (LOP-21, CA-01).
 *
 * **Unique lecture** du référentiel : l'écran de gestion et la modal tags du formulaire de
 * transaction s'abonnent ici, et non chacun à `TagRepository.observeAll`. Une entrée par tag ;
 * un référentiel vide émet une liste vide.
 *
 * Délégation pure, et volontairement : la lecture ne porte aucune règle aujourd'hui. Ce que le use
 * case apporte est le **point unique** exigé par P-4 — si un tri ou un filtre métier apparaît, il
 * aura un seul endroit où vivre au lieu de deux ViewModels à corriger de concert.
 */
@Singleton
class ObserveTagsUseCase @Inject constructor(
    private val tagRepo: TagRepository,
) {
    operator fun invoke(): Flow<List<TagEntity>> = tagRepo.observeAll()
}
