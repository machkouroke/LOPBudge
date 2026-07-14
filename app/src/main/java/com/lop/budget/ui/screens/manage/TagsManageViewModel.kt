package com.lop.budget.ui.screens.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lop.budget.data.local.entity.TagEntity
import com.lop.budget.data.repository.BudgetRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TagsManageViewModel @Inject constructor(
    private val repo: BudgetRepository,
) : ViewModel() {

    val tags: StateFlow<List<TagEntity>> = repo.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createTag(name: String, colorArgb: Int) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repo.saveTag(TagEntity(name = name.trim(), colorArgb = colorArgb))
        }
    }

    fun updateTag(tag: TagEntity) {
        if (tag.name.isBlank()) return
        viewModelScope.launch {
            repo.saveTag(tag.copy(name = tag.name.trim()))
        }
    }

    fun deleteTag(id: Long) {
        viewModelScope.launch {
            repo.deleteTag(id)
        }
    }
}
