package com.lop.budget.data.seed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lop.budget.data.local.LopDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Receiver permettant de déclencher le seeding depuis ADB pour les tests Maestro.
 * Usage : adb shell am broadcast -a com.lop.budget.ACTION_SEED --es scenario TC_30
 */
@AndroidEntryPoint
class TestSeederReceiver : BroadcastReceiver() {

    @Inject
    lateinit var database: LopDatabase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.lop.budget.ACTION_SEED") {
            val scenario = intent.getStringExtra("scenario") ?: "DEFAULT"
            scope.launch {
                DatabaseSeeder.seed(database, scenario)
            }
        }
    }
}
