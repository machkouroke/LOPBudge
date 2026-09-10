package com.lop.budget.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

/**
 * Transaction accompagnée de ses entités liées, pour l'affichage
 * (catégorie, compte, tags). Construite par Room via @Relation.
 *
 * `@Immutable` : le `List<TagEntity>` rend la classe instable pour Compose, et le *strong
 * skipping* retombe alors sur l'égalité d'instance. Or Room reconstruit toutes les instances à
 * chaque émission, donc **toutes** les lignes se recomposaient à chaque invalidation, malgré des
 * `key` corrects dans la `LazyColumn`. L'annotation rétablit le saut par `equals`, structurel ici
 * puisque la classe est une `data class` d'entités elles-mêmes immuables.
 *
 * Room ne mute jamais l'instance après construction : la promesse tenue par l'annotation est
 * vérifiée. Elle introduit une dépendance de `data/local` vers `compose.runtime`, assumée faute
 * de fichier de configuration de stabilité côté build.
 */
@Immutable
data class TransactionWithRelations(
    @Embedded val transaction: TransactionEntity,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: CategoryEntity?,
    @Relation(parentColumn = "accountId", entityColumn = "id")
    val account: AccountEntity?,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = TransactionTagCrossRef::class,
            parentColumn = "transactionId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<TagEntity>,
)
