package com.lop.budget.ui.robots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick

class CommonRobot(private val composeTestRule: ComposeTestRule) {
    fun clickUndo() {
        // Attendre que la snackbar ou le bouton d'annulation apparaisse
        // On cherche "ANNULER" ou "UNDO" dans tout l'arbre (y compris unmerged)
        composeTestRule.waitUntil(10000) {
            composeTestRule.onAllNodes(
                hasText("ANNULER", ignoreCase = true).or(hasText("UNDO", ignoreCase = true)),
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        
        composeTestRule.onAllNodes(
            hasText("ANNULER", ignoreCase = true).or(hasText("UNDO", ignoreCase = true)),
            useUnmergedTree = true
        ).onFirst().performClick()
        
        composeTestRule.waitForIdle()
    }
}
