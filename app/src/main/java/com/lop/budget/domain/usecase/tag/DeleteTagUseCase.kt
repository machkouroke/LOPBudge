package com.lop.budget.domain.usecase.tag

import com.lop.budget.data.repository.TagRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Suppression d'un tag (LOP-21, CA-06, CA-07, CA-08, I-1, I-2), sur le même principe que
 * `DeleteAccountUseCase`.
 *
 * **Unique point d'entrée** de la suppression : `TagsManageViewModel` et
 * `TransactionEditViewModel` appelaient chacun `TagRepository.delete` de leur côté ; ils passent
 * désormais tous deux par ici.
 *
 * **Un tag utilisé n'est pas refusé** (P-1). Aucun garde d'usage n'est posé :
 * `TagDao.countUsages` n'entre pas dans le contrat de suppression, et le rebrancher ferait échouer
 * CA-07.
 *
 * Les liens disparaissent sans qu'on les efface : `transaction_tags` et `series_tags` déclarent
 * toutes deux `ON DELETE CASCADE` vers `tags`. Recopier ici un `DELETE` des liens ajouterait un
 * second chemin pour un effet que la base produit déjà — et masquerait la perte de la contrainte
 * si elle survenait. Les transactions et les séries, elles, ne référencent pas `tags` : elles
 * survivent avec exactement leurs autres tags (I-1).
 *
 * La confirmation, libellé compris, reste à l'écran : le use case ne confirme pas.
 */
@Singleton
class DeleteTagUseCase @Inject constructor(
    private val tagRepo: TagRepository,
) {
    suspend operator fun invoke(tagId: Long) {
        tagRepo.delete(tagId)
    }
}
