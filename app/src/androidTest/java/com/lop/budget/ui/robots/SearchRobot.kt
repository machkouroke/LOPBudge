package com.lop.budget.ui.robots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule

class SearchRobot(private val composeTestRule: ComposeTestRule) {
    fun searchFor(text: String) {
        composeTestRule.onNodeWithTag("search_bar").performTextReplacement(text)
    }

    fun assertTransactionVisible(title: String) {
        composeTestRule.onNodeWithText(title).assertIsDisplayed()
    }

    fun assertTransactionHidden(title: String) {
        composeTestRule.onNodeWithText(title).assertDoesNotExist()
    }

    fun clickOnTransaction(title: String) {
        composeTestRule.onNodeWithText(title).performClick()
    }
}
