package com.lop.budget.data.repository

import com.lop.budget.data.local.dao.TagDao
import com.lop.budget.data.local.dao.TagOperations
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accès persistant au référentiel de tags : `observeAll`, `upsert` et `delete`, délégués au DAO.
 *
 * **Aucune règle ici** (LOP-21, P-4). Observation, création, renommage et suppression vivent dans
 * `com.lop.budget.domain.usecase.tag`. `createOrFind` était porté par ce repository ; il est devenu
 * `CreateTagUseCase` et n'a délibérément **pas** été conservé ici — deux chemins d'écriture, c'est
 * deux endroits où la règle se perdra au prochain écran.
 */
@Singleton
class TagRepository @Inject constructor(
    tagDao: TagDao
) : TagOperations by tagDao
