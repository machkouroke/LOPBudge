package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TagOperations
import com.lop.budget.data.local.entity.TagEntity
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepository @Inject constructor(
    private val tagDao: TagDao
) : TagOperations by tagDao {

    /**
     * Crée un tag, ou rend celui qui porte déjà le même **nom normalisé** (US LOP-3, I-2 et P-1).
     *
     * Un nom est comparé après `trim` et sans tenir compte de la casse, et stocké tel que saisi
     * après `trim` : la casse de la première saisie est conservée, une seconde saisie ne l'écrase
     * jamais.
     *
     * @return l'identifiant du tag à sélectionner, ou `null` si le nom est vide ou composé
     *   uniquement d'espaces — auquel cas **rien n'est écrit**.
     *
     * La comparaison se fait en Kotlin et non en SQL : `LOWER()` de SQLite ne traite que l'ASCII,
     * si bien que « SANTÉ » et « santé » y resteraient distincts.
     */
    suspend fun createOrFind(name: String, colorArgb: Int): Long? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null

        val normalized = trimmed.normalizedTagName()
        val existing = tagDao.observeAll().first().firstOrNull {
            it.name.normalizedTagName() == normalized
        }
        // Le tag existant est rendu **inchangé** : ni son nom ni sa couleur ne sont écrasés.
        return existing?.id ?: tagDao.upsert(TagEntity(name = trimmed, colorArgb = colorArgb))
    }

    private companion object {
        /** `Locale.ROOT` : la normalisation ne doit pas dépendre de la langue de l'appareil. */
        fun String.normalizedTagName(): String = trim().lowercase()
    }
}
