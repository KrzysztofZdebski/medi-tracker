package pl.agh.meditracker.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.data.account.AccountState
import pl.agh.meditracker.feature.account.AccountReadyScreen
import pl.agh.meditracker.feature.account.AuthViewModel
import pl.agh.meditracker.feature.account.LoginScreen
import pl.agh.meditracker.feature.account.NewPasswordScreen
import pl.agh.meditracker.feature.account.RecoveryCodeScreen
import pl.agh.meditracker.feature.account.RegisterScreen
import pl.agh.meditracker.feature.account.ResetCodeScreen
import pl.agh.meditracker.feature.account.ResetDoneContent
import pl.agh.meditracker.feature.account.ResetEmailScreen
import pl.agh.meditracker.feature.account.ResetRecoveryScreen
import pl.agh.meditracker.feature.account.RestoreChoiceHost
import pl.agh.meditracker.feature.account.SessionExpiredContent
import pl.agh.meditracker.feature.account.VerifyEmailScreen
import pl.agh.meditracker.feature.addmedication.AddMedicationScreen
import pl.agh.meditracker.feature.medications.MedicationsScreen
import pl.agh.meditracker.feature.onboarding.OnboardingScreen
import pl.agh.meditracker.feature.today.TodayScreen
import pl.agh.meditracker.ui.components.AppBottomBar
import pl.agh.meditracker.feature.history.HistoryScreen
import pl.agh.meditracker.feature.medicationdetails.MedicationDetailsScreen
import pl.agh.meditracker.feature.settings.SettingsScreen
import pl.agh.meditracker.feature.settings.NotificationSettingsScreen
import pl.agh.meditracker.feature.settings.AboutScreen

private val tabs = listOf("today", "medications", "history", "settings")

// Trasy konta leżą w grafie "auth"; argument `from` (onboarding | settings) mówi, dokąd wracamy po przepływie.
private const val AUTH = "auth"
private const val REGISTER = "register"
private const val VERIFY = "verify"
private const val READY = "ready"
private const val RECOVERY_CODE = "recovery-code"
private const val LOGIN = "login"
private const val RESET_EMAIL = "reset/email"
private const val RESET_CODE = "reset/code"
private const val RESET_RECOVERY = "reset/recovery"
private const val RESET_PASSWORD = "reset/password"
private const val RESET_DONE = "reset/done"
private const val SESSION_EXPIRED = "session-expired"

private const val FROM_ONBOARDING = "onboarding"
private const val FROM_SETTINGS = "settings"

private fun authPattern(name: String) = "$AUTH/$name?from={from}"

private fun authRoute(name: String, from: String) = "$AUTH/$name?from=$from"

@Composable
fun AppNavHost(onboardingDone: Boolean, onOnboardingFinished: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route?.substringBefore('?')
    val container = (LocalContext.current.applicationContext as MediTrackerApp).container

    // Start po zimnym starcie wg stanu konta: nieskończona rejestracja wraca tam, gdzie stanęła (spec §6.1.3).
    // SessionExpired nie wymusza U13 (spec §6.8), a pytanie przywróć / zachowaj (R17) pokazuje RestoreChoiceHost.
    val coldStart = remember {
        val authStart = when (container.account.state.value) {
            is AccountState.AwaitingVerification -> VERIFY
            is AccountState.ShowRecoveryCode -> RECOVERY_CODE
            else -> null
        }
        val from = if (onboardingDone) FROM_SETTINGS else FROM_ONBOARDING
        Triple(from, authStart, if (authStart != null) AUTH else if (onboardingDone) "today" else "onboarding?step={step}")
    }
    val (coldFrom, authStart, startRoute) = coldStart
    // `from` ma domyślną wartość tylko dla zimnego startu; każda nawigacja podaje je jawnie
    val fromArg = listOf(navArgument("from") { type = NavType.StringType; defaultValue = coldFrom })

    // wszystkie ekrany grafu dzielą jeden AuthViewModel (e-mail resetu, nowy kod z resetu bez kodu)
    @Composable
    fun authViewModel(e: NavBackStackEntry): AuthViewModel {
        val owner = remember(e) { nav.getBackStackEntry(AUTH) }
        return appViewModel(owner) { AuthViewModel(it.account) { it.backup.afterLogin() } }
    }

    fun NavBackStackEntry.from() = arguments?.getString("from") ?: coldFrom

    fun goToday() = nav.navigate("today") { popUpTo(nav.graph.id) { inclusive = true } }

    // Wyjście z grafu "auth" do ustawień pod spodem. Gdy ich tam nie ma (start z kodem do zapisania), idziemy do Dzisiaj zamiast do pustego stosu.
    fun leaveAuth() {
        val hasUnder = runCatching { nav.getBackStackEntry("settings") }.isSuccess
        if (hasUnder) nav.popBackStack(AUTH, inclusive = true) else goToday()
    }

    // przejście do kroku `name` z wycięciem kroku `replacing` ze stosu (Wstecz nie wraca do wypełnionego już ekranu)
    fun replaceWith(name: String, from: String, replacing: String) =
        nav.navigate(authRoute(name, from)) { popUpTo(authPattern(replacing)) { inclusive = true } }

    Box {
        Scaffold(
            bottomBar = {
                if (route in tabs) AppBottomBar(tabs.indexOf(route), designIcons = route == "history" || route == "settings") { nav.openTab(tabs[it]) }
            },
        ) { padding ->
            NavHost(nav, startDestination = startRoute, Modifier.padding(padding)) {
                composable("onboarding?step={step}", arguments = listOf(navArgument("step") { defaultValue = "welcome" })) { e ->
                    OnboardingScreen(
                        onFinished = {
                            onOnboardingFinished()
                            goToday()
                        },
                        onCreateAccount = { nav.navigate(authRoute(REGISTER, FROM_ONBOARDING)) },
                        onLogin = { nav.navigate(authRoute(LOGIN, FROM_ONBOARDING)) },
                        startAtReminders = e.arguments?.getString("step") == "reminders",
                    )
                }
                navigation(startDestination = authPattern(authStart ?: REGISTER), route = AUTH) {
                    composable(authPattern(REGISTER), fromArg) { e ->
                        val from = e.from()
                        RegisterScreen(
                            authViewModel(e),
                            onBack = { nav.popBackStack() },
                            onRegistered = { nav.navigate(authRoute(VERIFY, from)) },
                            onLogin = { replaceWith(LOGIN, from, REGISTER) },
                        )
                    }
                    composable(authPattern(VERIFY), fromArg) { e ->
                        val from = e.from()
                        VerifyEmailScreen(
                            authViewModel(e),
                            // z U04 nie ma powrotu do wypełnionej rejestracji, a konto jest już utworzone
                            onVerified = {
                                val hasRegister = runCatching { nav.getBackStackEntry(authPattern(REGISTER)) }.isSuccess
                                replaceWith(READY, from, if (hasRegister) REGISTER else VERIFY)
                            },
                            onChangeEmail = { replaceWith(REGISTER, from, VERIFY) },
                        )
                    }
                    composable(authPattern(READY), fromArg) { e ->
                        val from = e.from()
                        // Wstecz z U04 prowadzi do zapisania kodu, nie z powrotem do rejestracji
                        BackHandler { replaceWith(RECOVERY_CODE, from, READY) }
                        AccountReadyScreen(authViewModel(e), onNext = { replaceWith(RECOVERY_CODE, from, READY) })
                    }
                    composable(authPattern(RECOVERY_CODE), fromArg) { e ->
                        val from = e.from()
                        val vm = authViewModel(e)
                        RecoveryCodeScreen(vm, onDone = {
                            when {
                                // nowy kod po resecie bez kodu: dalej U08, potem logowanie (R7)
                                vm.wipeCode != null -> replaceWith(RESET_DONE, from, RECOVERY_CODE)
                                from == FROM_ONBOARDING -> nav.navigate("onboarding?step=reminders") { popUpTo(nav.graph.id) { inclusive = true } }
                                else -> leaveAuth()
                            }
                        })
                    }
                    composable(authPattern(LOGIN), fromArg) { e ->
                        val from = e.from()
                        val vm = authViewModel(e)
                        LoginScreen(
                            vm,
                            onBack = { nav.popBackStack() },
                            onLoggedIn = {
                                // logowanie z onboardingu kończy go tak jak O02; pytanie przywróć / zachowaj wyskoczy nad Dzisiaj
                                if (from == FROM_ONBOARDING) {
                                    onOnboardingFinished()
                                    goToday()
                                } else {
                                    leaveAuth()
                                }
                            },
                            onForgotPassword = { nav.navigate(authRoute(RESET_EMAIL, from)) },
                            onCreateAccount = { replaceWith(REGISTER, from, LOGIN) },
                        )
                    }
                    composable(authPattern(RESET_EMAIL), fromArg) { e ->
                        val from = e.from()
                        val vm = authViewModel(e)
                        ResetEmailScreen(vm, vm.resetEmail, onBack = { nav.popBackStack() }, onSent = { nav.navigate(authRoute(RESET_CODE, from)) })
                    }
                    composable(authPattern(RESET_CODE), fromArg) { e ->
                        val from = e.from()
                        ResetCodeScreen(authViewModel(e), onBack = { nav.popBackStack() }, onVerified = { nav.navigate(authRoute(RESET_RECOVERY, from)) })
                    }
                    composable(authPattern(RESET_RECOVERY), fromArg) { e ->
                        val from = e.from()
                        ResetRecoveryScreen(
                            authViewModel(e), onBack = { nav.popBackStack() },
                            onCodeAccepted = { nav.navigate(authRoute(RESET_PASSWORD, from)) },
                            onNoCode = { nav.navigate(authRoute(RESET_PASSWORD, from)) },
                        )
                    }
                    composable(authPattern(RESET_PASSWORD), fromArg) { e ->
                        val from = e.from()
                        NewPasswordScreen(authViewModel(e), onBack = { nav.popBackStack() }, onSaved = { showCode ->
                            replaceWith(if (showCode) RECOVERY_CODE else RESET_DONE, from, RESET_EMAIL)
                        })
                    }
                    composable(authPattern(RESET_DONE), fromArg) { e ->
                        val from = e.from()
                        val vm = authViewModel(e)
                        ResetDoneContent(onLogin = {
                            vm.finishReset()
                            replaceWith(LOGIN, from, LOGIN)
                        })
                    }
                    // U13: bez wyzwalacza na starcie; wiersz w ustawieniach (Task 13) otwiera tę trasę
                    composable(authPattern(SESSION_EXPIRED), fromArg) { e ->
                        val from = e.from()
                        SessionExpiredContent(onBack = { nav.popBackStack() }, onLogin = { replaceWith(LOGIN, from, SESSION_EXPIRED) })
                    }
                }
                composable("today") {
                    TodayScreen(
                        onAddMedication = { nav.navigate("add") },
                        onOpenHistory = { nav.openTab("history") },
                        onNotifications = { nav.navigate("notifications") },
                        onOpenMedication = { nav.navigate("medication/$it") },
                    )
                }
                composable("medications?added={added}") { e ->
                    MedicationsScreen(
                        justAdded = e.arguments?.getString("added") == "true",
                        onAddMedication = { nav.navigate("add") },
                        onOpenToday = { nav.openTab("today") },
                        onOpenMedication = { nav.navigate("medication/$it") },
                        onNotifications = { nav.navigate("notifications") },
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
                composable("medication/{id}") { e ->
                    val id = e.arguments?.getString("id").orEmpty()
                    MedicationDetailsScreen(id, onBack = { nav.popBackStack() }, onEdit = { schedule ->
                        nav.navigate("edit/$id?schedule=$schedule")
                    })
                }
                composable("edit/{id}?schedule={schedule}") { e ->
                    AddMedicationScreen(
                        onClose = { nav.popBackStack() }, onSaved = { nav.popBackStack() },
                        medicationId = e.arguments?.getString("id"), editSchedule = e.arguments?.getString("schedule") == "true",
                    )
                }
                composable("history") { HistoryScreen(onOpenToday = { nav.openTab("today") }, onNotifications = { nav.navigate("notifications") }) }
                composable("settings") { SettingsScreen(onNotifications = { nav.navigate("notifications") }, onAbout = { nav.navigate("about") }) }
                composable("notifications") { NotificationSettingsScreen(onBack = { nav.popBackStack() }, onOpenSettings = { nav.openTab("settings") }) }
                composable("about") { AboutScreen(onBack = { nav.popBackStack() }) }
            }
        }
        RestoreChoiceHost()
    }
}

private fun NavHostController.openTab(tab: String) {
    if (tab == "today") {
        if (!popBackStack("today", inclusive = false)) navigate("today") { launchSingleTop = true }
        return
    }
    navigate(if (tab == "medications") "medications?added=false" else tab) {
        popUpTo("today")
        launchSingleTop = true
    }
}
