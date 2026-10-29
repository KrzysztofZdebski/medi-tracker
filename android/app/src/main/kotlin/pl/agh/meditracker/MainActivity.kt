package pl.agh.meditracker

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.ui.navigation.AppNavHost
import pl.agh.meditracker.ui.theme.MediTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val settings = (application as MediTrackerApp).container.settings
        setContent {
            MediTrackerTheme {
                AppNavHost(settings.onboardingDone) { settings.onboardingDone = true }
            }
        }
    }
}
