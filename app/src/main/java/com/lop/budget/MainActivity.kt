package com.lop.budget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lop.budget.ui.navigation.LopNavHost
import com.lop.budget.ui.theme.LopBudgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            LopBudgeTheme {
                LopNavHost(startRoute = intent.getStringExtra("route"))
            }
        }
    }
}
