package de.leserkonto.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.leserkonto.app.ui.AppViewModel
import de.leserkonto.app.ui.screens.LeserkontoApp
import de.leserkonto.app.ui.theme.LeserkontoTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels {
        AppViewModel.factory((application as LeserkontoApplication).container, applicationContext)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            LeserkontoTheme {
                val state by viewModel.state.collectAsState()
                LeserkontoApp(state = state, vm = viewModel)
            }
        }
    }
}
