package com.lop.budget.ui.screens.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagsManageViewModel @Inject constructor(
    private val tagRepo: TagRepository,
) : ViewModel() {

    val tags: StateFlow<List<TagEntity>> = tagRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun updateTag(tag: TagEntity, newName: String, newColor: Int) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            tagRepo.upsert(tag.copy(name = newName.trim(), colorArgb = newColor))
        }
    }

    fun deleteTag(tagId: Long) {
        viewModelScope.launch {
            tagRepo.delete(tagId)
        }
    }

    fun createTag(name: String, color: Int) {
        if (name.isBlank()) return
        viewModelScope.launch {
            tagRepo.upsert(TagEntity(name = name.trim(), colorArgb = color))
        }
    }
}
