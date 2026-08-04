package com.lop.budget.ui.robots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick

class CommonRobot(private val composeTestRule: ComposeTestRule) {
    fun clickUndo() {
        composeTestRule.onNodeWithTag("snackbar_host")
            .onChildren()
            .filter(hasText("ANNULER", ignoreCase = true))
            .onFirst()
            .performClick()
    }
}
