package com.lop.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lop.budget.data.local.entity.TagEntity
import kotlinx.coroutines.flow.Flow

interface TagOperations {
    fun observeAll(): Flow<List<TagEntity>>
    suspend fun upsert(tag: TagEntity): Long
    suspend fun delete(id: Long)
}

@Dao
interface TagDao : TagOperations {
    @Query("SELECT * FROM tags ORDER BY name")
    override fun observeAll(): Flow<List<TagEntity>>

    // `getByName` retiré (LOP-21, P-4) : son `WHERE name = :name` comparait en exact, sans `trim`
    // ni casse ignorée, là où l'unicité d'un tag se juge sur le nom normalisé (I-2 de LOP-3). Son
    // dernier appelant, `DatabaseSeeder`, passe désormais par `CreateTagUseCase` — seul chemin de
    // création. Le rétablir rouvrirait un chemin qui recrée « Essentiel » à côté d'« essentiel ».
    // `COLLATE NOCASE` ne le sauverait pas : SQLite n'y replie que l'ASCII.

    // `countUsages` retiré (LOP-21, P-1) : aucun appelant, et un tag utilisé se supprime désormais
    // sans garde d'usage. Le laisser là invitait à rebrancher le blocage que P-1 a tranché — et il
    // ne comptait que `transaction_tags`, jamais `series_tags`.

    @Upsert override suspend fun upsert(tag: TagEntity): Long

    @Query("DELETE FROM tags WHERE id = :id") override suspend fun delete(id: Long)

    @Query("DELETE FROM tags") fun deleteAll()
}
