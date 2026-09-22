package com.lop.budget.domain.usecase.tag

import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.repository.TagRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nom d'un tag réduit à sa forme comparable : `trim` des espaces de bord, casse ignorée
 * (LOP-3, P-1).
 *
 * `lowercase()` sans argument applique `Locale.ROOT` : la normalisation ne doit pas dépendre de la
 * langue de l'appareil. La comparaison se fait en Kotlin et non en SQL, car `LOWER()` de SQLite ne
 * traite que l'ASCII — « SANTÉ » et « santé » y resteraient distincts.
 *
 * Partagée par [CreateTagUseCase] et [UpdateTagUseCase] : l'unicité du nom normalisé (I-2 de
 * LOP-3) ne tient que si les deux chemins d'écriture comparent de la même façon.
 */
internal fun String.normalizedTagName(): String = trim().lowercase()

/**
 * Création d'un tag (LOP-21, CA-02 et CA-03).
 *
 * **Unique point d'entrée** de la création, partagé par l'écran de gestion et par la création
 * rapide depuis la modal tags (LOP-3). `TagRepository.createOrFind` portait cette règle ; elle a
 * été déplacée ici et **n'a pas été laissée** en second chemin dans le repository.
 *
 * Le nom est comparé après `trim` et sans tenir compte de la casse, et stocké tel que saisi après
 * `trim` : la casse de la première saisie est conservée, une seconde saisie ne l'écrase jamais.
 * La couleur est persistée telle que saisie (P-2).
 *
 * @return l'identifiant du tag à sélectionner — celui du tag créé, ou celui du tag existant de même
 *   nom normalisé, rendu **inchangé** (CA-03) — ou `null` si le nom est vide ou composé uniquement
 *   d'espaces, auquel cas **rien n'est écrit**.
 */
@Singleton
class CreateTagUseCase @Inject constructor(
    private val tagRepo: TagRepository,
) {
    suspend operator fun invoke(name: String, colorArgb: Int): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null

        val normalized = trimmed.normalizedTagName()
        val existing = tagRepo.observeAll().first().firstOrNull {
            it.name.normalizedTagName() == normalized
        }
        // Le tag existant est rendu **inchangé** : ni son nom ni sa couleur ne sont écrasés.
        return existing?.id ?: tagRepo.upsert(TagEntity(name = trimmed, colorArgb = colorArgb))
    }
}
