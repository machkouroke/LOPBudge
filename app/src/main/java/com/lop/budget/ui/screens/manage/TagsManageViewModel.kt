package com.lop.budget.ui.screens.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.domain.usecase.tag.CreateTagUseCase
import com.lop.budget.domain.usecase.tag.DeleteTagUseCase
import com.lop.budget.domain.usecase.tag.ObserveTagsUseCase
import com.lop.budget.domain.usecase.tag.UpdateTagUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Écran de gestion du référentiel de tags (LOP-21).
 *
 * **Aucune règle ici** (P-4) : chaque opération est déléguée à son use case, les mêmes que ceux de
 * la modal tags du formulaire de transaction. Le refus d'un nom vide et le dédoublonnage par nom
 * normalisé vivent dans [CreateTagUseCase] et [UpdateTagUseCase] — ce ViewModel ne les redouble
 * pas, sinon la règle existerait à deux endroits et divergerait au premier correctif.
 */
@HiltViewModel
class TagsManageViewModel @Inject constructor(
    observeTagsUseCase: ObserveTagsUseCase,
    private val createTagUseCase: CreateTagUseCase,
    private val updateTagUseCase: UpdateTagUseCase,
    private val deleteTagUseCase: DeleteTagUseCase,
) : ViewModel() {

    val tags: StateFlow<List<TagEntity>> = observeTagsUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateTag(tag: TagEntity, newName: String, newColor: Int) {
        viewModelScope.launch {
            updateTagUseCase(tag, newName, newColor)
        }
    }

    fun deleteTag(tagId: Long) {
        viewModelScope.launch {
            deleteTagUseCase(tagId)
        }
    }

    fun createTag(name: String, color: Int) {
        viewModelScope.launch {
            createTagUseCase(name, color)
        }
    }
}
