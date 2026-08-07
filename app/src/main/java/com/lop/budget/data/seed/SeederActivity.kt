package com.lop.budget.data.seed

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.lop.budget.data.local.LopDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Activité sans interface permettant de déclencher le seeding via Deep Link.
 * Indispensable pour Maestro Cloud où les broadcasts ADB ne sont pas toujours disponibles.
 * URL : lopbudge://seed?scenario=TC_30
 */
@AndroidEntryPoint
class SeederActivity : ComponentActivity() {

    @Inject
    lateinit var database: LopDatabase

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val scenario = intent.data?.getQueryParameter("scenario") ?: "DEFAULT"
        
        scope.launch {
            DatabaseSeeder.seed(database, scenario)
            finish() // On ferme l'activité dès que le seeding est lancé
        }
    }
}
