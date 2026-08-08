package com.lop.budget.ui.screens.transaction

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.lop.budget.data.local.entity.TransactionEntity
import com.lop.budget.data.local.entity.TransactionWithRelations
import com.lop.budget.data.repository.BudgetRepository
import com.lop.budget.data.repository.SettingsRepository
import com.lop.budget.domain.model.EditScope
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Test JUnit — État de l'UI et navigation édition (Complément TC-38).
 * Objectif : Vérifier que le ViewModel parse correctement la portée d'édition 
 * passée par la navigation, ce qui pilote l'affichage dynamique de l'écran.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionEditViewModelTest {

    private val repo = mockk<BudgetRepository>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Vérifie que la portée est positionnée à SINGLE par défaut (CA-01).
     */
    @Test
    fun `ViewModel should initialize with SINGLE scope by default`() {
        // Étape 1 : Créer un SavedStateHandle vide
        val savedStateHandle = SavedStateHandle()

        // Étape 2 : Initialiser le ViewModel
        val viewModel = TransactionEditViewModel(repo, settings, savedStateHandle, context)

        // Résultat attendu : La portée doit être SINGLE
        assertEquals(EditScope.SINGLE, viewModel.editScope)
    }

    /**
     * Vérifie que la portée FUTURE est correctement extraite des arguments (CA-06).
     */
    @Test
    fun `ViewModel should parse FUTURE scope from navigation arguments`() {
        // Étape 1 : Simuler les arguments de navigation (?scope=FUTURE)
        val savedStateHandle = SavedStateHandle(mapOf("scope" to "FUTURE"))

        // Étape 2 : Initialiser le ViewModel
        val viewModel = TransactionEditViewModel(repo, settings, savedStateHandle, context)

        // Résultat attendu : La portée doit être FUTURE (pilote l'affichage du bloc récurrence)
        assertEquals(EditScope.FUTURE, viewModel.editScope)
    }

    /**
     * Vérifie que la portée ALL est correctement extraite des arguments (CA-07).
     */
    @Test
    fun `ViewModel should parse ALL scope from navigation arguments`() {
        // Étape 1 : Simuler les arguments de navigation (?scope=ALL)
        val savedStateHandle = SavedStateHandle(mapOf("scope" to "ALL"))

        // Étape 2 : Initialiser le ViewModel
        val viewModel = TransactionEditViewModel(repo, settings, savedStateHandle, context)

        // Résultat attendu : La portée doit être ALL
        assertEquals(EditScope.ALL, viewModel.editScope)
    }
}
