package com.lop.budget

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Runner d'instrumentation du dépôt, déclaré par `app/build.gradle.kts` :
 * `testInstrumentationRunner = "com.lop.budget.HiltTestRunner"`.
 *
 * Il était **référencé sans exister** jusqu'au 21 septembre 2026 : aucune suite instrumentée ne
 * pouvait démarrer. Il substitue [HiltTestApplication] à `LopBudgeApp` pour que l'injection soit
 * pilotée par les modules de test (voir `di/TestAppModule`).
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
