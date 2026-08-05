package com.lop.budget.ui.robots

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until

class CommonRobot(
    private val composeTestRule: ComposeTestRule,
    private val device: UiDevice
) {
    fun clickUndo() {
        // --- STRATÉGIE HYBRIDE ---
        // 1. On tente d'abord de trouver la Snackbar via UI Automator (plus robuste pour les overlays)
        // On cherche le texte "ANNULER" ou "UNDO" (majuscules/minuscules gérées par By.text)
        val undoButton = device.wait(Until.findObject(By.textContains("ANNULER")), 10000) 
            ?: device.wait(Until.findObject(By.textContains("UNDO")), 2000)

        if (undoButton != null) {
            println("[ROBOT] Undo button found via UI Automator. Clicking...")
            undoButton.click()
        } else {
            // 2. Fallback sur Compose s'il y a un souci avec UI Automator
            println("[ROBOT] Undo button NOT found via UI Automator. Falling back to Compose...")
            composeTestRule.onAllNodes(
                hasText("ANNULER", ignoreCase = true).or(hasText("UNDO", ignoreCase = true)),
                useUnmergedTree = true
            ).onFirst().performClick()
        }
        
        composeTestRule.waitForIdle()
        device.waitForIdle()
    }
}
