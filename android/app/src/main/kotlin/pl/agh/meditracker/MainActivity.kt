package pl.agh.meditracker

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.SideEffect
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.launch
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.ui.navigation.AppNavHost
import pl.agh.meditracker.ui.theme.MediTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        val app = application as MediTrackerApp
        app.applicationScope.launch { app.container.reminders.refresh() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val settings = (application as MediTrackerApp).container.settings
        setContent {
            val preferences by settings.state.collectAsStateWithLifecycle()
            MediTrackerTheme(preferences.theme) {
                val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                SideEffect {
                    enableEdgeToEdge(
                        if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                        if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    )
                }
                AppNavHost(settings.onboardingDone) { settings.onboardingDone = true }
            }
        }
    }
}
