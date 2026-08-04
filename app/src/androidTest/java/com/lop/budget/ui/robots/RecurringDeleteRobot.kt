package com.lop.budget.ui.robots

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick

class RecurringDeleteRobot(private val composeTestRule: ComposeTestRule) {
    fun chooseOccurrenceOnly() {
        composeTestRule.onNodeWithText("Cette occurrence", ignoreCase = true).performClick()
    }

    fun chooseFutureOnly() {
        composeTestRule.onNodeWithText("Les suivantes uniquement", ignoreCase = true).performClick()
    }

    fun chooseAllSeries() {
        composeTestRule.onNodeWithText("Toute la série", ignoreCase = true).performClick()
    }
}
