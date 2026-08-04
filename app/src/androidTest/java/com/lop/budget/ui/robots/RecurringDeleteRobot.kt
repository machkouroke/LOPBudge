package com.lop.budget.ui.robots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick

class RecurringDeleteRobot(private val composeTestRule: ComposeTestRule) {
    fun chooseOccurrenceOnly() {
        val text = "Cette occurrence"
        composeTestRule.waitUntil(3000) {
            composeTestRule.onAllNodesWithText(text, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(text, ignoreCase = true).performClick()
    }

    fun chooseFutureOnly() {
        val text = "Les suivantes uniquement"
        composeTestRule.waitUntil(3000) {
            composeTestRule.onAllNodesWithText(text, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(text, ignoreCase = true).performClick()
    }

    fun chooseAllSeries() {
        val text = "Toute la série"
        composeTestRule.waitUntil(3000) {
            composeTestRule.onAllNodesWithText(text, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(text, ignoreCase = true).performClick()
    }
}
