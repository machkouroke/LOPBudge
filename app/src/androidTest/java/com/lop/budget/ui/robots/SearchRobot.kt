package com.lop.budget.ui.robots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

class SearchRobot(private val composeTestRule: ComposeTestRule) {
    fun searchFor(text: String) {
        composeTestRule.onNodeWithTag("search_bar").performTextReplacement(text)
        // On ferme le clavier pour éviter les recouvrements (Masterclass UI testing)
        composeTestRule.onNodeWithTag("search_bar").performImeAction()
        composeTestRule.waitForIdle()
    }

    fun clickOnTransactionInStack(seriesId: String, index: Int) {
        val tag = "stack_item_${seriesId}_$index"
        composeTestRule.waitUntil(3000) {
            composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(tag).performClick()
    }

    fun assertTransactionVisible(title: String) {
        // On attend que l'élément soit affiché (Wait Selector)
        composeTestRule.waitUntil(3000) {
            composeTestRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        // Utilisation de onFirst() pour gérer les occurrences multiples (Stack)
        composeTestRule.onAllNodesWithText(title).onFirst().assertIsDisplayed()
    }

    fun assertTransactionHidden(title: String) {
        // On attend que l'élément disparaisse
        composeTestRule.waitUntil(3000) {
            composeTestRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
        composeTestRule.onAllNodesWithText(title).assertCountEquals(0)
    }

    fun clickExpandStack(seriesId: String) {
        val tag = "transaction_stack_$seriesId"
        composeTestRule.waitUntil(3000) {
            composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(tag).performClick()
    }

    fun assertStackExpanded(seriesId: String) {
        val tag = "transaction_stack_expanded_$seriesId"
        composeTestRule.waitUntil(3000) {
            composeTestRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(tag).assertIsDisplayed()
    }
}
