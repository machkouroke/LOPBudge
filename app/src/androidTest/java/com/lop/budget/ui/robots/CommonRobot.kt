package com.lop.budget.ui.robots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick

class CommonRobot(private val composeTestRule: ComposeTestRule) {
    fun clickUndo() {
        // Attendre que la snackbar apparaisse
        composeTestRule.waitUntil(5000) {
            composeTestRule.onAllNodesWithTag("snackbar_host").fetchSemanticsNodes().isNotEmpty()
        }
        
        composeTestRule.onNodeWithTag("snackbar_host", useUnmergedTree = true)
            .onChildren()
            .filter(hasText("ANNULER", ignoreCase = true).or(hasText("UNDO", ignoreCase = true)))
            .onFirst()
            .performClick()
    }
}
