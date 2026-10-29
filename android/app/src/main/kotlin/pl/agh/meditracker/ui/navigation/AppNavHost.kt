package pl.agh.meditracker.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import pl.agh.meditracker.feature.addmedication.AddMedicationScreen
import pl.agh.meditracker.feature.medications.MedicationsScreen
import pl.agh.meditracker.feature.onboarding.OnboardingScreen
import pl.agh.meditracker.feature.today.TodayScreen
import pl.agh.meditracker.ui.components.AppBottomBar

private val tabs = listOf("today", "medications", "history", "settings")

@Composable
fun AppNavHost(onboardingDone: Boolean, onOnboardingFinished: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')
    Scaffold(
        bottomBar = {
            if (route in tabs) AppBottomBar(tabs.indexOf(route)) { nav.openTab(tabs[it]) }
        },
    ) { padding ->
        NavHost(nav, startDestination = if (onboardingDone) "today" else "onboarding", Modifier.padding(padding)) {
            composable("onboarding") {
                OnboardingScreen(onFinished = {
                    onOnboardingFinished()
                    nav.navigate("today") { popUpTo("onboarding") { inclusive = true } }
                })
            }
            composable("today") {
                TodayScreen(
                    onAddMedication = { nav.navigate("add") },
                    onOpenHistory = { nav.openTab("history") },
                )
            }
            composable("medications?added={added}") { e ->
                MedicationsScreen(
                    justAdded = e.arguments?.getString("added") == "true",
                    onAddMedication = { nav.navigate("add") },
                    onOpenToday = { nav.openTab("today") },
                )
            }
            composable("add") {
                AddMedicationScreen(
                    onClose = { nav.popBackStack() },
                    onSaved = {
                        nav.navigate("medications?added=true") { popUpTo("today") }
                    },
                )
            }
            composable("history") { Placeholder("Historia") }
            composable("settings") { Placeholder("Ustawienia") }
        }
    }
}

private fun NavHostController.openTab(tab: String) {
    if (tab == "today") {
        popBackStack("today", inclusive = false)
        return
    }
    navigate(if (tab == "medications") "medications?added=false" else tab) {
        popUpTo("today")
        launchSingleTop = true
    }
}

@Composable
private fun Placeholder(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(title) }
}
